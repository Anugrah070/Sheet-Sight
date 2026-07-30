package com.sheetsight.app.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * UI state for the Settings tab. Empty for now; later phases will replace this
 * with real state as that feature is implemented.
 */
data class SettingsUiState(
    val isLoading: Boolean = false
)

/**
 * Holds and exposes [SettingsUiState] to [SettingsScreen] via [StateFlow].
 * No dependencies are wired in yet — those arrive with later phases.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState
}
