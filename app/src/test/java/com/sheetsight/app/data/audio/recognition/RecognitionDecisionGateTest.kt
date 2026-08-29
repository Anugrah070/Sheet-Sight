package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.StablePitchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDecisionGateTest {
    @Test
    fun `correct requires consecutive agreement`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        assertNull(gate.process(frame(context, 0L, onset = true, present = setOf(60))))
        val event = gate.process(frame(context, 30L, present = setOf(60)))
        assertEquals(setOf(60), (event as StablePitchEvent.NoteGroup).midiNumbers)
    }

    @Test
    fun `staggered chord assembles without early wrong decision`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(60, 64, 67).map(PracticePitch::fromMidi))
        assertNull(gate.process(frame(context, 0L, onset = true, present = setOf(60))))
        assertNull(gate.process(frame(context, 40L, present = setOf(60, 64))))
        assertNull(gate.process(frame(context, 80L, present = setOf(64, 67))))
        val event = gate.process(frame(context, 120L, present = setOf(67)))
        assertEquals(setOf(60, 64, 67), (event as StablePitchEvent.NoteGroup).midiNumbers)
    }

    @Test
    fun `one wrong frame never emits wrong`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        assertNull(gate.process(frame(context, 0L, onset = true, unexpectedMidi = 62)))
        assertNull(gate.process(frame(context, 30L)))
    }

    @Test
    fun `wrong decision also requires consecutive agreement`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        assertNull(gate.process(frame(context, 0L, onset = true, unexpectedMidi = 62)))
        assertTrue(gate.process(frame(context, 30L, unexpectedMidi = 62)) is StablePitchEvent.Wrong)
    }

    @Test
    fun `incomplete chord waits through assembly window before wrong`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(60, 64, 67).map(PracticePitch::fromMidi))
        assertNull(gate.process(frame(context, 0L, onset = true, present = setOf(60))))
        assertNull(gate.process(frame(context, 40L, present = setOf(60))))
        assertNull(gate.process(frame(context, 250L, present = setOf(60))))
        assertTrue(gate.process(frame(context, 280L, present = setOf(60))) is StablePitchEvent.Wrong)
    }

    @Test
    fun `correct emits once until release and rearms after release`() {
        val gate = RecognitionDecisionGate()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        assertNull(gate.process(frame(context, 0L, onset = true, present = setOf(60))))
        assertTrue(gate.process(frame(context, 30L, present = setOf(60))) is StablePitchEvent.NoteGroup)
        assertNull(gate.process(frame(context, 60L, present = setOf(60))))
        assertNull(gate.process(releaseFrame(context, 90L)))
        assertNull(gate.process(releaseFrame(context, 120L)))
        assertEquals(StablePitchEvent.Release, gate.process(releaseFrame(context, 150L)))
        assertNull(gate.process(frame(context, 180L, onset = true, present = setOf(60))))
        val retrigger = gate.process(frame(context, 210L, present = setOf(60))) as StablePitchEvent.NoteGroup
        assertEquals(com.sheetsight.app.domain.practice.NoteOnsetEvidence.AfterRelease, retrigger.onsetEvidence)
    }

    private fun frame(
        context: PracticeRecognitionContext,
        timestamp: Long,
        onset: Boolean = false,
        present: Set<Int> = emptySet(),
        unexpectedMidi: Int? = null
    ): RecognitionEvidenceFrame {
        val expected = context.expectedPitches.map { pitch ->
            val confidence = if (pitch.midiNumber in present) 0.9 else 0.1
            ExpectedPitchEvidence(
                pitch,
                detected(pitch.midiNumber, confidence, timestamp),
                confidence,
                confidence,
                0.1,
                if (confidence > 0.5) 3 else 0,
                0.0
            )
        }
        return RecognitionEvidenceFrame(
            context,
            ScoreMatchFrame(
                timestamp,
                0.1,
                expected,
                unexpectedMidi?.let { detected(it, 0.9, timestamp) }
            ),
            onset,
            released = false
        )
    }

    private fun detected(midi: Int, confidence: Double, timestamp: Long) = DetectedPitch(
        440.0,
        PracticePitch.fromMidi(midi),
        0.0,
        confidence,
        timestamp,
        0.1
    )

    private fun releaseFrame(context: PracticeRecognitionContext, timestamp: Long) = RecognitionEvidenceFrame(
        context = context,
        match = ScoreMatchFrame(timestamp, 0.0, emptyList()),
        onset = false,
        released = true
    )
}
