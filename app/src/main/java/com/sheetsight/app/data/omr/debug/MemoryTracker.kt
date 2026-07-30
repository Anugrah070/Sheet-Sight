package com.sheetsight.app.data.omr.debug

import android.os.Debug
import android.util.Log

/**
 * Detailed memory metrics for a pipeline stage.
 */
data class MemorySnapshot(
    val javaUsedMb: Long,
    val javaTotalMb: Long,
    val javaMaxMb: Long,
    val nativeUsedMb: Long,
    val bitmapEstimateMb: Long = 0,
    val openCvMatCount: Int = 0,
    val onnxTensorCount: Int = 0
)

/**
 * Utility for capturing and logging detailed memory usage across Java and Native heaps.
 */
object MemoryTracker {
    private const val TAG = "MemoryTracker"

    fun capture(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val javaTotal = runtime.totalMemory()
        val javaFree = runtime.freeMemory()
        val javaUsed = javaTotal - javaFree
        val javaMax = runtime.maxMemory()

        // Native heap usage
        val nativeUsed = Debug.getNativeHeapAllocatedSize()

        return MemorySnapshot(
            javaUsedMb = javaUsed / (1024 * 1024),
            javaTotalMb = javaTotal / (1024 * 1024),
            javaMaxMb = javaMax / (1024 * 1024),
            nativeUsedMb = nativeUsed / (1024 * 1024)
            // Note: bitmapEstimateMb, openCvMatCount, onnxTensorCount require manual instrumentation
            // in the specific stages where they are created/managed.
        )
    }

    fun log(stageName: String, snapshot: MemorySnapshot) {
        val msg = buildString {
            append("[MEM] Stage: $stageName | ")
            append("Java: ${snapshot.javaUsedMb}/${snapshot.javaTotalMb}MB (Max ${snapshot.javaMaxMb}MB) | ")
            append("Native: ${snapshot.nativeUsedMb}MB")
            if (snapshot.bitmapEstimateMb > 0) append(" | Bitmaps: ~${snapshot.bitmapEstimateMb}MB")
            if (snapshot.openCvMatCount > 0) append(" | Mats: ${snapshot.openCvMatCount}")
            if (snapshot.onnxTensorCount > 0) append(" | Tensors: ${snapshot.onnxTensorCount}")
        }
        Log.d(TAG, msg)
    }
}
