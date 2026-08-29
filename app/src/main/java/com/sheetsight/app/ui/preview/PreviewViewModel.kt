package com.sheetsight.app.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.omr.OmrProgressListener
import com.sheetsight.app.data.omr.OmrProgressUpdate
import com.sheetsight.app.data.omr.ScoreOmrProcessor
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PreviewRecognitionState {
    data object Idle : PreviewRecognitionState
    data class Running(val progress: OmrProgressUpdate?) : PreviewRecognitionState
    data object Completed : PreviewRecognitionState
    data class Failed(val message: String) : PreviewRecognitionState
}

sealed interface PreviewEvent {
    data class OpenEditor(val scoreId: Long) : PreviewEvent
}

/**
 * UI state for the Sheet Preview screen.
 *
 * @property score The score being previewed.
 * @property isLoading True while the score metadata is being fetched from Room.
 * @property error User-facing error message if the score can't be found.
 * @property isFullscreen True if toolbars should be hidden.
 */
data class PreviewUiState(
    val score: Score? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isFullscreen: Boolean = false,
    val recognition: PreviewRecognitionState = PreviewRecognitionState.Idle
)

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val scoreRepository: ScoreRepository,
    private val scoreOmrProcessor: ScoreOmrProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<PreviewEvent>(Channel.BUFFERED)
    val events: Flow<PreviewEvent> = eventChannel.receiveAsFlow()

    private var currentScoreId: Long? = null
    private var currentPageIndex: Int = 0

    fun loadScore(scoreId: Long) {
        if (currentScoreId == scoreId) return
        currentScoreId = scoreId
        
        viewModelScope.launch {
            var isFirstScoreEmission = true
            scoreRepository.getScoreById(scoreId).collect { score ->
                if (score != null) {
                    val lastPage = (score.pageCount - 1).coerceAtLeast(0)
                    currentPageIndex = if (isFirstScoreEmission) {
                        score.lastViewedPage.coerceIn(0, lastPage)
                    } else {
                        currentPageIndex.coerceIn(0, lastPage)
                    }
                    isFirstScoreEmission = false
                    _uiState.update { it.copy(score = score, isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Score not found") }
                }
            }
        }
    }

    fun onToggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    /**
     * Persists the current page index so it can be restored on next open.
     */
    fun onPageChanged(page: Int) {
        val score = _uiState.value.score ?: return
        currentPageIndex = page.coerceIn(0, (score.pageCount - 1).coerceAtLeast(0))
        if (score.lastViewedPage == page) return
        viewModelScope.launch {
            scoreRepository.updateLastViewedPage(score.id, page)
        }
    }

    /** Recognizes the page currently visible in Preview, persists its output, and opens Editor. */
    fun onRunOmrRequested() {
        val score = _uiState.value.score ?: return
        if (_uiState.value.recognition is PreviewRecognitionState.Running) return

        viewModelScope.launch {
            _uiState.update { it.copy(recognition = PreviewRecognitionState.Running(progress = null)) }
            try {
                val result = scoreOmrProcessor.recognizePage(
                    score = score,
                    pageIndex = currentPageIndex,
                    listener = object : OmrProgressListener {
                        override fun onProgressUpdate(update: OmrProgressUpdate) {
                            _uiState.update {
                                it.copy(recognition = PreviewRecognitionState.Running(update))
                            }
                        }
                    }
                )
                scoreRepository.setGeneratedMusicXmlPath(score.id, result.musicXmlPath)
                _uiState.update { it.copy(recognition = PreviewRecognitionState.Completed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(
                        recognition = PreviewRecognitionState.Failed(
                            failure.message ?: "Recognition failed. Please try another page image."
                        )
                    )
                }
            }
        }
    }

    fun onRecognitionErrorShown() {
        if (_uiState.value.recognition is PreviewRecognitionState.Failed) {
            _uiState.update { it.copy(recognition = PreviewRecognitionState.Idle) }
        }
    }

    /**
     * Persists the current zoom level so it can be restored on next open.
     */
    fun onZoomChanged(zoom: Float) {
        val score = _uiState.value.score ?: return
        if (score.lastViewedZoom == zoom) return
        viewModelScope.launch {
            scoreRepository.updateLastViewedZoom(score.id, zoom)
        }
    }
}
