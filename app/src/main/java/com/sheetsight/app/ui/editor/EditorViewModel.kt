package com.sheetsight.app.ui.editor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * UI state for the Editor tab. Empty for now; Phase 5 (Editor) will replace this
 * with real state as that feature is implemented.
 */
data class EditorUiState(
    val isLoading: Boolean = false
)

/**
 * Holds and exposes [EditorUiState] to [EditorScreen] via [StateFlow].
 * No dependencies are wired in yet — those arrive with Phase 5 (Editor).
 */
@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState
}
