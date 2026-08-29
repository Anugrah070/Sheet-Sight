package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffGeometryResolverTest {
    @Test
    fun `pitch position follows the staff slope at note x`() {
        val staff = assignedStaff(
            leftX = 0,
            rightX = 100,
            leftTopY = 40,
            rightTopY = 60,
            track = 0,
            group = 0
        )

        val assignment = StaffGeometryResolver.assignNote(listOf(listOf(staff)), x = 100, y = 100)

        assertEquals(0, assignment.staff.track)
        assertEquals(0, assignment.staff.group)
        assertEquals(1, assignment.staffLinePosition)
        assertEquals(10.0, assignment.localUnitSize, 0.001)
    }

    @Test
    fun `x-local zone geometry preserves the source system`() {
        val system0Left = assignedStaff(0, 49, 40, 40, track = 0, group = 0)
        val system0Right = assignedStaff(50, 100, 70, 70, track = 0, group = 0)
        val system1Left = assignedStaff(0, 49, 160, 160, track = 0, group = 1)
        val system1Right = assignedStaff(50, 100, 190, 190, track = 0, group = 1)

        val assignment = StaffGeometryResolver.assignNote(
            listOf(
                listOf(system0Left, system1Left),
                listOf(system0Right, system1Right)
            ),
            x = 90,
            y = 95
        )

        assertEquals(0, assignment.staff.group)
        assertEquals(4, assignment.staffLinePosition)
    }

    @Test
    fun `unit interpolation weights the nearer staff more strongly`() {
        val upper = assignedStaff(0, 100, 20, 20, track = 0, group = 0, unit = 10)
        val lower = assignedStaff(0, 100, 100, 100, track = 1, group = 0, unit = 20)

        val unit = StaffGeometryResolver.unitSizeAt(listOf(listOf(upper, lower)), x = 50, y = 70)

        assertEquals(13.0, unit, 0.001)
    }

    private fun assignedStaff(
        leftX: Int,
        rightX: Int,
        leftTopY: Int,
        rightTopY: Int,
        track: Int,
        group: Int,
        unit: Int = 10
    ): AssignedStaff = AssignedStaff(
        staff = ZoneStaff(
            StafflinePosition.entries.mapIndexed { index, position ->
                Staffline(
                    position,
                    listOf(
                        StafflinePoint(leftX, leftTopY + index * unit),
                        StafflinePoint(rightX, rightTopY + index * unit)
                    )
                )
            }
        ),
        track = track,
        group = group
    )
}
