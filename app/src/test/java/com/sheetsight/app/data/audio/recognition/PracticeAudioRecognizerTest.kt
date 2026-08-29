package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.StablePitchEvent
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeAudioRecognizerTest {
    @Test
    fun `one attack emits one confirmed group and sustain does not repeat it`() {
        val recognizer = PracticeAudioRecognizer()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        var timestamp = 0L
        repeat(12) {
            recognizer.process(FloatArray(CHUNK_SIZE), timestamp, context)
            timestamp += CHUNK_MILLIS
        }

        val events = mutableListOf<StablePitchEvent>()
        var sampleOffset = 0
        repeat(60) {
            recognizer.process(harmonicChunk(60, sampleOffset), timestamp, context)
                ?.recognitionEvent
                ?.let(events::add)
            sampleOffset += CHUNK_SIZE
            timestamp += CHUNK_MILLIS
        }

        val groups = events.filterIsInstance<StablePitchEvent.NoteGroup>()
        assertEquals(events.toString(), 1, groups.size)
        assertEquals(setOf(60), groups.single().midiNumbers)
    }

    @Test
    fun `adjacent semitone attack emits wrong rather than expected`() {
        val recognizer = PracticeAudioRecognizer()
        val context = PracticeRecognitionContext(0, listOf(PracticePitch.fromMidi(60)))
        var timestamp = 0L
        repeat(12) {
            recognizer.process(FloatArray(CHUNK_SIZE), timestamp, context)
            timestamp += CHUNK_MILLIS
        }

        val events = mutableListOf<StablePitchEvent>()
        var sampleOffset = 0
        repeat(60) {
            recognizer.process(harmonicChunk(61, sampleOffset), timestamp, context)
                ?.recognitionEvent
                ?.let(events::add)
            sampleOffset += CHUNK_SIZE
            timestamp += CHUNK_MILLIS
        }

        assertTrue(events.toString(), events.any { it is StablePitchEvent.Wrong })
        assertTrue(events.toString(), events.none { it is StablePitchEvent.NoteGroup })
    }

    private fun harmonicChunk(midi: Int, sampleOffset: Int): FloatArray {
        val fundamental = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return FloatArray(CHUNK_SIZE) { index ->
            val absolute = sampleOffset + index
            var sample = 0.0
            for (partial in 1..7) {
                sample += 0.12 / partial * sin(2.0 * PI * fundamental * partial * absolute / SAMPLE_RATE)
            }
            sample.toFloat()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val CHUNK_SIZE = 256
        const val CHUNK_MILLIS = 12L
    }
}
