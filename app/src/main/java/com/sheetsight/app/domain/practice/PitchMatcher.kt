package com.sheetsight.app.domain.practice

import kotlin.math.abs

data class PitchMatchConfig(
    // Score-aware evidence has already passed consecutive-frame hysteresis before it reaches here.
    val minimumConfidence: Double = 0.52,
    val centsTolerance: Double = 35.0
) {
    init {
        require(minimumConfidence in 0.0..1.0)
        require(centsTolerance in 0.0..50.0)
    }
}

class PitchMatcher(
    private val config: PitchMatchConfig = PitchMatchConfig()
) {
    fun match(step: PracticeStep, detected: DetectedPitch?): MatchState {
        if (step.isRest) return MatchState.Unsupported
        if (detected == null || detected.confidence < config.minimumConfidence) return MatchState.LowConfidence
        return match(step, listOf(detected))
    }

    /** Compares distinct sounding pitches; acoustic input cannot count doubled unisons. */
    fun match(step: PracticeStep, detected: List<DetectedPitch>): MatchState {
        if (step.isRest || step.expectedPitches.isEmpty()) return MatchState.Unsupported
        if (detected.isEmpty()) return MatchState.LowConfidence
        if (detected.any { it.confidence < config.minimumConfidence }) return MatchState.LowConfidence

        val expectedMidi = step.expectedPitches.mapTo(linkedSetOf()) { it.midiNumber }
        val detectedByMidi = detected.associateBy { it.nearestPitch.midiNumber }
        if (detectedByMidi.keys != expectedMidi) return MatchState.WrongPitch
        return if (detectedByMidi.values.all { abs(it.centsOffset) <= config.centsTolerance }) {
            MatchState.CorrectPitchOnly
        } else {
            MatchState.WrongPitch
        }
    }
}
