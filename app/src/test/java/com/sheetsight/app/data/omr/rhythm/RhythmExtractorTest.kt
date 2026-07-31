package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadStaffAssignment
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.symbol.ClassifiedRestCandidate
import com.sheetsight.app.data.omr.symbol.RestSymbolLabel
import com.sheetsight.app.data.omr.symbol.SvmModelKind
import com.sheetsight.app.data.omr.symbol.SvmModelSpec
import com.sheetsight.app.data.omr.symbol.SymbolClassification
import com.sheetsight.app.data.omr.symbol.SymbolStaffAssignment
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmExtractorTest {

    @Test
    fun `stemmed solid note resolves to quarter`() {
        val page = SyntheticPage()
        val note = page.note(0, BoundingBox(30, 55, 39, 63))
        page.stem(BoundingBox(37, 25, 40, 60))
        val chord = chord(0, listOf(note), BoundingBox(30, 25, 40, 63), StemDirection.UP)

        val result = page.extract(listOf(note), listOf(chord)).noteGroups.single()

        assertEquals(StemAssociationStatus.ASSIGNED, result.stemAssociation.status)
        assertEquals(StemDirection.UP, result.stemDirection)
        assertEquals(0, result.beamCount)
        assertEquals(0, result.flagCount)
        assertEquals(RhythmDuration.QUARTER, result.baseDuration)
        assertEquals(RhythmValue.of(1, 4), result.dottedDuration)
        assertEquals(RhythmResolutionState.RESOLVED, result.resolutionState)
    }

    @Test
    fun `open notehead with stem resolves to half`() {
        val page = SyntheticPage()
        val note = page.note(
            0,
            BoundingBox(30, 55, 39, 63),
            type = NoteheadType.HALF_OR_WHOLE
        )
        page.stem(BoundingBox(37, 25, 40, 60))
        val chord = chord(0, listOf(note), BoundingBox(30, 25, 40, 63), StemDirection.UP)

        val result = page.extract(listOf(note), listOf(chord)).noteGroups.single()

        assertEquals(RhythmDuration.HALF, result.baseDuration)
        assertEquals(RhythmValue.of(1, 2), result.dottedDuration)
    }

    @Test
    fun `stemless open notehead resolves to whole`() {
        val page = SyntheticPage()
        val note = page.note(
            0,
            BoundingBox(30, 55, 39, 63),
            type = NoteheadType.HALF_OR_WHOLE
        )
        val chord = chord(
            0,
            listOf(note),
            note.boundingBox,
            StemDirection.NONE,
            hasStem = false
        )

        val result = page.extract(listOf(note), listOf(chord)).noteGroups.single()

        assertEquals(StemAssociationStatus.NONE, result.stemAssociation.status)
        assertEquals(RhythmDuration.WHOLE, result.baseDuration)
        assertEquals(RhythmValue.of(1, 1), result.dottedDuration)
    }

    @Test
    fun `isolated flag resolves to eighth and is not reported as a beam`() {
        val page = SyntheticPage()
        val note = page.note(0, BoundingBox(30, 55, 39, 63))
        page.stem(BoundingBox(37, 25, 40, 60))
        page.symbol(BoundingBox(38, 25, 50, 30))
        val chord = chord(0, listOf(note), BoundingBox(30, 25, 40, 63), StemDirection.UP)

        val result = page.extract(listOf(note), listOf(chord)).noteGroups.single()

        assertEquals(0, result.beamCount)
        assertEquals(1, result.flagCount)
        assertEquals(RhythmDuration.EIGHTH, result.baseDuration)
    }

    @Test
    fun `one beam associates two note groups`() {
        val fixture = beamedFixture(beamCount = 1)

        val results = fixture.page.extract(fixture.notes, fixture.chords).noteGroups

        assertEquals(2, results.size)
        assertTrue(results.all { it.beamCount == 1 })
        assertTrue(results.all { it.flagCount == 0 })
        assertTrue(results.all { it.baseDuration == RhythmDuration.EIGHTH })
    }

    @Test
    fun `two beams resolve both groups to sixteenth`() {
        val fixture = beamedFixture(beamCount = 2)

        val results = fixture.page.extract(fixture.notes, fixture.chords).noteGroups

        assertTrue(results.all { it.beamCount == 2 })
        assertTrue(results.all { it.flagCount == 0 })
        assertTrue(results.all { it.baseDuration == RhythmDuration.SIXTEENTH })
    }

    @Test
    fun `augmentation dot preserves scan evidence and applies dotted value`() {
        val page = SyntheticPage()
        val note = page.note(0, BoundingBox(30, 55, 39, 63))
        page.stem(BoundingBox(37, 25, 40, 60))
        page.symbol(BoundingBox(45, 56, 49, 60))
        val chord = chord(0, listOf(note), BoundingBox(30, 25, 40, 63), StemDirection.UP)

        val result = page.extract(listOf(note), listOf(chord)).noteGroups.single()

        assertEquals(1, result.dotCount)
        assertEquals(RhythmDuration.QUARTER, result.baseDuration)
        assertEquals(RhythmValue.of(3, 8), result.dottedDuration)
        val dot = result.dotEvidence.single()
        assertEquals(true, dot.detected)
        assertTrue(dot.foregroundPixelCount!! in dot.minimumPixelCount!!..dot.maximumPixelCount!!)
        assertEquals(BoundingBox(44, 50, 50, 63), dot.scanRegion)
    }

    @Test
    fun `all notes in one chord share one duration`() {
        val page = SyntheticPage()
        val upper = page.note(0, BoundingBox(30, 45, 39, 53), staffPosition = 4)
        val lower = page.note(1, BoundingBox(30, 65, 39, 73), staffPosition = 0)
        page.stem(BoundingBox(37, 20, 40, 70))
        val chord = chord(
            3,
            listOf(upper, lower),
            BoundingBox(30, 20, 40, 73),
            StemDirection.UP
        )

        val result = page.extract(listOf(upper, lower), listOf(chord)).noteGroups.single()

        assertEquals(listOf(1, 0), result.noteheads.map { it.id })
        assertEquals(RhythmDuration.QUARTER, result.baseDuration)
        assertEquals(RhythmValue.of(1, 4), result.dottedDuration)
    }

    @Test
    fun `shared stem stays unresolved and is never assigned to unrelated groups`() {
        val page = SyntheticPage()
        val upper = page.note(0, BoundingBox(30, 45, 39, 53), staffPosition = 4)
        val lower = page.note(1, BoundingBox(30, 65, 39, 73), staffPosition = 0)
        page.stem(BoundingBox(37, 20, 40, 70))
        val upperChord = chord(
            0,
            listOf(upper),
            BoundingBox(30, 20, 40, 53),
            StemDirection.UP
        )
        val lowerChord = chord(
            1,
            listOf(lower),
            BoundingBox(30, 65, 40, 73),
            StemDirection.UP
        )

        val results = page.extract(
            listOf(upper, lower),
            listOf(upperChord, lowerChord)
        ).noteGroups

        assertTrue(results.all {
            it.stemAssociation.status == StemAssociationStatus.SHARED_BETWEEN_GROUPS
        })
        assertTrue(results.all { it.baseDuration == null })
        assertTrue(results.all {
            RhythmUnresolvedReason.STEM_SHARED_BETWEEN_GROUPS in it.unresolvedReasons
        })
    }

    @Test
    fun `rhythm extraction does not mutate note group geometry`() {
        val page = SyntheticPage()
        val note = page.note(0, BoundingBox(30, 55, 39, 63))
        page.stem(BoundingBox(37, 25, 40, 60))
        val sourcePixels = note.sourcePixelIndices.copyOf()
        val originalBox = BoundingBox(30, 25, 40, 63)
        val chord = chord(0, listOf(note), originalBox, StemDirection.UP)

        page.extract(listOf(note), listOf(chord))

        assertEquals(originalBox, chord.boundingBox)
        assertArrayEquals(sourcePixels, note.sourcePixelIndices)
    }

    @Test
    fun `output ordering is deterministic by existing group identifier`() {
        val page = SyntheticPage()
        val left = page.note(10, BoundingBox(20, 55, 29, 63))
        val right = page.note(11, BoundingBox(60, 55, 69, 63))
        val leftChord = chord(
            7,
            listOf(left),
            left.boundingBox,
            StemDirection.NONE,
            hasStem = false
        )
        val rightChord = chord(
            2,
            listOf(right),
            right.boundingBox,
            StemDirection.NONE,
            hasStem = false
        )

        val first = page.extract(
            listOf(right, left),
            listOf(leftChord, rightChord)
        ).noteGroups.map { it.noteGroupId }
        val second = page.extract(
            listOf(left, right),
            listOf(rightChord, leftChord)
        ).noteGroups.map { it.noteGroupId }

        assertEquals(listOf(2, 7), first)
        assertEquals(first, second)
    }

    @Test
    fun `verified classifier rests map to exact supported durations`() {
        val page = SyntheticPage()
        val labels = listOf(
            RestSymbolLabel.QUARTER,
            RestSymbolLabel.EIGHTH,
            RestSymbolLabel.SIXTEENTH,
            RestSymbolLabel.THIRTY_SECOND,
            RestSymbolLabel.SIXTY_FOURTH
        )
        val rests = labels.mapIndexed { index, label ->
            classifiedRest(label, BoundingBox(10 + index * 12, 45, 18 + index * 12, 60))
        }.reversed()

        val results = RhythmExtractor.extract(
            noteheads = emptyList(),
            chords = emptyList(),
            evidence = page.evidence(),
            rests = rests
        ).rests

        assertEquals(listOf(0, 1, 2, 3, 4), results.map { it.restId })
        assertEquals(
            listOf(
                RhythmDuration.QUARTER,
                RhythmDuration.EIGHTH,
                RhythmDuration.SIXTEENTH,
                RhythmDuration.THIRTY_SECOND,
                RhythmDuration.SIXTY_FOURTH
            ),
            results.map { it.baseDuration }
        )
        assertTrue(results.all { it.resolutionState == RhythmResolutionState.RESOLVED })
        assertEquals(labels, results.map { it.label })
    }

    @Test
    fun `classified dotted rest applies dot and retains classifier source`() {
        val source = classifiedRest(
            RestSymbolLabel.QUARTER,
            BoundingBox(20, 45, 28, 60),
            hasDot = true
        )

        val result = RhythmExtractor.extract(
            noteheads = emptyList(),
            chords = emptyList(),
            evidence = SyntheticPage().evidence(),
            rests = listOf(source)
        ).rests.single()

        assertEquals(source, result.source)
        assertEquals(1, result.dotCount)
        assertEquals(RhythmValue.of(3, 8), result.dottedDuration)
    }

    @Test
    fun `whole-or-half classifier result remains explicitly unresolved`() {
        val source = classifiedRest(
            RestSymbolLabel.WHOLE_OR_HALF,
            BoundingBox(20, 45, 28, 60)
        )

        val result = RhythmExtractor.extract(
            noteheads = emptyList(),
            chords = emptyList(),
            evidence = SyntheticPage().evidence(),
            rests = listOf(source)
        ).rests.single()

        assertNull(result.baseDuration)
        assertNull(result.dottedDuration)
        assertEquals(RhythmResolutionState.UNRESOLVED, result.resolutionState)
        assertEquals(
            listOf(RhythmUnresolvedReason.REST_WHOLE_HALF_AMBIGUOUS),
            result.unresolvedReasons
        )
    }

    private fun classifiedRest(
        label: RestSymbolLabel,
        box: BoundingBox,
        hasDot: Boolean = false
    ): ClassifiedRestCandidate {
        val coarseLabel = when (label) {
            RestSymbolLabel.SIXTEENTH,
            RestSymbolLabel.THIRTY_SECOND,
            RestSymbolLabel.SIXTY_FOURTH -> RestSymbolLabel.EIGHTH
            else -> label
        }
        val coarse = classification(SvmModelKind.REST, coarseLabel)
        val refined = if (coarseLabel == RestSymbolLabel.EIGHTH) {
            classification(SvmModelKind.REST_ABOVE_EIGHTH, label)
        } else {
            null
        }
        return ClassifiedRestCandidate(
            boundingBox = box,
            label = label,
            assignment = SymbolStaffAssignment(track = 0, group = 0),
            hasAugmentationDot = hasDot,
            coarseClassification = coarse,
            refinedClassification = refined
        )
    }

    private fun classification(
        model: SvmModelKind,
        label: RestSymbolLabel
    ): SymbolClassification {
        val spec = SvmModelSpec.forKind(model)
        return SymbolClassification(model, spec.labels.indexOf(label), label, emptyList())
    }

    private data class BeamFixture(
        val page: SyntheticPage,
        val notes: List<NoteheadCandidate>,
        val chords: List<ChordCandidate>
    )

    private fun beamedFixture(beamCount: Int): BeamFixture {
        val page = SyntheticPage()
        val left = page.note(0, BoundingBox(25, 55, 34, 63))
        val right = page.note(1, BoundingBox(65, 55, 74, 63))
        page.stem(BoundingBox(32, 25, 35, 60))
        page.stem(BoundingBox(72, 25, 75, 60))
        page.symbol(BoundingBox(33, 25, 74, 30))
        if (beamCount == 2) page.symbol(BoundingBox(33, 33, 74, 38))
        return BeamFixture(
            page,
            notes = listOf(left, right),
            chords = listOf(
                chord(
                    0,
                    listOf(left),
                    BoundingBox(25, 25, 35, 63),
                    StemDirection.UP
                ),
                chord(
                    1,
                    listOf(right),
                    BoundingBox(65, 25, 75, 63),
                    StemDirection.UP
                )
            )
        )
    }

    private fun chord(
        id: Int,
        notes: List<NoteheadCandidate>,
        box: BoundingBox,
        direction: StemDirection,
        hasStem: Boolean = true
    ): ChordCandidate =
        ChordCandidate(
            id = id,
            noteheads = notes.sortedBy { it.staffAssignment.staffLinePosition },
            boundingBox = box,
            stemDirection = direction,
            hasStem = hasStem,
            track = 0,
            group = 0
        )

    private class SyntheticPage(
        private val width: Int = 100,
        private val height: Int = 100
    ) {
        private val staff = BooleanArray(width * height)
        private val symbols = BooleanArray(width * height)
        private val stems = BooleanArray(width * height)
        private val noteheads = BooleanArray(width * height)
        private val clefs = BooleanArray(width * height)

        fun note(
            id: Int,
            box: BoundingBox,
            type: NoteheadType = NoteheadType.SOLID,
            staffPosition: Int = 2
        ): NoteheadCandidate {
            fill(noteheads, box)
            fill(symbols, box)
            return NoteheadCandidate(
                id = id,
                boundingBox = box,
                type = type,
                staffAssignment = NoteheadStaffAssignment(0, 0, staffPosition),
                sourcePixelIndices = indices(box),
                stemOnRight = true
            )
        }

        fun stem(box: BoundingBox) {
            fill(stems, box)
        }

        fun symbol(box: BoundingBox) {
            fill(symbols, box)
        }

        fun evidence(): RhythmEvidenceMasks =
            RhythmEvidenceMasks(
                width = width,
                height = height,
                staff = staff,
                symbols = symbols,
                stems = stems,
                noteheads = noteheads,
                clefsKeys = clefs,
                staffGrid = listOf(listOf(syntheticStaff()))
            )

        fun extract(
            notes: List<NoteheadCandidate>,
            chords: List<ChordCandidate>
        ): RhythmExtractionResult =
            RhythmExtractor.extract(notes, chords, evidence())

        private fun fill(mask: BooleanArray, box: BoundingBox) {
            for (y in box.top until box.bottom) {
                for (x in box.left until box.right) {
                    mask[y * width + x] = true
                }
            }
        }

        private fun indices(box: BoundingBox): IntArray =
            buildList {
                for (y in box.top until box.bottom) {
                    for (x in box.left until box.right) add(y * width + x)
                }
            }.toIntArray()

        private fun syntheticStaff(): AssignedStaff {
            val lines = StafflinePosition.entries.mapIndexed { index, position ->
                Staffline(
                    position = position,
                    points = (0 until width).map { x ->
                        StafflinePoint(x, 30 + index * 10)
                    }
                )
            }
            return AssignedStaff(ZoneStaff(lines), track = 0, group = 0)
        }
    }
}
