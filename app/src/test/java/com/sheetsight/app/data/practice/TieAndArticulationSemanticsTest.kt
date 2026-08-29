package com.sheetsight.app.data.practice

import com.sheetsight.app.data.audio.DurationClassifier
import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.domain.practice.StablePitchEvent
import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.DurationComparisonReliability
import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.ExpectedArticulation
import com.sheetsight.app.domain.practice.ExpectedDuration
import com.sheetsight.app.domain.practice.MatchState
import com.sheetsight.app.domain.practice.MonotonicTimeSource
import com.sheetsight.app.domain.practice.MusicalBeat
import com.sheetsight.app.domain.practice.PracticeClock
import com.sheetsight.app.domain.practice.PracticeEngine
import com.sheetsight.app.domain.practice.PracticePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TieAndArticulationSemanticsTest {
    @Test
    fun `tie across measure creates one logical duration without destroying source identities`() {
        val sequence = load(TIE_ACROSS_MEASURE)

        assertEquals(3, sequence.totalSteps)
        assertTrue(sequence.steps[0].tieStart)
        assertTrue(sequence.steps[1].tieContinuation)
        assertTrue(sequence.steps[1].tieEnd)
        assertEquals("3", sequence.steps[0].tieSemantics.combinedExpectedDurationBeats.toString())
        assertEquals(sequence.steps[0].tieGroupId, sequence.steps[1].tieGroupId)
        assertNotEquals(sequence.steps[0].sourceNoteIds.single(), sequence.steps[1].sourceNoteIds.single())
        assertEquals(DurationComparisonReliability.Reliable, sequence.steps[0].durationComparisonReliability)
    }

    @Test
    fun `three-note tie chain combines all written durations`() {
        val sequence = load(THREE_NOTE_TIE)

        assertEquals("3", sequence.steps.first().tieSemantics.combinedExpectedDurationBeats.toString())
        assertTrue(sequence.steps[1].tieContinuation)
        assertFalse(sequence.steps[1].tieEnd)
        assertTrue(sequence.steps[2].tieEnd)
    }

    @Test
    fun `tie continuation never demands a second onset and following note is rearmed`() {
        var now = 0L
        val clock = PracticeClock(MonotonicTimeSource { now })
        val engine = PracticeEngine(clock = clock)
        engine.load(load(TIE_ACROSS_MEASURE))
        engine.setTempo(60)
        engine.start()

        engine.onPitchEvent(StablePitchEvent.Stable(detected('C', 0L)))
        assertEquals(1, engine.progress.currentStepIndex)
        engine.onPitchEvent(StablePitchEvent.Stable(detected('C', 500L), NoteOnsetEvidence.None))
        assertEquals(1, engine.progress.currentStepIndex)

        now = 3_000L
        engine.onClockTick()
        assertEquals(2, engine.progress.currentStepIndex)
        assertEquals(MatchState.TieContinuation, engine.progress.matchState)
        engine.onPitchEvent(StablePitchEvent.Stable(detected('D', 3_100L), NoteOnsetEvidence.None))
        assertEquals(2, engine.progress.currentStepIndex)
        engine.onPitchEvent(StablePitchEvent.Stable(detected('D', 3_200L), NoteOnsetEvidence.PitchTransition))
        assertEquals(PracticePhase.Completed, engine.progress.phase)
    }

    @Test
    fun `malformed and different-pitch ties remain unresolved`() {
        val sequence = load(MALFORMED_TIE)

        assertTrue(sequence.steps.all { it.durationComparisonReliability == DurationComparisonReliability.UnresolvedTie })
        assertTrue(sequence.steps.all { !it.tieSemantics.resolved })
        assertNull(sequence.steps.first().tieGroupId)
    }

    @Test
    fun `slur remains distinct and next pitch still needs an onset`() {
        val sequence = load(SLUR)
        assertTrue(sequence.steps.all { it.hasSlur })
        assertTrue(sequence.steps.none { it.tieContinuation })

        val engine = PracticeEngine()
        engine.load(sequence)
        engine.start()
        engine.onPitchEvent(StablePitchEvent.Stable(detected('C', 0L)))
        engine.onPitchEvent(StablePitchEvent.Stable(detected('D', 100L), NoteOnsetEvidence.None))
        assertEquals(1, engine.progress.currentStepIndex)
        engine.onPitchEvent(StablePitchEvent.Stable(detected('D', 120L), NoteOnsetEvidence.PitchTransition))
        assertEquals(PracticePhase.Completed, engine.progress.phase)
    }

    @Test
    fun `articulations change only informational duration interpretation`() {
        val sequence = load(ARTICULATIONS)
        assertEquals(
            listOf(
                ExpectedArticulation.Normal,
                ExpectedArticulation.Staccato,
                ExpectedArticulation.Tenuto,
                ExpectedArticulation.Fermata,
                ExpectedArticulation.Accent
            ),
            sequence.steps.map { it.expectedArticulation }
        )
        assertEquals(DurationFeedback.TooShort, classify(ExpectedArticulation.Normal, 250L))
        assertEquals(DurationFeedback.StaccatoConsistent, classify(ExpectedArticulation.Staccato, 250L))
        assertEquals(DurationFeedback.ArticulationInconsistent, classify(ExpectedArticulation.Staccato, 1_700L))
        assertEquals(DurationFeedback.PossiblyShort, classify(ExpectedArticulation.Tenuto, 250L))
        assertEquals(DurationFeedback.TenutoSustained, classify(ExpectedArticulation.Tenuto, 900L))
        assertEquals(DurationFeedback.FermataFlexible, classify(ExpectedArticulation.Fermata, 5_000L))
        assertEquals(DurationFeedback.ApproximatelyCorrect, classify(ExpectedArticulation.Accent, 900L))
    }

    private fun classify(articulation: ExpectedArticulation, observed: Long) = DurationClassifier.classify(
        ExpectedDuration(MusicalBeat.of(1), 1_000L, articulation),
        observed,
        sustainAmbiguous = false
    )

    private fun load(xml: String) = PracticeMusicXmlLoader().load("test.musicxml", xml.toByteArray()).sequence

    private fun detected(step: Char, timestamp: Long) = DetectedPitch(
        frequencyHz = if (step == 'C') 261.63 else 293.66,
        nearestPitch = com.sheetsight.app.domain.practice.PracticePitch(step, 0, 4),
        centsOffset = 0.0,
        confidence = 0.95,
        timestampMillis = timestamp,
        signalLevel = 0.1
    )

    private companion object {
        fun score(body: String) = """
            <score-partwise version="4.0"><part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
            <part id="P1">$body</part></score-partwise>
        """.trimIndent()

        fun note(step: String, duration: Int = 1, extra: String = "") =
            "<note><pitch><step>$step</step><octave>4</octave></pitch><duration>$duration</duration><type>${if (duration == 2) "half" else "quarter"}</type>$extra</note>"

        val TIE_ACROSS_MEASURE = score("""
            <measure number="1"><attributes><divisions>1</divisions></attributes>${note("C", 1, "<tie type=\"start\"/>")}</measure>
            <measure number="2">${note("C", 2, "<notations><tied type=\"stop\"/></notations>")}${note("D")}</measure>
        """.trimIndent())

        val THREE_NOTE_TIE = score("""
            <measure number="1"><attributes><divisions>1</divisions></attributes>
            ${note("C", 1, "<tie type=\"start\"/>")}
            ${note("C", 1, "<tie type=\"stop\"/><tie type=\"start\"/>")}
            ${note("C", 1, "<tie type=\"stop\"/>")}
            </measure>
        """.trimIndent())

        val MALFORMED_TIE = score("""
            <measure number="1"><attributes><divisions>1</divisions></attributes>
            ${note("C", 1, "<tie type=\"start\"/>")}${note("D", 1, "<tie type=\"stop\"/>")}
            </measure>
        """.trimIndent())

        val SLUR = score("""
            <measure number="1"><attributes><divisions>1</divisions></attributes>
            ${note("C", 1, "<notations><slur type=\"start\" number=\"1\"/></notations>")}
            ${note("D", 1, "<notations><slur type=\"stop\" number=\"1\"/></notations>")}
            </measure>
        """.trimIndent())

        val ARTICULATIONS = score("""
            <measure number="1"><attributes><divisions>1</divisions></attributes>
            ${note("C")}
            ${note("D", 1, "<notations><articulations><staccato/></articulations></notations>")}
            ${note("E", 1, "<notations><articulations><tenuto/></articulations></notations>")}
            ${note("F", 1, "<notations><fermata/></notations>")}
            ${note("G", 1, "<notations><articulations><accent/></articulations></notations>")}
            </measure>
        """.trimIndent())
    }
}
