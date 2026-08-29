package com.sheetsight.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.audio.AcousticValidationPlacement
import com.sheetsight.app.data.audio.AcousticValidationSession
import com.sheetsight.app.data.audio.AcousticValidationTestCase
import com.sheetsight.app.data.audio.AcousticValidationTestMatrix
import com.sheetsight.app.data.audio.AcousticValidationUpdate
import com.sheetsight.app.data.audio.AudioPitchSource
import com.sheetsight.app.data.audio.ExpectedPhysicalAction
import com.sheetsight.app.data.audio.ReleaseCalibrationProfile
import com.sheetsight.app.data.audio.ReleaseCalibrationQuality
import com.sheetsight.app.data.audio.ReleaseCalibrationStore
import com.sheetsight.app.data.audio.compactLine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ValidationCalibrationMode(val displayName: String) {
    STORED("Stored profile"),
    NO_PROFILE("No profile"),
    GOOD("Stored features / GOOD policy"),
    MODERATE("Stored features / MODERATE policy"),
    POOR("Stored features / POOR policy")
}

data class AcousticValidationUiState(
    val selectedTestCase: AcousticValidationTestCase = AcousticValidationTestCase.NORMAL_QUARTER,
    val expectedAction: ExpectedPhysicalAction = ExpectedPhysicalAction.NORMAL_RELEASE,
    val placement: AcousticValidationPlacement = AcousticValidationPlacement.NORMAL,
    val calibrationMode: ValidationCalibrationMode = ValidationCalibrationMode.STORED,
    val storedCalibrationQuality: ReleaseCalibrationQuality? = null,
    val isRunning: Boolean = false,
    val microphonePermissionDenied: Boolean = false,
    val update: AcousticValidationUpdate? = null,
    val error: String? = null
) {
    val selectedPlan get() = AcousticValidationTestMatrix.planFor(selectedTestCase)
}

/** Developer controller for the bounded, non-persistent physical-piano validation harness. */
@HiltViewModel
class AcousticValidationViewModel @Inject constructor(
    private val audioPitchSource: AudioPitchSource,
    private val calibrationStore: ReleaseCalibrationStore
) : ViewModel() {
    private val logger = Logger.getLogger(AcousticValidationViewModel::class.java.name)
    private val _uiState = MutableStateFlow(
        AcousticValidationUiState(storedCalibrationQuality = calibrationStore.load()?.quality)
    )
    val uiState: StateFlow<AcousticValidationUiState> = _uiState.asStateFlow()
    private var captureJob: Job? = null

    fun selectTestCase(testCase: AcousticValidationTestCase) {
        if (_uiState.value.isRunning) return
        val plan = AcousticValidationTestMatrix.planFor(testCase)
        _uiState.update {
            it.copy(selectedTestCase = testCase, expectedAction = plan.defaultAction, update = null, error = null)
        }
    }

    fun selectExpectedAction(action: ExpectedPhysicalAction) {
        if (!_uiState.value.isRunning) _uiState.update { it.copy(expectedAction = action, update = null) }
    }

    fun selectPlacement(placement: AcousticValidationPlacement) {
        if (!_uiState.value.isRunning) _uiState.update { it.copy(placement = placement, update = null) }
    }

    fun selectCalibrationMode(mode: ValidationCalibrationMode) {
        if (!_uiState.value.isRunning) _uiState.update { it.copy(calibrationMode = mode, update = null, error = null) }
    }

    fun start(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _uiState.update { it.copy(microphonePermissionDenied = true) }
            return
        }
        if (captureJob?.isActive == true) return
        val selected = _uiState.value
        val profile = profileFor(selected.calibrationMode)
        if (selected.calibrationMode in QUALITY_OVERRIDE_MODES && profile == null) {
            _uiState.update {
                it.copy(error = "Run release calibration first; quality overrides reuse its measured features.")
            }
            return
        }
        val session = AcousticValidationSession(
            plan = selected.selectedPlan,
            expectedAction = selected.expectedAction,
            placement = selected.placement,
            calibrationProfile = profile
        )
        _uiState.update {
            it.copy(
                isRunning = true,
                microphonePermissionDenied = false,
                update = session.snapshot(),
                error = null
            )
        }
        captureJob = viewModelScope.launch {
            var lastUiPublishAt: Long? = null
            try {
                audioPitchSource.frames().collect { frame ->
                    val update = session.process(frame)
                    update.newResults.forEach { result ->
                        logger.info(
                            "ACOUSTIC_VALIDATION ${result.compactLine()} " +
                                "action=${result.expectedAction} placement=${result.placement} " +
                                "attackConfidence=${result.attackConfidence} initialEnergy=${result.initialEnergy} " +
                                "dropoutsMs=${result.dropoutDurationsMillis} residualMs=${result.residualEnergyDurationMillis} " +
                                "feedback=${result.feedback} notes=${result.notes}"
                        )
                    }
                    if (
                        update.newResults.isNotEmpty() ||
                        lastUiPublishAt == null ||
                        frame.timestampMillis - requireNotNull(lastUiPublishAt) >= UI_PUBLISH_INTERVAL_MS
                    ) {
                        _uiState.update { it.copy(update = update) }
                        lastUiPublishAt = frame.timestampMillis
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Acoustic validation capture failed.", failure)
                _uiState.update { it.copy(error = failure.message ?: "Microphone validation failed.") }
            } finally {
                _uiState.update { it.copy(isRunning = false, update = session.snapshot()) }
                captureJob = null
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        _uiState.update { it.copy(isRunning = false) }
    }

    override fun onCleared() {
        captureJob?.cancel()
        super.onCleared()
    }

    private fun profileFor(mode: ValidationCalibrationMode): ReleaseCalibrationProfile? {
        val stored = calibrationStore.load()
        return when (mode) {
            ValidationCalibrationMode.STORED -> stored
            ValidationCalibrationMode.NO_PROFILE -> null
            ValidationCalibrationMode.GOOD -> stored?.copy(quality = ReleaseCalibrationQuality.GOOD)
            ValidationCalibrationMode.MODERATE -> stored?.copy(quality = ReleaseCalibrationQuality.MODERATE)
            ValidationCalibrationMode.POOR -> stored?.copy(quality = ReleaseCalibrationQuality.POOR)
        }
    }

    private companion object {
        const val UI_PUBLISH_INTERVAL_MS = 200L
        val QUALITY_OVERRIDE_MODES = setOf(
            ValidationCalibrationMode.GOOD,
            ValidationCalibrationMode.MODERATE,
            ValidationCalibrationMode.POOR
        )
    }
}
