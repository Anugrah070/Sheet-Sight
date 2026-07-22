package com.sheetsight.app.data.omr.inference

import android.graphics.Bitmap
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.OmrPreprocessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the full Phase 4.2–4.4 pipeline for a single decoded page: oemer
 * compatible preprocessing/tiling, per-tile ONNX inference for both
 * [OmrModelSpec]s, and overlap-averaged merging into full-page raw
 * prediction maps. Also surfaces the canonical (resized) page's own pixel
 * data — [OmrPreprocessingResult.canonicalImageChannels] — since Phase
 * 4.5E's dewarp integration needs the decoded image alongside the
 * prediction maps, and re-deriving it separately would mean resizing the
 * page twice.
 *
 * Deliberately stops before class-mask extraction or dewarping — those
 * are [com.sheetsight.app.data.omr.inference.ClassMaskExtractor]'s and
 * [com.sheetsight.app.data.omr.dewarp.DewarpPipeline]'s concerns,
 * composed together by [com.sheetsight.app.data.omr.dewarp.OmrPageDewarpRunner].
 * Staff detection, symbol interpretation, note reconstruction and
 * MusicXML generation are later phases this class still has no knowledge
 * of. Nothing in [com.sheetsight.app.data.omr.OnnxOmrEngine] calls this
 * yet; wiring it into [com.sheetsight.app.data.omr.OmrEngine.recognize]
 * is a future phase's concern.
 */
@Singleton
class OmrPageInferenceRunner @Inject constructor(
    private val preprocessor: OmrPreprocessor,
    private val tileInferenceRunner: TileInferenceRunner
) {

    /** Produces one raw [OmrPredictionMap] per [OmrModelSpec] for [page], plus the canonical image itself. */
    fun run(page: Bitmap): OmrPageInferenceResult {
        val preprocessed = preprocessor.preprocess(page)
        try {
            val predictionsByModel = preprocessed.tilesByModel.mapValues { (spec, tiles) ->
                val predictions = tileInferenceRunner.run(spec, tiles)
                PredictionMapMerger.merge(
                    canonicalWidth = preprocessed.canonicalWidth,
                    canonicalHeight = preprocessed.canonicalHeight,
                    predictions = predictions
                )
            }
            return OmrPageInferenceResult(
                canonicalWidth = preprocessed.canonicalWidth,
                canonicalHeight = preprocessed.canonicalHeight,
                canonicalImageChannels = preprocessed.canonicalImageChannels,
                predictionsByModel = predictionsByModel
            )
        } finally {
            preprocessed.tilesByModel.values.forEach { tiles -> tiles.forEach { it.release() } }
        }
    }
}

/**
 * @property canonicalImageChannels The same array
 *   [com.sheetsight.app.data.omr.preprocessing.OmrPreprocessingResult]
 *   produced — not re-derived — so it's guaranteed pixel-for-pixel
 *   consistent with [predictionsByModel]'s (canonical-resolution) tiling.
 * @property predictionsByModel One raw, merged [OmrPredictionMap] per
 *   [OmrModelSpec], at canonical resolution (occasionally slightly larger
 *   than [canonicalWidth]x[canonicalHeight] — see [PredictionMapMerger]'s
 *   own KDoc on the rare padding case).
 */
data class OmrPageInferenceResult(
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val canonicalImageChannels: List<FloatArray>,
    val predictionsByModel: Map<OmrModelSpec, OmrPredictionMap>
)