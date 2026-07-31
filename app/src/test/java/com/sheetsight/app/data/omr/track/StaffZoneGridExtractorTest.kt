package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffZoneGridExtractorTest {
    @Test
    fun `full-width staff is extracted into eight aligned zones`() {
        val width = 80
        val height = 80
        val staffRows = setOf(20, 30, 40, 50, 60)
        val mask = BooleanArray(width * height) { index ->
            index / width in staffRows
        }

        val result = StaffZoneGridExtractor.extract(mask, width, height)

        assertEquals(8, result.size)
        assertEquals(List(8) { 1 }, result.map { it.size })
        result.forEach { zone ->
            assertEquals(40.0, zone.single().yCenter, 0.01)
            assertEquals(10.0, zone.single().unitSize, 0.01)
        }
    }
}
