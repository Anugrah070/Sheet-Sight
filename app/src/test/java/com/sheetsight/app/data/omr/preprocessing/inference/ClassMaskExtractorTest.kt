package com.sheetsight.app.data.omr.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClassMaskExtractorTest {

    /** Builds a 1-row [OmrPredictionMap] where each pixel is a one-hot vector for [classes]. */
    private fun oneHotRow(channels: Int, classes: List<Int>): OmrPredictionMap {
        val data = FloatArray(classes.size * channels)
        classes.forEachIndexed { pixelIndex, targetClass ->
            data[pixelIndex * channels + targetClass] = 1f
        }
        return OmrPredictionMap(width = classes.size, height = 1, channels = channels, data = data)
    }

    @Test
    fun `staff and symbols argmax picks the one-hot class per pixel`() {
        // background, staff, symbols
        val map = oneHotRow(channels = 3, classes = listOf(0, 1, 2))
        val symbolDetail = oneHotRow(channels = 4, classes = listOf(0, 0, 0))

        val masks = ClassMaskExtractor.extract(map, symbolDetail)

        assertEquals(listOf(false, true, false), masks.staff.toList())
        assertEquals(listOf(false, false, true), masks.symbols.toList())
    }

    @Test
    fun `symbol detail argmax picks the one-hot class per pixel`() {
        val staffAndSymbols = oneHotRow(channels = 3, classes = listOf(0, 0, 0, 0))
        // background, stems/rests, noteheads, clefs/keys
        val symbolDetail = oneHotRow(channels = 4, classes = listOf(0, 1, 2, 3))

        val masks = ClassMaskExtractor.extract(staffAndSymbols, symbolDetail)

        assertEquals(listOf(false, true, false, false), masks.stemsRests.toList())
        assertEquals(listOf(false, false, true, false), masks.noteheads.toList())
        assertEquals(listOf(false, false, false, true), masks.clefsKeys.toList())
    }

    @Test
    fun `ties resolve to the lowest class index, matching argmax convention`() {
        // Pixel 0: background and staff tied -> background wins (index 0 first).
        val data = floatArrayOf(0.5f, 0.5f, 0f)
        val map = OmrPredictionMap(width = 1, height = 1, channels = 3, data = data)
        val symbolDetail = oneHotRow(channels = 4, classes = listOf(0))

        val masks = ClassMaskExtractor.extract(map, symbolDetail)

        assertEquals(false, masks.staff[0])
        assertEquals(false, masks.symbols[0])
    }

    @Test
    fun `isSet indexes row-major by width`() {
        // 2x2 page; pixel (1,1) i.e. index 3 is a notehead.
        val staffAndSymbols = oneHotRow(channels = 3, classes = listOf(0, 0, 0, 0)).let {
            it.copy(width = 2, height = 2)
        }
        val symbolDetail = OmrPredictionMap(
            width = 2,
            height = 2,
            channels = 4,
            data = floatArrayOf(
                1f, 0f, 0f, 0f, // (0,0) background
                1f, 0f, 0f, 0f, // (1,0) background
                1f, 0f, 0f, 0f, // (0,1) background
                0f, 0f, 1f, 0f  // (1,1) notehead
            )
        )

        val masks = ClassMaskExtractor.extract(staffAndSymbols, symbolDetail)

        assertEquals(true, masks.isSet(masks.noteheads, x = 1, y = 1))
        assertEquals(false, masks.isSet(masks.noteheads, x = 0, y = 0))
    }

    @Test
    fun `rejects a staffAndSymbols map with the wrong channel count`() {
        val wrongChannels = OmrPredictionMap(width = 1, height = 1, channels = 4, data = FloatArray(4))
        val symbolDetail = oneHotRow(channels = 4, classes = listOf(0))

        assertThrows(IllegalArgumentException::class.java) {
            ClassMaskExtractor.extract(wrongChannels, symbolDetail)
        }
    }

    @Test
    fun `rejects prediction maps with mismatched page dimensions`() {
        val staffAndSymbols = oneHotRow(channels = 3, classes = listOf(0, 0))
        val symbolDetail = oneHotRow(channels = 4, classes = listOf(0))

        assertThrows(IllegalArgumentException::class.java) {
            ClassMaskExtractor.extract(staffAndSymbols, symbolDetail)
        }
    }
}