package com.sheetsight.app.data.omr.dewarp

import android.graphics.Bitmap
import com.sheetsight.app.data.omr.inference.ClassMaskExtractor
import com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects the existing OMR pipeline to [DewarpPipeline]: runs
 * [OmrPageInferenceRunner] (preprocess → tile → infer → merge) for
 * [page], extracts the five class masks via [ClassMaskExtractor] — both
 * completely unchanged, per this phase's requirement to leave raw
 * prediction maps and class-mask behavior alone — then remaps the
 * canonical image and masks through [DewarpPipeline].
 *
 * Only genuinely new piece: [OmrPredictionMap][com.sheetsight.app.data.omr.inference.OmrPredictionMap]
 * (hence [com.sheetsight.app.data.omr.inference.OmrClassMasks]) can
 * occasionally be a few pixels larger than the canonical image itself —
 * a documented, rare padding edge case in `PredictionMapMerger`. Passing
 * a smaller image into [DewarpPipeline] than the masks it's paired with
 * would silently misalign every pixel after that point, so [ImageMaskAligner]
 * edge-replicates the image up to the masks' exact size first — the same
 * border convention used throughout this dewarp package — rather than
 * letting [DewarpPipeline]'s own size check reject it.
 *
 * Still stops before staffline extraction or any later OMR phase; nothing
 * here is wired into [com.sheetsight.app.data.omr.OnnxOmrEngine] yet.
 */
@Singleton
class OmrPageDewarpRunner @Inject constructor(
    private val inferenceRunner: OmrPageInferenceRunner
) {

    fun run(page: Bitmap): DewarpedPage {
        val inferenceResult = inferenceRunner.run(page)
        val masks = ClassMaskExtractor.extract(inferenceResult.predictionsByModel)

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