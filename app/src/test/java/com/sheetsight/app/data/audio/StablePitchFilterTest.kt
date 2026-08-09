package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
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

    private fun frame(step: Char, timestamp: Long, level: Double = 0.1): PitchFrame {
        val pitch = DetectedPitch(261.63, PracticePitch(step, 0, 4), 0.0, 0.95, timestamp, level)
        return PitchFrame(pitch, level, timestamp)
    }
}
