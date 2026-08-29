package com.sheetsight.app.data.omr.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class OmrAccuracyMetricsTest {
    @Test
    fun `one-to-one matching reports precision recall and rest type separately`() {
        val expected = listOf(
            OmrLocatedDetection(100, 50, "QUARTER", group = 0),
            OmrLocatedDetection(200, 50, "EIGHTH", group = 0)
        )
        val actual = listOf(
            OmrLocatedDetection(102, 51, "QUARTER", group = 0),
            OmrLocatedDetection(198, 50, "SIXTEENTH", group = 0),
            OmrLocatedDetection(280, 50, "QUARTER", group = 0)
        )

        val metrics = OmrAccuracyMetrics.labeled(
            expected,
            actual,
            staffSpacing = 10.0,
            toleranceInStaffSpaces = 0.5
        )

        assertEquals(2, metrics.detection.truePositives)
        assertEquals(1, metrics.detection.falsePositives)
        assertEquals(0, metrics.detection.falseNegatives)
        assertEquals(2.0 / 3.0, metrics.detection.precision, 0.0001)
        assertEquals(1.0, metrics.detection.recall, 0.0001)
        assertEquals(0.8, metrics.detection.f1, 0.0001)
        assertEquals(0.5, metrics.typeAccuracy, 0.0001)
    }

    @Test
    fun `matching never crosses an annotated system or staff`() {
        val expected = listOf(OmrLocatedDetection(100, 50, "SOLID", group = 2, track = 0))
        val actual = listOf(
            OmrLocatedDetection(100, 50, "SOLID", group = 3, track = 0),
            OmrLocatedDetection(101, 50, "SOLID", group = 2, track = 1)
        )

        val metrics = OmrAccuracyMetrics.detection(
            expected,
            actual,
            staffSpacing = 10.0,
            toleranceInStaffSpaces = 0.5
        )

        assertEquals(0, metrics.truePositives)
        assertEquals(2, metrics.falsePositives)
        assertEquals(1, metrics.falseNegatives)
    }

    @Test
    fun `dense matching maximizes true positives before minimizing distance`() {
        val expected = listOf(
            OmrLocatedDetection(0, 0, "SOLID", group = 0, track = 0),
            OmrLocatedDetection(4, 0, "SOLID", group = 0, track = 0)
        )
        val actual = listOf(
            // Equally close to both references. A nearest-edge greedy pass
            // consumes it for expected[0] and strands expected[1].
            OmrLocatedDetection(2, 0, "SOLID", group = 0, track = 0),
            // Compatible only with expected[0] at the five-pixel tolerance.
            OmrLocatedDetection(-3, 0, "SOLID", group = 0, track = 0)
        )

        val metrics = OmrAccuracyMetrics.detection(
            expected,
            actual,
            staffSpacing = 10.0,
            toleranceInStaffSpaces = 0.5
        )

        assertEquals(2, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(0, metrics.falseNegatives)
    }
}
