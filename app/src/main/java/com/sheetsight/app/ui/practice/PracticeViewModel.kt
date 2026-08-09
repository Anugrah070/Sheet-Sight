package com.sheetsight.app.ui.practice

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.audio.AcousticNoteEventTracker
import com.sheetsight.app.data.audio.AudioPitchSource
import com.sheetsight.app.data.audio.ReleaseCalibrationSession
import com.sheetsight.app.data.audio.ReleaseCalibrationStage
import com.sheetsight.app.data.audio.ReleaseCalibrationStatus
import com.sheetsight.app.data.audio.ReleaseCalibrationStore
import com.sheetsight.app.data.audio.StablePitchFilter
import com.sheetsight.app.data.audio.StablePitchEvent
import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.DurationResult
import com.sheetsight.app.domain.practice.MatchState
import com.sheetsight.app.domain.practice.PracticeEngine
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.domain.practice.PracticeProgress
import com.sheetsight.app.domain.usecase.LoadPracticeScoreUseCase
import com.sheetsight.app.domain.usecase.PracticeScoreLoadOutcome
import com.sheetsight.app.ui.editor.notation.NotationDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MicrophonePermissionState { Unknown, Granted, Denied }

data class PracticeUiState(
    val progress: PracticeProgress = PracticeProgress(),
    val microphonePermission: MicrophonePermissionState = MicrophonePermissionState.Unknown,
    val notation: NotationDocument? = null,
    val durationFeedback: DurationFeedback? = null,
    val articulationActive: Boolean = false,
    val recentDurationResults: List<DurationResult> = emptyList(),
    val releaseCalibrationStatus: ReleaseCalibrationStatus = ReleaseCalibrationStatus.NOT_CALIBRATED,
    val releaseCalibrationStage: ReleaseCalibrationStage? = null,
    val releaseCalibrationAcceptedSamples: Int = 0,
    val releaseCalibrationTargetSamples: Int = 0
)

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val loadPracticeScore: LoadPracticeScoreUseCase,
    private val audioPitchSource: AudioPitchSource,
    private val releaseCalibrationStore: ReleaseCalibrationStore
) : ViewModel() {
    private val logger = Logger.getLogger(PracticeViewModel::class.java.name)
    private val engine = PracticeEngine()
    private val stabilityFilter = StablePitchFilter()
    private val articulationTracker = AcousticNoteEventTracker()
    private val _uiState = MutableStateFlow(
        PracticeUiState(releaseCalibrationStatus = releaseCalibrationStore.status())
    )
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private var importJob: Job? = null
    private var countInJob: Job? = null
    private var audioJob: Job? = null
    private var clockJob: Job? = null
    private var calibrationJob: Job? = null

    init {
        articulationTracker.applyCalibrationProfile(releaseCalibrationStore.load())
    }

    fun importMusicXml(uri: Uri) {
        stopPractice()
        resetArticulation()
        importJob?.cancel()
        publish(engine.loading(), notation = null)
        importJob = viewModelScope.launch {
            when (val outcome = loadPracticeScore(uri)) {
                is PracticeScoreLoadOutcome.Success -> publish(engine.load(outcome.sequence), outcome.notation)
                is PracticeScoreLoadOutcome.Failure -> publish(engine.fail(outcome.message), notation = null)
            }
        }
    }

    fun startOrResume(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _uiState.value = _uiState.value.copy(microphonePermission = MicrophonePermissionState.Denied)
            return
        }
        _uiState.value = _uiState.value.copy(microphonePermission = MicrophonePermissionState.Granted)
        when (engine.progress.phase) {
            PracticePhase.Ready -> beginSession()
            PracticePhase.Paused -> {
                articulationTracker.resume(monotonicMillis())
                stabilityFilter.reset()
                publish(engine.resume())
                startRuntimeJobs()
            }
            else -> Unit
        }
    }

    /** Backwards-compatible entry point retained for existing activity/tests. */
    fun startListening(permissionGranted: Boolean) = startOrResume(permissionGranted)

    fun pausePractice() {
        if (engine.progress.phase != PracticePhase.Listening) return
        articulationTracker.pause(monotonicMillis())
        cancelRuntimeJobs()
        stabilityFilter.reset()
        publish(engine.pause())
    }

    fun stopPractice() {
        cancelCalibration()
        countInJob?.cancel()
        countInJob = null
        cancelRuntimeJobs()
        stabilityFilter.reset()
        resetArticulation()
        publish(engine.stop())
    }

    /** Backwards-compatible entry point retained for lifecycle callers. */
    fun stopListening() = stopPractice()

    fun setTempo(bpm: Int) = publish(engine.setTempo(bpm))

    fun setCountInEnabled(enabled: Boolean) = publish(engine.setCountInEnabled(enabled))

    fun microphonePermissionDenied() {
        stopPractice()
        _uiState.value = _uiState.value.copy(microphonePermission = MicrophonePermissionState.Denied)
    }

    fun startReleaseCalibration(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _uiState.value = _uiState.value.copy(microphonePermission = MicrophonePermissionState.Denied)
            return
        }
        stopPractice()
        val session = ReleaseCalibrationSession()
        _uiState.value = _uiState.value.copy(
            microphonePermission = MicrophonePermissionState.Granted,
            releaseCalibrationStage = ReleaseCalibrationStage.QUIET,
            releaseCalibrationAcceptedSamples = 0,
            releaseCalibrationTargetSamples = 0
        )
        calibrationJob = viewModelScope.launch {
            try {
                audioPitchSource.frames().collect { frame ->
                    val update = session.process(frame)
                    val current = _uiState.value
                    if (
                        current.releaseCalibrationStage != update.stage ||
                        current.releaseCalibrationAcceptedSamples != update.acceptedSamples ||
                        current.releaseCalibrationTargetSamples != update.targetSamples
                    ) {
                        _uiState.value = current.copy(
                            releaseCalibrationStage = update.stage,
                            releaseCalibrationAcceptedSamples = update.acceptedSamples,
                            releaseCalibrationTargetSamples = update.targetSamples
                        )
                    }
                    update.profile?.let { profile ->
                        releaseCalibrationStore.save(profile)
                        articulationTracker.applyCalibrationProfile(profile)
                        _uiState.value = _uiState.value.copy(
                            releaseCalibrationStatus = releaseCalibrationStore.status(),
                            releaseCalibrationStage = ReleaseCalibrationStage.COMPLETE
                        )
                        calibrationJob?.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Release calibration failed.", failure)
                _uiState.value = _uiState.value.copy(
                    releaseCalibrationStatus = ReleaseCalibrationStatus.NEEDS_IMPROVEMENT,
                    releaseCalibrationStage = null
                )
            } finally {
                calibrationJob = null
            }
        }
    }

    override fun onCleared() {
        importJob?.cancel()
        countInJob?.cancel()
        cancelRuntimeJobs()
        cancelCalibration()
        stabilityFilter.reset()
        super.onCleared()
    }

    private fun beginSession() {
        if (countInJob?.isActive == true || engine.progress.phase != PracticePhase.Ready) return
        stabilityFilter.reset()
        resetArticulation()
        if (!engine.progress.countInEnabled) {
            publish(engine.start())
            startRuntimeJobs()
            return
        }

        publish(engine.beginCountIn())
        countInJob = viewModelScope.launch {
            try {
                val pulseMillis = engine.countInPulseMillis()
                for (remaining in engine.countInPulseCount() downTo 1) {
                    publish(engine.updateCountIn(remaining))
                    delay(pulseMillis)
                }
                publish(engine.completeCountIn())
                startRuntimeJobs()
            } finally {
                countInJob = null
            }
        }
    }

    private fun startRuntimeJobs() {
        if (engine.progress.phase != PracticePhase.Listening) return
        startClockTicker()
        if (audioJob?.isActive == true) return
        audioJob = viewModelScope.launch {
            try {
                audioPitchSource.frames().collect { frame ->
                    publishArticulation(articulationTracker.process(frame))
                    if (engine.progress.phase == PracticePhase.Completed && articulationTracker.activeEventCount == 0) {
                        audioJob?.cancel()
                        audioJob = null
                        return@collect
                    }
                    stabilityFilter.process(frame)?.let { event ->
                        if (event is StablePitchEvent.Stable && event.isNewOnset) {
                            publishArticulation(
                                articulationTracker.onNewOnset(
                                    event.pitch.nearestPitch,
                                    event.pitch.timestampMillis
                                )
                            )
                        }
                        val before = engine.progress
                        val after = engine.onPitchEvent(event)
                        if (after != before) publish(after)
                        logTransition(before, after)
                        if (
                            event is StablePitchEvent.Stable &&
                            after.currentStepIndex > before.currentStepIndex &&
                            before.currentStep?.isRest == false
                        ) {
                            publishArticulation(
                                articulationTracker.acceptNote(
                                    step = requireNotNull(before.currentStep),
                                    pitch = event.pitch.nearestPitch,
                                    onsetRawMillis = event.pitch.timestampMillis,
                                    bpm = before.tempo.bpm
                                ),
                                trackingStarted = true
                            )
                        }
                        if (after.phase == PracticePhase.Completed && articulationTracker.activeEventCount == 0) {
                            audioJob?.cancel()
                            audioJob = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                logger.log(Level.WARNING, "Practice microphone permission was lost.", security)
                _uiState.value = _uiState.value.copy(microphonePermission = MicrophonePermissionState.Denied)
                cancelRuntimeJobs()
                publish(engine.pause())
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Practice microphone capture failed.", failure)
                cancelRuntimeJobs()
                publish(engine.fail(failure.message ?: "Microphone capture failed."))
            }
        }
    }

    private fun startClockTicker() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (engine.progress.phase == PracticePhase.Listening) {
                val before = engine.progress
                val after = engine.onClockTick()
                if (after != before) {
                    publish(after)
                    logTransition(before, after)
                }
                if (after.phase == PracticePhase.Completed) {
                    break
                }
                delay(CLOCK_TICK_MILLIS)
            }
        }
    }

    private fun cancelRuntimeJobs() {
        audioJob?.cancel()
        audioJob = null
        clockJob?.cancel()
        clockJob = null
    }

    private fun cancelCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        if (_uiState.value.releaseCalibrationStage != ReleaseCalibrationStage.COMPLETE) {
            _uiState.value = _uiState.value.copy(releaseCalibrationStage = null)
        }
    }

    private fun publish(progress: PracticeProgress, notation: NotationDocument? = _uiState.value.notation) {
        _uiState.value = _uiState.value.copy(progress = progress, notation = notation)
    }

    private fun publishArticulation(
        update: com.sheetsight.app.data.audio.ArticulationTrackerUpdate,
        trackingStarted: Boolean = false
    ) {
        if (update.completed.isEmpty() && !trackingStarted) return
        update.completed.forEach(::logArticulation)
        _uiState.value = _uiState.value.copy(
            durationFeedback = update.completed.lastOrNull()?.feedback ?: _uiState.value.durationFeedback,
            articulationActive = update.activeEventCount > 0,
            recentDurationResults = articulationTracker.recentResults
        )
    }

    private fun resetArticulation() {
        articulationTracker.reset()
        _uiState.value = _uiState.value.copy(
            durationFeedback = null,
            articulationActive = false,
            recentDurationResults = emptyList()
        )
    }

    private fun logArticulation(result: DurationResult) {
        val observed = result.observedEvent
        logger.info(
            "ARTICULATION step=${result.stepIndex} pitch=${result.pitch.displayName} " +
                "expectedMs=${result.expectedDuration?.milliseconds ?: "unresolved"} " +
                "onset=${observed.onsetTimeMillis} release=${observed.releaseTimeMillis ?: "unresolved"} " +
                "observedMs=${observed.observedDurationMillis ?: "unresolved"} " +
                "releaseConfidence=${String.format(Locale.US, "%.2f", observed.releaseConfidence)} " +
                "residualEnergy=${observed.residualEnergy} targetPitchEvidence=${observed.targetPitchEvidence} " +
                "newOnsetsPresent=${observed.newOnsetsPresent} feedback=${result.feedback}"
        )
    }

    private fun logTransition(before: PracticeProgress, after: PracticeProgress) {
        if (before == after) return
        val detected = after.lastDetectedPitch
        val expected = before.currentStep?.displayText ?: "none"
        val advanced = after.currentStepIndex != before.currentStepIndex
        logger.info(
            "Practice: step=${before.currentStepIndex + 1}/${before.totalSteps} " +
                "expected=$expected detected=${detected?.nearestPitch?.displayName ?: "none"} " +
                "cents=${detected?.let { String.format(Locale.US, "%+.0f", it.centsOffset) } ?: "n/a"} " +
                "confidence=${detected?.let { String.format(Locale.US, "%.2f", it.confidence) } ?: "n/a"} " +
                "match=${after.matchState} timingMs=${after.timingOffsetMillis ?: "n/a"} " +
                if (advanced) "advancedTo=${after.currentStepIndex + 1}" else "advanced=false"
        )
    }

    private companion object {
        const val CLOCK_TICK_MILLIS = 40L
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
