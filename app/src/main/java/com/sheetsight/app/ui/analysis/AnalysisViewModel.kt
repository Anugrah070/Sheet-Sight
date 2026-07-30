package com.sheetsight.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.omr.OmrProgressUpdate
import com.sheetsight.app.data.omr.OmrRepository
import com.sheetsight.app.data.omr.OmrState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Analysis tab.
 */
data class AnalysisUiState(
    val omrState: OmrState = OmrState.Idle,
    val progress: OmrProgressUpdate? = null
)

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val omrRepository: OmrRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            omrRepository.state.collect { state ->
                _uiState.update { it.copy(
                    omrState = state,
                    progress = (state as? OmrState.InProgress)?.progress
                ) }
            }
        }
    }
}
