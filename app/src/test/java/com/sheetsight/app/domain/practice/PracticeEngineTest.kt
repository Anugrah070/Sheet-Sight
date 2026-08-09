package com.sheetsight.app.domain.practice

import com.sheetsight.app.data.audio.NoteOnsetEvidence
import com.sheetsight.app.data.audio.StablePitchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeEngineTest {
    @Test
    fun `wrong note never advances and correct note advances exactly once`() {
        val fixture = started(note(0, 'C'), note(1, 'D'))
        repeat(4) { fixture.engine.onPitchEvent(fixture.stable('E')) }
        assertEquals(0, fixture.engine.progress.currentStepIndex)
        assertEquals(MatchState.WrongPitch, fixture.engine.progress.matchState)

        fixture.engine.onPitchEvent(fixture.stable('C'))
        assertEquals(1, fixture.engine.progress.currentStepIndex)
        repeat(4) { fixture.engine.onPitchEvent(fixture.stable('C')) }
        assertEquals(1, fixture.engine.progress.currentStepIndex)
    }

    @Test
    fun `timing alone never advances a playable note`() {
        val fixture = started(note(0, 'C'))
        fixture.time.nowMillis = 10_000L
        fixture.engine.onClockTick()

        assertEquals(0, fixture.engine.progress.currentStepIndex)
        assertEquals(MatchState.Missed, fixture.engine.progress.matchState)
    }

    @Test
    fun `low confidence and silence do not advance`() {
        val fixture = started(note(0, 'C'))
        fixture.engine.onPitchEvent(StablePitchEvent.LowConfidence(fixture.detected('C', confidence = 0.2)))
        fixture.engine.onPitchEvent(StablePitchEvent.Release)
        assertEquals(0, fixture.engine.progress.currentStepIndex)
    }

    @Test
    fun `sustained C4 cannot advance repeated C4 steps but a genuine restrike can`() {
        val fixture = started(note(0, 'C'), note(1, 'C'), note(2, 'C'))

        fixture.engine.onPitchEvent(fixture.stable('C'))
        repeat(8) { fixture.engine.onPitchEvent(fixture.stable('C')) }
        assertEquals(1, fixture.engine.progress.currentStepIndex)

        fixture.time.nowMillis = 500L
        fixture.engine.onPitchEvent(fixture.stable('C', NoteOnsetEvidence.AmplitudeRise))
        assertEquals(2, fixture.engine.progress.currentStepIndex)

        fixture.engine.onPitchEvent(StablePitchEvent.Release)
        fixture.engine.onPitchEvent(fixture.stable('C', NoteOnsetEvidence.AfterRelease))
        assertEquals(3, fixture.engine.progress.currentStepIndex)
        assertEquals(PracticePhase.Completed, fixture.engine.progress.phase)
    }

    @Test
    fun `different stable pitch transition can complete the next note without silence`() {
        val fixture = started(note(0, 'C'), note(1, 'D'))
        fixture.engine.onPitchEvent(fixture.stable('C'))
        fixture.engine.onPitchEvent(fixture.stable('D', NoteOnsetEvidence.PitchTransition))
        assertEquals(PracticePhase.Completed, fixture.engine.progress.phase)
    }

    @Test
    fun `resolved rest advances exactly once after its duration`() {
        val fixture = started(rest(0, duration = MusicalBeat.of(1)), note(1, 'C'))
        fixture.time.nowMillis = 999L
        fixture.engine.onClockTick()
        assertEquals(0, fixture.engine.progress.currentStepIndex)

        fixture.time.nowMillis = 1_000L
        fixture.engine.onClockTick()
        assertEquals(1, fixture.engine.progress.currentStepIndex)
        fixture.engine.onClockTick()
        assertEquals(1, fixture.engine.progress.currentStepIndex)
    }

    @Test
    fun `pause freezes rest and resume continues without a jump`() {
        val fixture = started(rest(0, duration = MusicalBeat.of(1)), note(1, 'C'))
        fixture.time.nowMillis = 400L
        fixture.engine.pause()
        fixture.time.nowMillis = 10_400L
        fixture.engine.onClockTick()
        assertEquals(0, fixture.engine.progress.currentStepIndex)

        fixture.engine.resume()
        fixture.time.nowMillis = 10_999L
        fixture.engine.onClockTick()
        assertEquals(0, fixture.engine.progress.currentStepIndex)
        fixture.time.nowMillis = 11_000L
        fixture.engine.onClockTick()
        assertEquals(1, fixture.engine.progress.currentStepIndex)
    }

    @Test
    fun `note during rest records violation without corrupting index`() {
        val fixture = started(rest(0, duration = MusicalBeat.of(1)), note(1, 'C'))
        fixture.engine.onPitchEvent(fixture.stable('E'))

        assertEquals(0, fixture.engine.progress.currentStepIndex)
        assertEquals(MatchState.RestViolation, fixture.engine.progress.matchState)
        assertEquals(1, fixture.engine.progress.restViolationCount)
        fixture.time.nowMillis = 1_000L
        fixture.engine.onClockTick()
        assertEquals(1, fixture.engine.progress.currentStepIndex)
    }

    @Test
    fun `unresolved playable rhythm falls back to pitch only without fabricated duration`() {
        val unresolved = note(0, 'C').copy(
            durationBeats = null,
            timingResolution = PracticeTimingResolution.UnresolvedDuration,
            unresolvedTimingReason = "missing duration"
        )
        val fixture = started(unresolved)
        fixture.time.nowMillis = 5_000L
        fixture.engine.onPitchEvent(fixture.stable('C'))

        assertEquals(PracticePhase.Completed, fixture.engine.progress.phase)
        assertEquals(MatchState.CorrectPitchOnly, fixture.engine.progress.matchState)
        assertEquals(null, fixture.engine.progress.timingOffsetMillis)
    }

    @Test
    fun `unresolved rest is skipped once as unsupported without inventing time`() {
        val unresolvedRest = rest(0, duration = null).copy(
            timingResolution = PracticeTimingResolution.UnresolvedDuration,
            unresolvedTimingReason = "missing duration"
        )
        val fixture = started(unresolvedRest, note(1, 'C'))
        fixture.engine.onClockTick()

        assertEquals(1, fixture.engine.progress.currentStepIndex)
        assertEquals(MatchState.Unsupported, fixture.engine.progress.matchState)
    }

    @Test
    fun `tempo change while paused preserves beat and resumes deterministically`() {
        val fixture = started(note(0, 'C'), bpm = 60)
        fixture.time.nowMillis = 1_000L
        fixture.engine.pause()
        assertEquals(1.0, fixture.engine.clock.currentBeat(), 0.0001)

        fixture.engine.setTempo(120)
        fixture.time.nowMillis = 6_000L
        fixture.engine.resume()
        assertEquals(1.0, fixture.engine.clock.currentBeat(), 0.0001)
        fixture.time.nowMillis = 6_500L
        assertEquals(2.0, fixture.engine.clock.currentBeat(), 0.0001)
    }

    @Test
    fun `count in uses known meter and does not listen before completion`() {
        val time = FakeTimeSource()
        val engine = PracticeEngine(clock = PracticeClock(time)).apply {
            load(
                PracticeSequence(
                    PracticeSource("meter.xml", 1, initialMeter = PracticeMeter(6, 8)),
                    listOf(note(0, 'C'))
                )
            )
            setTempo(120)
            beginCountIn()
        }

        assertEquals(PracticePhase.CountIn, engine.progress.phase)
        assertEquals(6, engine.countInPulseCount())
        assertEquals(250L, engine.countInPulseMillis())
        engine.onPitchEvent(StablePitchEvent.Stable(detectedAt('C', 0L)))
        assertEquals(0, engine.progress.currentStepIndex)

        engine.completeCountIn()
        assertEquals(PracticePhase.Listening, engine.progress.phase)
        assertEquals(PracticeClockState.Running, engine.clock.state)
    }

    @Test
    fun `index never exceeds sequence size after completion`() {
        val fixture = started(note(0, 'C'))
        repeat(5) { fixture.engine.onPitchEvent(fixture.stable('C')) }
        assertEquals(1, fixture.engine.progress.currentStepIndex)
        assertTrue(fixture.engine.progress.currentStepIndex <= fixture.engine.progress.totalSteps)
    }

    private fun started(vararg steps: PracticeStep, bpm: Int = 60): Fixture {
        val time = FakeTimeSource()
        val engine = PracticeEngine(clock = PracticeClock(time)).apply {
            load(PracticeSequence(PracticeSource("test.xml", steps.size), steps.toList()))
            setTempo(bpm)
            start()
        }
        return Fixture(engine, time)
    }

    private fun note(index: Int, pitch: Char) = PracticeStep(
        index = index,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = listOf(PracticePitch(pitch, 0, 4)),
        sourceNoteIds = listOf("$index"),
        onsetDivisions = index,
        startBeat = MusicalBeat.of(index.toLong()),
        durationBeats = MusicalBeat.of(1),
        measureBeat = MusicalBeat.of(index.toLong())
    )

    private fun rest(index: Int, duration: MusicalBeat?) = PracticeStep(
        index = index,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = emptyList(),
        sourceNoteIds = emptyList(),
        onsetDivisions = index,
        startBeat = MusicalBeat.of(index.toLong()),
        durationBeats = duration,
        measureBeat = MusicalBeat.of(index.toLong()),
        isRest = true
    )

    private data class Fixture(val engine: PracticeEngine, val time: FakeTimeSource) {
        fun stable(
            step: Char,
            evidence: NoteOnsetEvidence = NoteOnsetEvidence.InitialAttack
        ) = StablePitchEvent.Stable(detected(step), evidence)

        fun detected(step: Char, confidence: Double = 0.95) = DetectedPitch(
            261.63,
            PracticePitch(step, 0, 4),
            0.0,
            confidence,
            time.nowMillis,
            0.1
        )
    }

    private class FakeTimeSource(var nowMillis: Long = 0L) : MonotonicTimeSource {
        override fun nowMillis(): Long = nowMillis
    }

    private fun detectedAt(step: Char, timestamp: Long) = DetectedPitch(
        261.63,
        PracticePitch(step, 0, 4),
        0.0,
        0.95,
        timestamp,
        0.1
    )
}
