package com.sheetsight.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.omr.musicxml.UnsupportedMusicXmlException
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import com.sheetsight.app.domain.repository.GeneratedScoreDeletionResult
import com.sheetsight.app.domain.repository.MusicXmlVersionPersistenceResult
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.identity.EditableScoreIdentityIndex
import com.sheetsight.app.ui.editor.identity.NoteIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorSourceKey(
    val scoreId: Long,
    val currentMusicXmlPath: String,
    val fileSizeBytes: Long,
    val lastModifiedMillis: Long
) {
    val rendererKey: String = "$scoreId|$currentMusicXmlPath|$fileSizeBytes|$lastModifiedMillis"
}

sealed interface EditorUiState {
    data object NoScoreSelected : EditorUiState
    data object Loading : EditorUiState
    data class Ready(
        val scoreId: Long,
        val title: String,
        val currentMusicXmlPath: String,
        val sourceKey: EditorSourceKey,
        /** Stable for the visible renderer session; artifact versions change independently. */
        val renderSessionKey: String,
        val document: NotationDocument,
        val musicXml: String,
        val initialSystemIndex: Int,
        val initialZoom: Float,
        val warningSummary: String?,
        val identityIndex: EditableScoreIdentityIndex
    ) : EditorUiState
    data class NoCurrentMusicXml(val title: String) : EditorUiState
    data class FileMissing(val title: String, val path: String) : EditorUiState
    data class UnreadableFile(val title: String, val path: String) : EditorUiState
    data class EmptyFile(val title: String, val path: String) : EditorUiState
    data class ParseError(val title: String, val debugMessage: String) : EditorUiState
    data class UnsupportedScore(val title: String, val reason: String) : EditorUiState
    data class RenderError(val title: String, val debugMessage: String) : EditorUiState
    data class ScoreNotFound(val scoreId: Long) : EditorUiState
}

sealed interface EditorFeedback {
    data object GeneratedScoreDeleted : EditorFeedback
    data class DeleteFailed(val message: String) : EditorFeedback
    data class PitchEditFailed(val message: String) : EditorFeedback
}

sealed interface EditorPitchVisualUpdate {
    val revision: Long
    val noteIdentity: NoteIdentity

    data class Apply(
        override val revision: Long,
        override val noteIdentity: NoteIdentity,
        val pitchMidi: Int,
        val pitchStep: String,
        val pitchOctave: Int
    ) : EditorPitchVisualUpdate

    data class Rollback(
        override val revision: Long,
        override val noteIdentity: NoteIdentity
    ) : EditorPitchVisualUpdate

    data class Commit(
        override val revision: Long,
        override val noteIdentity: NoteIdentity
    ) : EditorPitchVisualUpdate
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val scoreRepository: ScoreRepository,
    private val musicXmlLoader: EditorMusicXmlLoader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val logger = Logger.getLogger(EditorViewModel::class.java.name)
    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.NoScoreSelected)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    private val _feedback = MutableStateFlow<EditorFeedback?>(null)
    val feedback: StateFlow<EditorFeedback?> = _feedback.asStateFlow()
    private val _deletingScoreIds = MutableStateFlow<Set<Long>>(emptySet())
    val deletingScoreIds: StateFlow<Set<Long>> = _deletingScoreIds.asStateFlow()
    private val _selection = MutableStateFlow<EditorSelection?>(null)
    val selection: StateFlow<EditorSelection?> = _selection.asStateFlow()
    private val _noteEditInProgress = MutableStateFlow(false)
    val noteEditInProgress: StateFlow<Boolean> = _noteEditInProgress.asStateFlow()
    private val _pitchVisualUpdate = MutableStateFlow<EditorPitchVisualUpdate?>(null)
    val pitchVisualUpdate: StateFlow<EditorPitchVisualUpdate?> = _pitchVisualUpdate.asStateFlow()

    private var currentScoreId: Long? = null
    private var observedScore: Score? = null
    private var scoreObserverJob: Job? = null
    private var contentLoadJob: Job? = null
    private var zoomPersistenceJob: Job? = null
    private var loadedSourceKey: EditorSourceKey? = null
    private var loadingSourceKey: EditorSourceKey? = null
    private var lastPersistedSystem: Int? = null
    private var pendingSelectionIdentity: NoteIdentity? = null
    private var pitchVisualRevision = 0L
    private var editBaseSourceKey: EditorSourceKey? = null
    private val pendingNoteEdits = ArrayDeque<NaturalNoteDirection>()

    /** Scores that have completed OMR and can be viewed in the Editor. */
    val recognizedScores: StateFlow<List<Score>> = scoreRepository.observeEditorScores()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun deleteGeneratedScore(scoreId: Long) {
        if (scoreId <= 0L || scoreId in _deletingScoreIds.value) return
        viewModelScope.launch(ioDispatcher) {
            _deletingScoreIds.update { it + scoreId }
            try {
                when (val result = scoreRepository.deleteGeneratedScore(scoreId)) {
                    is GeneratedScoreDeletionResult.Success -> {
                        if (currentScoreId == scoreId) {
                            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { showScorePicker() }
                        }
                        _feedback.value = EditorFeedback.GeneratedScoreDeleted
                    }
                    is GeneratedScoreDeletionResult.Failure -> {
                        _feedback.value = EditorFeedback.DeleteFailed(result.message)
                    }
                }
            } finally {
                _deletingScoreIds.update { it - scoreId }
            }
        }
    }

    fun onFeedbackShown() {
        _feedback.value = null
    }

    fun loadScore(scoreId: Long, force: Boolean = false) {
        if (scoreId <= 0L) {
            clearSession()
            _uiState.value = EditorUiState.NoScoreSelected
            return
        }

        // If navigated from bottom tab (NoScoreSelected) and user picks a score,
        // always proceed even though currentScoreId wasn't previously set.
        if (currentScoreId == scoreId && _uiState.value !is EditorUiState.NoScoreSelected) {
            if (force) observedScore?.let { resolveAndLoad(it, force = true) }
            return
        }

        scoreObserverJob?.cancel()
        contentLoadJob?.cancel()
        currentScoreId = scoreId
        observedScore = null
        loadedSourceKey = null
        loadingSourceKey = null
        lastPersistedSystem = null
        _selection.value = null
        _uiState.value = EditorUiState.Loading

        scoreObserverJob = viewModelScope.launch {
            scoreRepository.getScoreById(scoreId).collect { score ->
                if (currentScoreId != scoreId) return@collect
                observedScore = score
                if (score == null) {
                    contentLoadJob?.cancel()
                    loadedSourceKey = null
                    loadingSourceKey = null
                    _uiState.value = EditorUiState.ScoreNotFound(scoreId)
                } else {
                    resolveAndLoad(score)
                }
            }
        }
    }

    private fun resolveAndLoad(score: Score, force: Boolean = false) {
        val path = score.currentMusicXmlPath?.trim()
        val editingBase = editBaseSourceKey
        if (_noteEditInProgress.value && editingBase != null && path != null &&
            path != editingBase.currentMusicXmlPath
        ) {
            logger.info("EDITOR_EDIT deferring observer reload for optimistic version path=$path")
            return
        }
        logger.info(
            "EDITOR_RESOLVE scoreId=${score.id} title=${score.title} " +
                "originalFilePath=${score.originalFilePath} " +
                "currentMusicXmlPath=$path force=$force " +
                "loadedSourceKey=${loadedSourceKey?.rendererKey}"
        )
        if (path.isNullOrEmpty()) {
            contentLoadJob?.cancel()
            loadedSourceKey = null
            loadingSourceKey = null
            _uiState.value = EditorUiState.NoCurrentMusicXml(score.title)
            return
        }

        val previousLoadJob = contentLoadJob
        contentLoadJob = viewModelScope.launch {
            val file = File(path)
            val fileFacts = withContext(ioDispatcher) {
                EditorFileFacts(
                    exists = file.exists(),
                    isFile = file.isFile,
                    canRead = file.canRead(),
                    size = runCatching { file.length() }.getOrDefault(0L),
                    lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
                )
            }
            if (!fileFacts.exists || !fileFacts.isFile) {
                previousLoadJob?.cancel()
                failSource(EditorUiState.FileMissing(score.title, path))
                return@launch
            }
            if (!fileFacts.canRead) {
                previousLoadJob?.cancel()
                failSource(EditorUiState.UnreadableFile(score.title, path))
                return@launch
            }
            if (fileFacts.size <= 0L) {
                previousLoadJob?.cancel()
                failSource(EditorUiState.EmptyFile(score.title, path))
                return@launch
            }

            val sourceKey = EditorSourceKey(score.id, path, fileFacts.size, fileFacts.lastModified)
            if (!force && (sourceKey == loadedSourceKey || sourceKey == loadingSourceKey)) return@launch
            previousLoadJob?.cancel()
            if (_selection.value?.sourceKey != sourceKey) _selection.value = null
            loadingSourceKey = sourceKey
            _uiState.value = EditorUiState.Loading
            val readinessStart = System.nanoTime()

            try {
                val result = withContext(ioDispatcher) { musicXmlLoader.load(file, score.id) }
                if (currentScoreId != score.id || loadingSourceKey != sourceKey) return@launch
                if (!result.document.hasRenderableEvents) {
                    failSource(
                        EditorUiState.UnsupportedScore(
                            score.title,
                            "The MusicXML has no supported notes or rests to display."
                        )
                    )
                    return@launch
                }
                val stats = result.document.statistics
                val warning = result.document.unsupportedElements.takeIf { it.isNotEmpty() }
                    ?.entries?.joinToString(
                        prefix = "Some elements are not displayed: ",
                        separator = ", "
                    ) { (name, count) -> "$name ($count)" }
                val initialSystem = score.lastViewedPage.coerceIn(0, result.document.systems.lastIndex)
                loadedSourceKey = sourceKey
                loadingSourceKey = null
                _uiState.value = EditorUiState.Ready(
                    scoreId = score.id,
                    title = score.title,
                    currentMusicXmlPath = path,
                    sourceKey = sourceKey,
                    renderSessionKey = sourceKey.rendererKey,
                    document = result.document,
                    musicXml = result.musicXml,
                    initialSystemIndex = initialSystem,
                    initialZoom = score.lastViewedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
                    warningSummary = warning,
                    identityIndex = requireNotNull(result.identityIndex)
                )
                restorePendingSelection(sourceKey, requireNotNull(result.identityIndex))
                _noteEditInProgress.value = false
                lastPersistedSystem = initialSystem
                val readyMs = (System.nanoTime() - readinessStart) / 1_000_000L
                logger.info(
                    "EDITOR_LOAD scoreId=${score.id} " +
                        "originalMusicXmlPath=${score.originalMusicXmlPath} " +
                        "currentMusicXmlPath=$path selectedSource=CURRENT fileExists=true " +
                        "fileSize=${result.fileSizeBytes} measures=${stats.measureCount} " +
                        "systems=${result.document.systems.size} readinessMs=$readyMs"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (empty: EmptyEditorScoreException) {
                logger.log(Level.WARNING, "Empty current MusicXML scoreId=${score.id} path=$path", empty)
                failSource(EditorUiState.EmptyFile(score.title, path))
            } catch (unsupported: UnsupportedEditorScoreException) {
                logger.log(Level.WARNING, "Unsupported current MusicXML scoreId=${score.id} path=$path", unsupported)
                failSource(EditorUiState.UnsupportedScore(score.title, unsupported.message ?: "Unsupported score."))
            } catch (unsupported: UnsupportedMusicXmlException) {
                logger.log(Level.WARNING, "Unsupported current MusicXML scoreId=${score.id} path=$path", unsupported)
                failSource(
                    EditorUiState.UnsupportedScore(
                        score.title,
                        unsupported.message ?: "This MusicXML format is not supported."
                    )
                )
            } catch (io: IOException) {
                logger.log(Level.WARNING, "Unreadable current MusicXML scoreId=${score.id} path=$path", io)
                val state = if (!file.exists()) {
                    EditorUiState.FileMissing(score.title, path)
                } else {
                    EditorUiState.UnreadableFile(score.title, path)
                }
                failSource(state)
            } catch (security: SecurityException) {
                logger.log(Level.WARNING, "Permission denied for current MusicXML scoreId=${score.id} path=$path", security)
                failSource(EditorUiState.UnreadableFile(score.title, path))
            } catch (failure: Exception) {
                val debugMessage = "${failure::class.java.simpleName}: ${failure.message ?: "unknown parse failure"}"
                logger.log(Level.WARNING, "Current MusicXML parse failed scoreId=${score.id} path=$path", failure)
                failSource(EditorUiState.ParseError(score.title, debugMessage))
            }
        }
    }

    private fun failSource(state: EditorUiState) {
        loadedSourceKey = null
        loadingSourceKey = null
        _selection.value = null
        pendingSelectionIdentity = null
        _noteEditInProgress.value = false
        pendingNoteEdits.clear()
        _uiState.value = state
    }

    fun retry() {
        observedScore?.let { resolveAndLoad(it, force = true) }
    }

    /** Returns to the score picker list, clearing any loaded score. */
    fun showScorePicker() {
        clearSession()
        _uiState.value = EditorUiState.NoScoreSelected
    }

    fun onRenderError(sourceKey: EditorSourceKey, message: String) {
        val ready = _uiState.value as? EditorUiState.Ready ?: return
        if (ready.sourceKey != sourceKey) return
        logger.warning("alphaTab render failed scoreId=${ready.scoreId} path=${ready.currentMusicXmlPath}: $message")
        _selection.value = null
        _uiState.value = EditorUiState.RenderError(ready.title, message)
    }

    fun onSelectionChanged(selection: EditorSelection?) {
        if (selection == null) {
            _selection.value = null
            return
        }
        val ready = _uiState.value as? EditorUiState.Ready ?: return
        if (selection.sourceKey != ready.sourceKey) {
            logger.warning("EDITOR_SELECTION rejected stale source=${selection.sourceKey.rendererKey}")
            return
        }
        val valid = when (selection) {
            is EditorSelection.NoteSelection -> ready.identityIndex.notes.singleOrNull {
                it.identity == selection.note.identity
            } == selection.note && ready.identityIndex.chords.singleOrNull {
                it.identity == selection.chordIdentity
            }?.notes?.contains(selection.note) == true
            is EditorSelection.ChordSelection -> ready.identityIndex.chords.singleOrNull {
                it.identity == selection.chord.identity
            } == selection.chord
            is EditorSelection.RestSelection -> ready.identityIndex.rests.singleOrNull {
                it.identity == selection.rest.identity
            } == selection.rest
            is EditorSelection.ClefSelection -> ready.identityIndex.clefs.singleOrNull {
                it.identity == selection.clef.identity
            } == selection.clef
            is EditorSelection.BarlineSelection -> ready.identityIndex.barlines.singleOrNull {
                it.identity == selection.barline.identity
            } == selection.barline
            is EditorSelection.MeasureSelection -> ready.identityIndex.measures.singleOrNull {
                it.identity == selection.measure.identity
            } == selection.measure
        }
        if (valid) {
            _selection.value = selection
        } else {
            logger.warning("EDITOR_SELECTION rejected identity outside the current score index")
        }
    }

    fun moveSelectedNote(direction: NaturalNoteDirection) {
        if (_noteEditInProgress.value) {
            pendingNoteEdits.addLast(direction)
            logger.info("EDITOR_NOTE_EDIT queued direction=$direction pending=${pendingNoteEdits.size}")
            return
        }
        val ready = _uiState.value as? EditorUiState.Ready ?: return
        val selected = _selection.value as? EditorSelection.NoteSelection ?: return
        if (selected.sourceKey != ready.sourceKey) return
        val currentStep = selected.note.pitchStep ?: return
        val currentOctave = selected.note.pitchOctave ?: return
        val previewTarget = naturalTarget(currentStep, currentOctave, direction) ?: return

        _noteEditInProgress.value = true
        editBaseSourceKey = ready.sourceKey
        val applyRevision = ++pitchVisualRevision
        _pitchVisualUpdate.value = EditorPitchVisualUpdate.Apply(
            revision = applyRevision,
            noteIdentity = selected.note.identity,
            pitchMidi = previewTarget.midi,
            pitchStep = previewTarget.step,
            pitchOctave = previewTarget.octave
        )
        viewModelScope.launch {
            val prepared = withContext(ioDispatcher) {
                runCatching {
                    val edit = SelectedNotePitchEditor.edit(
                        scoreId = ready.scoreId,
                        sourceBytes = ready.musicXml.toByteArray(Charsets.UTF_8),
                        noteIdentity = selected.note.identity,
                        direction = direction
                    )
                    edit to musicXmlLoader.loadBytes(edit.musicXmlBytes, ready.scoreId)
                }
            }.getOrElse { failure ->
                failOptimisticNoteEdit(selected.note.identity, failure.message)
                return@launch
            }
            val (edit, reconciled) = prepared
            if (!reconciled.document.hasRenderableEvents) {
                failOptimisticNoteEdit(selected.note.identity, "The edited score is not renderable.")
                return@launch
            }

            when (
                val persistence = withContext(ioDispatcher) {
                    scoreRepository.persistEditedMusicXmlVersion(
                        id = ready.scoreId,
                        expectedCurrentPath = ready.currentMusicXmlPath,
                        musicXmlBytes = edit.musicXmlBytes
                    )
                }
            ) {
                is MusicXmlVersionPersistenceResult.Failure -> {
                    failOptimisticNoteEdit(edit.noteIdentity, persistence.message)
                }
                is MusicXmlVersionPersistenceResult.Success -> {
                    reconcileSuccessfulEdit(ready, edit, reconciled, persistence.currentMusicXmlPath)
                }
            }
        }
    }

    private suspend fun reconcileSuccessfulEdit(
        previous: EditorUiState.Ready,
        edit: SelectedNotePitchEdit,
        reconciled: EditorLoadResult,
        currentPath: String
    ) {
        val facts = withContext(ioDispatcher) {
            val file = File(currentPath)
            EditorFileFacts(file.isFile, file.isFile, file.canRead(), file.length(), file.lastModified())
        }
        if (!facts.exists || !facts.canRead || facts.size <= 0L) {
            failOptimisticNoteEdit(edit.noteIdentity, "The validated edited artifact is unavailable.")
            return
        }
        val latestState = _uiState.value as? EditorUiState.Ready
        if (latestState?.sourceKey != previous.sourceKey || currentScoreId != previous.scoreId) {
            failOptimisticNoteEdit(edit.noteIdentity, "The edited score is no longer open.")
            return
        }
        val nextSourceKey = EditorSourceKey(previous.scoreId, currentPath, facts.size, facts.lastModified)
        val index = requireNotNull(reconciled.identityIndex)
        val note = index.notes.singleOrNull { it.identity == edit.noteIdentity }
        val chord = index.chords.singleOrNull { candidate ->
            candidate.notes.count { it.identity == edit.noteIdentity } == 1
        }
        if (note == null || chord == null) {
            failOptimisticNoteEdit(edit.noteIdentity, "The edited note identity could not be reconciled.")
            return
        }
        val warning = reconciled.document.unsupportedElements.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString(prefix = "Some elements are not displayed: ", separator = ", ") {
                (name, count) -> "$name ($count)"
            }
        loadedSourceKey = nextSourceKey
        loadingSourceKey = null
        editBaseSourceKey = null
        _uiState.value = previous.copy(
            currentMusicXmlPath = currentPath,
            sourceKey = nextSourceKey,
            document = reconciled.document,
            musicXml = reconciled.musicXml,
            warningSummary = warning,
            identityIndex = index
        )
        _selection.value = EditorSelection.NoteSelection(nextSourceKey, chord.identity, note)
        _pitchVisualUpdate.value = EditorPitchVisualUpdate.Commit(
            revision = ++pitchVisualRevision,
            noteIdentity = edit.noteIdentity
        )
        _noteEditInProgress.value = false
        observedScore = observedScore?.takeIf { it.id == previous.scoreId }
            ?.copy(currentMusicXmlPath = currentPath)
        logger.info(
            "EDITOR_NOTE_EDIT committed identity=${edit.noteIdentity.value} target=${edit.pitchStep}${edit.pitchOctave} " +
                "currentMusicXmlPath=$currentPath originalMusicXmlPath=${observedScore?.originalMusicXmlPath}"
        )
        pendingNoteEdits.removeFirstOrNull()?.let(::moveSelectedNote)
    }

    private fun failOptimisticNoteEdit(noteIdentity: NoteIdentity, message: String?) {
        editBaseSourceKey = null
        _pitchVisualUpdate.value = EditorPitchVisualUpdate.Rollback(
            revision = ++pitchVisualRevision,
            noteIdentity = noteIdentity
        )
        _noteEditInProgress.value = false
        pendingNoteEdits.clear()
        _feedback.value = EditorFeedback.PitchEditFailed(
            message ?: "The selected note could not be edited safely."
        )
    }

    private fun restorePendingSelection(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex
    ) {
        val identity = pendingSelectionIdentity ?: return
        pendingSelectionIdentity = null
        val note = identityIndex.notes.singleOrNull { it.identity == identity } ?: return
        val chord = identityIndex.chords.singleOrNull { candidate ->
            candidate.notes.count { it.identity == identity } == 1
        } ?: return
        _selection.value = EditorSelection.NoteSelection(sourceKey, chord.identity, note)
    }

    fun onSystemChanged(systemIndex: Int) {
        val ready = _uiState.value as? EditorUiState.Ready ?: return
        val bounded = systemIndex.coerceIn(0, ready.document.systems.lastIndex)
        if (bounded == lastPersistedSystem) return
        lastPersistedSystem = bounded
        viewModelScope.launch(ioDispatcher) {
            scoreRepository.updateLastViewedPage(ready.scoreId, bounded)
        }
    }

    fun onZoomChanged(zoom: Float) {
        val ready = _uiState.value as? EditorUiState.Ready ?: return
        val bounded = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomPersistenceJob?.cancel()
        zoomPersistenceJob = viewModelScope.launch {
            delay(ZOOM_SAVE_DEBOUNCE_MS)
            withContext(ioDispatcher) {
                scoreRepository.updateLastViewedZoom(ready.scoreId, bounded)
            }
        }
    }

    private fun clearSession() {
        currentScoreId = null
        observedScore = null
        scoreObserverJob?.cancel()
        contentLoadJob?.cancel()
        loadedSourceKey = null
        loadingSourceKey = null
        lastPersistedSystem = null
        _selection.value = null
        pendingSelectionIdentity = null
        _noteEditInProgress.value = false
        _pitchVisualUpdate.value = null
        editBaseSourceKey = null
        pendingNoteEdits.clear()
    }

    companion object {
        const val MIN_ZOOM = 0.8f
        const val MAX_ZOOM = 3f
        private const val ZOOM_SAVE_DEBOUNCE_MS = 300L

        private fun naturalTarget(
            step: String,
            octave: Int,
            direction: NaturalNoteDirection
        ): NaturalTarget? {
            val steps = listOf("C", "D", "E", "F", "G", "A", "B")
            val current = steps.indexOf(step.uppercase()).takeIf { it >= 0 } ?: return null
            val next = when (direction) {
                NaturalNoteDirection.UP -> (current + 1) % steps.size
                NaturalNoteDirection.DOWN -> (current - 1 + steps.size) % steps.size
            }
            val targetOctave = octave + when {
                direction == NaturalNoteDirection.UP && current == steps.lastIndex -> 1
                direction == NaturalNoteDirection.DOWN && current == 0 -> -1
                else -> 0
            }
            val semitone = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
                .getValue(steps[next])
            val midi = (targetOctave + 1) * 12 + semitone
            return NaturalTarget(steps[next], targetOctave, midi).takeIf { midi in 0..127 }
        }
    }
}

private data class NaturalTarget(val step: String, val octave: Int, val midi: Int)

private data class EditorFileFacts(
    val exists: Boolean,
    val isFile: Boolean,
    val canRead: Boolean,
    val size: Long,
    val lastModified: Long
)
