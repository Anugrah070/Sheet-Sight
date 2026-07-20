package com.sheetsight.app.data.omr.preprocessing

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

/**
 * Reproduces oemer's sliding-window tiling loop (`oemer/inference.py`,
 * `inference()`): windows of `windowSize`×`windowSize` are cut out of the
 * source image on a [DEFAULT_STEP_SIZE]-pixel stride, with the last
 * row/column of windows clamped flush against the bottom/right edge
 * instead of padded — so edge windows overlap their neighbor rather than
 * including blank pixels. oemer does not deduplicate the resulting
 * coordinates: near an edge, the same clamped origin can be produced
 * twice in a row, and both copies are kept. That's replicated exactly
 * here, since Phase 4.3's merge/averaging step
 * (`mask[y:y+win, x:x+win] += 1`) expects it.
 */
object SlidingWindowTiler {

    /** oemer's `inference()` default `step_size` — same stride for both models. */
    const val DEFAULT_STEP_SIZE = 128

    /**
     * Crops [source] into overlapping [windowSize]-sized tiles. If either
     * dimension of [source] is smaller than [windowSize] — which cannot
     * happen for oemer's own ~3–4.35-megapixel canonical resize target,
     * but can for a tiny or extremely elongated user photo — [source] is
     * first padded (replicated edge pixels) up to [windowSize] on that
     * axis. oemer has no equivalent safeguard; without it, this edge case
     * would compute a negative crop origin and crash.
     */
    fun tile(source: Mat, windowSize: Int, stepSize: Int = DEFAULT_STEP_SIZE): List<ImageTile> {
        require(windowSize > 0) { "windowSize must be positive, got $windowSize" }
        require(stepSize > 0) { "stepSize must be positive, got $stepSize" }

        val padded = padUpToWindowSize(source, windowSize)
        val origins = computeOrigins(padded.width(), padded.height(), windowSize, stepSize)
        val tiles = origins.map { (x, y) ->
            val crop = Mat(padded, Rect(x, y, windowSize, windowSize)).clone()
            ImageTile(originX = x, originY = y, mat = crop)
        }
        if (padded !== source) padded.release()
        return tiles
    }

    /**
     * Pure coordinate math for [tile], split out so it can be unit-tested
     * without touching OpenCV's native library. Mirrors oemer's
     * `range(0, length, step)` + clamp-to-edge loop axis-by-axis, then
     * takes the cross product of the two axes in (x, y) order — matching
     * oemer's `for y: for x:` nesting so batches are fed to the model in
     * the same order oemer's own merge loop expects them back.
     */
    fun computeOrigins(width: Int, height: Int, windowSize: Int, stepSize: Int): List<Pair<Int, Int>> {
        require(width >= windowSize && height >= windowSize) {
            "Source (${width}x$height) must be at least windowSize " +
                    "($windowSize) on both axes; callers should pad first"
        }
        val yOrigins = axisOrigins(height, windowSize, stepSize)
        val xOrigins = axisOrigins(width, windowSize, stepSize)
        return yOrigins.flatMap { y -> xOrigins.map { x -> x to y } }
    }

    private fun axisOrigins(length: Int, windowSize: Int, stepSize: Int): List<Int> {
        val origins = mutableListOf<Int>()
        var pos = 0
        while (pos < length) {
            origins.add(if (pos + windowSize > length) length - windowSize else pos)
            pos += stepSize
        }
        return origins
    }

    private fun padUpToWindowSize(source: Mat, windowSize: Int): Mat {
        val bottomPad = (windowSize - source.height()).coerceAtLeast(0)
        val rightPad = (windowSize - source.width()).coerceAtLeast(0)
        if (bottomPad == 0 && rightPad == 0) return source
        val padded = Mat()
        Core.copyMakeBorder(
            source, padded,
            0, bottomPad, 0, rightPad,
            Core.BORDER_REPLICATE, Scalar(0.0, 0.0, 0.0)
        )
        return padded
    }
}