package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class BarlineCandidateFilterTest {

    // --- getDegree ---

    @Test
    fun `a perfectly horizontal segment (dy=0) is 0 degrees`() {
        val line = HoughLine(topX = 0, topY = 5, btX = 20, btY = 5)
        assertEquals(0.0, BarlineCandidateFilter.getDegree(line), 1e-9)
    }

    @Test
    fun `a perfectly vertical segment (dx=0) is 90 degrees`() {
        val line = HoughLine(topX = 5, topY = 0, btX = 5, btY = 20)
        assertEquals(90.0, BarlineCandidateFilter.getDegree(line), 1e-9)
    }

    @Test
    fun `an equal dx dy segment is 45 degrees`() {
        val line = HoughLine(topX = 0, topY = 0, btX = 10, btY = 10)
        assertEquals(45.0, BarlineCandidateFilter.getDegree(line), 1e-9)
    }

    // --- angle filtering: near-horizontal vs near-vertical, and the exact boundary ---

    private val wideOpenBounds = listOf(StaffBounds(yUpper = 0, yLower = 1000, xLeft = 0, xRight = 1000))

    @Test
    fun `a near-horizontal line is rejected at the default min degree`() {
        // dx=100, dy=5 -> ~2.86 degrees, well under the default 75.
        val line = HoughLine(topX = 0, topY = 0, btX = 100, btY = 5)
        val result = BarlineCandidateFilter.filterLines(listOf(line), wideOpenBounds)
        assertEquals(emptyList<HoughLine>(), result)
    }

    @Test
    fun `a near-vertical line is accepted at the default min degree`() {
        // dx=5, dy=100 -> ~87.1 degrees, comfortably over the default 75.
        val line = HoughLine(topX = 0, topY = 0, btX = 5, btY = 100)
        val result = BarlineCandidateFilter.filterLines(listOf(line), wideOpenBounds)
        assertEquals(listOf(line), result)
    }

    @Test
    fun `a line whose degree exactly equals min degree is kept, not rejected`() {
        // dx=dy=10 -> exactly 45 degrees. minDegree=45 -> "degree < min_degree" is
        // false at equality, so oemer's strict-less-than rejection keeps it.
        val line = HoughLine(topX = 0, topY = 0, btX = 10, btY = 10)
        val result = BarlineCandidateFilter.filterLines(listOf(line), wideOpenBounds, minDegree = 45)
        assertEquals(listOf(line), result)
    }

    @Test
    fun `a line one degree under min degree is rejected`() {
        // Same 45-degree line; minDegree=46 -> 45 < 46 is true -> rejected.
        val line = HoughLine(topX = 0, topY = 0, btX = 10, btY = 10)
        val result = BarlineCandidateFilter.filterLines(listOf(line), wideOpenBounds, minDegree = 46)
        assertEquals(emptyList<HoughLine>(), result)
    }

    // --- position filtering against a hand-built staff-grid envelope ---
    // minDegree=0 bypasses angle filtering entirely (a HoughLine's degree is
    // always >= 0, and 0 < 0 is false), isolating the position check.

    private val handBuiltBounds = listOf(
        StaffBounds(yUpper = 100, yLower = 100, xLeft = 50, xRight = 50), // contributes min_y=100, min_x=50
        StaffBounds(yUpper = 300, yLower = 300, xLeft = 250, xRight = 250) // contributes max_y=300, max_x=250
    )
    // Combined envelope: min_y=100, max_y=300, min_x=50, max_x=250.

    @Test
    fun `a line exactly flush against every envelope edge is accepted`() {
        val line = HoughLine(topX = 50, topY = 100, btX = 250, btY = 300)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(listOf(line), result)
    }

    @Test
    fun `topY one unit above min_y is rejected`() {
        val line = HoughLine(topX = 50, topY = 99, btX = 250, btY = 300)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(emptyList<HoughLine>(), result)
    }

    @Test
    fun `btY one unit below max_y is still accepted (only exceeding max_y rejects)`() {
        val line = HoughLine(topX = 50, topY = 100, btX = 250, btY = 299)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(listOf(line), result)
    }

    @Test
    fun `btY one unit past max_y is rejected`() {
        val line = HoughLine(topX = 50, topY = 100, btX = 250, btY = 301)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(emptyList<HoughLine>(), result)
    }

    @Test
    fun `topX one unit left of min_x is rejected`() {
        val line = HoughLine(topX = 49, topY = 100, btX = 250, btY = 300)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(emptyList<HoughLine>(), result)
    }

    @Test
    fun `btX one unit past max_x is rejected`() {
        val line = HoughLine(topX = 50, topY = 100, btX = 251, btY = 300)
        val result = BarlineCandidateFilter.filterLines(listOf(line), handBuiltBounds, minDegree = 0)
        assertEquals(emptyList<HoughLine>(), result)
    }

    @Test
    fun `an empty staff grid falls back to source's sentinel envelope, rejecting ordinary lines`() {
        // Mirrors oemer's un-special-cased sentinel init (min=9999999, max=0):
        // with no staffs, max_y=max_x=0, so any line with a positive btY/btX
        // (the ordinary case) fails the position check.
        val line = HoughLine(topX = 1, topY = 1, btX = 5, btY = 5)
        val result = BarlineCandidateFilter.filterLines(listOf(line), emptyList(), minDegree = 0)
        assertEquals(emptyList<HoughLine>(), result)
    }
}