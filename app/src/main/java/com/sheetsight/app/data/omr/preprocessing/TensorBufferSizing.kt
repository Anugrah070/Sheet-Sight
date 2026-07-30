package com.sheetsight.app.data.omr.preprocessing

/**
 * Pure arithmetic for sizing the direct [java.nio.ByteBuffer]s
 * [OmrTensorFactory] packs NHWC UINT8 tile batches into. Split out from
 * [OmrTensorFactory] so the sizing math is unit-testable on the plain JVM
 * without touching OpenCV or ONNX Runtime's native libraries — the same
 * split [CanonicalImageResizer] and [SlidingWindowTiler] already use for
 * their own pure-math helpers, extended here for this phase's buffer-reuse
 * optimization.
 */
object TensorBufferSizing {

    /** Bytes one [spec] tile occupies: `windowSize * windowSize * channels`, one byte per UINT8 pixel channel. */
    fun tileByteCount(spec: OmrModelSpec): Int =
        spec.windowSize * spec.windowSize * OmrModelSpec.CHANNELS

    /** Bytes needed to hold [batchSize] tiles of [spec] back-to-back, NHWC-flattened. */
    fun requiredCapacityBytes(spec: OmrModelSpec, batchSize: Int): Int {
        require(batchSize > 0) { "batchSize must be positive, got $batchSize" }
        return tileByteCount(spec) * batchSize
    }

    /**
     * The largest single mini-batch [com.sheetsight.app.data.omr.inference.TileInferenceRunner]
     * will ever build for a page with [totalTiles] tiles under a fixed
     * [maxBatchSize] cap — i.e. the size a reused buffer must be allocated
     * for, once, up front, to safely cover every mini-batch in that run.
     */
    fun maxBatchSizeForRun(totalTiles: Int, maxBatchSize: Int): Int {
        require(totalTiles > 0) { "totalTiles must be positive, got $totalTiles" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive, got $maxBatchSize" }
        return minOf(maxBatchSize, totalTiles)
    }
}