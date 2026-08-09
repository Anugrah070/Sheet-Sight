package com.sheetsight.app.ui.preview

import com.sheetsight.app.data.omr.OmrProgressListener
import com.sheetsight.app.data.omr.OmrResult
import com.sheetsight.app.data.omr.ScoreOmrProcessor
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `successful OMR persists MusicXML and requests Editor`() = runTest(dispatcher) {
        val repository = FakeScoreRepository(score())
        val processor = FakeScoreOmrProcessor(resultPath = "generated.musicxml")
        val viewModel = PreviewViewModel(repository, processor)
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()

        val event = async { viewModel.events.first() }
        viewModel.onPageChanged(2)
        viewModel.onRunOmrRequested()
        advanceUntilIdle()

        assertEquals(SCORE_ID, processor.recognizedScoreId)
        assertEquals(2, processor.recognizedPage)
        assertEquals("generated.musicxml", repository.persistedMusicXmlPath)
        assertEquals(PreviewEvent.OpenEditor(SCORE_ID), event.await())
        assertTrue(viewModel.uiState.value.recognition is PreviewRecognitionState.Idle)
    }

    @Test
    fun `failed OMR remains in Preview with an actionable error`() = runTest(dispatcher) {
        val viewModel = PreviewViewModel(
            FakeScoreRepository(score()),
            FakeScoreOmrProcessor(failure = IllegalStateException("No staff lines found"))
        )
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        viewModel.onRunOmrRequested()
        advanceUntilIdle()

        val failed = viewModel.uiState.value.recognition as PreviewRecognitionState.Failed
        assertEquals("No staff lines found", failed.message)
    }

    private fun score() = Score(
        id = SCORE_ID,
        title = "Test score",
        originalFilePath = "source.pdf",
        importDate = 1L,
        pageCount = 3
    )

    private class FakeScoreOmrProcessor(
        private val resultPath: String? = null,
        private val failure: Exception? = null
    ) : ScoreOmrProcessor {
        var recognizedScoreId: Long? = null
        var recognizedPage: Int? = null

        override suspend fun recognizePage(
            score: Score,
            pageIndex: Int,
            listener: OmrProgressListener?
        ): OmrResult {
            recognizedScoreId = score.id
            recognizedPage = pageIndex
            failure?.let { throw it }
            return OmrResult(checkNotNull(resultPath))
        }
    }

    private class FakeScoreRepository(score: Score) : ScoreRepository {
        private val value = MutableStateFlow<Score?>(score)
        var persistedMusicXmlPath: String? = null

        override fun getAllScores(): Flow<List<Score>> = error("Not used")
        override fun getFavoriteScores(): Flow<List<Score>> = error("Not used")
        override fun getScoreById(id: Long): Flow<Score?> = value
        override suspend fun addScore(score: Score): Long = error("Not used")
        override suspend fun updateScore(score: Score) { value.value = score }
        override suspend fun deleteScore(score: Score) = Unit
        override suspend fun markOpened(id: Long, timestamp: Long) = Unit
        override suspend fun updateLastViewedPage(id: Long, page: Int) {
            value.value = value.value?.copy(lastViewedPage = page)
        }
        override suspend fun updateLastViewedZoom(id: Long, zoom: Float) = Unit
        override suspend fun updatePracticeProgress(id: Long, progress: Float) = Unit
        override suspend fun setMusicXmlPath(id: Long, path: String) {
            persistedMusicXmlPath = path
            value.value = value.value?.copy(musicXmlPath = path)
        }
    }

    private companion object { const val SCORE_ID = 42L }
}
