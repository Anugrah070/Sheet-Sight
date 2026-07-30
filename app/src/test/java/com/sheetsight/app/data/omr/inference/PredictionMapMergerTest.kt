package com.sheetsight.app.data.omr.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionMapMergerTest {

    @Test
    fun `incremental accumulator produces identical output to one-shot merge`() {
        val canonicalWidth = 100
        val canonicalHeight = 100
        val channels = 3
        val windowSize = 10

        // Create some dummy predictions with overlaps
        val p1 = TilePrediction(
            originX = 0, originY = 0, windowSize = windowSize, channels = channels,
            values = FloatArray(windowSize * windowSize * channels) { it.toFloat() * 0.1f }
        )
        val p2 = TilePrediction(
            originX = 5, originY = 0, windowSize = windowSize, channels = channels,
            values = FloatArray(windowSize * windowSize * channels) { it.toFloat() * 0.2f }
        )
        val p3 = TilePrediction(
            originX = 0, originY = 5, windowSize = windowSize, channels = channels,
            values = FloatArray(windowSize * windowSize * channels) { it.toFloat() * 0.3f }
        )
        val allPredictions = listOf(p1, p2, p3)

        // One-shot merge
        val oneShotResult = PredictionMapMerger.merge(canonicalWidth, canonicalHeight, allPredictions)

        // Incremental merge
        val accumulator = PredictionMapAccumulator(oneShotResult.width, oneShotResult.height, channels)
        allPredictions.forEach { p ->
            accumulator.accumulate(p.originX, p.originY, p.windowSize, p.values)
        }
        val incrementalResult = accumulator.finish()

        // Verify metadata
        assertEquals("Width must match", oneShotResult.width, incrementalResult.width)
        assertEquals("Height must match", oneShotResult.height, incrementalResult.height)
        assertEquals("Channels must match", oneShotResult.channels, incrementalResult.channels)

        // Verify data bit-identically
        assertArrayEquals("Data must be bit-identical", oneShotResult.data, incrementalResult.data, 0.0f)
    }

    @Test
    fun `accumulator handles empty accumulation correctly`() {
        val canonicalWidth = 50
        val canonicalHeight = 50
        val channels = 1
        
        val accumulator = PredictionMapAccumulator(canonicalWidth, canonicalHeight, channels)
        val result = accumulator.finish()
        
        assertEquals(canonicalWidth, result.width)
        assertEquals(canonicalHeight, result.height)
        assertEquals(channels, result.channels)
        assertEquals(canonicalWidth * canonicalHeight * channels, result.data.size)
        assertArrayEquals(FloatArray(result.data.size), result.data, 0.0f)
    }
}
