package com.sheetsight.app.data.omr.preprocessing

import android.graphics.Bitmap
import org.opencv.core.Mat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs oemer-compatible preprocessing on a single decoded page: BGR-order
 * conversion (see [ImagePreprocessing]) and canonical resize (see
 * [CanonicalImageResizer]).
 *
 * **Memory note.** This class used to also eagerly tile the resized page
 * for *both* [OmrModelSpec]s before returning — materializing every tile
 * for the whole page, for both models, up front. That responsibility has
 * moved to
 * [com.sheetsight.app.data.omr.inference.TileInferenceRunner.runStreaming],
 * which tiles, infers, and merges one small batch at a time instead of
 * all at once (see that method's KDoc for the full memory reasoning). As
 * a direct result, [preprocess] now hands back the still-open,
 * canonical-resolution [Mat] itself
 * ([OmrPreprocessingResult.canonicalMat]) instead of releasing it
 * internally: the caller must keep it open until tiling/inference has
 * finished for *every* [OmrModelSpec], then release it exactly once.
 *
 * Deciding *which* thread this runs on is deliberately left to the
 * caller — matching [com.sheetsight.app.data.local.ScoreFileStorage]'s
 * plain-synchronous style elsewhere in `data/` — rather than this class
 * reaching for [com.sheetsight.app.di.IoDispatcher]/[com.sheetsight.app.di.DefaultDispatcher] itself.
 */
@Singleton
class OmrPreprocessor @Inject constructor() {

    /**
     * Produces the canonical (resized) page plus its own pixel channels
     * from a single decoded [page]. The caller now owns
     * [OmrPreprocessingResult.canonicalMat] and is responsible for
     * releasing it once tiling/inference for every model is done.
     */
    fun preprocess(page: Bitmap): OmrPreprocessingResult {
        val oemerOrdered = ImagePreprocessing.toOemerOrderedMat(page)
        val resized = CanonicalImageResizer.resize(oemerOrdered)
        oemerOrdered.release()

        val canonicalWidth = resized.width()
        val canonicalHeight = resized.height()
        val canonicalImageChannels = ImagePreprocessing.extractChannels(resized)

        return OmrPreprocessingResult(
            canonicalWidth = canonicalWidth,
            canonicalHeight = canonicalHeight,
            canonicalImageChannels = canonicalImageChannels,
            canonicalMat = resized
        )
    }
}

/**
 * @property canonicalWidth Width of the resized page tiles will be cut
 *   from — needed to size each model's [com.sheetsight.app.data.omr.inference.PredictionMapAccumulator]
 *   ahead of tiling/inference.
 * @property canonicalHeight Height of the same resized page.
 * @property canonicalImageChannels The resized page's own pixel data (BGR,
 *   oemer's byte order — see [ImagePreprocessing]), as one row-major
 *   `canonicalWidth*canonicalHeight` [FloatArray] per channel. This is a
 *   plain copy taken before tiling and is independent of [canonicalMat]'s
 *   lifecycle — it needs no [Mat.release] of its own. This is the
 *   "original image" [com.sheetsight.app.data.omr.dewarp.DewarpPipeline]
 *   remaps alongside the five class masks.
 * @property canonicalMat The resized page itself, **still open**. Tiling
 *   is now performed lazily, one batch at a time, directly against this
 *   [Mat] (see
 *   [com.sheetsight.app.data.omr.inference.TileInferenceRunner.runStreaming]),
 *   so it must stay open until every [OmrModelSpec] has finished tiling
 *   against it. The caller — not this class — owns calling [Mat.release]
 *   on it exactly once that's done.
 */
data class OmrPreprocessingResult(
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val canonicalImageChannels: List<FloatArray>,
    val canonicalMat: Mat
)