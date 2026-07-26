package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class NearestStaffUnitSizeResolverTest {

    @Test
    fun `resolves the nearest of a 3-staff synthetic grid by squared distance`() {
        val grid = listOf(
            listOf(
                StaffCenterInfo(xCenter = 0.0, yCenter = 0.0, unitSize = 10.0),
                StaffCenterInfo(xCenter = 100.0, yCenter = 0.0, unitSize = 20.0),
                StaffCenterInfo(xCenter = 200.0, yCenter = 0.0, unitSize = 30.0)
            )
        )

        // Closest to the second staff (100,0).
        val result = NearestStaffUnitSizeResolver.resolve(grid, x = 90.0, y = 0.0)

        assertEquals(20.0, result, 1e-9)
    }

    @Test
    fun `an exact tie resolves to the first-encountered staff in flatten order`() {
        // Staff A at (0,0), Staff B at (10,0); query at (5,0) -> both distSq = 25, an exact tie.
        val grid = listOf(
            listOf(
                StaffCenterInfo(xCenter = 0.0, yCenter = 0.0, unitSize = 11.0),  // A: appears first
                StaffCenterInfo(xCenter = 10.0, yCenter = 0.0, unitSize = 22.0)  // B: appears second
            )
        )

        val result = NearestStaffUnitSizeResolver.resolve(grid, x = 5.0, y = 0.0)

        // Stable sort keeps A ahead of B on an exact tie -> A's unit_size wins.
        assertEquals(11.0, result, 1e-9)
    }

    @Test
    fun `tie-break is row-major, not just within-row - an earlier row wins over a later one`() {
        // Row 0 has one staff; row 1 has one staff; both exactly equidistant from the query.
        // Row-major flatten order visits row 0 before row 1, so row 0's staff must win.
        val grid = listOf(
            listOf(StaffCenterInfo(xCenter = 0.0, yCenter = 0.0, unitSize = 111.0)),   // row 0
            listOf(StaffCenterInfo(xCenter = 10.0, yCenter = 0.0, unitSize = 222.0))   // row 1
        )

        val result = NearestStaffUnitSizeResolver.resolve(grid, x = 5.0, y = 0.0)

        assertEquals(111.0, result, 1e-9)
    }

    @Test
    fun `reversing which row appears first flips which tied staff wins`() {
        // Same two staffs as above, but row order swapped - row-major order now
        // visits the (10,0) staff first, confirming the tie-break truly follows
        // flatten order rather than some other implicit rule (e.g. smallest coordinate).
        val grid = listOf(
            listOf(StaffCenterInfo(xCenter = 10.0, yCenter = 0.0, unitSize = 222.0)), // row 0 now
            listOf(StaffCenterInfo(xCenter = 0.0, yCenter = 0.0, unitSize = 111.0))    // row 1 now
        )

        val result = NearestStaffUnitSizeResolver.resolve(grid, x = 5.0, y = 0.0)

        assertEquals(222.0, result, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an entirely empty staff grid`() {
        NearestStaffUnitSizeResolver.resolve(emptyList(), x = 0.0, y = 0.0)
    }
}