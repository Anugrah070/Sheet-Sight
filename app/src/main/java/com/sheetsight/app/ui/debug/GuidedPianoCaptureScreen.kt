package com.sheetsight.app.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.data.audio.DeveloperCaptureAudioSource
import com.sheetsight.app.data.audio.DeveloperCapturePlanType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedPianoCaptureScreen(
    onBack: () -> Unit,
    viewModel: GuidedPianoCaptureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.start(granted)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> viewModel.export(uri) }
    DisposableEffect(viewModel) { onDispose(viewModel::stop) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guided Piano Recording (Debug)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                    "This developer tool records only when you press Record take. Preview sound stays in memory. " +
                        "Export creates one ZIP containing WAV files, device/audio provenance, and an unverified " +
                        "manifest you can attach to a chat later. Ordinary Practice Mode audio is never included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                CapturePicker(
                    label = "Test series",
                    selected = state.planType,
                    values = DeveloperCapturePlanType.entries,
                    display = { it.displayName },
                    enabled = !state.isListening,
                    onSelected = viewModel::selectPlan
                )
            }
            item {
                CapturePicker(
                    label = "Microphone source",
                    selected = state.audioSource,
                    values = DeveloperCaptureAudioSource.entries,
                    display = { it.displayName },
                    enabled = !state.isListening,
                    onSelected = viewModel::selectAudioSource
                )
            }
            item {
                OutlinedTextField(
                    value = state.pianoDescription,
                    onValueChange = viewModel::updatePianoDescription,
                    enabled = !state.isListening,
                    singleLine = true,
                    label = { Text("Piano description") },
                    placeholder = { Text("Example: Yamaha U1 upright") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.roomCondition,
                    onValueChange = viewModel::updateRoomCondition,
                    enabled = !state.isListening,
                    singleLine = true,
                    label = { Text("Room / noise condition") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) viewModel.start(true)
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !state.isListening && state.stage != GuidedPianoCaptureStage.EXPORTING
                    ) { Text(if (state.capturedTakeCount == 0) "Start session" else "Start over") }
                    OutlinedButton(onClick = viewModel::stop, enabled = state.isListening) { Text("Stop") }
                }
            }
            if (state.microphonePermissionDenied) {
                item { Text("Microphone permission is required.", color = MaterialTheme.colorScheme.error) }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Live microphone", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = { state.liveLevel },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(state.pitchText())
                        Text(
                            "RMS ${String.format(Locale.US, "%.4f", state.liveRms)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        state.sourceSummary?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(state.progressText(), style = MaterialTheme.typography.titleMedium)
                        state.currentPrompt?.let { prompt ->
                            Text(prompt.title, style = MaterialTheme.typography.titleSmall)
                            Text(prompt.instruction)
                            Text(
                                "Placement: ${prompt.placement.displayName} · Record ${prompt.durationMillis / 1_000}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.stage == GuidedPianoCaptureStage.RECORDING) {
                            LinearProgressIndicator(
                                progress = { state.recordingProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text("Captured ${state.capturedTakeCount} take(s)")
                    }
                }
            }
            if (state.isListening && state.currentPrompt != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::recordCurrentTake,
                            enabled = state.stage == GuidedPianoCaptureStage.READY
                        ) { Text("Record take") }
                        OutlinedButton(
                            onClick = viewModel::skipCurrent,
                            enabled = state.stage == GuidedPianoCaptureStage.READY
                        ) { Text("Skip") }
                    }
                }
            }
            if (state.capturedTakeCount > 0 && state.stage != GuidedPianoCaptureStage.RECORDING) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::discardLastAndGoBack,
                            enabled = state.stage != GuidedPianoCaptureStage.EXPORTING
                        ) { Text("Redo last") }
                        Button(
                            onClick = { exportLauncher.launch(viewModel.suggestedFileName()) },
                            enabled = state.stage != GuidedPianoCaptureStage.EXPORTING
                        ) { Text("Export ZIP") }
                    }
                }
            }
            state.exportMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> CapturePicker(
    label: String,
    selected: T,
    values: List<T>,
    display: (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(display(value)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
