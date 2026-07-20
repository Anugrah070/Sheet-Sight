package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OnnxTensor
import com.sheetsight.app.data.omr.preprocessing.ImageTile
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.OmrTensorFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one [OmrModelSpec]'s ONNX session against a batch of [ImageTile]s
 * and unpacks the result into per-tile [TilePrediction]s. Mirrors oemer's
 * own batched `session.run()` call in `oemer/inference.py`, except the
 * whole tile batch is sent in a single [OmrTensorFactory]-built tensor
 * rather than oemer's fixed-size mini-batches — consistent with how
 * [OmrTensorFactory] already batches every tile together.
 *
 * Deliberately synchronous, like [com.sheetsight.app.data.omr.preprocessing.OmrPreprocessor]:
 * thread choice is left to the caller.
 */
@Singleton
class TileInferenceRunner @Inject constructor(
    private val sessionProvider: OrtSessionProvider,
    private val tensorFactory: OmrTensorFactory
) {

    /** Runs [spec]'s model over every tile in [tiles], returning one [TilePrediction] each. */
    fun run(spec: OmrModelSpec, tiles: List<ImageTile>): List<TilePrediction> {
        if (tiles.isEmpty()) return emptyList()

        val session = sessionProvider.sessionFor(spec)
        tensorFactory.createInputTensor(spec, tiles).use { inputTensor ->
            session.run(mapOf(spec.inputTensorName to inputTensor)).use { results ->
                val outputTensor = results[spec.outputTensorName]
                    .orElseThrow {
                        IllegalStateException(
                            "$spec produced no output tensor named '${spec.outputTensorName}'"
                        )
                    } as OnnxTensor
                return extractPredictions(spec, tiles, outputTensor)
            }
        }
    }

    /**
     * [outputTensor]'s value is a nested `[batch][height][width][channel]`
     * float array — batch index `i` corresponds to `tiles[i]`, since
     * [OmrTensorFactory] preserves tile order when building the batch.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractPredictions(
        spec: OmrModelSpec,
        tiles: List<ImageTile>,
        outputTensor: OnnxTensor
    ): List<TilePrediction> {
        val batchOutput = outputTensor.value as Array<Array<Array<FloatArray>>>
        return tiles.indices.map { index ->
            TilePrediction(
                originX = tiles[index].originX,
                originY = tiles[index].originY,
                windowSize = spec.windowSize,
                channels = spec.outputChannels,
                values = batchOutput[index]
            )
        }
    }
}