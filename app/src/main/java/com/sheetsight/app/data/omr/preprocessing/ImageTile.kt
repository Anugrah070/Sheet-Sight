package com.sheetsight.app.data.omr.preprocessing

import org.opencv.core.Mat

/**
 * One square crop out of the canonically-resized page, ready for model
 * input. [originX]/[originY] are this tile's top-left corner in the
 * *resized* image's coordinate space — Phase 4.3's post-processing needs
 * them to stitch per-tile predictions back into a full-page map, the same
 * way oemer's `out[y:y+win, x:x+win] += hop` merge step does.
 *
 * [mat] is a `CV_8UC3` [Mat] owned by this tile. Callers must call
 * [release] once done with it — OpenCV `Mat`s hold native memory that the
 * JVM garbage collector does not free on its own.
 */
data class ImageTile(
    val originX: Int,
    val originY: Int,
    val mat: Mat
) {
    /** Releases the native memory backing [mat]. Safe to call more than once. */
    fun release() {
        mat.release()
    }
}