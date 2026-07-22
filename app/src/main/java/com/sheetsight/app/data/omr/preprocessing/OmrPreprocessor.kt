package com.sheetsight.app.data.omr.preprocessing

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs oemer-compatible preprocessing on a single decoded page and tiles
 * the result for both segmentation checkpoints
 * ([OmrModelSpec.STAFF_AND_SYMBOLS] and [OmrModelSpec.SYMBOL_DETAIL]).
 *
 * Mirrors `oemer/inference.py`'s per-model flow (canonical resize →
 * sliding-window crop), but resizes the page once and tiles it twice.
 * oemer's own script calls `inference()` independently per model, redoing
 * the (deterministic) resize each time purely as an artifact of its CLI
 * structure — sharing one resize here is behavior-equivalent and avoids
 * doing the same bicubic resample twice on a mobile device.
 *
 * Deciding *which* thread this runs on is deliberately left to the
 * caller — matching [com.sheetsight.app.data.local.ScoreFileStorage]'s
 * plain-synchronous style elsewhere in `data/` — rather than this class
 * reaching for [com.sheetsight.app.di.IoDispatcher]/[com.sheetsight.app.di.DefaultDispatcher] itself.
 *
 * Stops at producing [ImageTile]s per model: batching tiles into
 * [ai.onnxruntime.OnnxTensor]s is [OmrTensorFactory]'s job, and running
 * the model or merging predictions back into a page-sized map is Phase
 * 4.3's post-processing, not preprocessing.
 */
@Singleton
class OmrPreprocessor @Inject constructor() {

    /**
     * Produces tiles for every [OmrModelSpec] from a single decoded
     * [page]. Callers must release every tile in the result (via
     * [ImageTile.release]) once done — each tile owns native OpenCV
     * memory that isn't freed by the garbage collector.
     */
    fun preprocess(page: Bitmap): OmrPreprocessingResult {
        val oemerOrdered = ImagePreprocessing.toOemerOrderedMat(page)
        val resized = CanonicalImageResizer.resize(oemerOrdered)
        oemerOrdered.release()

        val tilesByModel = OmrModelSpec.entries.associateWith { spec ->
            SlidingWindowTiler.tile(resized, spec.windowSize)
        }
        val canonicalWidth = resized.width()
        val canonicalHeight = resized.height()
        val canonicalImageChannels = ImagePreprocessing.extractChannels(resized)
        resized.release()

        return OmrPreprocessingResult(
            canonicalWidth = canonicalWidth,
            canonicalHeight = canonicalHeight,
            canonicalImageChannels = canonicalImageChannels,
            tilesByModel = tilesByModel
        )
    }
}

/**
 * @property canonicalWidth Width of the resized page tiles were cut from
 *   — needed by Phase 4.3 to allocate the merged per-model prediction map.
 * @property canonicalHeight Height of the same resized page.
 * @property canonicalImageChannels The resized page's own pixel data (BGR,
 *   oemer's byte order — see [ImagePreprocessing]), as one row-major
 *   `canonicalWidth*canonicalHeight` [FloatArray] per channel. Unlike
 *   [tilesByModel], this isn't native-backed and needs no [ImageTile.release]
 *   — it's a plain copy taken before the resized `Mat` is released. This is
 *   the "original image" [com.sheetsight.app.data.omr.dewarp.DewarpPipeline]
 *   remaps alongside the five class masks.
 * @property tilesByModel Every tile for each [OmrModelSpec], in the same
 *   row-major (y outer, x inner) order oemer's `inference()` produces
 *   them — see [SlidingWindowTiler.computeOrigins].
 */
data class OmrPreprocessingResult(
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val canonicalImageChannels: List<FloatArray>,
    val tilesByModel: Map<OmrModelSpec, List<ImageTile>>
)