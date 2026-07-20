package com.sheetsight.app.data.omr.inference

/**
 * Merges overlapping [TilePrediction]s back into one full-page raw
 * prediction map, reproducing oemer's own merge step in
 * `oemer/inference.py`: every tile's predictions are summed into a
 * page-sized accumulator at that tile's origin, a parallel per-pixel
 * count buffer tracks how many tiles touched each pixel, and the sum is
 * divided by the count at the end — i.e. plain overlap-averaging
 * (`out[y:y+win, x:x+win] += pred; mask[y:y+win, x:x+win] += 1; out /= mask`).
 *
 * Duplicate tile origins (produced deliberately by
 * [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler] at
 * clamped edges) need no special handling here: each occurrence just adds
 * another contribution and another count at the same pixels, which is
 * exactly the weighting oemer's own duplicate-origin behavior relies on.
 */
object PredictionMapMerger {

    /**
     * Merges [predictions] (all from the same [com.sheetsight.app.data.omr.preprocessing.OmrModelSpec])
     * into one [OmrPredictionMap].
     *
     * The output is sized to whichever is larger of ([canonicalWidth],
     * [canonicalHeight]) or the extent actually covered by tile origins.
     * These normally match; they can diverge only if
     * [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler] had
     * to pad a too-small source up to its window size, in which case
     * tile coordinates run past the canonical page size — matching
     * oemer's own `out = np.zeros(image.shape[:2] + ...)`, where `image`
     * is the (possibly padded) array actually being tiled.
     */
    fun merge(
        canonicalWidth: Int,
        canonicalHeight: Int,
        predictions: List<TilePrediction>
    ): OmrPredictionMap {
        require(predictions.isNotEmpty()) { "Cannot merge an empty tile-prediction list" }
        val channels = predictions.first().channels
        require(predictions.all { it.channels == channels }) {
            "All tile predictions being merged must share one channel count"
        }

        val width = maxOf(canonicalWidth, predictions.maxOf { it.originX + it.windowSize })
        val height = maxOf(canonicalHeight, predictions.maxOf { it.originY + it.windowSize })

        val sum = FloatArray(width * height * channels)
        val count = IntArray(width * height)
        accumulate(predictions, width, channels, sum, count)
        average(sum, count, channels)

        return OmrPredictionMap(width = width, height = height, channels = channels, data = sum)
    }

    private fun accumulate(
        predictions: List<TilePrediction>,
        pageWidth: Int,
        channels: Int,
        sum: FloatArray,
        count: IntArray
    ) {
        for (prediction in predictions) {
            for (dy in 0 until prediction.windowSize) {
                val py = prediction.originY + dy
                val row = prediction.values[dy]
                for (dx in 0 until prediction.windowSize) {
                    val px = prediction.originX + dx
                    val pixelIndex = py * pageWidth + px
                    count[pixelIndex] += 1
                    val base = pixelIndex * channels
                    val values = row[dx]
                    for (c in 0 until channels) {
                        sum[base + c] += values[c]
                    }
                }
            }
        }
    }

    private fun average(sum: FloatArray, count: IntArray, channels: Int) {
        for (pixelIndex in count.indices) {
            val n = count[pixelIndex]
            if (n <= 1) continue // untouched (n=0, left at 0f) or already-correct single contribution
            val base = pixelIndex * channels
            for (c in 0 until channels) {
                sum[base + c] /= n
            }
        }
    }
}

/**
 * Full-page raw, un-thresholded prediction map for one
 * [com.sheetsight.app.data.omr.preprocessing.OmrModelSpec]: tile outputs
 * merged and overlap-averaged, but not argmax'd into a discrete class map
 * and not yet interpreted as staff lines or symbols — that belongs to a
 * later phase.
 *
 * [data] is row-major HWC: pixel `(x, y)` channel `c` lives at
 * `(y * width + x) * channels + c`.
 */
data class OmrPredictionMap(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: FloatArray
) {
    /** Returns the [channels]-length raw prediction vector at pixel ([x], [y]). */
    fun valuesAt(x: Int, y: Int): FloatArray {
        val base = (y * width + x) * channels
        return data.copyOfRange(base, base + channels)
    }
}