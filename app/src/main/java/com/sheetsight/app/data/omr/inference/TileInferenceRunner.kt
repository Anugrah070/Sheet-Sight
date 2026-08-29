package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OnnxTensor
import android.os.SystemClock
import android.util.Log
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
 */
@Singleton
class TileInferenceRunner @Inject constructor(
    private val sessionProvider: OrtSessionProvider,
    private val tensorFactory: OmrTensorFactory
) {

    /** Returns metadata re-read from the loaded graph for diagnostics. */
    fun verifiedContract(spec: OmrModelSpec): OmrModelContract =
        OmrModelContractVerifier.verify(sessionProvider.sessionFor(spec), spec)

    /**
     * Streams [spec]'s sliding-window tiles over [source] in fixed-size
     * batches, running inference on each batch and immediately folding
     * its predictions into a page-sized [PredictionMapAccumulator]
     * before releasing that batch's tiles.
     */
    fun runStreaming(
        spec: OmrModelSpec,
        source: Mat,
        canonicalWidth: Int,
        canonicalHeight: Int,
        stepSize: Int = spec.windowSize,
        batchSize: Int = OmrRuntimeTuning.inferenceBatchSize,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): OmrPredictionMap {
        require(batchSize > 0)
        val paddedWidth = maxOf(source.width(), spec.windowSize)
        val paddedHeight = maxOf(source.height(), spec.windowSize)
        val width = maxOf(canonicalWidth, paddedWidth)
        val height = maxOf(canonicalHeight, paddedHeight)
        val accumulator = PredictionMapAccumulator(width, height, spec.outputChannels)

        val session = sessionProvider.sessionFor(spec)

        val totalTiles = SlidingWindowTiler.computeOrigins(paddedWidth, paddedHeight, spec.windowSize, stepSize).size
        var currentTileCount = 0

        val totalStart = SystemClock.elapsedRealtime()
        var totalTensorMs = 0L
        var totalRunMs = 0L
        var totalAccumMs = 0L

        for (batch in SlidingWindowTiler.tileBatches(
            source = source,
            windowSize = spec.windowSize,
            stepSize = stepSize,
            batchSize = batchSize
        )) {
            try {
                val tensorStart = SystemClock.elapsedRealtime()
                tensorFactory.createInputTensor(spec, batch).use { inputTensor ->
                    totalTensorMs += SystemClock.elapsedRealtime() - tensorStart
                    
                    val runStart = SystemClock.elapsedRealtime()
                    session.run(mapOf(spec.inputTensorName to inputTensor)).use { results ->
                        totalRunMs += SystemClock.elapsedRealtime() - runStart
                        
                        val outputTensor = results[spec.outputTensorName]
                            .orElseThrow {
                                IllegalStateException(
                                    "$spec produced no output tensor named '${spec.outputTensorName}'"
                                )
                            } as OnnxTensor
                        
                        val accumStart = SystemClock.elapsedRealtime()
                        accumulateBatchDirect(batch, outputTensor, spec.windowSize, spec.outputChannels, accumulator)
                        totalAccumMs += SystemClock.elapsedRealtime() - accumStart
                        
                        currentTileCount += batch.size
                        onProgress?.invoke(currentTileCount, totalTiles)
                    }
                }
            } finally {
                batch.forEach { it.release() }
            }
        }

        val totalTime = SystemClock.elapsedRealtime() - totalStart
        Log.i("OmrPerf", "[PERF] Model: ${spec.name} | Tiles: $totalTiles | Batch: $batchSize | Total: ${totalTime}ms " +
                "(Tensor: ${totalTensorMs}ms, Run: ${totalRunMs}ms, Accum: ${totalAccumMs}ms)")

        return accumulator.finish()
    }

    private fun accumulateBatchDirect(
        batch: List<com.sheetsight.app.data.omr.preprocessing.ImageTile>,
        outputTensor: OnnxTensor,
        windowSize: Int,
        channels: Int,
        accumulator: PredictionMapAccumulator
    ) {
        val tileElementCount = windowSize * windowSize * channels
        val floatBuffer = outputTensor.floatBuffer
        for (index in batch.indices) {
            accumulator.accumulate(
                originX = batch[index].originX,
                originY = batch[index].originY,
                windowSize = windowSize,
                buffer = floatBuffer,
                offset = index * tileElementCount
            )
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 8
    }
}
