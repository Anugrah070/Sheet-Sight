package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DewarpRemapperTest {

    @Test
    fun `identity coords reproduce the source exactly`() {
        // An interpolating cubic kernel has weight 1 at an exact-integer
        // offset and weight 0 at every other integer offset, so sampling
        // at coordsY=y should reproduce source[y] exactly.
        val width = 3
        val height = 5
        val source = FloatArray(width * height) { (it * 1.7f) }
        val coordsY = FloatArray(width * height) { i -> (i / width).toFloat() }

        val result = DewarpRemapper.remap(source, width, height, coordsY)

        for (i in source.indices) assertEquals(source[i], result[i], 1e-4f)
    }

    @Test
    fun `out-of-range coords clamp via border replicate`() {
        val width = 2
        val height = 4
        val source = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f) // rows: [1,2],[3,4],[5,6],[7,8]
        // Every output pixel asks for a wildly negative source row -> should replicate row 0.
        val coordsY = FloatArray(width * height) { -50f }

        val result = DewarpRemapper.remap(source, width, height, coordsY)

        assertEquals(1f, result[0], 1e-4f)
        assertEquals(2f, result[1], 1e-4f)
        // Every row's output should equal row 0's source values (replicated).
        for (y in 0 until height) {
            assertEquals(1f, result[y * width + 0], 1e-4f)
            assertEquals(2f, result[y * width + 1], 1e-4f)
        }
    }

    @Test
    fun `remapMask with identity coords preserves the mask exactly`() {
        val width = 4
        val height = 4
        val mask = BooleanArray(width * height) { it % 3 == 0 }
        val coordsY = FloatArray(width * height) { i -> (i / width).toFloat() }

        val result = DewarpRemapper.remapMask(mask, width, height, coordsY)

        assertEquals(mask.toList(), result.toList())
    }

    @Test
    fun `rejects mismatched array sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            DewarpRemapper.remap(FloatArray(4), width = 2, height = 2, coordsY = FloatArray(3))
        }
    }
}