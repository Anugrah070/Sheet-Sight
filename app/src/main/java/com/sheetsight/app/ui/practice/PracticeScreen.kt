package com.sheetsight.app.ui.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.common.PlaceholderContent

/**
 * Practice tab: scrolling cursor synced to live pitch detection from mic/MIDI input per requirement 3. Implemented in Phase 6.
 */
@Composable
fun PracticeScreen(
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlaceholderContent(
        message = stringResource(R.string.practice_placeholder),
        modifier = modifier
    )
}
