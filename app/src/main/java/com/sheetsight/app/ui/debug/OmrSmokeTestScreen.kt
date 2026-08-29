package com.sheetsight.app.ui.debug

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.data.omr.debug.OmrSmokeTestDiagnosticResult
import com.sheetsight.app.data.omr.debug.SmokeTestStage
import com.sheetsight.app.domain.model.Score
import java.io.File

private const val MUSICXML_MIME_TYPE = "application/vnd.recordare.musicxml+xml"
private const val ZIP_MIME_TYPE = "application/zip"

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
    var fullscreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val persistentMusicXmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MUSICXML_MIME_TYPE)
    ) { uri ->
        if (uri != null) viewModel.onPersistentMusicXmlTargetSelected(uri)
    }
    val persistentDebugBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ZIP_MIME_TYPE)
    ) { uri ->
        if (uri != null) viewModel.onPersistentDebugBundleTargetSelected(uri)
    }

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
        Box(modifier = Modifier.fillMaxSize()) {
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
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { (uiState.progress?.overallPercentage ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = uiState.progress?.let { update ->
                                    if (update.totalTiles > 0) {
                                        "${update.stage.displayName} — Tile ${update.currentTile} / ${update.totalTiles} (${update.overallPercentage}%)"
                                    } else {
                                        "${update.stage.displayName} (${update.overallPercentage}%)"
                                    }
                                } ?: "Initializing...",
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                    diagnostic.musicXmlOutputPath?.let { outputPath ->
                        item {
                            PersistentMusicXmlExportCard(
                                fileName = File(outputPath).name,
                                isSaving = uiState.isSavingMusicXml,
                                message = uiState.musicXmlSaveMessage,
                                error = uiState.musicXmlSaveError,
                                onSave = { persistentMusicXmlLauncher.launch(File(outputPath).name) }
                            )
                        }
                    }
                    diagnostic.debugBundlePath?.let { bundlePath ->
                        item {
                            PersistentDebugBundleExportCard(
                                fileName = File(bundlePath).name,
                                isSaving = uiState.isSavingDebugBundle,
                                message = uiState.debugBundleSaveMessage,
                                error = uiState.debugBundleSaveError,
                                onSave = { persistentDebugBundleLauncher.launch(File(bundlePath).name) }
                            )
                        }
                    }
                    items(diagnostic.stageDurations) { timing ->
                        val mem = timing.memoryAfter
                        Text(
                            text = "${timing.stage.logName}: ${timing.durationMs}ms\n" +
                                    "Java: ${mem.javaUsedMb}/${mem.javaTotalMb}MB (Max ${mem.javaMaxMb}MB) | " +
                                    "Native: ${mem.nativeUsedMb}MB",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
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
                                        ThumbnailImage(
                                            bitmap = preview.bitmap,
                                            onClick = { fullscreenBitmap = preview.bitmap }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            fullscreenBitmap?.let { bitmap ->
                BasicAlertDialog(
                    onDismissRequest = { fullscreenBitmap = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { fullscreenBitmap = null },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersistentMusicXmlExportCard(
    fileName: String,
    isSaving: Boolean,
    message: String?,
    error: String?,
    onSave: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Persistent MusicXML debug copy", style = MaterialTheme.typography.titleSmall)
            Text(
                "Save $fileName through the file explorer. The selected copy remains outside " +
                    "app-private storage and survives app data removal or uninstall.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onSave, enabled = !isSaving) {
                Text(if (isSaving) "Saving…" else "Save through file explorer")
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PersistentDebugBundleExportCard(
    fileName: String,
    isSaving: Boolean,
    message: String?,
    error: String?,
    onSave: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Portable OMR debug bundle", style = MaterialTheme.typography.titleSmall)
            Text(
                "$fileName contains stage previews, timings, memory, detections, and MusicXML. " +
                    "Save it before clearing app cache.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onSave, enabled = !isSaving) {
                Text(if (isSaving) "Saving…" else "Save debug ZIP")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
private fun ThumbnailImage(
    bitmap: Bitmap,
    onClick: () -> Unit
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clickable { onClick() }
    )
}
