package com.sheetsight.app.data.omr.notehead

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteheadExtractorTest {

    @Test
    fun `ellipse kernel matches OpenCV even-sized layout`() {
        val kernel = NoteheadExtractor.ellipseKernel(kernelWidth = 4, kernelHeight = 6)
        val rows = kernel.toList().chunked(4)

        assertEquals(
            listOf(
                listOf(false, false, true, false),
                listOf(false, true, true, true),
                listOf(true, true, true, true),
                listOf(true, true, true, true),
                listOf(true, true, true, true),
                listOf(false, true, true, true)
            ),
            rows
        )
    }

    @Test
    fun `hole filling distinguishes a hollow ring from its foreground count`() {
        val width = 7
        val mask = BooleanArray(width * 7)
        for (y in 1..5) {
            for (x in 1..5) {
                if (x == 1 || x == 5 || y == 1 || y == 5) mask[y * width + x] = true
            }
        }
        val box = BoundingBox(1, 1, 6, 6)

        val filled = NoteheadExtractor.fillHoleCount(mask, width, box)
        val foreground = mask.count { it }

        assertTrue(filled > foreground)
        assertEquals(25, filled)
    }

    @Test
    fun `extracts a synthetic notehead and assigns it to the validated staff`() {
        val width = 120
        val height = 110
        val noteheads = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        val stems = BooleanArray(width * height)

        // Unit size is 10px. This 13x9 solid blob matches oemer's expected
        // notehead width/height ratios and is safely past the clef zone.
        for (y in 57 until 66) {
            for (x in 70 until 83) {
                noteheads[y * width + x] = true
                symbols[y * width + x] = true
            }
        }
        // A right-side stem intersects the notehead after oemer's 3x2 dilation.
        for (y in 35 until 63) stems[y * width + 82] = true

        val result = NoteheadExtractor.extract(
            noteheadMask = noteheads,
            symbolsMask = symbols,
            stemMask = stems,
            width = width,
            height = height,
            validatedStaffGrid = listOf(listOf(syntheticStaff(width)))
        )

        assertEquals(1, result.size)
        val note = result.single()
        assertEquals(NoteheadType.SOLID, note.type)
        assertEquals(0, note.staffAssignment.track)
        assertEquals(0, note.staffAssignment.group)
        assertTrue(note.sourcePixelIndices.isNotEmpty())
        assertEquals(true, note.stemOnRight)
        assertFalse(note.boundingBox.width <= 0 || note.boundingBox.height <= 0)
    }

    @Test
    fun `empty validated grid yields no candidates without copying masks`() {
        val mask = BooleanArray(16)

        val result = NoteheadExtractor.extract(
            noteheadMask = mask,
            symbolsMask = mask,
            stemMask = mask,
            width = 4,
            height = 4,
            validatedStaffGrid = emptyList()
        )

        assertTrue(result.isEmpty())
    }

    private fun syntheticStaff(width: Int): AssignedStaff {
        val positions = StafflinePosition.entries
        val lines = (0 until 5).map { lineIndex ->
            Staffline(
                position = positions[lineIndex],
                points = (0 until width).map { x ->
                    StafflinePoint(x = x, y = 40 + lineIndex * 10)
                }
            )
        }
        return AssignedStaff(staff = ZoneStaff(lines), track = 0, group = 0)
    }
}
