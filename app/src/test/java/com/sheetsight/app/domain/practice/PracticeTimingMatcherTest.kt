package com.sheetsight.app.domain.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeTimingMatcherTest {
    private val matcher = PracticeTimingMatcher()
    private val step = PracticeStep(
        index = 0,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = listOf(PracticePitch('C', 0, 4)),
        sourceNoteIds = listOf("c4"),
        onsetDivisions = 2,
        startBeat = MusicalBeat.of(2),
        durationBeats = MusicalBeat.of(1),
        measureBeat = MusicalBeat.of(2)
    )

    @Test fun `correct pitch inside window is on time`() = assertEquals(
        MatchState.CorrectOnTime,
        matcher.match(step, detected('C'), actualBeat = 2.1, bpm = 60).state
    )

    @Test fun `correct pitch before window is early`() = assertEquals(
        MatchState.CorrectEarly,
        matcher.match(step, detected('C'), actualBeat = 1.5, bpm = 60).state
    )

    @Test fun `correct pitch after window is late`() = assertEquals(
        MatchState.CorrectLate,
        matcher.match(step, detected('C'), actualBeat = 2.5, bpm = 60).state
    )

    @Test fun `wrong pitch inside timing window is wrong and cannot advance`() {
        val result = matcher.match(step, detected('D'), actualBeat = 2.0, bpm = 60)
        assertEquals(MatchState.WrongPitch, result.state)
        assertEquals(false, result.state.advancesPlayableNote)
    }

    @Test fun `low confidence and silence cannot advance`() {
        assertEquals(MatchState.LowConfidence, matcher.match(step, detected('C', 0.2), 2.0, 60).state)
        assertEquals(MatchState.LowConfidence, matcher.match(step, null, 2.0, 60).state)
    }

    @Test fun `timing windows scale at different BPM values`() {
        val policy = TimingWindowPolicy()
        assertEquals(220L, policy.forTempo(60).onTimeToleranceMillis)
        assertEquals(110L, policy.forTempo(120).onTimeToleranceMillis)
    }

    private fun detected(step: Char, confidence: Double = 0.95) = DetectedPitch(
        261.63,
        PracticePitch(step, 0, 4),
        0.0,
        confidence,
        1L,
        0.1
    )
}
