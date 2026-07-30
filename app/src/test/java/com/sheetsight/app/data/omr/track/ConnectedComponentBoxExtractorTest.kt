package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedComponentBoxExtractorTest {

    private fun mask(width: Int, height: Int, on: Set<Pair<Int, Int>>): BooleanArray =
        BooleanArray(width * height) { i -> (i % width to i / width) in on }

    @Test
    fun `a single solid rectangle produces one bbox matching its exact extent`() {
        val width = 10
        val height = 8
        val on = (2..6).flatMap { x -> (1..4).map { y -> x to y } }.toSet() // x[2,6] y[1,4]
        val result = ConnectedComponentBoxExtractor.extract(mask(width, height, on), width, height)

        assertEquals(1, result.size)
        assertEquals(BoundingBox(left = 2, top = 1, right = 7, bottom = 5), result.single())
    }

    @Test
    fun `an L-shape produces one bbox spanning its full extent, not a shape-fitted box`() {
        val width = 10
        val height = 10
        // Vertical arm: x=2, y=1..6. Horizontal arm: y=6, x=2..6. Orthogonally connected.
        val vertical = (1..6).map { 2 to it }
        val horizontal = (2..6).map { it to 6 }
        val on = (vertical + horizontal).toSet()

        val result = ConnectedComponentBoxExtractor.extract(mask(width, height, on), width, height)

        assertEquals(1, result.size)
        // Bounding box is the full rectangular extent of the L, corners included even though empty.
        assertEquals(BoundingBox(left = 2, top = 1, right = 7, bottom = 7), result.single())
    }

    @Test
    fun `two disjoint blobs produce two separate bboxes`() {
        val width = 20
        val height = 10
        val blobA = (1..2).flatMap { x -> (1..2).map { y -> x to y } }.toSet() // x[1,2] y[1,2]
        val blobB = (10..12).flatMap { x -> (5..6).map { y -> x to y } }.toSet() // x[10,12] y[5,6]

        val result = ConnectedComponentBoxExtractor.extract(mask(width, height, blobA + blobB), width, height)

        assertEquals(2, result.size)
        val expected = setOf(
            BoundingBox(left = 1, top = 1, right = 3, bottom = 3),
            BoundingBox(left = 10, top = 5, right = 13, bottom = 7)
        )
        assertEquals(expected, result.toSet())
    }

    @Test
    fun `two pixels touching only at a corner merge into one blob (8-connectivity)`() {
        // (3,3) and (4,4) touch only diagonally - cv2.findContours' 8-connected
        // tracing treats them as one object, unlike 4-connected labeling.
        val width = 10
        val height = 10
        val on = setOf(3 to 3, 4 to 4)

        val result = ConnectedComponentBoxExtractor.extract(mask(width, height, on), width, height)

        assertEquals(1, result.size)
        assertEquals(BoundingBox(left = 3, top = 3, right = 5, bottom = 5), result.single())
    }

    @Test
    fun `an empty mask produces no boxes`() {
        val width = 5
        val height = 5
        val result = ConnectedComponentBoxExtractor.extract(BooleanArray(width * height), width, height)
        assertEquals(emptyList<BoundingBox>(), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a mask whose size doesn't match width times height`() {
        ConnectedComponentBoxExtractor.extract(BooleanArray(5), width = 3, height = 3)
    }
}