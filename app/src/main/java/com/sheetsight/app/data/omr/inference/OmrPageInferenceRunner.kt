package com.sheetsight.app.data.omr.inference

import android.graphics.Bitmap
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.OmrPreprocessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full Phase 4.2–4.4 pipeline for a single decoded page: oemer
 * compatible preprocessing, per-tile ONNX inference for both
 * [OmrModelSpec]s, overlap-averaged merging, and **immediate per-model
 * argmax** into the five class masks downstream stages need.
 *
 * Also surfaces the canonical (resized) page's own pixel data —
 * [OmrPageInferenceResult.canonicalImageChannels] — since Phase 4.5E's
 * dewarp integration needs the decoded image alongside the masks, and
 * re-deriving it separately would mean resizing the page twice.
 *
 * **Streaming-inference memory fix (fix #3).** Tiling and inference are
 * fused per [OmrModelSpec] via [TileInferenceRunner.runStreaming]: tiles
 * for a given model are generated, inferred, and merged into that model's
 * [OmrPredictionMap] in small batches, one at a time — see
 * [TileInferenceRunner]'s class KDoc for the full history.
 *
 * **Immediate-argmax memory fix (fix #4).** The previous version of [run]
 * held *both* models' fully-merged [OmrPredictionMap]s (44.1 MB + 58.8 MB
 * = 102.9 MB `FloatArray` heap for a typical canonical page) alive
 * simultaneously in the returned [OmrPageInferenceResult], even though
 * nothing after [ClassMaskExtractor] ever reads the raw float data again.
 * [run] now argmaxes each model's prediction map **immediately** after
 * that model's streaming inference finishes, reducing its multi-channel
 * `FloatArray` (44.1 or 58.8 MB) to a single-channel `IntArray` (14.7 MB)
 * before the next model starts — so the two full float maps are never
 * alive concurrently. The five boolean masks are then built from the two
 * `IntArray`s via [ClassMaskExtractor.extractFromArgmaxed].
 *
 * [OmrPreprocessor.preprocess]'s resized [org.opencv.core.Mat] is kept
 * open across *both* models' streaming runs and released exactly once, in
 * [run]'s `finally` block, since tiles for either model are cut from it on
 * demand rather than all at once up front.
 */
@Singleton
class OmrPageInferenceRunner @Inject constructor(
    private val preprocessor: OmrPreprocessor,
    private val tileInferenceRunner: TileInferenceRunner
) {

    /**
     * Preprocesses [page], runs both models' streaming inference, argmaxes
     * each immediately, and builds the five class masks — all without ever
     * holding both models' full float prediction maps simultaneously.
     */
    fun run(page: Bitmap): OmrPageInferenceResult {
        val preprocessed = preprocessor.preprocess(page)
        try {
            // --- Model 1: STAFF_AND_SYMBOLS ---
            val staffAndSymbolsMap = tileInferenceRunner.runStreaming(
                spec = OmrModelSpec.STAFF_AND_SYMBOLS,
                source = preprocessed.canonicalMat,
                canonicalWidth = preprocessed.canonicalWidth,
                canonicalHeight = preprocessed.canonicalHeight
            )
            // Argmax immediately — the 44.1 MB FloatArray in
            // staffAndSymbolsMap.data becomes GC-eligible right here,
            // replaced by a 14.7 MB IntArray.
            val staffAndSymbolsClasses = ClassMaskExtractor.argmaxMap(staffAndSymbolsMap)
            val maskWidth = staffAndSymbolsMap.width
            val maskHeight = staffAndSymbolsMap.height
            // staffAndSymbolsMap is now unreferenced and GC-eligible.

            // --- Model 2: SYMBOL_DETAIL ---
            val symbolDetailMap = tileInferenceRunner.runStreaming(
                spec = OmrModelSpec.SYMBOL_DETAIL,
                source = preprocessed.canonicalMat,
                canonicalWidth = preprocessed.canonicalWidth,
                canonicalHeight = preprocessed.canonicalHeight
            )
            // Argmax immediately — the 58.8 MB FloatArray in
            // symbolDetailMap.data becomes GC-eligible right here.
            val symbolDetailClasses = ClassMaskExtractor.argmaxMap(symbolDetailMap)
            // symbolDetailMap is now unreferenced and GC-eligible.

            val masks = ClassMaskExtractor.extractFromArgmaxed(
                staffAndSymbolsClasses = staffAndSymbolsClasses,
                symbolDetailClasses = symbolDetailClasses,
                width = maskWidth,
                height = maskHeight
            )

            return OmrPageInferenceResult(
                canonicalWidth = preprocessed.canonicalWidth,
                canonicalHeight = preprocessed.canonicalHeight,
                canonicalImageChannels = preprocessed.canonicalImageChannels,
                masks = masks
            )
        } finally {
            preprocessed.canonicalMat.release()
        }
    }
}

/**
 * @property canonicalImageChannels The same array
 *   [com.sheetsight.app.data.omr.preprocessing.OmrPreprocessingResult]
 *   produced — not re-derived — so it's guaranteed pixel-for-pixel
 *   consistent with the (canonical-resolution) tiling the masks were
 *   derived from.
 * @property masks The five boolean class masks, already argmax'd from both
 *   models' merged prediction maps. The raw float prediction maps are
 *   intentionally **not** retained — they were GC'd immediately after
 *   argmaxing each model (see [OmrPageInferenceRunner.run]'s memory-fix
 *   KDoc). Mask dimensions may occasionally be a few pixels larger than
 *   [canonicalWidth]x[canonicalHeight] — see
 *   [TileInferenceRunner.runStreaming]'s own KDoc on the rare padding case.
 */
data class OmrPageInferenceResult(
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val canonicalImageChannels: List<FloatArray>,
    val masks: OmrClassMasks
)