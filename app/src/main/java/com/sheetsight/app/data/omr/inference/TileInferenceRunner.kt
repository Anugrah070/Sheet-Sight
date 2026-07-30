package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OnnxTensor
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.OmrTensorFactory
import com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler
import org.opencv.core.Mat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one [OmrModelSpec]'s ONNX session against a canonical page and
 * produces that model's fully-merged [OmrPredictionMap], tiling and
 * merging as it goes rather than as two separate phases.
 *
 * **History of memory fixes on this class:**
 *  1. Unbounded batch sizes — fixed by mini-batching (see
 *     [DEFAULT_BATCH_SIZE]'s KDoc).
 *  2. `OnnxTensor.value` materializing nested JVM arrays instead of
 *     reading flat `FloatBuffer`s — fixed by reading the tensor's flat
 *     buffer directly (see [accumulateBatch]'s KDoc).
 *  3. **Whole-page tile-list accumulation before merging** — the
 *     previous `run(spec, tiles): List<TilePrediction>` returned every
 *     tile's raw, un-reduced, multi-channel float output for the *entire
 *     page* as one `List`, which [PredictionMapMerger.merge] only
 *     reduced afterward. For a typical canonical page this list peaked
 *     at well over 100 MB for either model, and was the direct cause of
 *     the confirmed heap-cap crash. [runStreaming] replaces that
 *     two-phase design: [SlidingWindowTiler.tileBatches] generates one
 *     small batch of tiles at a time, each batch is inferred and
 *     immediately folded into a [PredictionMapAccumulator] sized up
 *     front, then that batch's tiles are released before the next batch
 *     is requested. At most [DEFAULT_BATCH_SIZE] tiles' raw model output
 *     is ever resident at once, for either model, instead of every tile
 *     for the whole page.
 */
@Singleton
class TileInferenceRunner @Inject constructor(
    private val sessionProvider: OrtSessionProvider,
    private val tensorFactory: OmrTensorFactory
) {

    /**
     * Streams [spec]'s sliding-window tiles over [source] in fixed-size
     * batches, running inference on each batch and immediately folding
     * its predictions into a page-sized [PredictionMapAccumulator]
     * before releasing that batch's tiles — see the class KDoc's "History
     * of memory fixes" entry #3 for the full reasoning.
     *
     * [canonicalWidth]/[canonicalHeight] are the canonical page's own
     * dimensions (before any tiling padding); the accumulator's actual
     * size mirrors exactly what [PredictionMapMerger.merge] would have
     * computed from the full tile list — `max(canonical size, padded
     * tiling size)` — computed here from tile geometry alone, before any
     * tile has been produced, since [padUpToWindowSize]'s padding formula
     * (`max(source dimension, windowSize)`) needs no image data to
     * evaluate.
     *
     * Produces a **byte-identical** [OmrPredictionMap] to
     * `PredictionMapMerger.merge(canonicalWidth, canonicalHeight, allTilesForSpec)`
     * — see [PredictionMapAccumulator]'s KDoc for why streaming
     * accumulation and whole-list accumulation are numerically the same.
     */
    fun runStreaming(
        spec: OmrModelSpec,
        source: Mat,
        canonicalWidth: Int,
        canonicalHeight: Int,
        stepSize: Int = spec.windowSize
    ): OmrPredictionMap {
        val paddedWidth = maxOf(source.width(), spec.windowSize)
        val paddedHeight = maxOf(source.height(), spec.windowSize)
        val width = maxOf(canonicalWidth, paddedWidth)
        val height = maxOf(canonicalHeight, paddedHeight)
        val accumulator = PredictionMapAccumulator(width, height, spec.outputChannels)

        val session = sessionProvider.sessionFor(spec)
        // One scratch array, reused for every tile of every batch below —
        // see accumulateBatch's KDoc for why this is safe.
        val scratch = FloatArray(spec.windowSize * spec.windowSize * spec.outputChannels)

        for (batch in SlidingWindowTiler.tileBatches(
            source = source,
            windowSize = spec.windowSize,
            stepSize = stepSize,
            batchSize = DEFAULT_BATCH_SIZE
        )) {
            try {
                tensorFactory.createInputTensor(spec, batch).use { inputTensor ->
                    session.run(mapOf(spec.inputTensorName to inputTensor)).use { results ->
                        val outputTensor = results[spec.outputTensorName]
                            .orElseThrow {
                                IllegalStateException(
                                    "$spec produced no output tensor named '${spec.outputTensorName}'"
                                )
                            } as OnnxTensor
                        accumulateBatch(batch, outputTensor, spec.windowSize, scratch, accumulator)
                    }
                }
            } finally {
                // Released as soon as this batch's predictions are folded in,
                // regardless of the batch it belongs to — no tile for the
                // whole page is ever held longer than its own batch.
                batch.forEach { it.release() }
            }
        }

        return accumulator.finish()
    }

    /**
     * Reads each tile's flat slice out of [outputTensor]'s
     * [java.nio.FloatBuffer] into the single, reused [scratch] array —
     * not a fresh [FloatArray] per tile — and immediately folds it into
     * [accumulator] before the next tile overwrites [scratch]. Batch
     * index `i` corresponds to `batch[i]`, since [OmrTensorFactory]
     * preserves tile order when building each batch's input tensor, and
     * ONNX Runtime preserves that same order in its output. Deliberately
     * does **not** call `outputTensor.value` — see the class KDoc's
     * "History of memory fixes" entry #2.
     */
    private fun accumulateBatch(
        batch: List<com.sheetsight.app.data.omr.preprocessing.ImageTile>,
        outputTensor: OnnxTensor,
        windowSize: Int,
        scratch: FloatArray,
        accumulator: PredictionMapAccumulator
    ) {
        val tileElementCount = scratch.size
        val floatBuffer = outputTensor.floatBuffer
        for (index in batch.indices) {
            floatBuffer.position(index * tileElementCount)
            floatBuffer.get(scratch)
            accumulator.accumulate(
                originX = batch[index].originX,
                originY = batch[index].originY,
                windowSize = windowSize,
                values = scratch
            )
        }
    }

    companion object {
        /**
         * Bounds native inference memory per `session.run()` call, and
         * now also bounds how many tiles' raw output are held before each
         * is folded into the running accumulator (see [runStreaming]).
         * Not verified against oemer's own `batch_size` constant (not
         * retrievable in this environment); a conservative, documented
         * mobile-safety choice.
         */
        const val DEFAULT_BATCH_SIZE = 8
    }
}