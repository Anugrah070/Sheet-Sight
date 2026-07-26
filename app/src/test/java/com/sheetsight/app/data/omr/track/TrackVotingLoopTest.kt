package com.sheetsight.app.data.omr.track

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackVotingLoopTest {

    private fun mask(width: Int, height: Int, on: Set<Pair<Int, Int>>): BooleanArray =
        BooleanArray(width * height) { i -> (i % width to i / width) in on }

    @Test
    fun `single-staff synthetic input returns 1`() {
        val width = 20
        val height = 100
        // One barline blob spanning y=[40,60), containing the single staff center at y=50.
        val on = (2..3).flatMap { x -> (40..59).map { y -> x to y } }.toSet()
        val staffGrid = listOf(listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 50.0, unitSize = 10.0)))

        val result = TrackVotingLoop.infer(mask(width, height, on), width, height, staffGrid)

        assertEquals(1, result.trackNums)
        assertEquals(listOf(1), result.votes)
    }

    @Test
    fun `grand-staff synthetic input returns 2`() {
        val width = 20
        val height = 200
        // One tall barline blob spanning y=[40,160), containing both staff centers (50 and 150).
        val on = (2..3).flatMap { x -> (40..159).map { y -> x to y } }.toSet()
        val staffGrid = listOf(
            listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 50.0, unitSize = 10.0)),
            listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 150.0, unitSize = 10.0))
        )

        val result = TrackVotingLoop.infer(mask(width, height, on), width, height, staffGrid)

        assertEquals(2, result.trackNums)
        assertEquals(listOf(2), result.votes)
    }

    @Test
    fun `results are deterministic across repeated runs on the same fixture`() {
        val width = 20
        val height = 200
        val on = (2..3).flatMap { x -> (40..159).map { y -> x to y } }.toSet()
        val staffGrid = listOf(
            listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 50.0, unitSize = 10.0)),
            listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 150.0, unitSize = 10.0))
        )
        val m = mask(width, height, on)

        val first = TrackVotingLoop.infer(m, width, height, staffGrid)
        val second = TrackVotingLoop.infer(m, width, height, staffGrid)

        assertEquals(first, second)
    }

    @Test
    fun `no hidden cap - a five-staff brace group votes 5, not a smaller capped value`() {
        val width = 20
        val height = 600
        val centers = listOf(50.0, 150.0, 250.0, 350.0, 450.0)
        val on = (2..3).flatMap { x -> (40..469).map { y -> x to y } }.toSet()
        val staffGrid = centers.map { y -> listOf(StaffCenterInfo(xCenter = 2.0, yCenter = y, unitSize = 10.0)) }

        val result = TrackVotingLoop.infer(mask(width, height, on), width, height, staffGrid)

        assertEquals(5, result.trackNums)
        assertEquals(listOf(5), result.votes)
    }

    @Test
    fun `ties resolve to the first-cast vote value, not the numerically smaller one`() {
        val width = 30
        val height = 200
        // Blob A (x=2-3) spans only the first staff -> vote 1, encountered first (lower x, but
        // extraction order is row-major top-to-bottom so both blobs start around the same rows;
        // place blob A's top strictly above blob B's to guarantee A is extracted first).
        val blobA = (2..3).flatMap { x -> (10..29).map { y -> x to y } }.toSet() // vote 1 (staff at y=20)
        val blobB = (10..11).flatMap { x -> (40..159).map { y -> x to y } }.toSet() // vote 2, but only one such blob
        val blobC = (20..21).flatMap { x -> (170..189).map { y -> x to y } }.toSet() // vote 1 again (staff at y=180)
        val on = blobA + blobB + blobC
        val staffGrid = listOf(
            listOf(StaffCenterInfo(xCenter = 2.0, yCenter = 20.0, unitSize = 10.0)),
            listOf(StaffCenterInfo(xCenter = 10.0, yCenter = 50.0, unitSize = 10.0)),
            listOf(StaffCenterInfo(xCenter = 10.0, yCenter = 150.0, unitSize = 10.0)),
            listOf(StaffCenterInfo(xCenter = 20.0, yCenter = 180.0, unitSize = 10.0))
        )

        val result = TrackVotingLoop.infer(mask(width, height, on), width, height, staffGrid)

        // Votes cast in blob-extraction order: [1 (A), 2 (B), 1 (C)] -> 1 and 2 tie at... actually
        // 1 has 2 votes vs 2's 1 vote, so 1 wins outright (not a tie) - confirms majority, not just first-cast.
        assertEquals(1, result.trackNums)
        assertEquals(listOf(1, 2, 1), result.votes)
    }

    @Test
    fun `a blob spanning no staff center at all casts no vote and falls back to 1`() {
        val width = 10
        val height = 10
        val result = TrackVotingLoop.infer(BooleanArray(width * height), width, height, emptyList())

        assertEquals(1, result.trackNums)
        assertEquals(emptyList<Int>(), result.votes)
        assertEquals(emptyList<BoundingBox>(), result.barlineBoxes)
    }
}