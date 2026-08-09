package com.sheetsight.app.data.omr.symbol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalBarlineExtractorTest {

    @Test
    fun `a straight line cannot validate itself without model one symbol evidence`() {
        val width = 8
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        for (y in 1..6) stems[y * width + 2] = true

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            BooleanArray(width * height),
            width,
            height
        )

        assertTrue(selected.none { it })
    }

    @Test
    fun `only symbol components overlapping stem candidates survive`() {
        val width = 12
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        for (y in 1..6) stems[y * width + 2] = true
        for (y in 1..6) symbols[y * width + 2] = true
        symbols[3 * width + 8] = true
        symbols[3 * width + 9] = true

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(selected[4 * width + 2])
        assertFalse(selected[3 * width + 8])
    }

    @Test
    fun `note-group occupancy removes an otherwise valid stem component`() {
        val width = 8
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        for (y in 1..6) {
            val index = y * width + 2
            stems[index] = true
            symbols[index] = true
            groupMap[index] = 0
        }

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(selected.none { it })
    }

    @Test
    fun `busy claimed stems can leave only one stray component like oemer`() {
        val width = 40
        val height = 30
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        val claimedBarXs = listOf(5, 12, 19, 26)
        for (x in claimedBarXs + 35) {
            for (y in 3 until 27) {
                val index = y * width + x
                stems[index] = true
                symbols[index] = true
                if (x in claimedBarXs) groupMap[index] = 0
            }
        }

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(claimedBarXs.all { x -> (3 until 27).none { y -> selected[y * width + x] } })
        assertTrue((3 until 27).all { y -> selected[y * width + 35] })
    }
}
