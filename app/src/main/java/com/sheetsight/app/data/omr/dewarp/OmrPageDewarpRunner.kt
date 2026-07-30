package com.sheetsight.app.data.omr.dewarp

import android.graphics.Bitmap
import com.sheetsight.app.data.omr.OmrProgressListener
import com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects the existing OMR pipeline to [DewarpPipeline]: runs
 * [OmrPageInferenceRunner] (preprocess → tile → infer → argmax → masks)
 * for [page], then remaps the canonical image and masks through
 * [DewarpPipeline].
 *
 * **Memory-fix note.** [OmrPageInferenceRunner.run] now returns masks
 * directly (already argmax'd, raw prediction maps already discarded) —
 * see its class KDoc for the full memory reasoning. This class no longer
 * calls [com.sheetsight.app.data.omr.inference.ClassMaskExtractor]
 * itself; it simply takes the pre-built masks from the inference result.
 *
 * The [ImageMaskAligner] edge-replication step is still needed: the
 * masks' dimensions can occasionally be a few pixels larger than the
 * canonical image itself (a documented, rare padding edge case), and
 * passing a smaller image into [DewarpPipeline] than the masks it's
 * paired with would silently misalign every pixel after that point.
 *
 * Still stops before staffline extraction or any later OMR phase; nothing
 * here is wired into [com.sheetsight.app.data.omr.OnnxOmrEngine] yet.
 */
@Singleton
class OmrPageDewarpRunner @Inject constructor(
    private val inferenceRunner: OmrPageInferenceRunner
) {

    fun run(page: Bitmap, listener: OmrProgressListener? = null): DewarpedPage {
        val inferenceResult = inferenceRunner.run(page, listener)
        val masks = inferenceResult.masks

        val alignedImageChannels = ImageMaskAligner.alignToMaskSize(
            channels = inferenceResult.canonicalImageChannels,
            sourceWidth = inferenceResult.canonicalWidth,
            sourceHeight = inferenceResult.canonicalHeight,
            targetWidth = masks.width,
            targetHeight = masks.height
        )

        return DewarpPipeline.run(alignedImageChannels, masks)
    }
}

/**
 * Pure size-reconciliation logic, kept separate from [OmrPageDewarpRunner]
 * so it's unit-testable without constructing that class's OpenCV/ONNX-backed
 * dependency chain.
 */
internal object ImageMaskAligner {

    /**
     * Grows each channel from `sourceWidth x sourceHeight` up to
     * `targetWidth x targetHeight` by replicating the nearest edge pixel —
     * a no-op (returns [channels] as-is, same instance) in the
     * overwhelmingly common case where the sizes already match.
     */
    fun alignToMaskSize(
        channels: List<FloatArray>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<FloatArray> {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return channels
        require(sourceWidth <= targetWidth && sourceHeight <= targetHeight) {
            "Canonical image (${sourceWidth}x$sourceHeight) is larger than its own class masks " +
                    "(${targetWidth}x$targetHeight); this should never happen — PredictionMapMerger only ever pads up."
        }

        return channels.map { channel ->
            val aligned = FloatArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                val sourceY = y.coerceAtMost(sourceHeight - 1)
                val sourceRowBase = sourceY * sourceWidth
                val targetRowBase = y * targetWidth
                for (x in 0 until targetWidth) {
                    val sourceX = x.coerceAtMost(sourceWidth - 1)
                    aligned[targetRowBase + x] = channel[sourceRowBase + sourceX]
                }
            }
            aligned
        }
    }
}