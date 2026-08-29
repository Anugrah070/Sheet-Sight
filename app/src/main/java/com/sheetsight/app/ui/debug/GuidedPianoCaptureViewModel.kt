package com.sheetsight.app.ui.debug

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.audio.DeveloperCaptureAudioSource
import com.sheetsight.app.data.audio.DeveloperCapturePlanType
import com.sheetsight.app.data.audio.DeveloperCapturedPianoTake
import com.sheetsight.app.data.audio.DeveloperPianoCaptureBundleExporter
import com.sheetsight.app.data.audio.DeveloperPianoCapturePlans
import com.sheetsight.app.data.audio.DeveloperPianoCapturePrompt
import com.sheetsight.app.data.audio.DeveloperPianoCaptureRecorder
import com.sheetsight.app.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GuidedPianoCaptureStage { IDLE, READY, COUNTDOWN, RECORDING, COMPLETE, EXPORTING }

data class GuidedPianoCaptureUiState(
    val planType: DeveloperCapturePlanType = DeveloperCapturePlanType.PILOT,
    val audioSource: DeveloperCaptureAudioSource = DeveloperCaptureAudioSource.UNPROCESSED_PREFERRED,
    val pianoDescription: String = "",
    val roomCondition: String = "Quiet room",
    val stage: GuidedPianoCaptureStage = GuidedPianoCaptureStage.IDLE,
    val isListening: Boolean = false,
    val microphonePermissionDenied: Boolean = false,
    val currentIndex: Int = 0,
    val totalPrompts: Int = DeveloperPianoCapturePlans.pilot.size,
    val currentPrompt: DeveloperPianoCapturePrompt? = null,
    val capturedTakeCount: Int = 0,
    val liveRms: Double = 0.0,
    val liveLevel: Float = 0f,
    val detectedPitchName: String? = null,
    val detectedPitchConfidence: Double? = null,
    val countdownMillis: Long = 0L,
    val recordingProgress: Float = 0f,
    val sourceSummary: String? = null,
    val exportMessage: String? = null,
    val error: String? = null
)

@HiltViewModel
class GuidedPianoCaptureViewModel @Inject constructor(
    private val recorder: DeveloperPianoCaptureRecorder,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _uiState = MutableStateFlow(GuidedPianoCaptureUiState())
    val uiState: StateFlow<GuidedPianoCaptureUiState> = _uiState.asStateFlow()
    private var audioJob: Job? = null
    private var prompts: List<DeveloperPianoCapturePrompt> = emptyList()
    private val takes = mutableListOf<DeveloperCapturedPianoTake>()
    private var countdownEndsAt = 0L
    private val currentChunks = mutableListOf<ShortArray>()
    private var currentSampleCount = 0
    private var lastUiPublishAt = 0L

    fun selectPlan(type: DeveloperCapturePlanType) {
        if (_uiState.value.isListening) return
        _uiState.update {
            it.copy(planType = type, totalPrompts = DeveloperPianoCapturePlans.forType(type).size, error = null)
        }
    }

    fun selectAudioSource(source: DeveloperCaptureAudioSource) {
        if (!_uiState.value.isListening) _uiState.update { it.copy(audioSource = source, error = null) }
    }

    fun updatePianoDescription(value: String) {
        if (!_uiState.value.isListening) _uiState.update { it.copy(pianoDescription = value.take(120), error = null) }
    }

    fun updateRoomCondition(value: String) {
        if (!_uiState.value.isListening) _uiState.update { it.copy(roomCondition = value.take(120), error = null) }
    }

    fun start(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _uiState.update { it.copy(microphonePermissionDenied = true) }
            return
        }
        if (audioJob?.isActive == true) return
        val selected = _uiState.value
        if (selected.pianoDescription.isBlank() || selected.roomCondition.isBlank()) {
            _uiState.update { it.copy(error = "Enter the piano and room description before starting.") }
            return
        }
        prompts = DeveloperPianoCapturePlans.forType(selected.planType)
        takes.clear()
        currentChunks.clear()
        currentSampleCount = 0
        _uiState.update {
            it.copy(
                stage = GuidedPianoCaptureStage.READY,
                isListening = true,
                microphonePermissionDenied = false,
                currentIndex = 0,
                totalPrompts = prompts.size,
                currentPrompt = prompts.first(),
                capturedTakeCount = 0,
                liveRms = 0.0,
                liveLevel = 0f,
                detectedPitchName = null,
                detectedPitchConfidence = null,
                recordingProgress = 0f,
                sourceSummary = null,
                exportMessage = null,
                error = null
            )
        }
        audioJob = viewModelScope.launch {
            try {
                recorder.frames(selected.audioSource).collect { frame ->
                    var stage = _uiState.value.stage
                    if (stage == GuidedPianoCaptureStage.COUNTDOWN && frame.timestampMillis >= countdownEndsAt) {
                        currentChunks.clear()
                        currentSampleCount = 0
                        stage = GuidedPianoCaptureStage.RECORDING
                    }
                    if (stage == GuidedPianoCaptureStage.RECORDING) {
                        currentChunks += frame.pcm
                        currentSampleCount += frame.pcm.size
                        val prompt = requireNotNull(_uiState.value.currentPrompt)
                        val targetSamples =
                            (prompt.durationMillis * frame.provenance.actualSampleRateHz / 1_000L).toInt()
                        if (currentSampleCount >= targetSamples) {
                            takes += DeveloperCapturedPianoTake(
                                prompt = prompt,
                                sampleRateHz = frame.provenance.actualSampleRateHz,
                                samples = joinChunks(currentChunks, targetSamples),
                                provenance = frame.provenance
                            )
                            advanceAfterCapture()
                            stage = _uiState.value.stage
                        }
                    }
                    val now = frame.timestampMillis
                    if (now - lastUiPublishAt >= UI_UPDATE_MILLIS || stage != _uiState.value.stage) {
                        val current = _uiState.value
                        val prompt = current.currentPrompt
                        val targetSamples = prompt?.let {
                            (it.durationMillis * frame.provenance.actualSampleRateHz / 1_000L).toInt()
                        } ?: 1
                        _uiState.update {
                            it.copy(
                                stage = stage,
                                liveRms = frame.rms,
                                liveLevel = (frame.rms / LEVEL_BAR_FULL_SCALE_RMS).coerceIn(0.0, 1.0).toFloat(),
                                detectedPitchName = frame.detectedPitch?.nearestPitch?.displayName,
                                detectedPitchConfidence = frame.detectedPitch?.confidence,
                                countdownMillis = if (stage == GuidedPianoCaptureStage.COUNTDOWN) {
                                    (countdownEndsAt - now).coerceAtLeast(0L)
                                } else 0L,
                                recordingProgress = if (stage == GuidedPianoCaptureStage.RECORDING) {
                                    currentSampleCount.toFloat().div(targetSamples).coerceIn(0f, 1f)
                                } else 0f,
                                sourceSummary = "${frame.provenance.actualAudioSource}, " +
                                    "${frame.provenance.actualSampleRateHz} Hz, AGC ${frame.provenance.agcStatus}, " +
                                    "NS ${frame.provenance.noiseSuppressorStatus}"
                            )
                        }
                        lastUiPublishAt = now
                    }
                }
            } catch (_: CancellationException) {
                // Expected when leaving or stopping the explicit capture screen.
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        stage = GuidedPianoCaptureStage.IDLE,
                        isListening = false,
                        error = error.message ?: "Microphone capture failed."
                    )
                }
            }
        }
    }

    fun recordCurrentTake() {
        if (_uiState.value.stage != GuidedPianoCaptureStage.READY) return
        countdownEndsAt = System.nanoTime() / 1_000_000L + COUNTDOWN_MILLIS
        _uiState.update {
            it.copy(
                stage = GuidedPianoCaptureStage.COUNTDOWN,
                countdownMillis = COUNTDOWN_MILLIS,
                exportMessage = null,
                error = null
            )
        }
    }

    fun skipCurrent() {
        if (_uiState.value.stage != GuidedPianoCaptureStage.READY) return
        val next = _uiState.value.currentIndex + 1
        if (next >= prompts.size) {
            audioJob?.cancel()
            audioJob = null
            _uiState.update {
                it.copy(stage = GuidedPianoCaptureStage.COMPLETE, isListening = false, currentPrompt = null)
            }
        } else {
            _uiState.update { it.copy(currentIndex = next, currentPrompt = prompts[next]) }
        }
    }

    fun discardLastAndGoBack() {
        if (_uiState.value.stage !in setOf(GuidedPianoCaptureStage.READY, GuidedPianoCaptureStage.COMPLETE)) return
        val removed = takes.removeLastOrNull() ?: return
        val index = prompts.indexOfFirst { it.id == removed.prompt.id }.coerceAtLeast(0)
        _uiState.update {
            it.copy(
                stage = GuidedPianoCaptureStage.READY,
                currentIndex = index,
                currentPrompt = prompts[index],
                capturedTakeCount = takes.size,
                exportMessage = null
            )
        }
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
        currentChunks.clear()
        currentSampleCount = 0
        _uiState.update {
            it.copy(
                stage = if (takes.isEmpty()) GuidedPianoCaptureStage.IDLE else GuidedPianoCaptureStage.COMPLETE,
                isListening = false,
                countdownMillis = 0,
                recordingProgress = 0f
            )
        }
    }

    fun export(uri: Uri?) {
        if (uri == null || takes.isEmpty()) return
        val selected = _uiState.value
        audioJob?.cancel()
        audioJob = null
        _uiState.update {
            it.copy(
                stage = GuidedPianoCaptureStage.EXPORTING,
                isListening = false,
                exportMessage = null,
                error = null
            )
        }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                        "Unable to open the selected export file."
                    }
                    output.use {
                        DeveloperPianoCaptureBundleExporter.export(
                            output = it,
                            piano = selected.pianoDescription,
                            roomCondition = selected.roomCondition,
                            takes = takes.toList()
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        stage = GuidedPianoCaptureStage.COMPLETE,
                        exportMessage = "Exported ${takes.size} WAV take(s) and an unverified manifest."
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        stage = GuidedPianoCaptureStage.COMPLETE,
                        error = error.message ?: "Capture ZIP export failed."
                    )
                }
            }
        }
    }

    fun suggestedFileName(): String = "sheetsight-piano-captures-${System.currentTimeMillis()}.zip"

    private fun advanceAfterCapture() {
        val nextIndex = _uiState.value.currentIndex + 1
        currentChunks.clear()
        currentSampleCount = 0
        if (nextIndex >= prompts.size) {
            audioJob?.cancel()
            audioJob = null
            _uiState.update {
                it.copy(
                    stage = GuidedPianoCaptureStage.COMPLETE,
                    isListening = false,
                    currentIndex = prompts.size,
                    currentPrompt = null,
                    capturedTakeCount = takes.size,
                    recordingProgress = 0f
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    stage = GuidedPianoCaptureStage.READY,
                    currentIndex = nextIndex,
                    currentPrompt = prompts[nextIndex],
                    capturedTakeCount = takes.size,
                    recordingProgress = 0f
                )
            }
        }
    }

    private fun joinChunks(chunks: List<ShortArray>, sampleCount: Int): ShortArray {
        val output = ShortArray(sampleCount)
        var destination = 0
        chunks.forEach { chunk ->
            if (destination >= sampleCount) return@forEach
            val count = minOf(chunk.size, sampleCount - destination)
            chunk.copyInto(output, destinationOffset = destination, endIndex = count)
            destination += count
        }
        check(destination == sampleCount)
        return output
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private const val COUNTDOWN_MILLIS = 2_000L
        private const val UI_UPDATE_MILLIS = 80L
        private const val LEVEL_BAR_FULL_SCALE_RMS = 0.05
    }
}

fun GuidedPianoCaptureUiState.progressText(): String = when (stage) {
    GuidedPianoCaptureStage.COUNTDOWN -> "Recording starts in ${((countdownMillis + 999) / 1_000)}…"
    GuidedPianoCaptureStage.RECORDING -> "Recording ${(recordingProgress * 100).roundToInt()}%"
    GuidedPianoCaptureStage.COMPLETE -> "Capture session complete"
    GuidedPianoCaptureStage.EXPORTING -> "Creating ZIP…"
    GuidedPianoCaptureStage.READY -> "Ready for take ${currentIndex + 1} of $totalPrompts"
    GuidedPianoCaptureStage.IDLE -> "Enter setup details and start"
}

fun GuidedPianoCaptureUiState.pitchText(): String = detectedPitchName?.let { name ->
    val confidence = detectedPitchConfidence?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
    "$name · confidence $confidence"
} ?: "Listening — no stable pitch"
