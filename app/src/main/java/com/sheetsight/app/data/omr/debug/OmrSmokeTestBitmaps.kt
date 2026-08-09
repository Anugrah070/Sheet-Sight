package com.sheetsight.app.data.omr.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import kotlin.math.roundToInt

/**
 * Bitmap-rendering helpers used only by [OmrSmokeTestRunner] to turn raw
 * pipeline arrays (BGR float channels, boolean class masks) into small
 * on-screen previews. Purely a debug-visualization concern — nothing
 * here feeds back into or alters the real OMR pipeline's data.
 *
 * [DEFAULT_MAX_DIMENSION] lowered from the previous 480px to 320px per
 * the memory-isolation requirement (target range 256–512px on the
 * longest edge) — these are diagnostic thumbnails, not review-quality
 * previews, so erring smaller is preferable while chasing a stall/OOM.
 * Every method here builds one full-resolution temporary [Bitmap]
 * (unavoidable — `setPixels` has no downscaled-write variant), then
 * immediately downsamples and `recycle()`s the temporary before
 * returning, so no full-size bitmap outlives its own creating function.
 */
internal object OmrSmokeTestBitmaps {

    private const val DEFAULT_MAX_DIMENSION = 320
    private const val OPAQUE_BLACK = 0xFF shl 24
    private const val OPAQUE_WHITE = -1

    /** Downscaled copy of [bitmap]; [bitmap] itself is left untouched (caller may still need it). */
    fun thumbnailOf(bitmap: Bitmap, maxDimension: Int = DEFAULT_MAX_DIMENSION): Bitmap {
        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale >= 1f) {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }

    /**
     * Renders oemer's BGR-ordered [channels] (see
     * [com.sheetsight.app.data.omr.preprocessing.ImagePreprocessing]) as a
     * downscaled preview. A missing channel falls back to the previous
     * one so a 1-channel debug input still renders in grayscale instead
     * of crashing. The full-resolution [full] bitmap built here is
     * recycled before returning — only the thumbnail escapes this method.
     */
    fun channelsToThumbnail(
        channels: List<FloatArray>,
        width: Int,
        height: Int,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Bitmap {
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val blue = channels.getOrNull(0) ?: FloatArray(width * height)
        val green = channels.getOrNull(1) ?: blue
        val red = channels.getOrNull(2) ?: green
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = red[i].toInt().coerceIn(0, 255)
            val g = green[i].toInt().coerceIn(0, 255)
            val b = blue[i].toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        full.setPixels(pixels, 0, width, 0, 0, width, height)
        val thumbnail = thumbnailOf(full, maxDimension)
        full.recycle()
        return thumbnail
    }

    /** Renders a boolean mask as black-on-white (foreground = black), downscaled. Full-size temp is recycled. */
    fun maskToThumbnail(
        mask: BooleanArray,
        width: Int,
        height: Int,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Bitmap {
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { if (mask[it]) OPAQUE_BLACK else OPAQUE_WHITE }
        full.setPixels(pixels, 0, width, 0, 0, width, height)
        val thumbnail = thumbnailOf(full, maxDimension)
        full.recycle()
        return thumbnail
    }

    /** Renders all five [OmrClassMasks] layers as [OmrSmokeTestMaskThumbnails]. */
    fun masksToThumbnails(masks: OmrClassMasks, maxDimension: Int = DEFAULT_MAX_DIMENSION): OmrSmokeTestMaskThumbnails =
        OmrSmokeTestMaskThumbnails(
            staff = maskToThumbnail(masks.staff, masks.width, masks.height, maxDimension),
            symbols = maskToThumbnail(masks.symbols, masks.width, masks.height, maxDimension),
            stemsRests = maskToThumbnail(masks.stemsRests, masks.width, masks.height, maxDimension),
            noteheads = maskToThumbnail(masks.noteheads, masks.width, masks.height, maxDimension),
            clefsKeys = maskToThumbnail(masks.clefsKeys, masks.width, masks.height, maxDimension)
        )

    /** Draws coordinate-scaled evidence directly on a tiny bitmap copy. */
    fun overlayThumbnail(
        background: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        lines: List<DebugOverlayLine> = emptyList(),
        boxes: List<DebugOverlayBox> = emptyList(),
        labels: List<DebugOverlayLabel> = emptyList()
    ): Bitmap {
        require(sourceWidth > 0 && sourceHeight > 0)
        val output = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scaleX = output.width.toFloat() / sourceWidth
        val scaleY = output.height.toFloat() / sourceHeight
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        lines.forEach { line ->
            paint.color = line.color
            canvas.drawLine(
                line.left * scaleX,
                line.top * scaleY,
                line.right * scaleX,
                line.bottom * scaleY,
                paint
            )
        }
        boxes.forEach { box ->
            paint.color = box.color
            canvas.drawRect(
                box.left * scaleX,
                box.top * scaleY,
                box.right * scaleX,
                box.bottom * scaleY,
                paint
            )
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 9f
        labels.forEach { label ->
            paint.color = label.color
            canvas.drawText(label.text, label.x * scaleX, label.y * scaleY, paint)
        }
        return output
    }
}

internal data class DebugOverlayLine(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val color: Int
)

internal data class DebugOverlayBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val color: Int
)

internal data class DebugOverlayLabel(
    val x: Int,
    val y: Int,
    val text: String,
    val color: Int
)
