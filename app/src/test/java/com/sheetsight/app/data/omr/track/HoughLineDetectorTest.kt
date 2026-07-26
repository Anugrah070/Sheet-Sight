package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class HoughLineDetectorTest {

    @Test
    fun `endpoints already ascending in both axes are left unchanged`() {
        val line = HoughLineDetector.reorderEndpoints(x1 = 2, y1 = 3, x2 = 10, y2 = 15)
        assertEquals(HoughLine(topX = 2, topY = 3, btX = 10, btY = 15), line)
    }

    @Test
    fun `the same segment detected in the opposite direction reorders back to ascending`() {
        val line = HoughLineDetector.reorderEndpoints(x1 = 10, y1 = 15, x2 = 2, y2 = 3)
        assertEquals(HoughLine(topX = 2, topY = 3, btX = 10, btY = 15), line)
    }

    @Test
    fun `x ascending but y descending are reordered independently, not as a real endpoint pair`() {
        // Real segment runs (2,15) -> (10,3): x increases, y decreases.
        // oemer's per-axis reorder yields (2,3)-(10,15), which is neither real endpoint -
        // the documented, unusual, non-endpoint-preserving behavior being ported faithfully.
        val line = HoughLineDetector.reorderEndpoints(x1 = 2, y1 = 15, x2 = 10, y2 = 3)
        assertEquals(HoughLine(topX = 2, topY = 3, btX = 10, btY = 15), line)
    }

    @Test
    fun `mirrored raw order for the same non-monotonic segment reorders the same way`() {
        val line = HoughLineDetector.reorderEndpoints(x1 = 10, y1 = 3, x2 = 2, y2 = 15)
        assertEquals(HoughLine(topX = 2, topY = 3, btX = 10, btY = 15), line)
    }

    @Test
    fun `a vertical segment (equal x) still reorders y independently`() {
        val line = HoughLineDetector.reorderEndpoints(x1 = 5, y1 = 20, x2 = 5, y2 = 8)
        assertEquals(HoughLine(topX = 5, topY = 8, btX = 5, btY = 20), line)
    }

    @Test
    fun `a horizontal segment (equal y) still reorders x independently`() {
        val line = HoughLineDetector.reorderEndpoints(x1 = 20, y1 = 5, x2 = 8, y2 = 5)
        assertEquals(HoughLine(topX = 8, topY = 5, btX = 20, btY = 5), line)
    }
}