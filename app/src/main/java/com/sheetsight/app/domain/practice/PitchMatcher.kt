package com.sheetsight.app.domain.practice

import com.sheetsight.app.data.audio.PitchDetectionConfig
import kotlin.math.abs

class PitchMatcher(
    private val config: PitchDetectionConfig = PitchDetectionConfig()
) {
    fun match(step: PracticeStep, detected: DetectedPitch?): MatchState {
        if (step.isRest || step.requiresPolyphonicRecognition) return MatchState.Unsupported
        if (detected == null || detected.confidence < config.minimumConfidence) return MatchState.LowConfidence
        val expected = step.expectedPitches.singleOrNull() ?: return MatchState.Unsupported
        return if (
            expected.midiNumber == detected.nearestPitch.midiNumber &&
            abs(detected.centsOffset) <= config.centsTolerance
        ) MatchState.CorrectPitchOnly else MatchState.WrongPitch
    }
}
