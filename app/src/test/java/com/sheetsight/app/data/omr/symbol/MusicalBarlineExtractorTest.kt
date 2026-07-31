package com.sheetsight.app.data.omr.symbol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalBarlineExtractorTest {

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
}
