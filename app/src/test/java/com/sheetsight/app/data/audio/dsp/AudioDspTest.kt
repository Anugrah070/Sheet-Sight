package com.sheetsight.app.data.audio.dsp

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {
    @Test
    fun `high pass removes dc while preserving piano fundamentals`() {
        val filter = BiquadHighPassFilter(SAMPLE_RATE, 15.0)
        val dc = filter.process(FloatArray(SAMPLE_RATE) { 0.2f })
        val dcTail = RmsNoiseGate.rms(dc.copyOfRange(dc.size / 2, dc.size))
        assertTrue(dcTail < 0.0001)

        filter.reset()
        val a1 = FloatArray(SAMPLE_RATE) { index ->
            (0.2 * sin(2.0 * PI * 55.0 * index / SAMPLE_RATE)).toFloat()
        }
        val filtered = filter.process(a1)
        val ratio = RmsNoiseGate.rms(filtered.copyOfRange(filtered.size / 2, filtered.size)) /
            RmsNoiseGate.rms(a1.copyOfRange(a1.size / 2, a1.size))
        assertTrue(ratio > 0.95)
    }

    @Test
    fun `fft places an exact bin tone at the expected bin`() {
        val size = 1_024
        val bin = 37
        val samples = FloatArray(size) { index ->
            sin(2.0 * PI * bin * index / size).toFloat()
        }
        val spectrum = Radix2Fft.powerSpectrum(HannWindow.apply(samples))
        assertEquals(bin, spectrum.indices.maxBy { spectrum[it] })
        assertEquals(0.0, HannWindow.apply(floatArrayOf(1f, 1f, 1f)).first(), 1e-12)
    }

    @Test
    fun `spectral flux opens on an attack but not a steady continuation`() {
        val detector = SpectralFluxOnsetDetector()
        val silence = DoubleArray(513)
        val tone = silence.copyOf().also { it[20] = 0.1 }
        assertTrue(!detector.process(silence, false, 0L).onset)
        assertTrue(detector.process(tone, true, 20L).onset)
        assertTrue(!detector.process(tone, true, 120L).onset)
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
