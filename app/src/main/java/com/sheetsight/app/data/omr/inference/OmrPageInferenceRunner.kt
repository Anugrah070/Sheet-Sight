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
 * prediction maps.
 *
 * Deliberately stops here — staff detection, symbol interpretation, note
 * reconstruction and MusicXML generation are later phases and this class
 * has no knowledge of any of them. Nothing in [com.sheetsight.app.data.omr.OnnxOmrEngine]
 * calls this yet; wiring it into [com.sheetsight.app.data.omr.OmrEngine.recognize]
 * is a future phase's concern.
 */
@Singleton
class OmrPageInferenceRunner @Inject constructor(
    private val preprocessor: OmrPreprocessor,
    private val tileInferenceRunner: TileInferenceRunner
) {

    /** Produces one raw [OmrPredictionMap] per [OmrModelSpec] for [page]. */
    fun run(page: Bitmap): Map<OmrModelSpec, OmrPredictionMap> {
        val preprocessed = preprocessor.preprocess(page)
        try {
            return preprocessed.tilesByModel.mapValues { (spec, tiles) ->
                val predictions = tileInferenceRunner.run(spec, tiles)
                PredictionMapMerger.merge(
                    canonicalWidth = preprocessed.canonicalWidth,
                    canonicalHeight = preprocessed.canonicalHeight,
                    predictions = predictions
                )
            }
        } finally {
            preprocessed.tilesByModel.values.forEach { tiles -> tiles.forEach { it.release() } }
        }
    }
}