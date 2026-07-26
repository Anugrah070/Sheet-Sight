package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffGridValidatorTest {

    private fun mockStaff(yCenter: Double, unitSize: Double): ZoneStaff {
        // Create 5 lines centered around yCenter with unitSize spacing
        val lines = (0 until 5).map { i ->
            val offset = (i - 2) * unitSize
            Staffline(
                position = StafflinePosition.values()[i],
                points = listOf() // points not used by validator, only yCenter and unitSize of ZoneStaff
            )
        }
        // ZoneStaff.yCenter and unitSize are derived from lines.
        // But ZoneStaff yCenter is lines.sumOf { it.yCenter } / 5.
        // Wait, Staffline.yCenter is points.sumOf { it.y } / points.size.
        // Since I can't easily mock the lazy getters, I'll provide actual points.
        
        val staffLines = (0 until 5).map { i ->
            val y = (yCenter + (i - 2) * unitSize).toInt()
            Staffline(
                position = StafflinePosition.values()[i],
                points = listOf(com.sheetsight.app.data.omr.staffline.StafflinePoint(0, y))
            )
        }
        return ZoneStaff(staffLines)
    }

    private fun assigned(yCenter: Double, unitSize: Double, track: Int, group: Int): AssignedStaff {
        return AssignedStaff(mockStaff(yCenter, unitSize), track, group)
    }

    @Test
    fun `a consistent grid is left unchanged`() {
        val grid = listOf(
            listOf(assigned(100.0, 10.0, 0, 0)),
            listOf(assigned(100.0, 10.0, 0, 1))
        )
        
        val result = StaffGridValidator.validate(grid)
        
        assertEquals(grid, result)
    }

    @Test
    fun `a column with inconsistent y-centers is removed`() {
        // Two zones, one column.
        // Zone 0, Col 0: y=100
        // Zone 1, Col 0: y=120 (tolerance is 0.5 * 10 = 5)
        val grid = listOf(
            listOf(assigned(100.0, 10.0, 0, 0)),
            listOf(assigned(120.0, 10.0, 0, 1))
        )
        
        val result = StaffGridValidator.validate(grid)
        
        assertEquals(listOf(emptyList<AssignedStaff>(), emptyList<AssignedStaff>()), result)
    }

    @Test
    fun `a column with inconsistent unit sizes is removed`() {
        // Tolerance ratio 0.15. Mean = 15. diff = 5. 5/15 = 0.33 > 0.15.
        val grid = listOf(
            listOf(assigned(100.0, 10.0, 0, 0)),
            listOf(assigned(100.0, 20.0, 0, 1))
        )
        
        val result = StaffGridValidator.validate(grid)
        
        assertEquals(listOf(emptyList<AssignedStaff>(), emptyList<AssignedStaff>()), result)
    }

    @Test
    fun `mixed valid and invalid columns`() {
        val grid = listOf(
            listOf(assigned(100.0, 10.0, 0, 0), assigned(200.0, 10.0, 1, 0)),
            listOf(assigned(100.0, 10.0, 0, 1), assigned(250.0, 10.0, 1, 1))
        )
        // Col 0: valid (y centers 100, 100)
        // Col 1: invalid (y centers 200, 250)
        
        val result = StaffGridValidator.validate(grid)
        
        assertEquals(2, result.size)
        assertEquals(1, result[0].size)
        assertEquals(1, result[1].size)
        assertEquals(100.0, result[0][0].staff.yCenter, 0.1)
        assertEquals(100.0, result[1][0].staff.yCenter, 0.1)
    }
}
