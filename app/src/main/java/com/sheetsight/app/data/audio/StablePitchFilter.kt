package com.sheetsight.app.data.audio

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

    fun process(frame: PitchFrame): StablePitchEvent? {
        if (frame.signalLevel < config.releaseSignalRms) {
            previousSignalLevel = frame.signalLevel
            candidateMidi = null
            candidateCount = 0
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
        if (pitch == null || pitch.confidence < config.minimumConfidence || frame.signalLevel < config.minimumSignalRms) {
            candidateMidi = null
            candidateCount = 0
            return if (lastLowConfidenceTimestamp == null ||
                frame.timestampMillis - requireNotNull(lastLowConfidenceTimestamp) >= config.lowConfidenceUiIntervalMillis
            ) {
                lastLowConfidenceTimestamp = frame.timestampMillis
                StablePitchEvent.LowConfidence(pitch)
            } else null
        }

        val midi = pitch.nearestPitch.midiNumber
        val isAmplitudeRetrigger = stableMidi == midi &&
            frame.timestampMillis - lastOnsetTimestamp >= config.minimumRetriggerIntervalMillis &&
            frame.signalLevel - priorSignalLevel >= config.minimumAmplitudeRise &&
            frame.signalLevel >= priorSignalLevel.coerceAtLeast(config.releaseSignalRms) * config.amplitudeRiseRatio
        if (isAmplitudeRetrigger) {
            lastOnsetTimestamp = frame.timestampMillis
            candidateMidi = midi
            candidateCount = config.stableFrameCount
            return StablePitchEvent.Stable(pitch, NoteOnsetEvidence.AmplitudeRise)
        }

        val continuesCandidate = candidateMidi == midi &&
            frame.timestampMillis - lastCandidateTimestamp <= config.maximumStableGapMillis
        candidateCount = if (continuesCandidate) candidateCount + 1 else 1
        candidateMidi = midi
        lastCandidateTimestamp = frame.timestampMillis

        if (candidateCount < config.stableFrameCount || stableMidi == midi) return null
        val evidence = when {
            releasedSinceStable -> NoteOnsetEvidence.AfterRelease
            stableMidi == null -> NoteOnsetEvidence.InitialAttack
            else -> NoteOnsetEvidence.PitchTransition
        }
        stableMidi = midi
        releasedSinceStable = false
        lastOnsetTimestamp = frame.timestampMillis
        return StablePitchEvent.Stable(pitch, evidence)
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
    }
}
