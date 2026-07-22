package com.sheetsight.app.data.omr

import android.graphics.BitmapFactory
import com.sheetsight.app.data.omr.dewarp.DewarpedPage
import com.sheetsight.app.data.omr.dewarp.OmrPageDewarpRunner
import com.sheetsight.app.di.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OmrEngine] implementation wiring the existing OMR pipeline together for
 * a single decoded page: bitmap decode → [OmrPageDewarpRunner] (which
 * itself composes preprocessing → tiling → ONNX inference →
 * prediction-map merging → class-mask extraction → dewarping — none of
 * that is touched here, only invoked).
 *
 * [recognizeDewarpedPage] is the real, currently-implemented terminus of
 * that pipeline: a [DewarpedPage] (original image plus all five class
 * masks, dewarped and pixel-aligned) ready for a future staffline
 * extraction phase to consume. [recognize] still can't fulfil
 * [OmrEngine]'s MusicXML-producing contract — staffline extraction, note
 * detection, symbol classification, rhythm extraction and MusicXML
 * generation are later phases not touched here — so it runs the same
 * pipeline (proving it actually works end to end) and then fails clearly
 * and specifically, instead of silently returning a fabricated
 * [OmrResult].
 *
 * Runs on [defaultDispatcher] (`Dispatchers.Default`): decode, OpenCV
 * preprocessing and ONNX inference are all CPU-bound, not I/O-bound.
 */
@Singleton
class OnnxOmrEngine @Inject constructor(
    private val dewarpRunner: OmrPageDewarpRunner,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : OmrEngine {

    /**
     * Runs the full implemented pipeline against the image at [imagePath],
     * returning its [DewarpedPage]. Both decode failures and pipeline
     * failures surface as one clearly-worded [OmrPipelineException]
     * (wrapping the real cause) rather than a raw `NullPointerException`
     * or OpenCV crash, so callers like [OmrRepository] can show a sane
     * message instead of a stack trace.
     */
    suspend fun recognizeDewarpedPage(imagePath: String): DewarpedPage = withContext(defaultDispatcher) {
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: throw OmrPipelineException("Could not decode an image from '$imagePath'")
        try {
            dewarpRunner.run(bitmap)
        } catch (e: Exception) {
            throw OmrPipelineException("OMR pipeline failed for '$imagePath'", e)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun recognize(imagePath: String): OmrResult {
        recognizeDewarpedPage(imagePath) // runs and validates the real pipeline; result intentionally unused below
        throw NotImplementedError(
            "Dewarping succeeded for '$imagePath', but staffline extraction, note detection, " +
                    "symbol classification, rhythm extraction and MusicXML generation are not " +
                    "implemented yet — OmrEngine.recognize() cannot produce an OmrResult until they are."
        )
    }
}

/** Thrown when the OMR pipeline (image decode through dewarping) fails for a page. */
class OmrPipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)