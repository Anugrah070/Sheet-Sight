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
}
