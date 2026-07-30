package com.sheetsight.app.data.omr.dewarp

import kotlin.math.abs
import kotlin.math.floor

/**
 * Faithful port of oemer's `dewarp.py::dewarp()`:
 * `cv2.remap(src, coords_x, coords_y, INTER_CUBIC, BORDER_REPLICATE)`.
 *
 * oemer's own `coords_x` is always the identity column mapping — verified
 * directly from `dewarp.py`/`ete.py` (`coords_x = grid_y.astype(np.float32)`,
 * i.e. the column index, never remapped; dewarping only ever moves pixels
 * vertically). Because of that, the 2-D bicubic sample OpenCV would
 * normally do degenerates exactly to 1-D cubic interpolation along each
 * column, at the fractional row given by `coordsY` — this is not an
 * approximation, it's a mathematically exact simplification for an
 * interpolating cubic kernel sampled at an exact-integer x-offset.
 *
 * The cubic-convolution coefficient `a = -0.75` matches OpenCV's
 * documented `INTER_CUBIC` default. Border handling replicates the edge
 * pixel (`BORDER_REPLICATE`), matching oemer's explicit border argument.
 */
object DewarpRemapper {

    private const val CUBIC_A = -0.75f

    /**
     * Remaps [source] ([width]x[height], row-major) so that output pixel
     * `(x, y)` samples `source` column `x` at fractional row
     * `coordsY[y*width+x]`, using cubic interpolation with edge
     * replication for out-of-range rows.
     */
    fun remap(source: FloatArray, width: Int, height: Int, coordsY: FloatArray): FloatArray {
        require(source.size == width * height) { "source size ${source.size} doesn't match ${width}x$height" }
        require(coordsY.size == width * height) { "coordsY size ${coordsY.size} doesn't match ${width}x$height" }
        if (width == 0 || height == 0) return FloatArray(0)

        val result = FloatArray(width * height)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                result[rowBase + x] = cubicSampleColumn(source, width, height, x, coordsY[rowBase + x])
            }
        }
        return result
    }

    /**
     * Same remap for a boolean mask: converts to 0f/1f, remaps, then
     * re-binarizes via `> 0f` — matching how oemer's own downstream
     * stages threshold their (now-continuous, post-cubic-interpolation)
     * dewarped masks, e.g. `symbols_pred > 0` in `register_note_id()`,
     * rather than oemer re-binarizing at dewarp time itself (it doesn't).
     */
    fun remapMask(mask: BooleanArray, width: Int, height: Int, coordsY: FloatArray): BooleanArray {
        val asFloat = FloatArray(mask.size) { if (mask[it]) 1f else 0f }
        val remapped = remap(asFloat, width, height, coordsY)
        return BooleanArray(remapped.size) { remapped[it] > 0f }
    }

    private fun cubicSampleColumn(source: FloatArray, width: Int, height: Int, x: Int, srcY: Float): Float {
        val y0 = floor(srcY).toInt()
        val frac = srcY - y0
        var acc = 0f
        for (k in -1..2) {
            val weight = cubicWeight(k - frac)
            val sampleY = (y0 + k).coerceIn(0, height - 1) // BORDER_REPLICATE
            acc += weight * source[sampleY * width + x]
        }
        return acc
    }

    /** Standard cubic-convolution kernel (Keys, 1981) with coefficient [CUBIC_A]. */
    private fun cubicWeight(t: Float): Float {
        val a = CUBIC_A
        val absT = abs(t)
        return when {
            absT <= 1f -> ((a + 2) * absT - (a + 3)) * absT * absT + 1f
            absT < 2f -> (((absT - 5) * absT + 8) * absT - 4) * a
            else -> 0f
        }
    }
}