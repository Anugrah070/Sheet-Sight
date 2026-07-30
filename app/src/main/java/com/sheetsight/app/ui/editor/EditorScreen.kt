package com.sheetsight.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.common.PlaceholderContent

/**
 * Editor tab: notation editing (notes, rests, accidentals, durations, key/time signatures, ties, slurs, dynamics, tempo) per requirement 2. Implemented in Phase 5.
 */
@Composable
fun EditorScreen(
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlaceholderContent(
        message = stringResource(R.string.editor_placeholder),
        modifier = modifier
    )
}
