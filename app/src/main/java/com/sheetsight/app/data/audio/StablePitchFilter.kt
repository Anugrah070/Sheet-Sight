package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.domain.practice.StablePitchEvent

/** Separates stable pitch identity from genuine note-onset evidence. */
class StablePitchFilter(
    private val config: PitchDetectionConfig = PitchDetectionConfig()
) {
    private var candidateMidi: Int? = null
    private var candidateCount = 0
    private var lastCandidateTimestamp = 0L
    private var stableMidi: Int? = null
    private var silenceFrames = 0
    private var releasedSinceStable = false
    private var previousSignalLevel = 0.0
    private var lastOnsetTimestamp = 0L
    private var lastLowConfidenceTimestamp: Long? = null
    private var noiseFloorRms = config.analysisMinimumSignalRms * 0.5

    var latestDiagnostics: StablePitchFilterDiagnostics? = null
        private set

    fun process(frame: PitchFrame): StablePitchEvent? {
        if (frame.signalLevel < config.releaseSignalRms) {
            previousSignalLevel = frame.signalLevel
            candidateMidi = null
            candidateCount = 0
            latestDiagnostics = diagnostics(frame, StablePitchFrameStatus.SILENCE, requiredSignalLevel = null)
            silenceFrames++
            if (silenceFrames >= config.releaseFrameCount && stableMidi != null) {
                stableMidi = null
                releasedSinceStable = true
                return StablePitchEvent.Release
            }
            return null
        }

        val priorSignalLevel = previousSignalLevel
        previousSignalLevel = frame.signalLevel
        silenceFrames = 0
        val pitch = frame.detectedPitch
        if (stableMidi == null && pitch == null) {
            val smoothing = if (frame.signalLevel <= noiseFloorRms) {
                config.onsetNoiseSmoothing
            } else {
                // Ambient level may rise, but a missed musical attack must not immediately
                // teach the onset gate to reject itself.
                config.onsetNoiseSmoothing * 0.25
            }
            noiseFloorRms += (frame.signalLevel - noiseFloorRms) * smoothing
        }
        val requiredSignalLevel = pitch?.let { onsetSignalThreshold(it.nearestPitch.midiNumber) }
        val rejection = when {
            pitch == null -> StablePitchFrameStatus.NO_PITCH
            pitch.confidence < config.minimumConfidence -> StablePitchFrameStatus.LOW_CONFIDENCE
            frame.signalLevel < requireNotNull(requiredSignalLevel) -> StablePitchFrameStatus.BELOW_SIGNAL_GATE
            else -> null
        }
        if (rejection != null) {
            expireCandidateIfNeeded(frame.timestampMillis)
            latestDiagnostics = diagnostics(frame, rejection, requiredSignalLevel)
            return if (lastLowConfidenceTimestamp == null ||
                frame.timestampMillis - requireNotNull(lastLowConfidenceTimestamp) >= config.lowConfidenceUiIntervalMillis
            ) {
                lastLowConfidenceTimestamp = frame.timestampMillis
                StablePitchEvent.LowConfidence(pitch)
            } else null
        }

        val acceptedPitch = requireNotNull(pitch)
        val midi = acceptedPitch.nearestPitch.midiNumber
        val isAmplitudeRetrigger = stableMidi == midi &&
            frame.timestampMillis - lastOnsetTimestamp >= config.minimumRetriggerIntervalMillis &&
            frame.signalLevel - priorSignalLevel >= config.minimumAmplitudeRise &&
            frame.signalLevel >= priorSignalLevel.coerceAtLeast(config.releaseSignalRms) * config.amplitudeRiseRatio
        if (isAmplitudeRetrigger) {
            lastOnsetTimestamp = frame.timestampMillis
            latestDiagnostics = diagnostics(
                frame,
                StablePitchFrameStatus.ONSET_ACCEPTED,
                requiredSignalLevel,
                accepted = true
            )
            return StablePitchEvent.Stable(acceptedPitch, NoteOnsetEvidence.AmplitudeRise)
        }

        if (stableMidi == midi) {
            // During legato overlap YIN may alternate between the old and new fundamentals.
            // Do not let a frame of the already-stable note erase a valid transition candidate.
            expireCandidateIfNeeded(frame.timestampMillis)
            latestDiagnostics = diagnostics(frame, StablePitchFrameStatus.STABLE_CONTINUATION, requiredSignalLevel)
            return null
        }

        val continuesCandidate = candidateMidi == midi &&
            frame.timestampMillis - lastCandidateTimestamp <= config.maximumStableGapMillis
        candidateCount = if (continuesCandidate) candidateCount + 1 else 1
        candidateMidi = midi
        lastCandidateTimestamp = frame.timestampMillis

        if (candidateCount < config.stableFrameCount) {
            latestDiagnostics = diagnostics(frame, StablePitchFrameStatus.CANDIDATE, requiredSignalLevel)
            return null
        }
        val evidence = when {
            releasedSinceStable -> NoteOnsetEvidence.AfterRelease
            stableMidi == null -> NoteOnsetEvidence.InitialAttack
            else -> NoteOnsetEvidence.PitchTransition
        }
        stableMidi = midi
        releasedSinceStable = false
        lastOnsetTimestamp = frame.timestampMillis
        candidateMidi = null
        candidateCount = 0
        latestDiagnostics = diagnostics(
            frame,
            StablePitchFrameStatus.ONSET_ACCEPTED,
            requiredSignalLevel,
            accepted = true
        )
        return StablePitchEvent.Stable(acceptedPitch, evidence)
    }

    fun reset() {
        candidateMidi = null
        candidateCount = 0
        lastCandidateTimestamp = 0L
        stableMidi = null
        silenceFrames = 0
        releasedSinceStable = false
        previousSignalLevel = 0.0
        lastOnsetTimestamp = 0L
        lastLowConfidenceTimestamp = null
        noiseFloorRms = config.analysisMinimumSignalRms * 0.5
        latestDiagnostics = null
    }

    private fun onsetSignalThreshold(midi: Int): Double {
        val registerRatio = if (midi < 48 || midi >= 72) config.edgeRegisterSignalRatio else 1.0
        return maxOf(config.minimumSignalRms * registerRatio, noiseFloorRms * config.onsetNoiseMultiplier)
    }

    private fun expireCandidateIfNeeded(timestampMillis: Long) {
        if (candidateMidi != null && timestampMillis - lastCandidateTimestamp > config.maximumStableGapMillis) {
            candidateMidi = null
            candidateCount = 0
        }
    }

    private fun diagnostics(
        frame: PitchFrame,
        status: StablePitchFrameStatus,
        requiredSignalLevel: Double?,
        accepted: Boolean = false
    ) = StablePitchFilterDiagnostics(
        rawPitch = frame.detectedPitch,
        signalLevel = frame.signalLevel,
        requiredSignalLevel = requiredSignalLevel,
        noiseFloorRms = noiseFloorRms,
        candidateMidi = candidateMidi,
        candidateFrameCount = candidateCount,
        requiredCandidateFrameCount = config.stableFrameCount,
        status = status,
        onsetAccepted = accepted
    )
}

enum class StablePitchFrameStatus {
    SILENCE,
    NO_PITCH,
    LOW_CONFIDENCE,
    BELOW_SIGNAL_GATE,
    CANDIDATE,
    STABLE_CONTINUATION,
    ONSET_ACCEPTED
}

data class StablePitchFilterDiagnostics(
    val rawPitch: com.sheetsight.app.domain.practice.DetectedPitch?,
    val signalLevel: Double,
    val requiredSignalLevel: Double?,
    val noiseFloorRms: Double,
    val candidateMidi: Int?,
    val candidateFrameCount: Int,
    val requiredCandidateFrameCount: Int,
    val status: StablePitchFrameStatus,
    val onsetAccepted: Boolean
)
