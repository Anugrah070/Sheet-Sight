package com.sheetsight.app.domain.practice

import kotlin.math.roundToLong

data class TimingWindowConfig(
    val onTimeBeatFraction: Double = 0.22,
    val minimumOnTimeMillis: Long = 90L,
    val maximumOnTimeMillis: Long = 250L,
    val missedBeatFraction: Double = 1.5,
    val minimumMissedMillis: Long = 900L
)

data class TimingWindows(
    val onTimeToleranceMillis: Long,
    val missedAfterMillis: Long
)

/** Central timing policy scales with tempo while retaining humane bounds. */
class TimingWindowPolicy(
    private val config: TimingWindowConfig = TimingWindowConfig()
) {
    fun forTempo(bpm: Int): TimingWindows {
        require(bpm in MIN_PRACTICE_BPM..MAX_PRACTICE_BPM)
        val beatMillis = 60_000.0 / bpm
        return TimingWindows(
            onTimeToleranceMillis = (beatMillis * config.onTimeBeatFraction).roundToLong()
                .coerceIn(config.minimumOnTimeMillis, config.maximumOnTimeMillis),
            missedAfterMillis = (beatMillis * config.missedBeatFraction).roundToLong()
                .coerceAtLeast(config.minimumMissedMillis)
        )
    }
}

class PracticeTimingMatcher(
    private val pitchMatcher: PitchMatcher = PitchMatcher(),
    private val timingWindows: TimingWindowPolicy = TimingWindowPolicy()
) {
    fun match(
        step: PracticeStep,
        detected: DetectedPitch?,
        actualBeat: Double,
        bpm: Int
    ): PracticeMatchResult = match(step, detected?.let(::listOf).orEmpty(), actualBeat, bpm)

    fun match(
        step: PracticeStep,
        detected: List<DetectedPitch>,
        actualBeat: Double,
        bpm: Int
    ): PracticeMatchResult {
        val pitchState = pitchMatcher.match(step, detected)
        if (pitchState != MatchState.CorrectPitchOnly) return PracticeMatchResult(pitchState)
        if (!step.isTimingResolved || step.startBeat == null) {
            return PracticeMatchResult(MatchState.CorrectPitchOnly)
        }

        val offsetMillis = ((actualBeat - step.startBeat.toDouble()) * 60_000.0 / bpm).roundToLong()
        val tolerance = timingWindows.forTempo(bpm).onTimeToleranceMillis
        val state = when {
            offsetMillis < -tolerance -> MatchState.CorrectEarly
            offsetMillis > tolerance -> MatchState.CorrectLate
            else -> MatchState.CorrectOnTime
        }
        return PracticeMatchResult(state, offsetMillis)
    }

    fun isMissed(step: PracticeStep, currentBeat: Double, bpm: Int): Boolean {
        if (!step.isTimingResolved || step.startBeat == null || step.isRest) return false
        val lateMillis = (currentBeat - step.startBeat.toDouble()) * 60_000.0 / bpm
        return lateMillis > timingWindows.forTempo(bpm).missedAfterMillis
    }
}
