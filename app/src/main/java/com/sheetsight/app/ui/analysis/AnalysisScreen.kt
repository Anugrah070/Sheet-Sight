package com.sheetsight.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.data.omr.OmrState
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

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.omrState is OmrState.InProgress) {
            OmrProgressOverlay(
                update = uiState.progress,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            PlaceholderContent(
                message = stringResource(R.string.analysis_placeholder),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun OmrProgressOverlay(
    update: com.sheetsight.app.data.omr.OmrProgressUpdate?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Processing Score...",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        
        if (update != null) {
            if (update.isIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { update.overallPercentage / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Text(
                text = if (update.totalTiles > 0) {
                    "${update.stage.displayName}\nTile ${update.currentTile} / ${update.totalTiles} (${update.overallPercentage}%)"
                } else {
                    "${update.stage.displayName} (${update.overallPercentage}%)"
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = "Initializing...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
