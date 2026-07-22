package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageMaskAlignerTest {

    @Test
    fun `matching sizes are returned unchanged`() {
        val channels = listOf(floatArrayOf(1f, 2f, 3f, 4f))

        val result = ImageMaskAligner.alignToMaskSize(
            channels, sourceWidth = 2, sourceHeight = 2, targetWidth = 2, targetHeight = 2
        )

        assertSame(channels, result)
    }

    @Test
    fun `grows the image by replicating the edge pixel`() {
        // 2x2 source growing to 3x3 target: new row/column replicate row1/col1.
        val channel = floatArrayOf(
            1f, 2f,
            3f, 4f
        )

        val result = ImageMaskAligner.alignToMaskSize(
            listOf(channel), sourceWidth = 2, sourceHeight = 2, targetWidth = 3, targetHeight = 3
        )

        val expected = floatArrayOf(
            1f, 2f, 2f,
            3f, 4f, 4f,
            3f, 4f, 4f
        )
        assertEquals(expected.toList(), result[0].toList())
    }

    @Test
    fun `rejects a source larger than the target`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageMaskAligner.alignToMaskSize(
                listOf(FloatArray(9)), sourceWidth = 3, sourceHeight = 3, targetWidth = 2, targetHeight = 2
            )
        }
    }
}