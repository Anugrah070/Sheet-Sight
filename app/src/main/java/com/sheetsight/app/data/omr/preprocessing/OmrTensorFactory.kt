package com.sheetsight.app.data.omr.preprocessing

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.opencv.core.CvType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Packs [ImageTile]s into the exact [OnnxTensor] shape/dtype the verified
 * ONNX checkpoints expect: UINT8, NHWC, `[batch, windowSize, windowSize, 3]`
 * (see [OmrModelSpec]). Matches oemer's own batch construction —
 * `np.array(data[idx:idx+batch_size])` in `oemer/inference.py` — which is
 * plain uint8 pixel values with no float normalization; the checkpoints
 * were trained on raw 0–255 input, so none is applied here either.
 *
 * Returned tensors are [AutoCloseable]; the caller (Phase 4.3's inference
 * step) owns their lifecycle and must close them after `session.run(...)`.
 */
@Singleton
class OmrTensorFactory @Inject constructor(
    private val ortEnvironment: OrtEnvironment
) {

    /**
     * Builds one NHWC UINT8 tensor from [tiles]. All tiles must share
     * [spec]'s window size — [OmrPreprocessor] guarantees this since it
     * tiles per-[OmrModelSpec].
     */
    fun createInputTensor(spec: OmrModelSpec, tiles: List<ImageTile>): OnnxTensor {
        require(tiles.isNotEmpty()) { "Cannot build a tensor from an empty tile batch" }

        val windowSize = spec.windowSize
        val channels = OmrModelSpec.CHANNELS
        val tileByteCount = windowSize * windowSize * channels
        val buffer = ByteBuffer
            .allocateDirect(tiles.size * tileByteCount)
            .order(ByteOrder.nativeOrder())

        val row = ByteArray(windowSize * channels)
        for (tile in tiles) {
            val mat = tile.mat
            require(mat.width() == windowSize && mat.height() == windowSize) {
                "Tile at (${tile.originX}, ${tile.originY}) is ${mat.width()}x${mat.height()}, " +
                        "expected ${windowSize}x$windowSize for $spec"
            }
            require(mat.type() == CvType.CV_8UC3) {
                "Tile at (${tile.originX}, ${tile.originY}) has OpenCV type ${mat.type()}, expected CV_8UC3"
            }
            for (y in 0 until windowSize) {
                mat.get(y, 0, row)
                buffer.put(row)
            }
        }
        buffer.rewind()

        val shape = longArrayOf(
            tiles.size.toLong(),
            windowSize.toLong(),
            windowSize.toLong(),
            channels.toLong()
        )
        return OnnxTensor.createTensor(ortEnvironment, buffer, shape, OnnxJavaType.UINT8)
    }
}