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
 *
 * **Kept for compatibility, no longer the production hot path.** This
 * one-shot, whole-list form is still exactly correct, but the production
 * pipeline ([com.sheetsight.app.data.omr.inference.TileInferenceRunner.runStreaming])
 * now uses [PredictionMapAccumulator] directly, folding each tile's
 * prediction in immediately after inference rather than materializing a
 * `List<TilePrediction>` for the whole page first. [merge] is implemented
 * in terms of the same accumulator below, so both paths share one
 * reduction implementation and can never drift apart numerically.
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

        val accumulator = PredictionMapAccumulator(width, height, channels)
        for (prediction in predictions) {
            accumulator.accumulate(
                originX = prediction.originX,
                originY = prediction.originY,
                windowSize = prediction.windowSize,
                values = prediction.values
            )
        }
        return accumulator.finish()
    }
}

/**
 * Incremental, streaming form of the overlap-average merge
 * [PredictionMapMerger.merge] performs in one shot.
 *
 * Added for the streaming-inference memory fix (see
 * [TileInferenceRunner.runStreaming]): a page-sized `sum`/`count`
 * accumulator is built once, up front — its size is knowable from tile
 * geometry alone, before any tile has actually been produced — and each
 * tile's raw prediction is folded into it via [accumulate] the moment
 * that tile comes back from inference, instead of first collecting every
 * tile for the whole page into a `List` and reducing it afterward.
 *
 * **Produces byte-identical results to [PredictionMapMerger.merge]**,
 * provided [accumulate] is called once per tile in the same order
 * [merge] would have iterated its input list. Floating-point addition
 * has one fixed evaluation order either way — this class does no
 * reordering, batching, or reassociation of its own — so accumulating
 * incrementally versus summing a fully-materialized list are the exact
 * same sequence of additions in the exact same order. The smaller memory
 * footprint changes *when* each tile's contribution is added, never *how
 * many times* or *in what order*.
 */
class PredictionMapAccumulator(
    private val width: Int,
    private val height: Int,
    private val channels: Int
) {
    private val sum = FloatArray(width * height * channels)
    private val count = IntArray(width * height)

    /**
     * Folds one tile's raw, flat, row-major `[y][x][channel]` [values]
     * (see [TilePrediction.values] for the exact flattening convention)
     * into the running accumulator at ([originX], [originY]).
     */
    fun accumulate(originX: Int, originY: Int, windowSize: Int, values: FloatArray) {
        for (dy in 0 until windowSize) {
            val py = originY + dy
            val srcRowBase = dy * windowSize * channels
            val destRowBase = py * width
            for (dx in 0 until windowSize) {
                val px = originX + dx
                val pixelIndex = destRowBase + px
                count[pixelIndex] += 1
                val destBase = pixelIndex * channels
                val srcBase = srcRowBase + dx * channels
                for (c in 0 until channels) {
                    sum[destBase + c] += values[srcBase + c]
                }
            }
        }
    }

    /**
     * Averages every multiply-touched pixel and returns the finished
     * [OmrPredictionMap]. Call exactly once, after every tile has been
     * passed to [accumulate].
     */
    fun finish(): OmrPredictionMap {
        for (pixelIndex in count.indices) {
            val n = count[pixelIndex]
            if (n <= 1) continue // untouched (n=0, left at 0f) or already-correct single contribution
            val base = pixelIndex * channels
            for (c in 0 until channels) {
                sum[base + c] /= n
            }
        }
        return OmrPredictionMap(width = width, height = height, channels = channels, data = sum)
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