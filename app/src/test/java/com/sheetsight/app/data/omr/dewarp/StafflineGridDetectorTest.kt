package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StafflineGridDetectorTest {

    /** width x height mask, "on" for every column in rows [0, onRows). */
    private fun topRowsOnMask(width: Int, height: Int, onRows: Int): BooleanArray =
        BooleanArray(width * height) { it / width < onRows }

    @Test
    fun `two strips of a thin full-width run each register one grid`() {
        // width=22 -> two 11-wide strips. Rows 0-2 on (height 3, below the
        // splitUnit=11 registration threshold), rows 3-4 off.
        val mask = topRowsOnMask(width = 22, height = 5, onRows = 3)

        val result = StafflineGridDetector.detectGrids(mask, width = 22, height = 5)

        assertEquals(2, result.grids.size)
        assertEquals(StafflineGrid(id = 0, left = 0, top = 0, right = 11, bottom = 3), result.grids[0])
        assertEquals(StafflineGrid(id = 1, left = 11, top = 0, right = 22, bottom = 3), result.grids[1])
        // Rows 0-2 mapped to their grid id; rows 3-4 unassigned.
        assertEquals(0, result.gridMap[0 * 22 + 0])
        assertEquals(1, result.gridMap[0 * 22 + 11])
        assertEquals(-1, result.gridMap[3 * 22 + 0])
    }

    @Test
    fun `a blank mask registers no grids`() {
        val mask = BooleanArray(22 * 5)

        val result = StafflineGridDetector.detectGrids(mask, width = 22, height = 5)

        assertEquals(emptyList<StafflineGrid>(), result.grids)
        assertEquals(true, result.gridMap.all { it == -1 })
    }

    @Test
    fun `a solid block taller than the split unit is not registered as a grid`() {
        // A 13-row-tall on-block (>= splitUnit=11) looks like a barline or
        // beam, not a staffline segment, and must be filtered out.
        val mask = topRowsOnMask(width = 11, height = 15, onRows = 13)

        val result = StafflineGridDetector.detectGrids(mask, width = 11, height = 15)

        assertEquals(emptyList<StafflineGrid>(), result.grids)
    }

    @Test
    fun `rejects a mask whose size does not match width times height`() {
        assertThrows(IllegalArgumentException::class.java) {
            StafflineGridDetector.detectGrids(BooleanArray(5), width = 3, height = 3)
        }
    }
}