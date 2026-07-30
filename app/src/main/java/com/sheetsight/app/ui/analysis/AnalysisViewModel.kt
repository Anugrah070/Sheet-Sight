package com.sheetsight.app.ui.analysis

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * UI state for the Analysis tab. Empty for now; Phase 7 (Analysis) will replace this
 * with real state as that feature is implemented.
 */
data class AnalysisUiState(
    val isLoading: Boolean = false
)

/**
 * Holds and exposes [AnalysisUiState] to [AnalysisScreen] via [StateFlow].
 * No dependencies are wired in yet — those arrive with Phase 7 (Analysis).
 */
@HiltViewModel
class AnalysisViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState
}
