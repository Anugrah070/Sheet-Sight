package com.sheetsight.app.data.omr.dewarp

import com.sheetsight.app.data.omr.inference.OmrClassMasks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DewarpPipelineTest {

    private fun blankMasks(width: Int, height: Int) = OmrClassMasks(
        width = width,
        height = height,
        staff = BooleanArray(width * height),
        symbols = BooleanArray(width * height),
        stemsRests = BooleanArray(width * height),
        noteheads = BooleanArray(width * height),
        clefsKeys = BooleanArray(width * height)
    )

    @Test
    fun `an unreliable page is passed through unchanged`() {
        val width = 30
        val height = 20
        val masks = blankMasks(width, height)
        val imageChannels = listOf(FloatArray(width * height) { it.toFloat() })

        val result = DewarpPipeline.run(imageChannels, masks)

        assertFalse(result.wasDewarped)
        assertArrayEquals(imageChannels[0], result.imageChannels[0], 0f)
        assertEquals(masks, result.masks)
    }

    @Test
    fun `a page with a clear staffline runs end-to-end without crashing`() {
        val width = 60
        val height = 40
        val staff = BooleanArray(width * height)
        for (x in 0 until width) staff[15 * width + x] = true
        val masks = OmrClassMasks(
            width = width,
            height = height,
            staff = staff,
            symbols = BooleanArray(width * height),
            stemsRests = BooleanArray(width * height),
            noteheads = BooleanArray(width * height),
            clefsKeys = BooleanArray(width * height)
        )
        val imageChannels = listOf(FloatArray(width * height) { 128f }, FloatArray(width * height) { 64f })

        val result = DewarpPipeline.run(imageChannels, masks)

        assertTrue(result.wasDewarped)
        assertEquals(2, result.imageChannels.size)
        for (channel in result.imageChannels) assertEquals(width * height, channel.size)
        assertEquals(width * height, result.masks.staff.size)
    }

    @Test
    fun `rejects an image channel of the wrong size`() {
        val masks = blankMasks(width = 10, height = 10)
        assertThrows(IllegalArgumentException::class.java) {
            DewarpPipeline.run(listOf(FloatArray(5)), masks)
        }
    }
}