package com.sheetsight.app.domain.practice

import kotlin.math.roundToLong

/** Injectable monotonic source keeps clock arithmetic deterministic in JVM tests. */
fun interface MonotonicTimeSource {
    fun nowMillis(): Long
}

enum class PracticeClockState { Stopped, Running, Paused }

/**
 * Session clock independent of Compose and wall-clock time.
 *
 * Beat accumulation is piecewise, so changing BPM while paused cannot create
 * an elapsed-time or musical-position jump.
 */
class PracticeClock(
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource { System.nanoTime() / 1_000_000L }
) {
    var state: PracticeClockState = PracticeClockState.Stopped
        private set
    var bpm: Int = DEFAULT_PRACTICE_BPM
        private set

    private var segmentStartMillis = 0L
    private var accumulatedElapsedMillis = 0L
    private var accumulatedBeats = 0.0

    fun start(bpm: Int, nowMillis: Long = timeSource.nowMillis()) {
        requireTempo(bpm)
        this.bpm = bpm
        segmentStartMillis = nowMillis
        accumulatedElapsedMillis = 0L
        accumulatedBeats = 0.0
        state = PracticeClockState.Running
    }

    fun pause(nowMillis: Long = timeSource.nowMillis()) {
        if (state != PracticeClockState.Running) return
        accumulateRunningSegment(nowMillis)
        state = PracticeClockState.Paused
    }

    fun resume(newBpm: Int = bpm, nowMillis: Long = timeSource.nowMillis()) {
        if (state != PracticeClockState.Paused) return
        requireTempo(newBpm)
        bpm = newBpm
        segmentStartMillis = nowMillis
        state = PracticeClockState.Running
    }

    fun stop() {
        state = PracticeClockState.Stopped
        segmentStartMillis = 0L
        accumulatedElapsedMillis = 0L
        accumulatedBeats = 0.0
    }

    fun elapsedPracticeMillis(nowMillis: Long = timeSource.nowMillis()): Long = when (state) {
        PracticeClockState.Running -> accumulatedElapsedMillis + (nowMillis - segmentStartMillis).coerceAtLeast(0L)
        PracticeClockState.Paused -> accumulatedElapsedMillis
        PracticeClockState.Stopped -> 0L
    }

    fun currentBeat(nowMillis: Long = timeSource.nowMillis()): Double = when (state) {
        PracticeClockState.Running -> accumulatedBeats +
            (nowMillis - segmentStartMillis).coerceAtLeast(0L) * bpm / MILLIS_PER_MINUTE
        PracticeClockState.Paused -> accumulatedBeats
        PracticeClockState.Stopped -> 0.0
    }

    /** Beat corresponding to an audio onset timestamp from the same monotonic source. */
    fun beatAt(timestampMillis: Long): Double = currentBeat(timestampMillis)

    private fun accumulateRunningSegment(nowMillis: Long) {
        val segmentMillis = (nowMillis - segmentStartMillis).coerceAtLeast(0L)
        accumulatedElapsedMillis += segmentMillis
        accumulatedBeats += segmentMillis * bpm / MILLIS_PER_MINUTE
    }

    private fun requireTempo(value: Int) {
        require(value in MIN_PRACTICE_BPM..MAX_PRACTICE_BPM) {
            "Practice tempo must be in $MIN_PRACTICE_BPM..$MAX_PRACTICE_BPM BPM."
        }
    }

    companion object {
        /** Converts normalized quarter-note beats using the same tempo basis as the session clock. */
        fun durationMillis(beats: MusicalBeat, bpm: Int): Long {
            require(bpm in MIN_PRACTICE_BPM..MAX_PRACTICE_BPM)
            return (beats.toDouble() * MILLIS_PER_MINUTE / bpm).roundToLong().coerceAtLeast(1L)
        }

        private const val MILLIS_PER_MINUTE = 60_000.0
    }
}
