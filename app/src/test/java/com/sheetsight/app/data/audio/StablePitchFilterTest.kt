package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.StablePitchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePitchFilterTest {
    @Test
    fun `transient wrong pitch does not emit but stable correct pitch emits once`() {
        val filter = StablePitchFilter()
        assertNull(filter.process(frame('G', 0)))
        assertNull(filter.process(frame('C', 50)))
        val event = filter.process(frame('C', 100))
        assertEquals(60, (event as StablePitchEvent.Stable).pitch.nearestPitch.midiNumber)
        assertEquals(NoteOnsetEvidence.InitialAttack, event.onsetEvidence)
        assertNull(filter.process(frame('C', 150)))
        assertNull(filter.process(frame('C', 200)))
        assertNull(filter.process(frame('C', 250)))
    }

    @Test
    fun `noise remains unresolved`() {
        val filter = StablePitchFilter()
        val events = (0 until 12).mapNotNull { index ->
            filter.process(PitchFrame(null, signalLevel = 0.03, timestampMillis = index * 50L))
        }
        assertTrue(events.all { it is StablePitchEvent.LowConfidence })
        assertTrue(events.size in 2..3)
    }

    @Test
    fun `two silence frames release a stable note`() {
        val filter = StablePitchFilter()
        repeat(2) { filter.process(frame('C', it * 50L)) }
        assertNull(filter.process(PitchFrame(null, 0.0, 200)))
        assertEquals(StablePitchEvent.Release, filter.process(PitchFrame(null, 0.0, 250)))
    }

    @Test
    fun `same stable pitch emits a new onset only after a significant amplitude rise`() {
        val filter = StablePitchFilter()
        filter.process(frame('C', 0, level = 0.08))
        filter.process(frame('C', 50, level = 0.08))
        assertNull(filter.process(frame('C', 200, level = 0.06)))
        val restrike = filter.process(frame('C', 250, level = 0.12))

        assertEquals(NoteOnsetEvidence.AmplitudeRise, (restrike as StablePitchEvent.Stable).onsetEvidence)
    }

    @Test
    fun `small sustained-level fluctuations do not fabricate repeated onsets`() {
        val filter = StablePitchFilter()
        filter.process(frame('C', 0, level = 0.08))
        filter.process(frame('C', 50, level = 0.08))
        assertNull(filter.process(frame('C', 200, level = 0.075)))
        assertNull(filter.process(frame('C', 250, level = 0.082)))
        assertNull(filter.process(frame('C', 300, level = 0.078)))
    }

    @Test
    fun `brief old-note evidence does not erase a legato transition candidate`() {
        val filter = StablePitchFilter()
        filter.process(frame('C', 0, level = 0.01))
        filter.process(frame('C', 50, level = 0.01))

        assertNull(filter.process(frame('E', 100, level = 0.006)))
        assertNull(filter.process(frame('C', 150, level = 0.006)))
        val transition = filter.process(frame('E', 200, level = 0.006))

        assertEquals(NoteOnsetEvidence.PitchTransition, (transition as StablePitchEvent.Stable).onsetEvidence)
        assertEquals(64, transition.pitch.nearestPitch.midiNumber)
    }

    @Test
    fun `quiet edge-register attacks pass the register-aware gate after stable evidence`() {
        val low = StablePitchFilter()
        assertNull(low.process(frame('C', 0, level = 0.0029, octave = 2)))
        val lowOnset = low.process(frame('C', 50, level = 0.0029, octave = 2))
        assertEquals(36, (lowOnset as StablePitchEvent.Stable).pitch.nearestPitch.midiNumber)

        val high = StablePitchFilter()
        assertNull(high.process(frame('E', 0, level = 0.0029, octave = 6)))
        val highOnset = high.process(frame('E', 50, level = 0.0029, octave = 6))
        assertEquals(88, (highOnset as StablePitchEvent.Stable).pitch.nearestPitch.midiNumber)
    }

    private fun frame(step: Char, timestamp: Long, level: Double = 0.1, octave: Int = 4): PitchFrame {
        val pitch = DetectedPitch(261.63, PracticePitch(step, 0, octave), 0.0, 0.95, timestamp, level)
        return PitchFrame(pitch, level, timestamp)
    }
}
