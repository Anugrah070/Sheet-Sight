package com.sheetsight.app.data.omr.preprocessing

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reproduces oemer's `resize_image()` (`oemer/inference.py`): before any
 * tiling happens, the source page is rescaled — independent of its
 * original resolution — so its total pixel count lands at the midpoint
 * of oemer's trained-on range of 3,000,000–4,350,000 px, preserving
 * aspect ratio. This is oemer's only page-level normalization step at
 * this stage; deskewing in oemer runs later, on model *output*, not here.
 *
 * oemer's Python source also has an early-return guard —
 * `if 3_000_000 <= pixels <= 435_000: return image` — that can never
 * fire, since the range is inverted (435,000 < 3,000,000). Every image
 * always falls through to the scaling math below, so that dead branch is
 * intentionally omitted here; omitting unreachable code changes no
 * observable behavior.
 */
object CanonicalImageResizer {

    const val TARGET_PIXELS_LOWER_BOUND = 3_000_000
    const val TARGET_PIXELS_UPPER_BOUND = 4_350_000
    const val DEFAULT_TARGET_PIXELS =
        (TARGET_PIXELS_LOWER_BOUND + TARGET_PIXELS_UPPER_BOUND) / 2

    /**
     * Pure size computation, kept separate from [resize] so the scaling
     * math is unit-testable on the plain JVM without loading OpenCV's
     * native library.
     */
    fun computeTargetSize(sourceWidth: Int, sourceHeight: Int): TargetSize {
        return computeTargetSize(sourceWidth, sourceHeight, DEFAULT_TARGET_PIXELS)
    }

    /** Controlled debug/evaluation profile; production uses [DEFAULT_TARGET_PIXELS]. */
    fun computeTargetSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetPixels: Int
    ): TargetSize {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Source dimensions must be positive, got ${sourceWidth}x$sourceHeight"
        }
        require(targetPixels in TARGET_PIXELS_LOWER_BOUND..TARGET_PIXELS_UPPER_BOUND) {
            "targetPixels must stay inside the models' trained range, got $targetPixels"
        }
        val pixelCount = sourceWidth.toDouble() * sourceHeight.toDouble()
        val scale = sqrt(targetPixels / pixelCount)
        return TargetSize(
            width = (scale * sourceWidth).roundToInt(),
            height = (scale * sourceHeight).roundToInt()
        )
    }

    /**
     * Resizes [source] to [computeTargetSize]'s result. Uses bicubic
     * interpolation to match PIL's `Image.resize()` default resample
     * filter for RGB-mode images (`Image.Resampling.BICUBIC`) — oemer
     * never passes an explicit `resample` argument, so BICUBIC is what
     * the checkpoints were actually trained against.
     */
    fun resize(source: Mat, targetPixels: Int = DEFAULT_TARGET_PIXELS): Mat {
        val target = computeTargetSize(source.width(), source.height(), targetPixels)
        val destination = Mat()
        Imgproc.resize(
            source,
            destination,
            Size(target.width.toDouble(), target.height.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_CUBIC
        )
        return destination
    }

    data class TargetSize(val width: Int, val height: Int)
}
