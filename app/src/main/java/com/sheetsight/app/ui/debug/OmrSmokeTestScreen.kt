package com.sheetsight.app.ui.debug

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
//import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.data.omr.debug.OmrSmokeTestDiagnosticResult
import com.sheetsight.app.data.omr.debug.SmokeTestStage
import com.sheetsight.app.domain.model.Score

/**
 * Developer-only smoke test screen: pick one imported score, pick a stage
 * to stop after, and run the real OMR pipeline up to exactly that point.
 * "Run to <stage>" always restarts from stage 1; "Advance & run next
 * stage" bumps the stop point by one and re-runs. Neither ever shows a
 * fabricated OmrResult/MusicXML — only what the real pipeline produced
 * before it stopped or failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmrSmokeTestScreen(
    onBack: () -> Unit,
    viewModel: OmrSmokeTestViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OMR Smoke Test (Debug)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Stage-by-stage diagnostic. Each run restarts from stage 1 and stops " +
                            "after the selected stage. If the app is killed mid-run, check logcat " +
                            "for tag [OMR_SMOKE]: the last START line with no matching END/FAILED " +
                            "line is the stage that was executing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                ScorePicker(
                    scores = uiState.scores,
                    selected = uiState.selectedScore,
                    onSelected = viewModel::onScoreSelected
                )
            }
            item {
                StagePicker(
                    selected = uiState.stopAfter,
                    onSelected = viewModel::onStopAfterSelected
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::onRunRequested,
                        enabled = uiState.selectedScore != null && !uiState.isRunning
                    ) {
                        Text(if (uiState.isRunning) "Running…" else "Run to \"${uiState.stopAfter.label}\"")
                    }
                    OutlinedButton(
                        onClick = viewModel::onAdvanceToNextStageRequested,
                        enabled = uiState.selectedScore != null &&
                                !uiState.isRunning &&
                                uiState.stopAfter != SmokeTestStage.entries.last()
                    ) {
                        Text("Advance & run next stage")
                    }
                }
            }
            if (uiState.isRunning) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            uiState.error?.let { message ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            uiState.diagnostic?.let { diagnostic ->
                item { SummaryCard(diagnostic) }
                items(diagnostic.stageDurations) { timing ->
                    Text(
                        text = "${timing.stage.logName}: ${timing.durationMs}ms — " +
                                "usedMem=${timing.usedMemAfterMb}MB " +
                                "(total=${timing.totalMemAfterMb}MB free=${timing.freeMemAfterMb}MB)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                SmokeTestStage.entries.forEach { stage ->
                    val stagePreviews = diagnostic.previews[stage]
                    val stageDetails = diagnostic.stageDetails[stage]
                    if (stagePreviews != null || stageDetails != null) {
                        item {
                            StageCard(title = stage.logName) {
                                stageDetails?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                stagePreviews?.forEach { preview ->
                                    Text(preview.label, style = MaterialTheme.typography.labelSmall)
                                    ThumbnailImage(preview.bitmap)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScorePicker(
    scores: List<Score>,
    selected: Score?,
    onSelected: (Score) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.title ?: "Select a score",
            onValueChange = {},
            readOnly = true,
            label = { Text("Score") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            scores.forEach { score ->
                DropdownMenuItem(
                    text = { Text(score.title) },
                    onClick = {
                        onSelected(score)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StagePicker(
    selected: SmokeTestStage,
    onSelected: (SmokeTestStage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.logName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Stop after") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SmokeTestStage.entries.forEach { stage ->
                DropdownMenuItem(
                    text = { Text(stage.logName) },
                    onClick = {
                        onSelected(stage)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(diagnostic: OmrSmokeTestDiagnosticResult) {
    Card {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last completed stage", style = MaterialTheme.typography.titleSmall)
            Text(diagnostic.lastCompletedStage?.logName ?: "none (failed before stage 1 completed)")
        }
    }
}

@Composable
private fun StageCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ThumbnailImage(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
    )
}