package com.sheetsight.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.common.PlaceholderContent

/**
 * Settings tab: app-wide preferences (theme, tolerance, metronome defaults, etc). Populated across later phases as those features gain settings.
 *
 * Also hosts a small "Developer tools" section — currently just the entry
 * point into [com.sheetsight.app.ui.debug.OmrSmokeTestScreen], the
 * developer-only OMR pipeline inspector. That screen is not part of the
 * production feature set and does not belong in the bottom-bar navigation.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenOmrSmokeTest: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        PlaceholderContent(
            message = stringResource(R.string.settings_placeholder),
            modifier = Modifier.weight(1f)
        )
        HorizontalDivider()
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_developer_tools_heading),
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onOpenOmrSmokeTest) {
                Text(stringResource(R.string.settings_omr_smoke_test_entry))
            }
        }
    }
}