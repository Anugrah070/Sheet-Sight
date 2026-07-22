package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Test

class StafflineGridBridgerTest {

    @Test
    fun `no groups passes through unchanged`() {
        val groupMap = IntArray(10) { -1 }
        val gridMap = IntArray(10) { -1 }

        val result = StafflineGridBridger.bridge(groupMap, emptyList(), gridMap, emptyList(), width = 5, height = 2)

        assertEquals(groupMap.toList(), result.groupMap.toList())
        assertEquals(emptyList<StafflineGridGroup>(), result.groups)
        assertEquals(emptyList<StafflineGrid>(), result.grids)
    }

    @Test
    fun `single-grid groups are left untouched (ref_gids below 2 guard)`() {
        // Two isolated single-grid groups, far apart - reproduces the
        // Phase 4.5C "spatially separate grids" scenario. Neither group
        // has 2+ grids to fit a regression line through, so bridging must
        // be a no-op for both.
        val width = 11
        val height = 20
        val mask = BooleanArray(width * height) { index ->
            val y = index / width
            y in 0..2 || y in 10..12
        }
        val detection = StafflineGridDetector.detectGrids(mask, width, height)
        val grouping = StafflineGridGrouper.groupGrids(detection, width, height)
        check(grouping.groups.size == 2) { "test setup assumption violated" }
        check(grouping.groups.all { it.gridIds.size == 1 }) { "test setup assumption violated" }

        val result = StafflineGridBridger.bridge(
            grouping.groupMap, grouping.groups, detection.gridMap, detection.grids, width, height
        )

        assertEquals(2, result.groups.size)
        assertEquals(detection.grids, result.grids) // no synthetic grids added
        assertEquals(
            grouping.groups.map { it.gridIds }.toSet(),
            result.groups.map { it.gridIds }.toSet()
        )
    }
}