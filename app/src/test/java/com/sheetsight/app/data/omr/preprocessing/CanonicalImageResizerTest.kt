package com.sheetsight.app.data.omr.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalImageResizerTest {

    @Test
    fun `large image is downscaled toward the trained pixel-count range`() {
        val target = CanonicalImageResizer.computeTargetSize(sourceWidth = 6000, sourceHeight = 4000)
        val pixelCount = target.width.toLong() * target.height.toLong()
        assertTrue("expected ~3.675M px, got $pixelCount", pixelCount in 3_000_000..4_350_000)
    }

    @Test
    fun `small image is upscaled toward the trained pixel-count range`() {
        val target = CanonicalImageResizer.computeTargetSize(sourceWidth = 600, sourceHeight = 400)
        val pixelCount = target.width.toLong() * target.height.toLong()
        assertTrue("expected ~3.675M px, got $pixelCount", pixelCount in 3_000_000..4_350_000)
    }

    @Test
    fun `aspect ratio is preserved`() {
        val target = CanonicalImageResizer.computeTargetSize(sourceWidth = 1700, sourceHeight = 2200)
        val sourceRatio = 1700.0 / 2200.0
        val targetRatio = target.width.toDouble() / target.height.toDouble()
        assertEquals(sourceRatio, targetRatio, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive dimensions are rejected`() {
        CanonicalImageResizer.computeTargetSize(sourceWidth = 0, sourceHeight = 100)
    }
}
