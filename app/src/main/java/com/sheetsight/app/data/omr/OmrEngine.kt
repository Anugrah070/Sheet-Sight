package com.sheetsight.app.data.omr

/**
 * Contract for a native OMR engine that turns a single sheet-music page
 * image into an [OmrResult]. Lives in `data/omr` rather than
 * `domain/repository` because, like [com.sheetsight.app.data.local.ScoreFileStorage],
 * it is inherently a platform/framework concern (ONNX Runtime Mobile +
 * OpenCV are Android-native dependencies, not portable domain logic) —
 * see [com.sheetsight.app.domain.usecase.ImportScoreUseCase]'s KDoc for the
 * same reasoning applied elsewhere in this codebase.
 *
 * This interface only defines the seam. The Phase 4.2 implementation is
 * expected to: decode the source image, run OpenCV-based preprocessing
 * (deskew/normalize), run ONNX Runtime Mobile inference using oemer's
 * segmentation checkpoints, and write the resulting MusicXML to local
 * storage. None of that exists yet — see [OnnxOmrEngine].
 */
interface OmrEngine {

    /**
     * Recognizes the sheet-music page image at [imagePath] and returns the
     * produced [OmrResult]. Not implemented until Phase 4.2.
     */
    suspend fun recognize(imagePath: String): OmrResult
}