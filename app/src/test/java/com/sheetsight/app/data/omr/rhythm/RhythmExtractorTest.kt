package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadStaffAssignment
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmExtractorTest {

    @Test
    fun `framework creates unresolved candidates without inferring duration`() {
        val note = note()
        val chord = ChordCandidate(
            id = 0,
            noteheads = listOf(note),
            boundingBox = note.boundingBox,
            stemDirection = StemDirection.UP,
            hasStem = true,
            track = 0,
            group = 0
        )
        val masks = RhythmEvidenceMasks(
            width = 5,
            height = 5,
            stems = BooleanArray(25),
            beams = null,
            flags = null,
            dots = null
        )

        val result = RhythmExtractor.prepareCandidates(listOf(note), listOf(chord), masks)

        assertEquals(1, result.size)
        assertEquals(RhythmEvidenceStatus.INCOMPLETE, result.single().evidenceStatus)
        assertNull(result.single().duration)
        assertEquals(listOf(0), result.single().noteheads.map { it.id })
    }

    @Test
    fun `complete evidence remains unresolved until verified algorithm exists`() {
        val note = note()
        val chord = ChordCandidate(
            id = 0,
            noteheads = listOf(note),
            boundingBox = note.boundingBox,
            stemDirection = StemDirection.NONE,
            hasStem = false,
            track = 0,
            group = 0
        )
        val empty = BooleanArray(25)
        val masks = RhythmEvidenceMasks(5, 5, empty, empty, empty, empty)

        val result = RhythmExtractor.prepareCandidates(listOf(note), listOf(chord), masks)

        assertEquals(RhythmEvidenceStatus.COMPLETE, result.single().evidenceStatus)
        assertNull(result.single().duration)
    }

    @Test
    fun `duration resolution fails instead of assigning a default`() {
        try {
            RhythmExtractor.resolveDurations(emptyList())
            throw AssertionError("Expected NotImplementedError")
        } catch (error: NotImplementedError) {
            assertTrue(error.message.orEmpty().contains("not been ported"))
        }
    }

    private fun note(): NoteheadCandidate =
        NoteheadCandidate(
            id = 0,
            boundingBox = BoundingBox(1, 1, 4, 4),
            type = NoteheadType.SOLID,
            staffAssignment = NoteheadStaffAssignment(0, 0, 1),
            sourcePixelIndices = intArrayOf(6, 7, 8, 11, 12, 13),
            stemOnRight = true
        )
}
