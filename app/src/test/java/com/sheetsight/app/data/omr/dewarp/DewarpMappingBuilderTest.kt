package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DewarpMappingBuilderTest {

    @Test
    fun `a wide flat group yields periodic control points plus boundary rows`() {
        val width = 20
        val height = 10
        val groupMap = IntArray(width * height) { -1 }
        // Wide group: rows 3-5, every column (width 20 >= 0.4*20=8, passes filter).
        for (y in 3..5) for (x in 0 until width) groupMap[y * width + x] = 5
        // Narrow group: row 7, columns 0-2 only (width 3 < 8, filtered out).
        for (x in 0..2) groupMap[7 * width + x] = 9

        val points = DewarpMappingBuilder.build(groupMap, width, height)

        // From the wide group: columns 0 and 10 (period=10), targetRow = mean(3,4,5) = 4.
        val fromWideGroup = points.filter { it.targetRow == 4 }
        assertEquals(setOf(0, 10), fromWideGroup.map { it.column }.toSet())
        assertTrue(fromWideGroup.all { it.sourceRow == 4 })

        // Boundary rows: row 0 and row (height-1)=9 at every column.
        assertEquals(width, points.count { it.targetRow == 0 })
        assertEquals(width, points.count { it.targetRow == height - 1 })

        // Nothing from the filtered-out narrow group.
        assertTrue(points.none { it.targetRow == 7 })
    }

    @Test
    fun `no groups at all still yields the boundary rows`() {
        val width = 5
        val height = 4
        val groupMap = IntArray(width * height) { -1 }

        val points = DewarpMappingBuilder.build(groupMap, width, height)

        assertEquals(width * 2, points.size)
        assertTrue(points.all { it.targetRow == 0 || it.targetRow == height - 1 })
    }

    @Test
    fun `zero-size input yields no control points without crashing`() {
        val points = DewarpMappingBuilder.build(IntArray(0), width = 0, height = 0)

        assertEquals(emptyList<DewarpControlPoint>(), points)
    }
}
