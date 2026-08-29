package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch

/** Centralized thresholds for capture, YIN, stability, and matching. */
data class PitchDetectionConfig(
    val sampleRateHz: Int = 22_050,
    val frameSize: Int = 4_096,
    val hopSize: Int = 1_024,
    val minimumFrequencyHz: Double = 27.5,
    val maximumFrequencyHz: Double = 4_186.01,
    /** Cheap detector floor. Final onset acceptance uses the adaptive gate below. */
    val analysisMinimumSignalRms: Double = 0.0020,
    val minimumSignalRms: Double = 0.0032,
    val releaseSignalRms: Double = 0.0024,
    val onsetNoiseMultiplier: Double = 2.0,
    val onsetNoiseSmoothing: Double = 0.08,
    /** Piano bass and treble attacks are commonly quieter at the phone than the middle register. */
    val edgeRegisterSignalRatio: Double = 0.85,
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
        require(analysisMinimumSignalRms in 0.0..releaseSignalRms)
        require(releaseSignalRms in 0.0..minimumSignalRms)
        require(onsetNoiseMultiplier > 1.0 && onsetNoiseSmoothing in 0.0..1.0)
        require(edgeRegisterSignalRatio in 0.0..1.0)
        require(stableFrameCount > 0 && releaseFrameCount > 0)
        require(amplitudeRiseRatio > 1.0 && minimumAmplitudeRise >= 0.0)
    }
}

data class PitchFrame(
    val detectedPitch: DetectedPitch?,
    val signalLevel: Double,
    val timestampMillis: Long
)
