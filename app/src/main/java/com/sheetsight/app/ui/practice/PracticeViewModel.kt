package com.sheetsight.app.ui.practice

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * UI state for the Practice tab. Empty for now; Phase 6 (Practice Mode) will replace this
 * with real state as that feature is implemented.
 */
data class PracticeUiState(
    val isLoading: Boolean = false
)

/**
 * Holds and exposes [PracticeUiState] to [PracticeScreen] via [StateFlow].
 * No dependencies are wired in yet — those arrive with Phase 6 (Practice Mode).
 */
@HiltViewModel
class PracticeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState
}
