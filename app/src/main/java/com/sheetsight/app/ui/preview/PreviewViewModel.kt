package com.sheetsight.app.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val isFullscreen: Boolean = false
)

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val scoreRepository: ScoreRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private var currentScoreId: Long? = null

    fun loadScore(scoreId: Long) {
        if (currentScoreId == scoreId) return
        currentScoreId = scoreId
        
        viewModelScope.launch {
            scoreRepository.getScoreById(scoreId).collect { score ->
                if (score != null) {
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
        if (score.lastViewedPage == page) return
        viewModelScope.launch {
            scoreRepository.updateLastViewedPage(score.id, page)
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
