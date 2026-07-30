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
 *
 * **Memory note.** The direct [ByteBuffer] backing each batch's input
 * tensor used to be allocated fresh on every call
 * (`ByteBuffer.allocateDirect(...)`) — roughly 30 such allocations per
 * model per page, each up to ~1.9 MB. Direct buffers are native (off-heap)
 * memory whose cleanup depends on GC-triggered finalization rather than
 * ordinary heap collection, so repeatedly allocating and discarding them
 * churns native memory in a way that doesn't show up as Java-heap
 * pressure but still contributes to the process's total RSS and to
 * `lowmemorykiller` risk. [createInputTensor] now reuses one scratch
 * buffer, grown only if a larger batch is ever requested (see
 * [obtainScratchBuffer]).
 *
 * **Not thread-safe.** The scratch buffer is a single shared field reused
 * across calls; this class assumes the same sequential, single-dispatcher
 * usage pattern the rest of the OMR pipeline already follows (one page's
 * tiles processed one batch at a time, never concurrently). If concurrent
 * calls are ever introduced, this class would need external
 * synchronization or a buffer per caller.
 */
@Singleton
class OmrTensorFactory @Inject constructor(
    private val ortEnvironment: OrtEnvironment
) {

    // Reused across every createInputTensor call. Grown (never shrunk) the
    // first time a bigger batch than previously seen is requested, so at
    // most one direct allocation happens per distinct batch-byte-size this
    // factory is ever asked for over its lifetime, instead of one fresh
    // allocation every batch.
    private var scratchBuffer: ByteBuffer? = null
    private var scratchCapacity: Int = 0

    /**
     * Builds one NHWC UINT8 tensor from [tiles]. All tiles must share
     * [spec]'s window size — [OmrPreprocessor]/[SlidingWindowTiler]
     * guarantee this since tiling is always done per-[OmrModelSpec].
     */
    fun createInputTensor(spec: OmrModelSpec, tiles: List<ImageTile>): OnnxTensor {
        require(tiles.isNotEmpty()) { "Cannot build a tensor from an empty tile batch" }

        val windowSize = spec.windowSize
        val channels = OmrModelSpec.CHANNELS
        val tileByteCount = windowSize * windowSize * channels
        val neededBytes = tiles.size * tileByteCount

        val buffer = obtainScratchBuffer(neededBytes)

        val tileBytes = ByteArray(tileByteCount)
        for (tile in tiles) {
            val mat = tile.mat
            require(mat.width() == windowSize && mat.height() == windowSize) {
                "Tile at (${tile.originX}, ${tile.originY}) is ${mat.width()}x${mat.height()}, " +
                        "expected ${windowSize}x$windowSize for $spec"
            }
            require(mat.type() == CvType.CV_8UC3) {
                "Tile at (${tile.originX}, ${tile.originY}) has OpenCV type ${mat.type()}, expected CV_8UC3"
            }
            mat.get(0, 0, tileBytes)
            buffer.put(tileBytes)
        }
        buffer.rewind() // position back to 0; limit stays at neededBytes, set by obtainScratchBuffer

        val shape = longArrayOf(
            tiles.size.toLong(),
            windowSize.toLong(),
            windowSize.toLong(),
            channels.toLong()
        )
        return OnnxTensor.createTensor(ortEnvironment, buffer, shape, OnnxJavaType.UINT8)
    }

    /**
     * Returns a [ByteBuffer] positioned at 0 with its limit set to exactly
     * [neededBytes], reusing the previously-allocated backing native
     * memory whenever it's already big enough instead of allocating again.
     *
     * A [ByteBuffer.duplicate] of the shared field is returned — a new,
     * lightweight *view* object with its own independent position/limit,
     * not a new native allocation — rather than the shared field itself,
     * so each call gets clean position/limit state without disturbing any
     * other outstanding reference. This is safe to reuse across calls
     * because batches are always processed to completion sequentially:
     * [TileInferenceRunner.runStreaming] creates a tensor from this
     * buffer, runs inference, reads the output, and closes the tensor
     * before ever requesting the next batch — so by the time this method
     * is called again and starts overwriting the backing memory, nothing
     * still needs the previous batch's bytes.
     *
     * Note: [ByteBuffer.duplicate] does **not** preserve byte order (a
     * well-known JDK quirk — a duplicated buffer always starts
     * big-endian), so [ByteOrder.nativeOrder] is re-applied explicitly on
     * every duplicate returned here.
     */
    private fun obtainScratchBuffer(neededBytes: Int): ByteBuffer {
        if (scratchCapacity < neededBytes) {
            scratchBuffer = ByteBuffer.allocateDirect(neededBytes)
            scratchCapacity = neededBytes
        }
        val duplicate = scratchBuffer!!.duplicate().order(ByteOrder.nativeOrder())
        duplicate.clear()
        duplicate.limit(neededBytes)
        return duplicate
    }
}