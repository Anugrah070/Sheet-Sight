package com.sheetsight.app.ui.editor

import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `score without MusicXML path reaches NoMusicXml`() = runTest(dispatcher) {
        val viewModel = viewModel(score(musicXmlPath = null))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.NoMusicXml)
    }

    @Test
    fun `missing persisted file reaches FileMissing`() = runTest(dispatcher) {
        val viewModel = viewModel(score(musicXmlPath = File(temporaryFolder.root, "missing.musicxml").path))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.FileMissing)
    }

    @Test
    fun `malformed XML reaches ParseError`() = runTest(dispatcher) {
        val file = temporaryFolder.newFile("malformed.musicxml").apply { writeText("<score-partwise><part>") }
        val viewModel = viewModel(score(musicXmlPath = file.path))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.ParseError)
    }

    @Test
    fun `valid persisted MusicXML path reaches Ready with parsed content`() = runTest(dispatcher) {
        val file = validMusicXmlFile()
        val viewModel = viewModel(score(musicXmlPath = file.path))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(SCORE_ID, ready.scoreId)
        assertEquals(1, ready.document.statistics.measureCount)
        assertEquals(1, ready.document.statistics.noteCount)
        assertEquals("Test score", ready.title)
    }

    @Test
    fun `unsupported root reaches UnsupportedContent`() = runTest(dispatcher) {
        val file = temporaryFolder.newFile("timewise.musicxml").apply {
            writeText("<score-timewise version=\"4.0\"><measure number=\"1\"/></score-timewise>")
        }
        val viewModel = viewModel(score(musicXmlPath = file.path))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.UnsupportedContent)
    }

    @Test
    fun `repeated loading of the same score is deterministic`() = runTest(dispatcher) {
        val file = validMusicXmlFile()
        val viewModel = viewModel(score(musicXmlPath = file.path))
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        val first = viewModel.uiState.value
        viewModel.loadScore(SCORE_ID)
        advanceUntilIdle()
        assertEquals(first, viewModel.uiState.value)
    }

    private fun viewModel(score: Score) = EditorViewModel(
        scoreRepository = FakeScoreRepository(score),
        musicXmlLoader = EditorMusicXmlLoader(),
        ioDispatcher = dispatcher
    )

    private fun validMusicXmlFile() = temporaryFolder.newFile("valid-${System.nanoTime()}.musicxml").apply {
        writeText(
            """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions><clef><sign>G</sign><line>2</line></clef></attributes>
                <note><pitch><step>C</step><alter>0</alter><octave>4</octave></pitch>
                  <duration>1</duration><voice>1</voice><type>quarter</type><stem>up</stem><staff>1</staff></note>
              </measure></part>
            </score-partwise>
            """.trimIndent()
        )
    }

    private fun score(musicXmlPath: String?) = Score(
        id = SCORE_ID,
        title = "Test score",
        originalFilePath = "source.png",
        musicXmlPath = musicXmlPath,
        importDate = 1L,
        pageCount = 1
    )

    private class FakeScoreRepository(score: Score) : ScoreRepository {
        private val value = MutableStateFlow<Score?>(score)
        override fun getAllScores(): Flow<List<Score>> = error("Not used")
        override fun getFavoriteScores(): Flow<List<Score>> = error("Not used")
        override fun getScoreById(id: Long): Flow<Score?> = value
        override suspend fun addScore(score: Score): Long = error("Not used")
        override suspend fun updateScore(score: Score) = Unit
        override suspend fun deleteScore(score: Score) = Unit
        override suspend fun markOpened(id: Long, timestamp: Long) = Unit
        override suspend fun updateLastViewedPage(id: Long, page: Int) = Unit
        override suspend fun updateLastViewedZoom(id: Long, zoom: Float) = Unit
        override suspend fun updatePracticeProgress(id: Long, progress: Float) = Unit
        override suspend fun setMusicXmlPath(id: Long, path: String) = Unit
    }

    companion object { private const val SCORE_ID = 42L }
}
