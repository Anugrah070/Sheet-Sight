package com.sheetsight.app.data.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YinPitchDetectorTest {
    private val config = PitchDetectionConfig()
    private val detector = YinPitchDetector(config)

    @Test
    fun `clean C4 sine resolves to C4 with accepted confidence`() {
        val samples = FloatArray(config.frameSize) { index ->
            (0.25 * sin(2.0 * PI * 261.625565 * index / config.sampleRateHz)).toFloat()
        }
        val result = detector.analyze(samples, 123L).detectedPitch
        requireNotNull(result)
        assertEquals(60, result.nearestPitch.midiNumber)
        assertTrue(result.confidence >= config.minimumConfidence)
        assertTrue(kotlin.math.abs(result.centsOffset) < 2.0)
    }

    @Test
    fun `silence remains unresolved`() {
        assertNull(detector.analyze(FloatArray(config.frameSize), 123L).detectedPitch)
    }
}
