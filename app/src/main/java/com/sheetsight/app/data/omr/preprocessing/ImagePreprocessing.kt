package com.sheetsight.app.data.omr.preprocessing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Converts a decoded page [Bitmap] into the exact pixel layout oemer's
 * models were trained on.
 *
 * oemer's own loader has a quirk that's worth replicating rather than
 * "fixing": `oemer/inference.py` decodes with `cv2.imread` (which returns
 * BGR-order channels) and hands that array straight to
 * `PIL.Image.fromarray(...)` *without* a channel swap. `Image.fromarray`
 * labels a 3-channel array "RGB" purely by position, so the resulting
 * array is actually BGR-ordered bytes mislabeled as RGB, and that's what
 * both ONNX checkpoints were trained on — not true RGB. Converting to
 * true RGB here would feed the network a distribution it never saw
 * during training, so this deliberately reproduces the mislabeling
 * instead of correcting it.
 *
 * [Bitmap]s decode as RGBA, so this explicitly converts RGBA → BGR
 * (dropping alpha, swapping R and B) to land on oemer's exact byte order.
 */
object ImagePreprocessing {

    /** Converts [bitmap] to a 3-channel [Mat] in oemer's BGR-as-"RGB" byte order. */
    fun toOemerOrderedMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }
}