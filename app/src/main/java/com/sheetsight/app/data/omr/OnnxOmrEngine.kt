package com.sheetsight.app.data.omr

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder [OmrEngine] implementation for Phase 4.1. Its only job right
 * now is to give [com.sheetsight.app.di.OmrModule] something concrete to
 * bind [OmrEngine] to, so the Hilt graph compiles and later phases can
 * inject [OmrEngine] anywhere without a DI change.
 *
 * Deliberately imports neither ONNX Runtime nor OpenCV APIs yet — no
 * inference and no preprocessing happens here. Phase 4.2 replaces the body
 * of [recognize] with the actual pipeline: bitmap decode → OpenCV
 * preprocessing → ONNX Runtime Mobile inference on oemer's segmentation
 * checkpoints → MusicXML written to local storage.
 */
@Singleton
class OnnxOmrEngine @Inject constructor() : OmrEngine {

    override suspend fun recognize(imagePath: String): OmrResult {
        throw NotImplementedError(
            "OmrEngine.recognize() is not implemented until Phase 4.2."
        )
    }
}