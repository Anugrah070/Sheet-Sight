package com.sheetsight.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.omr.musicxml.UnsupportedMusicXmlException
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.repository.ScoreRepository
import com.sheetsight.app.ui.editor.notation.NotationDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

sealed interface EditorUiState {
    data object NoScoreSelected : EditorUiState
    data object Loading : EditorUiState
    data class Ready(
        val scoreId: Long,
        val title: String,
        val document: NotationDocument,
        val initialSystemIndex: Int,
        val initialZoom: Float,
        val warningSummary: String?
    ) : EditorUiState
    data class NoMusicXml(val title: String) : EditorUiState
    data class FileMissing(val title: String, val path: String) : EditorUiState
    data class ParseError(val title: String, val debugMessage: String) : EditorUiState
    data class UnsupportedContent(val title: String, val reason: String) : EditorUiState
    data class ScoreNotFound(val scoreId: Long) : EditorUiState
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

    private var currentScoreId: Long? = null
    private var loadJob: Job? = null
    private var zoomPersistenceJob: Job? = null
    private var lastPersistedSystem: Int? = null

    fun loadScore(scoreId: Long, force: Boolean = false) {
        if (scoreId <= 0L) {
            currentScoreId = null
            loadJob?.cancel()
            _uiState.value = EditorUiState.NoScoreSelected
            return
        }
        if (!force && currentScoreId == scoreId && _uiState.value !is EditorUiState.Loading) return

        currentScoreId = scoreId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            val readinessStart = System.nanoTime()
            val score = withContext(ioDispatcher) { scoreRepository.getScoreById(scoreId).first() }
            if (score == null) {
                _uiState.value = EditorUiState.ScoreNotFound(scoreId)
                return@launch
            }

            val path = score.musicXmlPath?.trim()
            if (path.isNullOrEmpty()) {
                _uiState.value = EditorUiState.NoMusicXml(score.title)
                return@launch
            }

            val file = File(path)
            if (!withContext(ioDispatcher) { file.isFile && file.canRead() }) {
                _uiState.value = EditorUiState.FileMissing(score.title, path)
                return@launch
            }

            try {
                val result = withContext(ioDispatcher) { musicXmlLoader.load(file) }
                if (!result.document.hasRenderableEvents) {
                    _uiState.value = EditorUiState.UnsupportedContent(
                        score.title,
                        "The MusicXML has no supported notes or rests to display."
                    )
                    return@launch
                }
                val stats = result.document.statistics
                val warning = result.document.unsupportedElements.takeIf { it.isNotEmpty() }
                    ?.entries?.joinToString(
                        prefix = "Some elements are not displayed: ",
                        separator = ", "
                    ) { (name, count) -> "$name ($count)" }
                _uiState.value = EditorUiState.Ready(
                    scoreId = score.id,
                    title = score.title,
                    document = result.document,
                    initialSystemIndex = score.lastViewedPage.coerceIn(0, result.document.systems.lastIndex),
                    initialZoom = score.lastViewedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
                    warningSummary = warning
                )
                lastPersistedSystem = score.lastViewedPage.coerceIn(0, result.document.systems.lastIndex)
                val readyMs = (System.nanoTime() - readinessStart) / 1_000_000L
                logger.info(
                    "[EDITOR_SMOKE] scoreId=${score.id} musicXmlPath=$path fileSize=${result.fileSizeBytes} " +
                        "measures=${stats.measureCount} staffs=${stats.staffCount} notes=${stats.noteCount} " +
                        "chords=${stats.chordCount} rests=${stats.restCount} " +
                        "explicitBarlines=${stats.explicitBarlineCount} " +
                        "barlineLocations=${stats.explicitBarlineLocations} " +
                        "renderedMeasures=${result.document.renderedMeasureCount} " +
                        "fileLoadMs=${result.timings.fileLoadMs} xmlParseMs=${result.timings.xmlParseMs} " +
                        "semanticConversionMs=${result.timings.semanticConversionMs} " +
                        "layoutMs=${result.timings.notationLayoutMs} firstRenderReadinessMs=$readyMs"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unsupported: UnsupportedMusicXmlException) {
                logger.log(Level.WARNING, "[EDITOR_SMOKE] Unsupported MusicXML scoreId=$scoreId path=$path", unsupported)
                _uiState.value = EditorUiState.UnsupportedContent(
                    score.title,
                    unsupported.message ?: "This MusicXML format is not supported."
                )
            } catch (io: IOException) {
                logger.log(Level.WARNING, "[EDITOR_SMOKE] MusicXML became unreadable scoreId=$scoreId path=$path", io)
                _uiState.value = EditorUiState.FileMissing(score.title, path)
            } catch (failure: Exception) {
                val debugMessage = "${failure::class.java.simpleName}: ${failure.message ?: "unknown parse failure"}"
                logger.log(Level.WARNING, "[EDITOR_SMOKE] MusicXML parse failed scoreId=$scoreId path=$path", failure)
                _uiState.value = EditorUiState.ParseError(score.title, debugMessage)
            }
        }
    }

    fun retry() {
        currentScoreId?.let { loadScore(it, force = true) }
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

    companion object {
        const val MIN_ZOOM = 0.8f
        const val MAX_ZOOM = 3f
        private const val ZOOM_SAVE_DEBOUNCE_MS = 300L
    }
}
