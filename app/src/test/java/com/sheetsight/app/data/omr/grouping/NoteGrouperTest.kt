package com.sheetsight.app.data.omr.grouping

import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadStaffAssignment
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteGrouperTest {

    @Test
    fun `groups vertically aligned noteheads connected by one upward stem`() {
        val width = 40
        val height = 60
        val notes = listOf(
            note(0, BoundingBox(15, 30, 19, 34), width, staffPosition = 2),
            note(1, BoundingBox(15, 40, 19, 44), width, staffPosition = 0)
        )
        val stems = BooleanArray(width * height)
        for (y in 18 until 43) stems[y * width + 18] = true

        val result = NoteGrouper.group(notes, stems, width, height)

        assertEquals(1, result.size)
        val chord = result.single()
        assertEquals(listOf(1, 0), chord.noteheads.map { it.id })
        assertEquals(StemDirection.UP, chord.stemDirection)
        assertTrue(chord.hasStem)
    }

    @Test
    fun `keeps disconnected noteheads in separate stemless groups`() {
        val width = 50
        val height = 40
        val notes = listOf(
            note(0, BoundingBox(8, 12, 18, 22), width, staffPosition = 1),
            note(1, BoundingBox(30, 12, 40, 22), width, staffPosition = 1)
        )

        val result = NoteGrouper.group(notes, BooleanArray(width * height), width, height)

        assertEquals(2, result.size)
        assertTrue(result.all { it.stemDirection == StemDirection.NONE })
        assertTrue(result.all { !it.hasStem })
    }

    @Test
    fun `empty candidates produce no chords`() {
        val result = NoteGrouper.group(emptyList(), BooleanArray(25), width = 5, height = 5)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `component crossing two staff assignments is partitioned`() {
        val width = 30
        val height = 40
        val first = note(0, BoundingBox(12, 15, 16, 19), width, 1)
        val second = note(1, BoundingBox(12, 21, 16, 25), width, 1).copy(
            staffAssignment = NoteheadStaffAssignment(track = 1, group = 0, staffLinePosition = 1)
        )
        val stems = BooleanArray(width * height)
        for (y in 15 until 25) stems[y * width + 15] = true

        val result = NoteGrouper.group(listOf(first, second), stems, width, height)

        assertEquals(2, result.size)
        assertFalse(result[0].track == result[1].track)
    }

    @Test
    fun `group map marks owned components and leaves unrelated stems as background`() {
        val width = 30
        val height = 30
        val note = note(0, BoundingBox(8, 15, 12, 19), width, 1)
        val stems = BooleanArray(width * height)
        for (y in 7 until 18) stems[y * width + 11] = true
        for (y in 5 until 20) stems[y * width + 24] = true

        val result = NoteGrouper.groupWithMap(listOf(note), stems, width, height)

        assertTrue(result.groupMap[10 * width + 11] >= 0)
        assertEquals(-1, result.groupMap[10 * width + 24])
    }

    private fun note(
        id: Int,
        box: BoundingBox,
        width: Int,
        staffPosition: Int
    ): NoteheadCandidate {
        val pixels = buildList {
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) add(y * width + x)
            }
        }.toIntArray()
        return NoteheadCandidate(
            id = id,
            boundingBox = box,
            type = NoteheadType.SOLID,
            staffAssignment = NoteheadStaffAssignment(0, 0, staffPosition),
            sourcePixelIndices = pixels,
            stemOnRight = true
        )
    }
}
