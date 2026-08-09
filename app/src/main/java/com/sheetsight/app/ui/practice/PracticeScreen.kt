package com.sheetsight.app.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.data.audio.ReleaseCalibrationStage
import com.sheetsight.app.data.audio.ReleaseCalibrationStatus
import com.sheetsight.app.domain.practice.ExpectedArticulation
import com.sheetsight.app.domain.practice.MAX_PRACTICE_BPM
import com.sheetsight.app.domain.practice.MIN_PRACTICE_BPM
import com.sheetsight.app.domain.practice.MatchState
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.domain.practice.PracticeTempoSource
import kotlin.math.roundToInt

private val PracticeChrome = Color(0xFF10131D)
private val PracticeChromeMuted = Color(0xFF9FA4AF)
private val PracticeAccent = Color(0xFFE6951A)

@Composable
fun PracticeScreen(
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importMusicXml)
    }
    var calibrationPermissionPending by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && calibrationPermissionPending) viewModel.startReleaseCalibration(permissionGranted = true)
        else if (granted) viewModel.startOrResume(permissionGranted = true)
        else viewModel.microphonePermissionDenied()
        calibrationPermissionPending = false
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pausePractice()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPractice()
        }
    }

    PracticeScreenContent(
        state = uiState,
        modifier = modifier,
        onImport = {
            importLauncher.launch(
                arrayOf(
                    "application/vnd.recordare.musicxml+xml",
                    "application/xml",
                    "text/xml"
                )
            )
        },
        onStart = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startOrResume(permissionGranted = true)
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onPause = viewModel::pausePractice,
        onStop = viewModel::stopPractice,
        onTempoChange = viewModel::setTempo,
        onCountInChange = viewModel::setCountInEnabled,
        onCalibrateRelease = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startReleaseCalibration(permissionGranted = true)
            else {
                calibrationPermissionPending = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    )
}

@Composable
fun PracticeScreenContent(
    state: PracticeUiState,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
    onStart: () -> Unit = {},
    onPause: () -> Unit = {},
    onStop: () -> Unit = {},
    onTempoChange: (Int) -> Unit = {},
    onCountInChange: (Boolean) -> Unit = {},
    onCalibrateRelease: () -> Unit = {}
) {
    val progress = state.progress
    Scaffold(modifier = modifier, containerColor = PracticeChrome) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).testTag("practice_screen")) {
            when (progress.phase) {
                PracticePhase.NoScore, PracticePhase.Loading, PracticePhase.Error -> EmptyPracticeState(
                    state = state,
                    onImport = onImport,
                    onCalibrateRelease = onCalibrateRelease
                )
                PracticePhase.Ready,
                PracticePhase.CountIn,
                PracticePhase.Listening,
                PracticePhase.Paused,
                PracticePhase.Completed -> Column(Modifier.fillMaxSize()) {
                    PracticeHeader(
                        state = state,
                        onImport = onImport,
                        onStart = onStart,
                        onPause = onPause,
                        onStop = onStop,
                        onTempoChange = onTempoChange,
                        onCountInChange = onCountInChange,
                        onCalibrateRelease = onCalibrateRelease
                    )
                    state.notation?.let { notation ->
                        PracticeScoreViewport(
                            document = notation,
                            state = state,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    } ?: PracticeMessage(
                        stringResource(R.string.practice_score_unavailable),
                        Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }

            if (progress.phase == PracticePhase.CountIn) {
                Surface(
                    color = PracticeAccent.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(112.dp)
                        .testTag("practice_count_in")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = progress.countInRemaining?.toString().orEmpty(),
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (state.microphonePermission == MicrophonePermissionState.Denied) {
                Text(
                    stringResource(R.string.practice_permission_denied),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp)
                        .testTag("practice_permission_denied")
                )
            }
        }
    }
}

@Composable
private fun EmptyPracticeState(
    state: PracticeUiState,
    onImport: () -> Unit,
    onCalibrateRelease: () -> Unit
) {
    val progress = state.progress
    Column(
        modifier = Modifier.fillMaxSize().background(PracticeChrome).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        OutlinedButton(onClick = onImport, modifier = Modifier.testTag("practice_import")) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Text(stringResource(R.string.practice_import_musicxml), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onCalibrateRelease,
            enabled = state.releaseCalibrationStage !in setOf(ReleaseCalibrationStage.QUIET, ReleaseCalibrationStage.NOTES),
            modifier = Modifier.testTag("practice_release_calibration")
        ) {
            Text(stringResource(R.string.practice_release_calibrate_action))
        }
        when (state.releaseCalibrationStage) {
            ReleaseCalibrationStage.QUIET -> Text(stringResource(R.string.practice_release_quiet_instruction), color = Color.White)
            ReleaseCalibrationStage.NOTES -> Text(
                stringResource(
                    R.string.practice_release_notes_instruction,
                    state.releaseCalibrationAcceptedSamples,
                    state.releaseCalibrationTargetSamples
                ),
                color = Color.White
            )
            ReleaseCalibrationStage.COMPLETE -> Text(stringResource(R.string.practice_release_complete), color = Color.White)
            null -> Unit
        }
        when (progress.phase) {
            PracticePhase.NoScore -> Text(stringResource(R.string.practice_no_score), color = Color.White)
            PracticePhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.testTag("practice_loading"), color = PracticeAccent)
                Text(stringResource(R.string.practice_loading), color = Color.White, modifier = Modifier.padding(start = 16.dp))
            }
            PracticePhase.Error -> Text(
                progress.errorMessage ?: stringResource(R.string.practice_load_error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("practice_error")
            )
            else -> Unit
        }
    }
}

@Composable
private fun PracticeHeader(
    state: PracticeUiState,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onTempoChange: (Int) -> Unit,
    onCountInChange: (Boolean) -> Unit,
    onCalibrateRelease: () -> Unit
) {
    val progress = state.progress
    val step = progress.currentStep ?: progress.sequence?.steps?.lastOrNull()
    var settingsExpanded by remember { mutableStateOf(false) }
    Surface(color = PracticeChrome, shadowElevation = 5.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 700.dp
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            progress.sequence?.source?.fileName.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        PracticeHeaderActions(
                            state = state,
                            settingsExpanded = settingsExpanded,
                            onSettingsExpanded = { settingsExpanded = it },
                            onImport = onImport,
                            onStart = onStart,
                            onPause = onPause,
                            onStop = onStop,
                            onTempoChange = onTempoChange,
                            onCountInChange = onCountInChange,
                            onCalibrateRelease = onCalibrateRelease
                        )
                    }
                    PracticeStatusValues(state, step?.measureNumber, compact = true)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            progress.sequence?.source?.fileName.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        PracticeStatusValues(state, step?.measureNumber, compact = false)
                    }
                    PracticeHeaderActions(
                        state = state,
                        settingsExpanded = settingsExpanded,
                        onSettingsExpanded = { settingsExpanded = it },
                        onImport = onImport,
                        onStart = onStart,
                        onPause = onPause,
                        onStop = onStop,
                        onTempoChange = onTempoChange,
                        onCountInChange = onCountInChange,
                        onCalibrateRelease = onCalibrateRelease
                    )
                }
            }
        }
    }
}

@Composable
private fun PracticeStatusValues(state: PracticeUiState, measureNumber: String?, compact: Boolean) {
    val progress = state.progress
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.spacedBy(18.dp)
    ) {
        HeaderValue(
            label = stringResource(R.string.practice_measure_label),
            value = measureNumber ?: "—",
            modifier = Modifier.testTag("practice_measure")
        )
        HeaderValue(
            label = stringResource(R.string.practice_expected),
            value = if (progress.phase == PracticePhase.Completed) "—" else progress.currentStep?.displayText ?: "—",
            modifier = Modifier.testTag("practice_expected_note")
        )
        if (!compact) {
            HeaderValue(
                label = stringResource(R.string.practice_detected),
                value = progress.lastDetectedPitch?.nearestPitch?.displayName ?: "—"
            )
        }
        if (!compact) {
            HeaderValue(
                label = stringResource(R.string.practice_articulation),
                value = articulationLabel(progress.currentStep?.expectedArticulation)
            )
        }
        HeaderValue(
            label = stringResource(R.string.practice_timing),
            value = timingLabel(progress.matchState),
            accent = progress.matchState.advancesPlayableNote || progress.matchState == MatchState.RestComplete,
            modifier = Modifier.testTag("practice_match_feedback")
        )
        HeaderValue(
            label = stringResource(R.string.practice_duration),
            value = durationLabel(state),
            accent = state.durationFeedback == DurationFeedback.ApproximatelyCorrect,
            modifier = Modifier.testTag("practice_duration_feedback")
        )
        HeaderValue(
            label = stringResource(R.string.practice_progress_label),
            value = "${progress.completedSteps} / ${progress.totalSteps}",
            modifier = Modifier.testTag("practice_progress")
        )
    }
}

@Composable
private fun PracticeHeaderActions(
    state: PracticeUiState,
    settingsExpanded: Boolean,
    onSettingsExpanded: (Boolean) -> Unit,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onTempoChange: (Int) -> Unit,
    onCountInChange: (Boolean) -> Unit,
    onCalibrateRelease: () -> Unit
) {
    val progress = state.progress
    Text(
        text = "${progress.tempo.bpm} BPM",
        color = PracticeAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("practice_tempo")
    )
    PracticeTransport(progress.phase, onStart, onPause, onStop)
    IconButton(onClick = onImport, modifier = Modifier.testTag("practice_import")) {
        Icon(Icons.Default.UploadFile, stringResource(R.string.practice_import_musicxml), tint = PracticeChromeMuted)
    }
    Box {
        IconButton(
            onClick = { onSettingsExpanded(true) },
            modifier = Modifier.testTag("practice_settings")
        ) {
            Icon(Icons.Default.Settings, stringResource(R.string.practice_settings), tint = PracticeChromeMuted)
        }
        PracticeSettingsMenu(
            state = state,
            expanded = settingsExpanded,
            onDismiss = { onSettingsExpanded(false) },
            onTempoChange = onTempoChange,
            onCountInChange = onCountInChange,
            onCalibrateRelease = onCalibrateRelease
        )
    }
}

@Composable
private fun HeaderValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false
) {
    Column(modifier) {
        Text(label.uppercase(), color = PracticeChromeMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Text(
            value,
            color = if (accent) PracticeAccent else Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun PracticeTransport(
    phase: PracticePhase,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (phase) {
            PracticePhase.Ready -> FilledIconButton(
                onClick = onStart,
                modifier = Modifier.testTag("practice_start")
            ) { Icon(Icons.Default.PlayArrow, stringResource(R.string.practice_start)) }
            PracticePhase.Listening -> {
                FilledIconButton(onClick = onPause, modifier = Modifier.testTag("practice_pause")) {
                    Icon(Icons.Default.Pause, stringResource(R.string.practice_pause))
                }
                IconButton(onClick = onStop, modifier = Modifier.testTag("practice_stop")) {
                    Icon(Icons.Default.Stop, stringResource(R.string.practice_stop), tint = PracticeChromeMuted)
                }
            }
            PracticePhase.Paused -> {
                FilledIconButton(onClick = onStart, modifier = Modifier.testTag("practice_resume")) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.practice_resume))
                }
                IconButton(onClick = onStop, modifier = Modifier.testTag("practice_stop")) {
                    Icon(Icons.Default.Stop, stringResource(R.string.practice_stop), tint = PracticeChromeMuted)
                }
            }
            PracticePhase.CountIn -> IconButton(onClick = onStop, modifier = Modifier.testTag("practice_stop")) {
                Icon(Icons.Default.Stop, stringResource(R.string.practice_stop), tint = PracticeChromeMuted)
            }
            PracticePhase.Completed -> Button(onClick = onStop, modifier = Modifier.testTag("practice_completed")) {
                Text(stringResource(R.string.practice_reset))
            }
            else -> Unit
        }
    }
}

@Composable
private fun PracticeSettingsMenu(
    state: PracticeUiState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onTempoChange: (Int) -> Unit,
    onCountInChange: (Boolean) -> Unit,
    onCalibrateRelease: () -> Unit
) {
    val progress = state.progress
    val enabled = progress.phase in setOf(PracticePhase.Ready, PracticePhase.Paused, PracticePhase.Completed)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(320.dp).testTag("practice_settings_menu")
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.practice_settings), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.practice_tempo), style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (progress.tempo.source == PracticeTempoSource.Detected) {
                            stringResource(R.string.practice_tempo_detected)
                        } else stringResource(R.string.practice_tempo_user_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onTempoChange(progress.tempo.bpm - 1) },
                    enabled = enabled && progress.tempo.bpm > MIN_PRACTICE_BPM
                ) { Icon(Icons.Default.Remove, contentDescription = null) }
                Text("${progress.tempo.bpm}", modifier = Modifier.width(34.dp), fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { onTempoChange(progress.tempo.bpm + 1) },
                    enabled = enabled && progress.tempo.bpm < MAX_PRACTICE_BPM
                ) { Icon(Icons.Default.Add, contentDescription = null) }
            }
            Slider(
                value = progress.tempo.bpm.toFloat(),
                onValueChange = { onTempoChange(it.roundToInt()) },
                valueRange = MIN_PRACTICE_BPM.toFloat()..MAX_PRACTICE_BPM.toFloat(),
                steps = MAX_PRACTICE_BPM - MIN_PRACTICE_BPM - 1,
                enabled = enabled,
                modifier = Modifier.testTag("practice_tempo_slider")
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.practice_count_in), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.practice_count_in_beats, progress.sequence?.source?.initialMeter?.beats ?: 4),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = progress.countInEnabled,
                    onCheckedChange = onCountInChange,
                    enabled = enabled,
                    modifier = Modifier.testTag("practice_count_in_toggle")
                )
            }
            progress.currentStep?.unresolvedTimingReason?.let { warning ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.practice_release_calibration), style = MaterialTheme.typography.labelLarge)
            Text(
                when (state.releaseCalibrationStatus) {
                    ReleaseCalibrationStatus.NOT_CALIBRATED -> stringResource(R.string.practice_release_not_calibrated)
                    ReleaseCalibrationStatus.CALIBRATED -> stringResource(R.string.practice_release_calibrated)
                    ReleaseCalibrationStatus.NEEDS_IMPROVEMENT -> stringResource(R.string.practice_release_needs_improvement)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.practice_release_position_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state.releaseCalibrationStage) {
                ReleaseCalibrationStage.QUIET -> Text(stringResource(R.string.practice_release_quiet_instruction))
                ReleaseCalibrationStage.NOTES -> Text(
                    stringResource(
                        R.string.practice_release_notes_instruction,
                        state.releaseCalibrationAcceptedSamples,
                        state.releaseCalibrationTargetSamples
                    )
                )
                ReleaseCalibrationStage.COMPLETE -> Column {
                    Text(stringResource(R.string.practice_release_complete))
                    TextButton(onClick = onCalibrateRelease, enabled = enabled) {
                        Text(stringResource(R.string.practice_release_calibrate_action))
                    }
                }
                null -> TextButton(onClick = onCalibrateRelease, enabled = enabled) {
                    Text(stringResource(R.string.practice_release_calibrate_action))
                }
            }
        }
    }
}

private fun timingLabel(state: MatchState): String = when (state) {
    MatchState.CorrectEarly -> "Early"
    MatchState.CorrectOnTime -> "Good"
    MatchState.CorrectLate -> "Late"
    MatchState.CorrectPitchOnly -> "Pitch only"
    MatchState.WrongPitch -> "Wrong pitch"
    MatchState.LowConfidence -> "Listening"
    MatchState.Missed -> "Late"
    MatchState.Unsupported -> "Unsupported"
    MatchState.RestViolation -> "Rest — note heard"
    MatchState.RestComplete -> "Rest complete"
    MatchState.TieContinuation -> "Tie sustain"
    MatchState.Waiting -> "Ready"
}

private fun durationLabel(state: PracticeUiState): String = when {
    state.articulationActive -> "Listening"
    state.durationFeedback == DurationFeedback.TooShort -> "Short"
    state.durationFeedback == DurationFeedback.ApproximatelyCorrect -> "Appropriate"
    state.durationFeedback == DurationFeedback.Long -> "Long"
    state.durationFeedback == DurationFeedback.SustainAmbiguous -> "Sustain ambiguous"
    state.durationFeedback == DurationFeedback.StaccatoConsistent -> "Staccato consistent"
    state.durationFeedback == DurationFeedback.ArticulationInconsistent -> "Articulation unclear"
    state.durationFeedback == DurationFeedback.TenutoSustained -> "Tenuto sustained"
    state.durationFeedback == DurationFeedback.PossiblyShort -> "Possibly short"
    state.durationFeedback == DurationFeedback.FermataFlexible -> "Fermata flexible"
    state.durationFeedback == DurationFeedback.Unknown -> "Unknown"
    else -> "—"
}

private fun articulationLabel(articulation: ExpectedArticulation?): String = when (articulation) {
    ExpectedArticulation.Staccato -> "Staccato"
    ExpectedArticulation.Tenuto -> "Tenuto"
    ExpectedArticulation.Accent -> "Accent"
    ExpectedArticulation.StrongAccent -> "Strong accent"
    ExpectedArticulation.Staccatissimo -> "Staccatissimo"
    ExpectedArticulation.Fermata -> "Fermata"
    ExpectedArticulation.Unknown -> "Unknown"
    ExpectedArticulation.Normal, null -> "Normal"
}

@Composable
private fun PracticeMessage(message: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
        }
    }
}
