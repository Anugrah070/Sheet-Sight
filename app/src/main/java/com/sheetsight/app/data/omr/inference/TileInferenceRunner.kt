package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OnnxTensor
import com.sheetsight.app.data.omr.preprocessing.ImageTile
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.OmrTensorFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one [OmrModelSpec]'s ONNX session against a batch of [ImageTile]s
 * and unpacks the result into per-tile [TilePrediction]s.
 *
 * **Mini-batched** (see [DEFAULT_BATCH_SIZE]'s KDoc) to bound native/RSS
 * memory — a prior fix for a confirmed `lowmemorykiller` OOM kill during
 * STAGE 4 (model 1 inference) diagnostics.
 *
 * **Flat-buffer extraction, not `.value`.** A second, distinct OOM was
 * then observed: a Java-heap `OutOfMemoryError` (`target footprint
 * 402653184`, i.e. the ~384MB app heap cap) thrown from an unrelated
 * Compose frame callback — a symptom of the heap already being
 * exhausted elsewhere. The cause was calling `outputTensor.value`, which
 * makes ONNX Runtime materialize its output as a triple-nested
 * `Array<Array<Array<FloatArray>>>` — for a 256x256 tile that's 65,536
 * individual [FloatArray] objects *per tile*, each with JVM
 * object-header overhead on top of its 3–4 floats of real data. Even at
 * a batch size of [DEFAULT_BATCH_SIZE], that was ~524,000 tiny heap
 * objects manufactured (and immediately garbage) per `session.run()`
 * call, repeated dozens of times per page — enough GC churn and
 * fragmentation to exhaust the heap on its own.
 *
 * [extractPredictions] instead reads `outputTensor.floatBuffer` — a flat
 * [java.nio.FloatBuffer] view directly over the tensor's own contiguous
 * memory — and copies each tile's slice into one flat
 * [TilePrediction.values] array. Same values, same order, one object
 * instead of tens of thousands.
 */
@Singleton
class TileInferenceRunner @Inject constructor(
    private val sessionProvider: OrtSessionProvider,
    private val tensorFactory: OmrTensorFactory
) {

    /**
     * Runs [spec]'s model over every tile in [tiles] in fixed-size
     * mini-batches of at most [DEFAULT_BATCH_SIZE], returning one
     * [TilePrediction] per tile in original order.
     */
    fun run(spec: OmrModelSpec, tiles: List<ImageTile>): List<TilePrediction> {
        if (tiles.isEmpty()) return emptyList()

        val session = sessionProvider.sessionFor(spec)
        val predictions = ArrayList<TilePrediction>(tiles.size)

        var start = 0
        while (start < tiles.size) {
            val end = minOf(start + DEFAULT_BATCH_SIZE, tiles.size)
            val batch = tiles.subList(start, end)

            tensorFactory.createInputTensor(spec, batch).use { inputTensor ->
                session.run(mapOf(spec.inputTensorName to inputTensor)).use { results ->
                    val outputTensor = results[spec.outputTensorName]
                        .orElseThrow {
                            IllegalStateException(
                                "$spec produced no output tensor named '${spec.outputTensorName}'"
                            )
                        } as OnnxTensor
                    predictions.addAll(extractPredictions(spec, batch, outputTensor))
                }
            }

            start = end
        }

        return predictions
    }

    /**
     * Copies each tile's flat `windowSize*windowSize*channels` slice out
     * of [outputTensor]'s underlying [java.nio.FloatBuffer] — batch index
     * `i` corresponds to `batch[i]`, since [OmrTensorFactory] preserves
     * tile order when building each mini-batch's input tensor, and ONNX
     * Runtime preserves that same order in its output. Deliberately does
     * **not** call `outputTensor.value` — see the class KDoc for why.
     */
    private fun extractPredictions(
        spec: OmrModelSpec,
        batch: List<ImageTile>,
        outputTensor: OnnxTensor
    ): List<TilePrediction> {
        val tileElementCount = spec.windowSize * spec.windowSize * spec.outputChannels
        val floatBuffer = outputTensor.floatBuffer
        return batch.indices.map { index ->
            val flat = FloatArray(tileElementCount)
            floatBuffer.position(index * tileElementCount)
            floatBuffer.get(flat)
            TilePrediction(
                originX = batch[index].originX,
                originY = batch[index].originY,
                windowSize = spec.windowSize,
                channels = spec.outputChannels,
                values = flat
            )
        }
    }

    companion object {
        /**
         * Bounds native inference memory per [run]'s `session.run()` call
         * — see the class KDoc's first OOM fix. Not verified against
         * oemer's own `batch_size` constant (not retrievable in this
         * environment); a conservative, documented mobile-safety choice.
         */
        private const val DEFAULT_BATCH_SIZE = 8
    }
}