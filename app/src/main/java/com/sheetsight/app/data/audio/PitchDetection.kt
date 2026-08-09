package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch

/** Centralized thresholds for capture, YIN, stability, and matching. */
data class PitchDetectionConfig(
    val sampleRateHz: Int = 22_050,
    val frameSize: Int = 4_096,
    val hopSize: Int = 1_024,
    val minimumFrequencyHz: Double = 27.5,
    val maximumFrequencyHz: Double = 4_186.01,
    val minimumSignalRms: Double = 0.0045,
    val releaseSignalRms: Double = 0.003,
    val yinThreshold: Double = 0.20,
    val minimumConfidence: Double = 0.72,
    val stableFrameCount: Int = 2,
    val releaseFrameCount: Int = 2,
    val maximumStableGapMillis: Long = 180,
    val amplitudeRiseRatio: Double = 1.55,
    val minimumAmplitudeRise: Double = 0.004,
    val minimumRetriggerIntervalMillis: Long = 140,
    val lowConfidenceUiIntervalMillis: Long = 220,
    val centsTolerance: Double = 35.0
) {
    init {
        require(frameSize > 0 && hopSize in 1..frameSize)
        require(minimumFrequencyHz > 0 && maximumFrequencyHz > minimumFrequencyHz)
        require(releaseSignalRms in 0.0..minimumSignalRms)
        require(stableFrameCount > 0 && releaseFrameCount > 0)
        require(amplitudeRiseRatio > 1.0 && minimumAmplitudeRise >= 0.0)
    }
}

data class PitchFrame(
    val detectedPitch: DetectedPitch?,
    val signalLevel: Double,
    val timestampMillis: Long
)

sealed interface StablePitchEvent {
    data class Stable(
        val pitch: DetectedPitch,
        val onsetEvidence: NoteOnsetEvidence = NoteOnsetEvidence.InitialAttack
    ) : StablePitchEvent {
        val isNewOnset: Boolean get() = onsetEvidence != NoteOnsetEvidence.None
    }
    data object Release : StablePitchEvent
    data class LowConfidence(val pitch: DetectedPitch?) : StablePitchEvent
}

enum class NoteOnsetEvidence { None, InitialAttack, AfterRelease, AmplitudeRise, PitchTransition }
