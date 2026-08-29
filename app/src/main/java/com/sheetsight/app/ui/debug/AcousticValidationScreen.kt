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
import androidx.compose.foundation.lazy.items
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
import com.sheetsight.app.data.audio.AcousticValidationPlacement
import com.sheetsight.app.data.audio.AcousticValidationResult
import com.sheetsight.app.data.audio.AcousticValidationTestCase
import com.sheetsight.app.data.audio.AcousticValidationVerdict
import com.sheetsight.app.data.audio.ExpectedPhysicalAction
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcousticValidationScreen(
    onBack: () -> Unit,
    viewModel: AcousticValidationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.start(granted)
    }
    DisposableEffect(viewModel) { onDispose(viewModel::stop) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Piano Acoustic Validation (Debug)") },
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
                    "Developer-only, session-local validation over the production microphone, YIN pitch, " +
                        "stable-onset, PracticeClock, progression, and release pipeline. It stores no PCM. " +
                        "The microphone observes acoustic persistence, not physical key or pedal state; the " +
                        "manual action label below is the validation ground truth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                EnumPicker(
                    label = "Test",
                    selected = state.selectedTestCase,
                    values = AcousticValidationTestCase.entries,
                    display = { it.displayName },
                    enabled = !state.isRunning,
                    onSelected = viewModel::selectTestCase
                )
            }
            item {
                EnumPicker(
                    label = "Intended physical action (manual ground truth)",
                    selected = state.expectedAction,
                    values = ExpectedPhysicalAction.entries,
                    display = { it.name.replace('_', ' ') },
                    enabled = !state.isRunning,
                    onSelected = viewModel::selectExpectedAction
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        EnumPicker(
                            label = "Phone placement",
                            selected = state.placement,
                            values = AcousticValidationPlacement.entries,
                            display = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                            enabled = !state.isRunning,
                            onSelected = viewModel::selectPlacement
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        EnumPicker(
                            label = "Calibration policy",
                            selected = state.calibrationMode,
                            values = ValidationCalibrationMode.entries,
                            display = { it.displayName },
                            enabled = !state.isRunning,
                            onSelected = viewModel::selectCalibrationMode
                        )
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Procedure", style = MaterialTheme.typography.titleSmall)
                        Text(state.selectedPlan.instructions)
                        Text(
                            "Stored profile: ${state.storedCalibrationQuality ?: "none"}. Repeat selected cases " +
                                "for normal, closer, and farther placements where practical.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                        enabled = !state.isRunning
                    ) { Text("Start case") }
                    OutlinedButton(onClick = viewModel::stop, enabled = state.isRunning) { Text("Stop") }
                }
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (state.microphonePermissionDenied) {
                item { Text("Microphone permission is required.", color = MaterialTheme.colorScheme.error) }
            }
            state.update?.let { update ->
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (state.isRunning) "Live diagnostics" else "Last case", style = MaterialTheme.typography.titleSmall)
                            Text("Progression: ${update.currentStepIndex} / ${update.totalSteps}")
                            Text("Verified accepted onsets: ${update.acceptedOnsetCount}")
                            Text("Latest verified stable pitch: ${update.latestDetectedPitch?.displayName ?: "none"}")
                            update.onsetDiagnostics?.let { diagnostic ->
                                val raw = diagnostic.rawPitch
                                Text(
                                    "Onset frame: ${diagnostic.status}; raw=${raw?.nearestPitch?.displayName ?: "none"}, " +
                                        "conf=${raw?.confidence?.let(::format) ?: "none"}, " +
                                        "rms=${format(diagnostic.signalLevel)}, " +
                                        "gate=${diagnostic.requiredSignalLevel?.let(::format) ?: "n/a"}, " +
                                        "noise=${format(diagnostic.noiseFloorRms)}, " +
                                        "candidate=${diagnostic.candidateFrameCount}/${diagnostic.requiredCandidateFrameCount}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            update.liveNotes.forEach { live ->
                                Text(
                                    "${live.pitch.displayName} ${live.register}: state=${live.sustainState}, " +
                                        "attackConf=${format(live.attackConfidence)}, initialRms=${format(live.initialEnergy)}, " +
                                        "targetConf=${live.latestTargetPitchConfidence?.let(::format) ?: "none"}, " +
                                        "dropout=${live.currentDropoutMillis}ms, residual=${live.residualEnergyDurationMillis}ms",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                items(update.allResults.asReversed()) { result -> ResultCard(result) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumPicker(
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

@Composable
private fun ResultCard(result: AcousticValidationResult) {
    val verdictColor = when (result.verdict) {
        AcousticValidationVerdict.PASS -> MaterialTheme.colorScheme.primary
        AcousticValidationVerdict.REVIEW -> MaterialTheme.colorScheme.error
        AcousticValidationVerdict.INCONCLUSIVE -> MaterialTheme.colorScheme.tertiary
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${result.verdict} - ${result.pitch.displayName} ${result.register}",
                style = MaterialTheme.typography.titleSmall,
                color = verdictColor
            )
            Text("Action: ${result.expectedAction}; inferred: ${result.inferredReleaseState}")
            Text(
                "Release confidence ${format(result.releaseConfidence)}; sustain ambiguous ${result.sustainAmbiguous}; " +
                    "feedback ${result.feedback}"
            )
            Text(
                "Onset ${result.onsetTimestampMillis}; probable acoustic release " +
                    "${result.probableAcousticReleaseTimestampMillis ?: "unresolved"}; observed " +
                    "${result.observedDurationMillis ?: "?"}ms / expected ${result.expectedDurationMillis ?: "?"}ms"
            )
            Text(
                "Attack confidence ${format(result.attackConfidence)}; initial RMS ${format(result.initialEnergy)}; " +
                    "dropouts ${result.dropoutDurationsMillis}; residual ${result.residualEnergyDurationMillis}ms"
            )
            Text(
                "Articulation ${result.articulationExpectation}; calibration ${result.calibrationQuality ?: "none"}; " +
                    "placement ${result.placement}"
            )
            Text(
                "Target-confidence points: ${result.targetPitchConfidenceOverTime.size}. ${result.notes}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)
