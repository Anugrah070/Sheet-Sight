package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffTrackGroupAssignerTest {
    private fun staff(center: Int): ZoneStaff =
        ZoneStaff(
            StafflinePosition.entries.mapIndexed { index, position ->
                Staffline(position, listOf(StafflinePoint(0, center + index - 2)))
            }
        )

    @Test
    fun `track and group numbering restarts for every horizontal zone`() {
        val zone = listOf(staff(20), staff(40), staff(60))

        val result = StaffTrackGroupAssigner.assign(listOf(zone, zone), numTrack = 2)

        assertEquals(listOf(0, 1, 0), result[0].map { it.track })
        assertEquals(listOf(0, 1, 0), result[1].map { it.track })
        assertEquals(listOf(0, 0, 1), result[0].map { it.group })
        assertEquals(listOf(0, 0, 1), result[1].map { it.group })
    }

    @Test
    fun `globally missing fourth-system treble does not invert the final system`() {
        val centers = listOf(320, 490, 700, 850, 1070, 1210, 1570, 1770, 1930)
        val zones = List(3) { zone -> centers.map { staff(it, zone * 100) } }

        val result = StaffTrackGroupAssigner.assign(zones, numTrack = 2)

        assertEquals(10, result[0].size)
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1), result[0].map { it.track })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4), result[0].map { it.group })
        assertEquals(
            listOf(false, false, false, false, false, false, true, false, false, false),
            result[0].map { it.isInterpolated }
        )
        assertEquals(1418.0, result[0][6].staff.yCenter, 0.1)
    }

    @Test
    fun `ambiguous final missing bass keeps existing row identities`() {
        val centers = listOf(80, 170, 340, 430, 610, 700, 880)
        val zones = List(3) { zone -> centers.map { staff(it, zone * 100) } }

        val result = StaffTrackGroupAssigner.assign(zones, numTrack = 2)

        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), result[0].map { it.track })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3), result[0].map { it.group })
    }

    private fun staff(center: Int, x: Int): ZoneStaff =
        ZoneStaff(
            StafflinePosition.entries.mapIndexed { index, position ->
                Staffline(position, listOf(StafflinePoint(x, center + (index - 2) * 10)))
            }
        )
}
