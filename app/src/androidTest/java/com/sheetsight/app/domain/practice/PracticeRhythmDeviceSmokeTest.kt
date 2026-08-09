package com.sheetsight.app.domain.practice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheetsight.app.data.audio.NoteOnsetEvidence
import com.sheetsight.app.data.audio.StablePitchEvent
import com.sheetsight.app.data.practice.PracticeMusicXmlLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime smoke of import, tempo, timing labels, rests, pause, and repeated-note re-arm. */
@RunWith(AndroidJUnit4::class)
class PracticeRhythmDeviceSmokeTest {
    @Test
    fun rhythmAwarePracticeFlowRemainsDeterministicOnDevice() {
        val sequence = PracticeMusicXmlLoader().load("device-smoke.musicxml", SCORE.toByteArray()).sequence
        val time = FakeTimeSource()
        val engine = PracticeEngine(clock = PracticeClock(time))
        engine.load(sequence)

        assertEquals(60, engine.progress.tempo.bpm)
        assertEquals(PracticeTempoSource.Detected, engine.progress.tempo.source)
        assertEquals(listOf("C4", "D4", "Rest", "E4", "C4", "C4"), sequence.steps.map { it.displayText })

        engine.beginCountIn()
        assertEquals(4, engine.countInPulseCount())
        engine.completeCountIn()

        engine.onPitchEvent(stable('F', time.nowMillis))
        assertEquals(0, engine.progress.currentStepIndex)
        assertEquals(MatchState.WrongPitch, engine.progress.matchState)

        engine.onPitchEvent(stable('C', time.nowMillis))
        assertEquals(MatchState.CorrectOnTime, engine.progress.matchState)
        time.nowMillis = 500L
        engine.onPitchEvent(stable('D', time.nowMillis, NoteOnsetEvidence.PitchTransition))
        assertEquals(MatchState.CorrectEarly, engine.progress.matchState)
        assertEquals(2, engine.progress.currentStepIndex)

        time.nowMillis = 800L
        engine.pause()
        time.nowMillis = 5_800L
        engine.onClockTick()
        assertEquals(2, engine.progress.currentStepIndex)
        engine.resume()
        time.nowMillis = 6_500L
        engine.onClockTick()
        assertEquals(3, engine.progress.currentStepIndex)

        time.nowMillis = 8_500L
        engine.onPitchEvent(stable('E', time.nowMillis, NoteOnsetEvidence.AfterRelease))
        assertEquals(MatchState.CorrectLate, engine.progress.matchState)
        time.nowMillis = 9_000L
        engine.onPitchEvent(stable('C', time.nowMillis, NoteOnsetEvidence.PitchTransition))
        assertEquals(5, engine.progress.currentStepIndex)

        engine.onPitchEvent(stable('C', time.nowMillis))
        assertEquals(5, engine.progress.currentStepIndex)
        time.nowMillis = 9_250L
        engine.onPitchEvent(stable('C', time.nowMillis, NoteOnsetEvidence.AmplitudeRise))
        assertEquals(PracticePhase.Completed, engine.progress.phase)
    }

    private fun stable(
        step: Char,
        timestamp: Long,
        evidence: NoteOnsetEvidence = NoteOnsetEvidence.InitialAttack
    ) = StablePitchEvent.Stable(
        DetectedPitch(
            frequencyHz = 261.63,
            nearestPitch = PracticePitch(step, 0, 4),
            centsOffset = 0.0,
            confidence = 0.95,
            timestampMillis = timestamp,
            signalLevel = 0.1
        ),
        evidence
    )

    private class FakeTimeSource(var nowMillis: Long = 0L) : MonotonicTimeSource {
        override fun nowMillis(): Long = nowMillis
    }

    private companion object {
        val SCORE = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                <direction><sound tempo="60"/></direction>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><type>quarter</type></note>
                <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><type>quarter</type></note>
                <note><rest/><duration>2</duration><type>quarter</type></note>
                <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><type>eighth</type></note>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>half</type></note>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
    }
}
