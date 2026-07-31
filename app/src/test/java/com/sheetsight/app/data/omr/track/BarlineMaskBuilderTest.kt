package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.inference.OmrClassMasks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarlineMaskBuilderTest {
    private fun masks(
        width: Int,
        height: Int,
        symbols: Set<Pair<Int, Int>> = emptySet(),
        stems: Set<Pair<Int, Int>> = emptySet(),
        noteheads: Set<Pair<Int, Int>> = emptySet(),
        clefs: Set<Pair<Int, Int>> = emptySet()
    ): OmrClassMasks {
        fun layer(points: Set<Pair<Int, Int>>) =
            BooleanArray(width * height) { index -> (index % width to index / width) in points }
        return OmrClassMasks(
            width,
            height,
            staff = BooleanArray(width * height),
            symbols = layer(symbols),
            stemsRests = layer(stems),
            noteheads = layer(noteheads),
            clefsKeys = layer(clefs)
        )
    }

    @Test
    fun `Hough input subtracts all detailed symbol classes`() {
        val input = masks(
            width = 5,
            height = 1,
            symbols = setOf(0 to 0, 1 to 0, 2 to 0, 3 to 0),
            stems = setOf(1 to 0),
            noteheads = setOf(2 to 0),
            clefs = setOf(3 to 0)
        )

        assertArrayEquals(
            booleanArrayOf(true, false, false, false, false),
            BarlineMaskBuilder.houghInput(input)
        )
    }

    @Test
    fun `unvalidated residual symbols are not admitted when Hough is empty`() {
        val input = masks(width = 9, height = 9, symbols = setOf(4 to 4))

        val result = BarlineMaskBuilder.build(input, acceptedLines = emptyList())

        assertFalse(result.any { it })
    }

    @Test
    fun `stems mask remains a barline source when Hough is empty`() {
        val stem = (2..6).map { 4 to it }.toSet()
        val input = masks(width = 9, height = 9, stems = stem)

        val result = BarlineMaskBuilder.build(input, acceptedLines = emptyList())

        assertTrue(result.any { it })
        // OpenCV's even-width kernel anchor shifts a close by one pixel right.
        assertTrue(result[4 * 9 + 5])
    }
}
