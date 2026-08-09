package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
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

    @Test
    fun `two nuisance peaks do not discard the upper row of a grand staff`() {
        val width = 80
        val height = 190
        val staffRows = setOf(
            // Upper row: one close nuisance peak followed by 13 px spacing.
            20, 28, 41, 54, 67, 80,
            // Lower row: one close nuisance peak followed by 10 px spacing.
            112, 120, 130, 140, 150, 160
        )
        val mask = BooleanArray(width * height) { index ->
            index / width in staffRows
        }

        val result = StaffZoneGridExtractor.extract(mask, width, height)

        // Golden-checked by executing oemer 0.1.8
        // staffline_extraction.py::extract_line/filter_line_peaks against
        // the same row-density fixture: both five-line staffs survive.
        assertEquals(8, result.size)
        assertEquals(List(8) { 2 }, result.map { it.size })
        assertEquals(listOf(54.0, 140.0), result.first().map { it.yCenter })
        assertEquals(listOf(13.0, 10.0), result.first().map { it.unitSize })
    }

    @Test
    fun `a missing staff in one zone cannot duplicate and shift later rows`() {
        val fullLeft = listOf(staff(20, 0), staff(50, 0), staff(110, 0), staff(140, 0))
        val missingUpper = listOf(staff(50, 20), staff(110, 20), staff(140, 20))
        val fullRight = listOf(staff(20, 40), staff(50, 40), staff(110, 40), staff(140, 40))

        val result = StaffZoneGridExtractor.align(listOf(fullLeft, missingUpper, fullRight))

        assertEquals(listOf(20.0, 50.0, 110.0, 140.0), result[1].map { it.yCenter })
        assertEquals(4, result[1].map { it.yCenter }.distinct().size)
        assertEquals(20, result[1][0].lines.first().xCenter.toInt())
    }

    private fun staff(center: Int, x: Int): ZoneStaff = ZoneStaff(
        StafflinePosition.entries.mapIndexed { index, position ->
            Staffline(position, listOf(StafflinePoint(x, center + (index - 2) * 4)))
        }
    )
}
