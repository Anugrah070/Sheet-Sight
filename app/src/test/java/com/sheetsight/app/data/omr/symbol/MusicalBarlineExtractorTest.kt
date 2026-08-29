package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalBarlineExtractorTest {

    @Test
    fun `a straight line cannot validate itself without model one symbol evidence`() {
        val width = 8
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        for (y in 1..6) stems[y * width + 2] = true

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            BooleanArray(width * height),
            width,
            height
        )

        assertTrue(selected.none { it })
    }

    @Test
    fun `only symbol components overlapping stem candidates survive`() {
        val width = 12
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        for (y in 1..6) stems[y * width + 2] = true
        for (y in 1..6) symbols[y * width + 2] = true
        symbols[3 * width + 8] = true
        symbols[3 * width + 9] = true

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(selected[4 * width + 2])
        assertFalse(selected[3 * width + 8])
    }

    @Test
    fun `note-group occupancy removes an otherwise valid stem component`() {
        val width = 8
        val height = 8
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        for (y in 1..6) {
            val index = y * width + 2
            stems[index] = true
            symbols[index] = true
            groupMap[index] = 0
        }

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(selected.none { it })
    }

    @Test
    fun `busy claimed stems can leave only one stray component like oemer`() {
        val width = 40
        val height = 30
        val groupMap = IntArray(width * height) { -1 }
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        val claimedBarXs = listOf(5, 12, 19, 26)
        for (x in claimedBarXs + 35) {
            for (y in 3 until 27) {
                val index = y * width + x
                stems[index] = true
                symbols[index] = true
                if (x in claimedBarXs) groupMap[index] = 0
            }
        }

        val selected = MusicalBarlineExtractor.selectOverlappingSymbolComponents(
            groupMap,
            stems,
            symbols,
            width,
            height
        )

        assertTrue(claimedBarXs.all { x -> (3 until 27).none { y -> selected[y * width + x] } })
        assertTrue((3 until 27).all { y -> selected[y * width + 35] })
    }

    @Test
    fun `staff-spanning vertical component passes structural barline rule`() {
        assertTrue(
            MusicalBarlineExtractor.crossesMostStaffLines(
                BoundingBox(30, 9, 32, 52),
                staffGrid()
            )
        )
    }

    @Test
    fun `partial-height note stem fails structural barline rule`() {
        assertFalse(
            MusicalBarlineExtractor.crossesMostStaffLines(
                BoundingBox(30, 24, 32, 51),
                staffGrid()
            )
        )
    }

    @Test
    fun `unclaimed staff-crossing stem is recovered without generic-symbol evidence`() {
        val width = 100
        val height = 70
        val stems = BooleanArray(width * height)
        for (y in 9 until 52) stems[y * width + 40] = true

        val candidates = MusicalBarlineExtractor.detectStructuralCandidates(
            groupMap = IntArray(width * height) { -1 },
            stemsRests = stems,
            symbols = BooleanArray(width * height),
            width = width,
            height = height,
            horizontalBounds = 0 until width,
            staffGrid = staffGrid()
        )

        assertTrue(candidates.size == 1)
        assertTrue(candidates.single().boundingBox.left == 40)
    }

    @Test
    fun `unclaimed partial note stem is not promoted to a barline`() {
        val width = 100
        val height = 70
        val stems = BooleanArray(width * height)
        for (y in 24 until 52) stems[y * width + 40] = true

        val candidates = MusicalBarlineExtractor.detectStructuralCandidates(
            groupMap = IntArray(width * height) { -1 },
            stemsRests = stems,
            symbols = BooleanArray(width * height),
            width = width,
            height = height,
            horizontalBounds = 0 until width,
            staffGrid = staffGrid()
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `multi-track fallback requires aligned staff-crossing evidence`() {
        val width = 100
        val height = 140
        val oneTrackOnly = BooleanArray(width * height)
        for (y in 9 until 52) oneTrackOnly[y * width + 40] = true

        val rejected = MusicalBarlineExtractor.detectStructuralCandidates(
            IntArray(width * height) { -1 }, oneTrackOnly, BooleanArray(width * height), width, height,
            0 until width, twoTrackStaffGrid()
        )
        assertTrue(rejected.isEmpty())

        val alignedTracks = oneTrackOnly.copyOf()
        for (y in 79 until 122) alignedTracks[y * width + 42] = true
        val accepted = MusicalBarlineExtractor.detectStructuralCandidates(
            IntArray(width * height) { -1 }, alignedTracks, BooleanArray(width * height), width, height,
            0 until width, twoTrackStaffGrid()
        )
        assertTrue(accepted.size == 1)
        assertTrue(accepted.single().boundingBox.top == 9)
        assertTrue(accepted.single().boundingBox.bottom == 122)
    }

    @Test
    fun `nearby staff-crossing note stem does not shift a multi-track barline`() {
        val width = 100
        val height = 140
        val stems = BooleanArray(width * height)
        for (y in 9 until 52) stems[y * width + 40] = true
        for (y in 79 until 122) stems[y * width + 40] = true
        for (y in 79 until 122) stems[y * width + 46] = true

        val candidates = MusicalBarlineExtractor.detectStructuralCandidates(
            IntArray(width * height) { -1 },
            stems,
            BooleanArray(width * height),
            width,
            height,
            0 until width,
            twoTrackStaffGrid()
        )

        assertEquals(1, candidates.size)
        assertEquals(BoundingBox(40, 9, 41, 122), candidates.single().boundingBox)
    }

    @Test
    fun `claimed barline is recovered only when independent symbol evidence corroborates both tracks`() {
        val width = 100
        val height = 140
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        val groupMap = IntArray(width * height) { -1 }
        for (y in 9 until 52) {
            val index = y * width + 40
            stems[index] = true
            symbols[index] = true
            groupMap[index] = 7
        }
        for (y in 79 until 122) {
            val index = y * width + 41
            stems[index] = true
            symbols[index] = true
            groupMap[index] = 7
        }

        val candidates = MusicalBarlineExtractor.detectStructuralCandidates(
            groupMap, stems, symbols, width, height, 0 until width, twoTrackStaffGrid()
        )

        assertTrue(candidates.size == 1)
        assertTrue(candidates.single().boundingBox.left == 40)
        assertTrue(candidates.single().boundingBox.right == 42)
    }

    @Test
    fun `claimed note stem on one track is not promoted by corroborating symbol evidence`() {
        val width = 100
        val height = 140
        val stems = BooleanArray(width * height)
        val symbols = BooleanArray(width * height)
        val groupMap = IntArray(width * height) { -1 }
        for (y in 9 until 52) {
            val index = y * width + 40
            stems[index] = true
            symbols[index] = true
            groupMap[index] = 7
        }

        val candidates = MusicalBarlineExtractor.detectStructuralCandidates(
            groupMap, stems, symbols, width, height, 0 until width, twoTrackStaffGrid()
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `nearby strokes of a double bar consolidate without merging distinct boundaries`() {
        val grid = twoTrackStaffGrid()
        val doubleBar = listOf(
            MusicalBarlineCandidate(BoundingBox(40, 9, 42, 122), group = 0, confidence = 0.9),
            MusicalBarlineCandidate(BoundingBox(49, 9, 51, 122), group = 0, confidence = 0.8)
        )
        val distinctBars = doubleBar +
            MusicalBarlineCandidate(BoundingBox(62, 9, 64, 122), group = 0, confidence = 0.9)

        assertEquals(1, MusicalBarlineExtractor.consolidateCandidates(doubleBar, grid).size)
        assertEquals(2, MusicalBarlineExtractor.consolidateCandidates(distinctBars, grid).size)
    }

    @Test
    fun `candidate consolidation uses the current system staff scale`() {
        val grid = listOf(
            listOf(assignedStaff(top = 10, spacing = 10, track = 0, group = 0)),
            listOf(assignedStaff(top = 80, spacing = 2, track = 0, group = 1))
        )
        val smallSystemCandidates = listOf(
            MusicalBarlineCandidate(BoundingBox(40, 79, 41, 89), group = 1, confidence = 0.9),
            MusicalBarlineCandidate(BoundingBox(43, 79, 44, 89), group = 1, confidence = 0.8)
        )

        assertEquals(
            2,
            MusicalBarlineExtractor.consolidateCandidates(smallSystemCandidates, grid).size
        )
    }

    private fun staffGrid(): List<List<AssignedStaff>> {
        val lines = StafflinePosition.entries.mapIndexed { index, position ->
            val y = 10 + index * 10
            Staffline(position, listOf(StafflinePoint(0, y), StafflinePoint(100, y)))
        }
        return listOf(listOf(AssignedStaff(ZoneStaff(lines), track = 0, group = 0)))
    }

    private fun twoTrackStaffGrid(): List<List<AssignedStaff>> {
        return listOf(
            listOf(
                assignedStaff(top = 10, spacing = 10, track = 0, group = 0),
                assignedStaff(top = 80, spacing = 10, track = 1, group = 0)
            )
        )
    }

    private fun assignedStaff(top: Int, spacing: Int, track: Int, group: Int): AssignedStaff {
        val lines = StafflinePosition.entries.mapIndexed { index, position ->
            val y = top + index * spacing
            Staffline(position, listOf(StafflinePoint(0, y), StafflinePoint(100, y)))
        }
        return AssignedStaff(ZoneStaff(lines), track = track, group = group)
    }
}
