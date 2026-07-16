package com.sheetsight.app.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.common.PlaceholderContent

/**
 * Analysis tab: key/scale, chords, cadences, arpeggios, intervals, motifs, modulation and difficulty overlays per requirement 4. Implemented in Phase 7.
 */
@Composable
fun AnalysisScreen(
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlaceholderContent(
        message = stringResource(R.string.analysis_placeholder),
        modifier = modifier
    )
}
