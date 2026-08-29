package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch

data class ScoreRecognitionConfig(
    val referenceA4Hz: Double = 440.0,
    val centsTolerance: Double = 35.0,
    val centsSearchStep: Double = 7.0,
    val maximumHarmonics: Int = 10,
    val minimumPresenceConfidence: Double = 0.52,
    val strongUnexpectedConfidence: Double = 0.82,
    val correctFrameCount: Int = 2,
    val wrongFrameCount: Int = 2,
    val chordAssemblyMillis: Long = 240L,
    val episodeTimeoutMillis: Long = 750L,
    val releaseFrameCount: Int = 3
) {
    init {
        require(referenceA4Hz > 0.0)
        require(centsTolerance in 0.0..50.0 && centsSearchStep > 0.0)
        require(maximumHarmonics >= 2)
        require(minimumPresenceConfidence in 0.0..1.0)
        require(strongUnexpectedConfidence in minimumPresenceConfidence..1.0)
        require(correctFrameCount > 0 && wrongFrameCount > 0 && releaseFrameCount > 0)
        require(chordAssemblyMillis > 0L && episodeTimeoutMillis >= chordAssemblyMillis)
    }
}

data class ExpectedPitchEvidence(
    val expectedPitch: PracticePitch,
    val detectedPitch: DetectedPitch,
    val harmonicScore: Double,
    val periodicityScore: Double,
    val competingScore: Double,
    val harmonicCoverage: Int,
    val inharmonicityCoefficient: Double
) {
    val confidence: Double get() = detectedPitch.confidence
    val present: Boolean get() = confidence > 0.0
}

data class ScoreMatchFrame(
    val timestampMillis: Long,
    val signalRms: Double,
    val expected: List<ExpectedPitchEvidence>,
    val unexpectedPitch: DetectedPitch? = null
)
