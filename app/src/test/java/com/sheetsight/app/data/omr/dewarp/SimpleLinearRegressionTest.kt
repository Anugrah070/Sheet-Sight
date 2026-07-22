package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SimpleLinearRegressionTest {

    @Test
    fun `fits a perfect line exactly`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(3.0, 5.0, 7.0, 9.0) // y = 2x + 3

        val model = SimpleLinearRegression.fit(xs, ys)

        assertEquals(3.0, model.predict(0.0), 1e-9)
        assertEquals(11.0, model.predict(4.0), 1e-9)
        assertEquals(-1.0, model.predict(-2.0), 1e-9)
    }

    @Test
    fun `identical x values fit a flat line at the mean y`() {
        val xs = doubleArrayOf(5.0, 5.0, 5.0)
        val ys = doubleArrayOf(1.0, 2.0, 3.0)

        val model = SimpleLinearRegression.fit(xs, ys)

        assertEquals(2.0, model.predict(100.0), 1e-9) // flat: same prediction everywhere
        assertEquals(2.0, model.predict(-100.0), 1e-9)
    }

    @Test
    fun `requires at least two points`() {
        assertThrows(IllegalArgumentException::class.java) {
            SimpleLinearRegression.fit(doubleArrayOf(1.0), doubleArrayOf(1.0))
        }
    }
}