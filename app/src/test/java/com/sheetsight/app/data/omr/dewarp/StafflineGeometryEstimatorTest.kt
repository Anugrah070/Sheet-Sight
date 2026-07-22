package com.sheetsight.app.data.omr.dewarp

import com.sheetsight.app.data.omr.inference.OmrClassMasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StafflineGeometryEstimatorTest {

    @Test
    fun `a blank page is reported unreliable, not an error`() {
        val mask = BooleanArray(40 * 20)

        val estimate = StafflineGeometryEstimator.estimate(mask, width = 40, height = 20)

        assertEquals(false, estimate.isReliable)
        assertEquals(emptyList<StafflineGridGroup>(), estimate.groups)
    }

    @Test
    fun `zero-size input is reported unreliable without crashing`() {
        val estimate = StafflineGeometryEstimator.estimate(BooleanArray(0), width = 0, height = 0)

        assertEquals(false, estimate.isReliable)
        assertEquals(0, estimate.groupMap.size)
    }

    @Test
    fun `rejects a mask whose size does not match width times height`() {
        assertThrows(IllegalArgumentException::class.java) {
            StafflineGeometryEstimator.estimate(BooleanArray(5), width = 3, height = 3)
        }
    }

    @Test
    fun `a clear full-width staffline-like row is reported reliable`() {
        // A single full-width "on" row well away from any edge, comfortably
        // thin relative to the split unit even after vertical dilation.
        val width = 40
        val height = 20
        val mask = BooleanArray(width * height)
        for (x in 0 until width) mask[10 * width + x] = true

        val estimate = StafflineGeometryEstimator.estimate(mask, width, height)

        assertTrue(estimate.isReliable)
        assertTrue(estimate.groups.isNotEmpty())
        // Every non-background groupMap entry must reference a real group id.
        val validIds = estimate.groups.map { it.id }.toSet()
        assertTrue(estimate.groupMap.all { it == -1 || it in validIds })
    }

    @Test
    fun `estimate from OmrClassMasks reads the staff mask`() {
        val width = 40
        val height = 20
        val staff = BooleanArray(width * height)
        for (x in 0 until width) staff[10 * width + x] = true
        val masks = OmrClassMasks(
            width = width,
            height = height,
            staff = staff,
            symbols = BooleanArray(width * height),
            stemsRests = BooleanArray(width * height),
            noteheads = BooleanArray(width * height),
            clefsKeys = BooleanArray(width * height)
        )

        val estimate = StafflineGeometryEstimator.estimate(masks)

        assertTrue(estimate.isReliable)
    }
}