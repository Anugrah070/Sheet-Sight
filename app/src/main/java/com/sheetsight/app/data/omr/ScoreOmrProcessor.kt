package com.sheetsight.app.data.omr

import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.data.omr.debug.OmrSmokeTestRunner
import com.sheetsight.app.data.omr.debug.SmokeTestStage
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.model.Score
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the complete, currently implemented single-page recognition pipeline. */
interface ScoreOmrProcessor {
    suspend fun recognizePage(
        score: Score,
        pageIndex: Int,
        listener: OmrProgressListener? = null
    ): OmrResult
}

/**
 * Adapts imported scores to the OMR pipeline. Images can be recognized
 * directly; PDFs are first rendered to a temporary PNG for the selected page.
 */
@Singleton
class DefaultScoreOmrProcessor @Inject constructor(
    private val runner: OmrSmokeTestRunner,
    private val fileStorage: ScoreFileStorage,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScoreOmrProcessor {

    override suspend fun recognizePage(
        score: Score,
        pageIndex: Int,
        listener: OmrProgressListener?
    ): OmrResult {
        val boundedPage = pageIndex.coerceIn(0, (score.pageCount - 1).coerceAtLeast(0))
        val source = File(score.originalFilePath)
        val temporaryPage = if (source.extension.equals("pdf", ignoreCase = true)) {
            withContext(ioDispatcher) {
                fileStorage.renderPdfPageForOmr(source.absolutePath, boundedPage, score.id)
            }
        } else {
            null
        }

        return try {
            val diagnostic = runner.run(
                imagePath = (temporaryPage ?: source).absolutePath,
                stopAfter = SmokeTestStage.MUSICXML_EXPORT,
                listener = listener
            )
            diagnostic.errorMessage?.let { rawMessage ->
                val detail = rawMessage.substringAfter(": ", rawMessage)
                throw OmrPipelineException("Could not recognize this page: $detail")
            }
            val musicXmlPath = diagnostic.musicXmlOutputPath
                ?.takeIf { File(it).isFile }
                ?: throw OmrPipelineException("Recognition finished without producing a MusicXML file.")
            listener?.onProgressUpdate(
                OmrProgressUpdate(OmrStage.MUSICXML_GENERATION, overallPercentage = 100)
            )
            OmrResult(musicXmlPath = musicXmlPath)
        } finally {
            temporaryPage?.delete()
        }
    }
}
