package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Test

class StafflineGridGrouperTest {

    @Test
    fun `adjacent grids from neighboring strips merge into one group`() {
        // Reuses the two-strip detection from StafflineGridDetectorTest:
        // grid 0 = (0,0,11,3), grid 1 = (11,0,22,3) - touching at x=10|11.
        val mask = BooleanArray(22 * 5) { it / 22 < 3 }
        val detection = StafflineGridDetector.detectGrids(mask, width = 22, height = 5)

        val result = StafflineGridGrouper.groupGrids(detection, width = 22, height = 5)

        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(0, group.left)
        assertEquals(0, group.top)
        assertEquals(22, group.right)
        assertEquals(3, group.bottom)
        assertEquals(listOf(0, 1), group.gridIds)
    }

    @Test
    fun `spatially separate grids form separate groups`() {
        // One strip (width=11), two on-runs far apart in y with off rows
        // between and around them - never spatially touching.
        val width = 11
        val height = 20
        val mask = BooleanArray(width * height) { index ->
            val y = index / width
            y in 0..2 || y in 10..12
        }
        val detection = StafflineGridDetector.detectGrids(mask, width, height)

        val result = StafflineGridGrouper.groupGrids(detection, width, height)

        assertEquals(2, result.groups.size)
        assertEquals(setOf(listOf(0), listOf(1)), result.groups.map { it.gridIds }.toSet())
    }

    @Test
    fun `no grids at all produces no groups gracefully`() {
        val detection = StafflineGridDetector.detectGrids(BooleanArray(22 * 5), width = 22, height = 5)

        val result = StafflineGridGrouper.groupGrids(detection, width = 22, height = 5)

        assertEquals(emptyList<StafflineGridGroup>(), result.groups)
        assertEquals(true, result.groupMap.all { it == -1 })
    }
}