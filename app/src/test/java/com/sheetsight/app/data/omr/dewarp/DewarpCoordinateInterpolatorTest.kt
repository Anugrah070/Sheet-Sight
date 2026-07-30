package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Test

class DewarpCoordinateInterpolatorTest {

    @Test
    fun `no control points produces an identity mapping`() {
        val coordsY = DewarpCoordinateInterpolator.interpolate(emptyList(), width = 3, height = 5)

        for (y in 0 until 5) for (x in 0 until 3) {
            assertEquals(y.toFloat(), coordsY[y * 3 + x], 1e-6f)
        }
    }

    @Test
    fun `only boundary points also produces an identity mapping`() {
        val width = 1
        val height = 5
        val points = (0 until width).flatMap { x ->
            listOf(
                DewarpControlPoint(targetRow = 0, column = x, sourceRow = 0),
                DewarpControlPoint(targetRow = height - 1, column = x, sourceRow = height - 1)
            )
        }

        val coordsY = DewarpCoordinateInterpolator.interpolate(points, width, height)

        for (y in 0 until height) assertEquals(y.toFloat(), coordsY[y], 1e-6f)
    }

    @Test
    fun `interpolates linearly between an internal control row and the boundaries`() {
        // width=1, single column. row0->source0, row2->source5, row4->source4.
        val points = listOf(
            DewarpControlPoint(targetRow = 0, column = 0, sourceRow = 0),
            DewarpControlPoint(targetRow = 2, column = 0, sourceRow = 5),
            DewarpControlPoint(targetRow = 4, column = 0, sourceRow = 4)
        )

        val coordsY = DewarpCoordinateInterpolator.interpolate(points, width = 1, height = 5)

        assertEquals(0f, coordsY[0], 1e-6f)
        assertEquals(2.5f, coordsY[1], 1e-6f)  // halfway between row0(0) and row2(5)
        assertEquals(5f, coordsY[2], 1e-6f)
        assertEquals(4.5f, coordsY[3], 1e-6f)  // halfway between row2(5) and row4(4)
        assertEquals(4f, coordsY[4], 1e-6f)
    }
}