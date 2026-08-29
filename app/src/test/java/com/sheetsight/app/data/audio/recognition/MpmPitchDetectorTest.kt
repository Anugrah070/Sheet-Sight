package com.sheetsight.app.data.audio.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpmPitchDetectorTest {
    private val detector = MpmPitchDetector()

    @Test
    fun `clean C4 resolves with high clarity`() {
        val result = detector.analyze(sine(261.625565), 123L).detectedPitch
        assertNotNull(result)
        assertEquals(60, result!!.nearestPitch.midiNumber)
        assertTrue(result.confidence > 0.9)
        assertTrue(abs(result.centsOffset) < 2.0)
    }

    @Test
    fun `adaptive long window resolves A1`() {
        val result = detector.analyze(sine(55.0), 123L).detectedPitch
        assertNotNull(result)
        assertEquals(33, result!!.nearestPitch.midiNumber)
        assertTrue(abs(result.centsOffset) < 2.0)
    }

    @Test
    fun `strong second harmonic does not force an octave error`() {
        val frequency = 110.0
        val samples = FloatArray(SIZE) { index ->
            (0.06 * sin(2.0 * PI * frequency * index / SAMPLE_RATE) +
                0.24 * sin(2.0 * PI * frequency * 2.0 * index / SAMPLE_RATE) +
                0.10 * sin(2.0 * PI * frequency * 3.0 * index / SAMPLE_RATE)).toFloat()
        }
        assertEquals(45, detector.analyze(samples, 1L).detectedPitch?.nearestPitch?.midiNumber)
    }

    private fun sine(frequency: Double) = FloatArray(SIZE) { index ->
        (0.2 * sin(2.0 * PI * frequency * index / SAMPLE_RATE)).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val SIZE = 8_192
    }
}
