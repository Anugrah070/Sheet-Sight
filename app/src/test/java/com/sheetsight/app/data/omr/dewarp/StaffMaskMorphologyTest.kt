package com.sheetsight.app.data.omr.dewarp

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffMaskMorphologyTest {

    private fun rowMask(width: Int, onX: Set<Int>): BooleanArray =
        BooleanArray(width) { it in onX }

    @Test
    fun `horizontal dilate spreads a single on pixel across the anchored window`() {
        // width=10, single "on" pixel at x=5. Kernel=6, anchor=3, window=[-3,2].
        // Output true at x iff 5 in [x-3, x+2] i.e. x in [3, 8].
        val mask = rowMask(width = 10, onX = setOf(5))

        val result = StaffMaskMorphology.slide(
            mask, width = 10, height = 1, kernelSize = 6, vertical = false, erode = false
        )

        val expected = (0 until 10).map { it in 3..8 }
        assertEquals(expected, result.toList())
    }

    @Test
    fun `horizontal erode shrinks a 7-wide run to its anchored core`() {
        // width=10, "on" for x in [2,8] (7 wide). Kernel=6, anchor=3.
        // A position survives only if its whole [-3,2] window lies inside [2,8]:
        // x-3>=2 and x+2<=8 -> x in {5,6}.
        val mask = rowMask(width = 10, onX = (2..8).toSet())

        val result = StaffMaskMorphology.slide(
            mask, width = 10, height = 1, kernelSize = 6, vertical = false, erode = true
        )

        val expected = (0 until 10).map { it == 5 || it == 6 }
        assertEquals(expected, result.toList())
    }

    @Test
    fun `vertical dilate spreads a single on pixel the same way as horizontal`() {
        // height=10, width=1, single "on" pixel at y=5 -> true for y in [3,8].
        val mask = BooleanArray(10) { it == 5 }

        val result = StaffMaskMorphology.slide(
            mask, width = 1, height = 10, kernelSize = 6, vertical = true, erode = false
        )

        val expected = (0 until 10).map { it in 3..8 }
        assertEquals(expected, result.toList())
    }

    @Test
    fun `dilate of an all-off mask stays all-off, even at the border`() {
        val mask = BooleanArray(12)

        val result = StaffMaskMorphology.slide(
            mask, width = 12, height = 1, kernelSize = 6, vertical = false, erode = false
        )

        assertEquals(List(12) { false }, result.toList())
    }

    @Test
    fun `thickenStafflines keeps a full-width staffline-thin run`() {
        // A one-row-thick, full-width "on" run is exactly what a real
        // staffline segment looks like pre-processing: should survive.
        val width = 20
        val mask = BooleanArray(width * 3) // 3 rows tall canvas
        for (x in 0 until width) mask[1 * width + x] = true // middle row on

        val result = StaffMaskMorphology.thickenStafflines(mask, width, height = 3)

        assertEquals(true, result.any { it })
    }

    @Test
    fun `thickenStafflines removes an isolated dot narrower than the open kernel`() {
        // A single on pixel is far narrower than the 6px horizontal open
        // kernel and should be fully removed, unlike a full-width run.
        val width = 20
        val mask = BooleanArray(width * 3)
        mask[1 * width + 10] = true

        val result = StaffMaskMorphology.thickenStafflines(mask, width, height = 3)

        assertEquals(false, result.any { it })
    }
}