package com.sheetsight.app.data.omr.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class SlidingWindowTilerTest {

    @Test
    fun `evenly divisible span produces stride-spaced origins`() {
        val origins = SlidingWindowTiler.computeOrigins(
                width = 512, height = 256, windowSize = 256, stepSize = 128
        )
        val xs = origins.map { it.first }.distinct().sorted()
        assertEquals(listOf(0, 128, 256), xs)
    }

    @Test
    fun `last window clamps flush against the edge instead of padding`() {
        val origins = SlidingWindowTiler.computeOrigins(
                width = 300, height = 256, windowSize = 256, stepSize = 128
        )
        val xs = origins.map { it.first }.distinct().sorted()
        // 300 - 256 = 44: the clamped last origin, not a padded 256..300 window.
        assertEquals(listOf(0, 44), xs)
    }

    @Test
    fun `origins are produced in row-major y-outer x-inner order`() {
        val origins = SlidingWindowTiler.computeOrigins(
                width = 300, height = 300, windowSize = 256, stepSize = 128
        )
        val ys = origins.map { it.second }
        assertEquals(ys.sorted(), ys) // y never decreases while walking the list
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source smaller than window size is rejected by the pure coordinate function`() {
        SlidingWindowTiler.computeOrigins(width = 100, height = 100, windowSize = 256, stepSize = 128)
    }
}
