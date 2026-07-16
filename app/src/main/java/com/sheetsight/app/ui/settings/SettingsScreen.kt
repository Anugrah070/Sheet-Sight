package com.sheetsight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.common.PlaceholderContent

/**
 * Settings tab: app-wide preferences (theme, tolerance, metronome defaults, etc). Populated across later phases as those features gain settings.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlaceholderContent(
        message = stringResource(R.string.settings_placeholder),
        modifier = modifier
    )
}
