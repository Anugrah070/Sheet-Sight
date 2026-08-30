package com.sheetsight.app.ui.editor

import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import com.sheetsight.app.domain.repository.GeneratedScoreDeletionResult
import com.sheetsight.app.domain.repository.MusicXmlVersionPersistenceResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `valid score id loads score and its current MusicXML`() = runTest(dispatcher) {
        val current = validMusicXmlFile("current.musicxml", "C")
        val original = validMusicXmlFile("original.musicxml", "D")
        val loader = CountingEditorMusicXmlLoader()
        val viewModel = viewModel(
            FakeScoreRepository(score(original.path, current.path)),
            loader
        )

        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(SCORE_A, ready.scoreId)
        assertEquals(current.path, ready.currentMusicXmlPath)
        assertEquals(listOf(current.canonicalPath), loader.loadedPaths)
        assertTrue(ready.musicXml.contains("<step>C</step>"))
    }

    @Test
    fun `zoom persistence debounces to the final clamped shared value`() = runTest(dispatcher) {
        val current = validMusicXmlFile("zoom.musicxml", "C")
        val repository = FakeScoreRepository(score(current.path, current.path))
        val viewModel = viewModel(repository)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()

        viewModel.onZoomChanged(1.4f)
        viewModel.onZoomChanged(EditorViewModel.MAX_ZOOM + 5f)
        advanceUntilIdle()

        assertEquals(listOf(SCORE_A to EditorViewModel.MAX_ZOOM), repository.persistedZoomUpdates)
    }

    @Test
    fun `nullable route picker load reaches Ready and is not masked as Loading`() = runTest(dispatcher) {
        val current = validMusicXmlFile("picker-route.musicxml", "C")
        val viewModel = viewModel(FakeScoreRepository(score(current.path, current.path)))

        viewModel.loadScore(-1L)
        assertTrue(viewModel.uiState.value is EditorUiState.NoScoreSelected)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(ready, editorVisibleStateForRoute(routeScoreId = null, uiState = ready))
        assertTrue(editorVisibleStateForRoute(routeScoreId = null, uiState = ready) !is EditorUiState.Loading)
    }

    @Test
    fun `direct route keeps matching Ready and masks only a stale different score`() = runTest(dispatcher) {
        val current = validMusicXmlFile("direct-route.musicxml", "D")
        val viewModel = viewModel(FakeScoreRepository(score(current.path, current.path)))
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val ready = viewModel.uiState.value as EditorUiState.Ready

        assertEquals(ready, editorVisibleStateForRoute(SCORE_A, ready))
        assertTrue(editorVisibleStateForRoute(SCORE_B, ready) is EditorUiState.Loading)
    }

    @Test
    fun `original MusicXML is never used when current path is missing`() = runTest(dispatcher) {
        val original = validMusicXmlFile("original-only.musicxml", "D")
        val loader = CountingEditorMusicXmlLoader()
        val viewModel = viewModel(FakeScoreRepository(score(original.path, null)), loader)

        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is EditorUiState.NoCurrentMusicXml)
        assertTrue(loader.loadedPaths.isEmpty())
    }

    @Test
    fun `missing current file reaches FileMissing`() = runTest(dispatcher) {
        val missing = File(temporaryFolder.root, "missing.musicxml")
        val viewModel = viewModel(FakeScoreRepository(score(null, missing.path)))
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.FileMissing)
    }

    @Test
    fun `empty current file reaches EmptyFile`() = runTest(dispatcher) {
        val empty = temporaryFolder.newFile("empty.musicxml")
        val viewModel = viewModel(FakeScoreRepository(score(null, empty.path)))
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.EmptyFile)
    }

    @Test
    fun `malformed current XML reaches ParseError`() = runTest(dispatcher) {
        val file = temporaryFolder.newFile("malformed.musicxml").apply {
            writeText("<score-partwise><part>")
        }
        val viewModel = viewModel(FakeScoreRepository(score(null, file.path)))
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EditorUiState.ParseError)
    }

    @Test
    fun `repeated load and unrelated score emissions do not reload source`() = runTest(dispatcher) {
        val current = validMusicXmlFile("stable.musicxml", "E")
        val repository = FakeScoreRepository(score(null, current.path))
        val loader = CountingEditorMusicXmlLoader()
        val viewModel = viewModel(repository, loader)

        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val first = viewModel.uiState.value
        viewModel.loadScore(SCORE_A)
        repository.emit(SCORE_A) { it.copy(isFavorite = true, lastViewedPage = 1) }
        advanceUntilIdle()

        assertEquals(first, viewModel.uiState.value)
        assertEquals(1, loader.loadedPaths.size)
    }

    @Test
    fun `changed current path triggers one controlled reload`() = runTest(dispatcher) {
        val first = validMusicXmlFile("first.musicxml", "F")
        val second = validMusicXmlFile("second.musicxml", "G")
        val repository = FakeScoreRepository(score(null, first.path))
        val loader = CountingEditorMusicXmlLoader()
        val viewModel = viewModel(repository, loader)

        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val firstKey = (viewModel.uiState.value as EditorUiState.Ready).sourceKey
        repository.emit(SCORE_A) { it.copy(currentMusicXmlPath = second.path) }
        advanceUntilIdle()

        val ready = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(second.path, ready.currentMusicXmlPath)
        assertNotEquals(firstKey, ready.sourceKey)
        assertEquals(listOf(first.canonicalPath, second.canonicalPath), loader.loadedPaths)
    }

    @Test
    fun `opening another score never reuses stale first score source`() = runTest(dispatcher) {
        val first = validMusicXmlFile("score-a.musicxml", "A")
        val second = validMusicXmlFile("score-b.musicxml", "B")
        val repository = FakeScoreRepository(
            score(null, first.path, id = SCORE_A),
            score(null, second.path, id = SCORE_B)
        )
        val loader = CountingEditorMusicXmlLoader()
        val viewModel = viewModel(repository, loader)

        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        viewModel.loadScore(SCORE_B)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(SCORE_B, ready.scoreId)
        assertEquals(second.path, ready.currentMusicXmlPath)
        assertTrue(ready.musicXml.contains("<step>B</step>"))
        assertEquals(listOf(first.canonicalPath, second.canonicalPath), loader.loadedPaths)
    }

    @Test
    fun `alphaTab failure becomes source-scoped RenderError`() = runTest(dispatcher) {
        val current = validMusicXmlFile("render.musicxml", "C")
        val viewModel = viewModel(FakeScoreRepository(score(null, current.path)))
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val ready = viewModel.uiState.value as EditorUiState.Ready

        viewModel.onRenderError(ready.sourceKey, "renderer failed")

        assertTrue(viewModel.uiState.value is EditorUiState.RenderError)
    }

    @Test
    fun `picker includes only completed OMR scores and keeps source title`() = runTest(dispatcher) {
        val importedImage = score(null, null, id = 1L).copy(
            title = "Imported page.jpg",
            originalFilePath = "/library/Imported page.jpg"
        )
        val importedPdf = score(null, null, id = 2L).copy(
            title = "Sonata.pdf",
            originalFilePath = "/library/Sonata.pdf"
        )
        val inProgress = score(null, null, id = 3L).copy(title = "Recognizing")
        val failed = score(null, null, id = 4L).copy(title = "Failed recognition")
        val completedFile = validMusicXmlFile("picker-current.musicxml", "C")
        val completed = score(
            originalPath = completedFile.path,
            currentPath = completedFile.path,
            id = 5L
        ).copy(title = "Moonlight Sonata")
        val repository = FakeScoreRepository(importedImage, importedPdf, inProgress, failed, completed)
        val viewModel = viewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.recognizedScores.collect()
        }

        advanceUntilIdle()

        assertEquals(listOf(5L), viewModel.recognizedScores.value.map { it.id })
        assertEquals("Moonlight Sonata", viewModel.recognizedScores.value.single().title)
        assertNotEquals(
            completed.currentMusicXmlPath,
            viewModel.recognizedScores.value.single().title
        )
        collection.cancel()
    }

    @Test
    fun `picker reacts when OMR completes and when score is deleted`() = runTest(dispatcher) {
        val awaitingOmr = score(null, null, id = 6L).copy(title = "Awaiting OMR")
        val repository = FakeScoreRepository(awaitingOmr)
        val viewModel = viewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.recognizedScores.collect()
        }
        advanceUntilIdle()
        assertTrue(viewModel.recognizedScores.value.isEmpty())

        repository.completeOmr(6L, validMusicXmlFile("awaiting.musicxml", "A").path)
        advanceUntilIdle()
        assertEquals(listOf(6L), viewModel.recognizedScores.value.map { it.id })

        repository.deleteById(6L)
        advanceUntilIdle()
        assertTrue(viewModel.recognizedScores.value.isEmpty())
        collection.cancel()
    }

    @Test
    fun `pitch edit writes a new current artifact preserves original and reselects stable note`() = runTest(dispatcher) {
        val original = validMusicXmlFile("pitch-original.musicxml", "C")
        val current = validMusicXmlFile("pitch-current.musicxml", "C")
        val repository = FakeScoreRepository(score(original.path, current.path))
        val viewModel = viewModel(repository)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val before = viewModel.uiState.value as EditorUiState.Ready
        val note = before.identityIndex.notes.single()
        val chord = before.identityIndex.chords.single()
        viewModel.onSelectionChanged(EditorSelection.NoteSelection(before.sourceKey, chord.identity, note))

        viewModel.moveSelectedNote(NaturalNoteDirection.UP)
        assertTrue(viewModel.uiState.value is EditorUiState.Ready)
        assertEquals(before.currentMusicXmlPath, (viewModel.uiState.value as EditorUiState.Ready).currentMusicXmlPath)
        assertTrue(viewModel.noteEditInProgress.value)
        assertTrue(viewModel.pitchVisualUpdate.value is EditorPitchVisualUpdate.Apply)
        advanceUntilIdle()

        val after = viewModel.uiState.value as EditorUiState.Ready
        assertNotEquals(current.path, after.currentMusicXmlPath)
        assertTrue(after.musicXml.contains("<step>D</step>"))
        assertTrue(!after.musicXml.contains("<alter>"))
        assertEquals(original.path, repository.scoreValue(SCORE_A)?.originalMusicXmlPath)
        assertEquals(note.identity, (viewModel.selection.value as EditorSelection.NoteSelection).note.identity)
        assertTrue(!viewModel.noteEditInProgress.value)
        assertEquals(before.renderSessionKey, after.renderSessionKey)
        assertTrue(viewModel.pitchVisualUpdate.value is EditorPitchVisualUpdate.Commit)
    }

    @Test
    fun `one drag persists its final multi-step pitch as one artifact`() = runTest(dispatcher) {
        val current = validMusicXmlFile("drag-current.musicxml", "C")
        val repository = FakeScoreRepository(score(current.path, current.path))
        val viewModel = viewModel(repository)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val ready = viewModel.uiState.value as EditorUiState.Ready
        val note = ready.identityIndex.notes.single()
        val chord = ready.identityIndex.chords.single()
        viewModel.onSelectionChanged(EditorSelection.NoteSelection(ready.sourceKey, chord.identity, note))

        viewModel.moveSelectedNoteBy(4)
        val preview = viewModel.pitchVisualUpdate.value as EditorPitchVisualUpdate.Apply
        assertEquals("G", preview.pitchStep)
        assertEquals(67, preview.pitchMidi)
        advanceUntilIdle()

        val after = viewModel.uiState.value as EditorUiState.Ready
        assertTrue(after.musicXml.contains("<step>G</step>"))
        assertEquals(1, repository.persistedEditCount)
        assertTrue(viewModel.pitchVisualUpdate.value is EditorPitchVisualUpdate.Commit)
    }

    @Test
    fun `failed persistence rolls optimistic note back without loading or changing Room path`() = runTest(dispatcher) {
        val original = validMusicXmlFile("rollback-original.musicxml", "C")
        val current = validMusicXmlFile("rollback-current.musicxml", "C")
        val repository = FakeScoreRepository(score(original.path, current.path)).apply {
            persistenceFailure = "disk full"
        }
        val viewModel = viewModel(repository)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val ready = viewModel.uiState.value as EditorUiState.Ready
        val note = ready.identityIndex.notes.single()
        val chord = ready.identityIndex.chords.single()
        viewModel.onSelectionChanged(EditorSelection.NoteSelection(ready.sourceKey, chord.identity, note))

        viewModel.moveSelectedNote(NaturalNoteDirection.UP)
        assertTrue(viewModel.uiState.value is EditorUiState.Ready)
        assertTrue(viewModel.pitchVisualUpdate.value is EditorPitchVisualUpdate.Apply)
        advanceUntilIdle()

        val after = viewModel.uiState.value as EditorUiState.Ready
        assertEquals(current.path, after.currentMusicXmlPath)
        assertEquals(current.path, repository.scoreValue(SCORE_A)?.currentMusicXmlPath)
        assertEquals(original.path, repository.scoreValue(SCORE_A)?.originalMusicXmlPath)
        assertTrue(viewModel.pitchVisualUpdate.value is EditorPitchVisualUpdate.Rollback)
        assertTrue(!viewModel.noteEditInProgress.value)
        assertEquals(note.identity, (viewModel.selection.value as EditorSelection.NoteSelection).note.identity)
    }

    @Test
    fun `repeated pitch controls are serialized while artifact persistence is pending`() = runTest(dispatcher) {
        val original = validMusicXmlFile("queue-original.musicxml", "C")
        val current = validMusicXmlFile("queue-current.musicxml", "C")
        val repository = FakeScoreRepository(score(original.path, current.path))
        val viewModel = viewModel(repository)
        viewModel.loadScore(SCORE_A)
        advanceUntilIdle()
        val ready = viewModel.uiState.value as EditorUiState.Ready
        val note = ready.identityIndex.notes.single()
        val chord = ready.identityIndex.chords.single()
        viewModel.onSelectionChanged(EditorSelection.NoteSelection(ready.sourceKey, chord.identity, note))

        viewModel.moveSelectedNote(NaturalNoteDirection.UP)
        viewModel.moveSelectedNote(NaturalNoteDirection.UP)
        assertTrue(viewModel.noteEditInProgress.value)
        advanceUntilIdle()

        val after = viewModel.uiState.value as EditorUiState.Ready
        assertTrue(after.musicXml.contains("<step>E</step>"))
        assertEquals(2, repository.persistedEditCount)
        assertEquals(original.path, repository.scoreValue(SCORE_A)?.originalMusicXmlPath)
        assertEquals(note.identity, (viewModel.selection.value as EditorSelection.NoteSelection).note.identity)
        assertTrue(!viewModel.noteEditInProgress.value)
    }

    private fun viewModel(
        repository: ScoreRepository,
        loader: EditorMusicXmlLoader = EditorMusicXmlLoader()
    ) = EditorViewModel(repository, loader, dispatcher)

    private fun validMusicXmlFile(name: String, step: String) = temporaryFolder.newFile(name).apply {
        writeText(
            """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions><clef><sign>G</sign><line>2</line></clef></attributes>
                <note><pitch><step>$step</step><octave>4</octave></pitch>
                  <duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
              </measure></part>
            </score-partwise>
            """.trimIndent()
        )
    }

    private fun score(
        originalPath: String?,
        currentPath: String?,
        id: Long = SCORE_A
    ) = Score(
        id = id,
        title = "Test score $id",
        originalFilePath = "source-$id.png",
        originalMusicXmlPath = originalPath,
        currentMusicXmlPath = currentPath,
        importDate = 1L,
        pageCount = 1
    )

    private class CountingEditorMusicXmlLoader : EditorMusicXmlLoader() {
        val loadedPaths = mutableListOf<String>()
        override fun load(file: File, scoreId: Long): EditorLoadResult {
            loadedPaths += file.canonicalPath
            return super.load(file, scoreId)
        }
    }

    private class FakeScoreRepository(vararg scores: Score) : ScoreRepository {
        private val values = scores.associate { it.id to MutableStateFlow<Score?>(it) }.toMutableMap()
        private val allScores = MutableStateFlow(scores.toList())
        var persistenceFailure: String? = null
        var persistedEditCount: Int = 0
        val persistedZoomUpdates = mutableListOf<Pair<Long, Float>>()

        fun emit(id: Long, transform: (Score) -> Score) {
            val flow = values.getValue(id)
            flow.value = transform(checkNotNull(flow.value))
            publishAll()
        }

        fun completeOmr(id: Long, path: String) = emit(id) { score ->
            score.copy(
                originalMusicXmlPath = score.originalMusicXmlPath ?: path,
                currentMusicXmlPath = path
            )
        }

        fun deleteById(id: Long) {
            values.remove(id)?.value = null
            publishAll()
        }

        fun scoreValue(id: Long): Score? = values[id]?.value

        private fun publishAll() {
            allScores.value = values.values.mapNotNull { it.value }
        }

        override fun getAllScores(): Flow<List<Score>> = allScores
        override fun observeEditorScores(): Flow<List<Score>> = allScores.map { scores ->
            scores.filter { score ->
                score.currentMusicXmlPath?.let(::File)?.let { it.isFile && it.canRead() && it.length() > 0L } == true
            }
        }
        override fun getFavoriteScores(): Flow<List<Score>> = error("Not used")
        override fun getScoreById(id: Long): Flow<Score?> = values.getOrPut(id) { MutableStateFlow(null) }
        override suspend fun addScore(score: Score): Long = error("Not used")
        override suspend fun updateScore(score: Score) { values.getValue(score.id).value = score }
        override suspend fun deleteScore(score: Score) = deleteById(score.id)
        override suspend fun markOpened(id: Long, timestamp: Long) = Unit
        override suspend fun updateLastViewedPage(id: Long, page: Int) = Unit
        override suspend fun updateLastViewedZoom(id: Long, zoom: Float) {
            persistedZoomUpdates += id to zoom
        }
        override suspend fun updatePracticeProgress(id: Long, progress: Float) = Unit
        override suspend fun setGeneratedMusicXmlPath(id: Long, path: String) = Unit
        override suspend fun setCurrentMusicXmlPath(id: Long, path: String) = Unit
        override suspend fun persistEditedMusicXmlVersion(
            id: Long,
            expectedCurrentPath: String,
            musicXmlBytes: ByteArray
        ): MusicXmlVersionPersistenceResult {
            persistedEditCount++
            persistenceFailure?.let { return MusicXmlVersionPersistenceResult.Failure(it) }
            val current = scoreValue(id)
                ?: return MusicXmlVersionPersistenceResult.Failure("Score not found")
            if (current.currentMusicXmlPath != expectedCurrentPath) {
                return MusicXmlVersionPersistenceResult.Failure("stale")
            }
            val source = File(expectedCurrentPath)
            val edited = File(source.parentFile, "${source.nameWithoutExtension}-edited-${System.nanoTime()}.musicxml")
            edited.writeBytes(musicXmlBytes)
            emit(id) { it.copy(currentMusicXmlPath = edited.path) }
            return MusicXmlVersionPersistenceResult.Success(edited.path)
        }
        override suspend fun deleteGeneratedScore(id: Long): GeneratedScoreDeletionResult {
            emit(id) { score -> score.copy(currentMusicXmlPath = null) }
            return GeneratedScoreDeletionResult.Success(fileWasAlreadyMissing = false)
        }
    }

    private companion object {
        const val SCORE_A = 42L
        const val SCORE_B = 84L
    }
}
