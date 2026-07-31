package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackVotingLoopTest {
    private fun mask(width: Int, height: Int, boxes: List<BoundingBox>): BooleanArray {
        val result = BooleanArray(width * height)
        for (box in boxes) {
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) result[y * width + x] = true
            }
        }
        return result
    }

    @Test
    fun `ordinary single-staff-height barlines keep one track`() {
        val width = 20
        val height = 100
        val boxes = listOf(BoundingBox(2, 30, 4, 70))
        val staffs = listOf(
            listOf(StaffCenterInfo(10.0, 50.0, 10.0))
        )

        val result = TrackVotingLoop.infer(mask(width, height, boxes), width, height, staffs)

        assertEquals(1, result.trackNums)
        assertEquals(listOf(4.0), result.heightRatios)
    }

    @Test
    fun `multiple barlines taller than ten units infer a grand staff`() {
        val width = 30
        val height = 160
        val boxes = listOf(
            BoundingBox(2, 20, 4, 140),
            BoundingBox(20, 20, 22, 140)
        )
        val staffs = listOf(
            listOf(
                StaffCenterInfo(10.0, 40.0, 10.0),
                StaffCenterInfo(10.0, 120.0, 10.0)
            )
        )

        val result = TrackVotingLoop.infer(mask(width, height, boxes), width, height, staffs)

        assertEquals(2, result.trackNums)
        assertEquals(listOf(12.0, 12.0), result.heightRatios)
    }

    @Test
    fun `threshold is strict and does not count exactly ten units`() {
        val width = 20
        val height = 120
        val boxes = listOf(
            BoundingBox(2, 10, 4, 110),
            BoundingBox(10, 10, 12, 110)
        )
        val staffs = listOf(
            listOf(
                StaffCenterInfo(10.0, 30.0, 10.0),
                StaffCenterInfo(10.0, 90.0, 10.0)
            )
        )

        val result = TrackVotingLoop.infer(mask(width, height, boxes), width, height, staffs)

        assertEquals(1, result.trackNums)
    }

    @Test
    fun `components no taller than one unit are excluded from ratios`() {
        val width = 10
        val height = 20
        val boxes = listOf(BoundingBox(2, 5, 4, 15))
        val staffs = listOf(listOf(StaffCenterInfo(5.0, 10.0, 10.0)))

        val result = TrackVotingLoop.infer(mask(width, height, boxes), width, height, staffs)

        assertEquals(emptyList<Double>(), result.heightRatios)
        assertEquals(1, result.trackNums)
        assertEquals(boxes, result.barlineBoxes)
    }

    @Test
    fun `empty staff grid falls back to one track`() {
        val width = 10
        val height = 10
        val result = TrackVotingLoop.infer(BooleanArray(width * height), width, height, emptyList())

        assertEquals(1, result.trackNums)
        assertEquals(emptyList<Double>(), result.heightRatios)
        assertEquals(emptyList<BoundingBox>(), result.barlineBoxes)
    }
}
