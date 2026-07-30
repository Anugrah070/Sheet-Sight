package com.sheetsight.app.data.omr.staffline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ZoneStafflineExtractor] over synthetic full-width staff
 * masks. Expected peak positions were cross-checked against a scipy
 * simulation of the same histogram -> z-score -> find_peaks pipeline.
 */
class ZoneStafflineExtractorTest {

    private val width = 30
    private val height = 140

    /** width x height mask with 1px-thick horizontal lines at [centers] spanning columns [cols). */
    private fun staffMask(centers: List<Int>, cols: IntRange = 0 until width): BooleanArray {
        val mask = BooleanArray(width * height)
        for (c in centers) for (x in cols) mask[c * width + x] = true
        return mask
    }

    @Test
    fun `detects a clean five-line staff and labels lines top to bottom`() {
        val centers = listOf(40, 52, 64, 76, 88)
        val result = ZoneStafflineExtractor.extract(staffMask(centers), width, height, 0, width)

        assertEquals(1, result.staffs.size)
        val staff = result.staffs.single()
        assertEquals(
            listOf(
                StafflinePosition.FIRST, StafflinePosition.SECOND, StafflinePosition.THIRD,
                StafflinePosition.FOURTH, StafflinePosition.FIFTH
            ),
            staff.lines.map { it.position }
        )
        // FIRST = top (smallest y) .. FIFTH = bottom (largest y), matching oemer's LineLabel.
        assertEquals(listOf(40.0, 52.0, 64.0, 76.0, 88.0), staff.lines.map { it.yCenter })
        assertEquals(12.0, staff.unitSize, 1e-9)
    }

    @Test
    fun `FIRST is always the topmost line and FIFTH is always the bottommost, ascending y`() {
        val centers = listOf(10, 30, 50, 75, 100) // irregular but ascending, fits one group
        val result = ZoneStafflineExtractor.extract(staffMask(centers), width, height, 0, width)

        assertEquals(1, result.staffs.size)
        val lines = result.staffs.single().lines

        assertEquals(StafflinePosition.FIRST, lines.first().position)
        assertEquals(StafflinePosition.FIFTH, lines.last().position)
        assertEquals(10.0, lines.first().yCenter, 1e-9) // FIRST = smallest y = topmost
        assertEquals(100.0, lines.last().yCenter, 1e-9) // FIFTH = largest y = bottommost

        // Every consecutive pair must be strictly ascending in y: FIRST..FIFTH tracks image row order.
        val yCenters = lines.map { it.yCenter }
        for (i in 1 until yCenters.size) {
            assertTrue("line $i (${yCenters[i]}) must be below line ${i - 1} (${yCenters[i - 1]})", yCenters[i] > yCenters[i - 1])
        }
    }

    @Test
    fun `drops a group with fewer than five lines`() {
        val result = ZoneStafflineExtractor.extract(staffMask(listOf(40, 52, 64, 76)), width, height, 0, width)
        assertTrue(result.staffs.isEmpty())
    }

    @Test
    fun `trims a group with more than five lines to the stronger head-or-tail five`() {
        // Seven equal-strength lines -> head five wins the tie (>=), so indices are 28, 40, 52, 64, 76.
        val centers = listOf(28, 40, 52, 64, 76, 88, 100)
        val result = ZoneStafflineExtractor.extract(staffMask(centers), width, height, 0, width)

        assertEquals(1, result.staffs.size)
        val staff = result.staffs.single()
        assertEquals(5, staff.lines.size)
        // Head five (rows 28..76) win the tie; FIRST=top of that set (28), FIFTH=bottom (76).
        assertEquals(28.0, staff.lines.first().yCenter, 1e-9)
        assertEquals(76.0, staff.lines.last().yCenter, 1e-9)
    }

    @Test
    fun `a blank zone yields no staff (zero-variance degradation path)`() {
        val result = ZoneStafflineExtractor.extract(BooleanArray(width * height), width, height, 0, width)
        assertTrue(result.staffs.isEmpty())
    }

    @Test
    fun `respects the zone column range`() {
        // Lines painted only in the left half; the right-half zone sees a blank profile.
        val mask = staffMask(listOf(40, 52, 64, 76, 88), cols = 0 until 15)
        val result = ZoneStafflineExtractor.extract(mask, width, height, 15, width)
        assertTrue(result.staffs.isEmpty())
    }
}