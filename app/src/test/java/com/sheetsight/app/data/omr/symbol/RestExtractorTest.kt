package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.rhythm.RhythmDuration
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceMasks
import com.sheetsight.app.data.omr.rhythm.RhythmExtractor
import com.sheetsight.app.data.omr.rhythm.RhythmResolutionState
import com.sheetsight.app.data.omr.rhythm.RhythmUnresolvedReason
import com.sheetsight.app.data.omr.rhythm.RhythmValue
import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestExtractorTest {

    @Test
    fun `eighth coarse result uses above-eighth classifier and preserves dot`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 20, 28, 40)
        val mergedSymbols = stemsRests.copyOf()
        mergedSymbols[30 * width + 30] = true
        val calls = mutableListOf<SvmModelKind>()
        val extractor = RestExtractor(loader(calls))

        val results = extractor.extract(
            groupMap = IntArray(width * height) { -1 },
            stemsRests = stemsRests,
            mergedSymbols = mergedSymbols,
            barlineBoxes = emptyList(),
            width = width,
            height = height,
            horizontalBounds = 0 until width,
            staffGrid = staffGrid(width)
        )

        assertEquals(1, results.size)
        assertEquals(RestSymbolLabel.SIXTEENTH, results.single().label)
        assertTrue(results.single().hasAugmentationDot)
        assertEquals(listOf(SvmModelKind.REST, SvmModelKind.REST_ABOVE_EIGHTH), calls)

        val rhythm = RhythmExtractor.extract(
            noteheads = emptyList(),
            chords = emptyList(),
            evidence = rhythmEvidence(width, height),
            rests = results
        ).rests.single()
        assertEquals(RhythmDuration.SIXTEENTH, rhythm.baseDuration)
        assertEquals(RhythmValue.of(3, 32), rhythm.dottedDuration)
        assertEquals(results.single(), rhythm.source)
    }

    @Test
    fun `coarse quarter result does not load refinement classifier`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 20, 28, 40)
        val calls = mutableListOf<SvmModelKind>()
        val loader = SymbolClassifierLoader(
            SvmClassifierBackend { spec ->
                calls += spec.kind
                SymbolClassifier {
                    SymbolClassification(
                        spec.kind,
                        1,
                        RestSymbolLabel.QUARTER,
                        emptyList()
                    )
                }
            }
        )

        val results = RestExtractor(loader).extract(
            IntArray(width * height) { -1 },
            stemsRests,
            stemsRests,
            emptyList(),
            width,
            height,
            0 until width,
            staffGrid(width)
        )

        assertEquals(RestSymbolLabel.QUARTER, results.single().label)
        assertEquals(listOf(SvmModelKind.REST), calls)
    }

    @Test
    fun `whole rest is resolved when its top edge hangs from a staff line`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 20, 32, 26)

        val candidate = RestExtractor(wholeHalfLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        ).single()

        assertEquals(RestWholeHalfPlacement.WHOLE, candidate.wholeHalfPlacement)
        val rhythm = RhythmExtractor.extract(
            emptyList(), emptyList(), rhythmEvidence(width, height), listOf(candidate)
        ).rests.single()
        assertEquals(RhythmDuration.WHOLE, rhythm.baseDuration)
        assertTrue(rhythm.unresolvedReasons.isEmpty())
    }

    @Test
    fun `half rest is resolved when its bottom edge sits on a staff line`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 24, 32, 31)

        val candidate = RestExtractor(wholeHalfLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        ).single()

        assertEquals(RestWholeHalfPlacement.HALF, candidate.wholeHalfPlacement)
        val rhythm = RhythmExtractor.extract(
            emptyList(), emptyList(), rhythmEvidence(width, height), listOf(candidate)
        ).rests.single()
        assertEquals(RhythmDuration.HALF, rhythm.baseDuration)
    }

    @Test
    fun `thin vertical fragment adjacent to a claimed note group is rejected as a stem`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 20, 25, 45)
        val groupMap = IntArray(width * height) { -1 }
        groupMap[32 * width + 17] = 4

        val candidates = RestExtractor(wholeHalfLoader()).extract(
            groupMap, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `page-edge component far below every staff is rejected before classification`() {
        val width = 80
        val height = 120
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 90, 28, 110)

        val candidates = RestExtractor(wholeHalfLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `right staff-edge component is rejected even when vertically centered`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 70, 20, 79, 40)

        val candidates = RestExtractor(wholeHalfLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `complete system extent keeps a real rest beyond one truncated staff row`() {
        val width = 80
        val height = 140
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 60, 20, 68, 40)

        val candidates = RestExtractor(quarterLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, truncatedTrebleFullBassGrid()
        )

        assertEquals(1, candidates.size)
        assertEquals(0, candidates.single().assignment.track)
    }

    @Test
    fun `quarter classification conflicting with clear half-rest placement stays unresolved`() {
        val width = 80
        val height = 70
        val stemsRests = BooleanArray(width * height)
        fill(stemsRests, width, 20, 24, 32, 31)
        val candidate = RestExtractor(quarterLoader()).extract(
            IntArray(width * height) { -1 }, stemsRests, stemsRests, emptyList(),
            width, height, 0 until width, staffGrid(width)
        ).single()

        assertEquals(RestSymbolLabel.QUARTER, candidate.label)
        assertEquals(RestWholeHalfPlacement.HALF, candidate.wholeHalfPlacement)
        val rhythm = RhythmExtractor.extract(
            emptyList(), emptyList(), rhythmEvidence(width, height), listOf(candidate)
        ).rests.single()
        assertEquals(RhythmResolutionState.UNRESOLVED, rhythm.resolutionState)
        assertTrue(
            RhythmUnresolvedReason.REST_CLASSIFIER_GEOMETRY_CONFLICT in rhythm.unresolvedReasons
        )
    }

    @Test
    fun `coarse eighth candidate cannot bypass required refinement`() {
        val coarseSpec = SvmModelSpec.REST
        val coarse = SymbolClassification(
            model = SvmModelKind.REST,
            classId = coarseSpec.labels.indexOf(RestSymbolLabel.EIGHTH),
            label = RestSymbolLabel.EIGHTH,
            decisionScores = emptyList()
        )

        assertThrows(IllegalArgumentException::class.java) {
            ClassifiedRestCandidate(
                boundingBox = BoundingBox(0, 0, 8, 16),
                label = RestSymbolLabel.EIGHTH,
                assignment = SymbolStaffAssignment(track = 0, group = 0),
                hasAugmentationDot = false,
                coarseClassification = coarse,
                refinedClassification = null
            )
        }
    }

    private fun loader(calls: MutableList<SvmModelKind>): SymbolClassifierLoader =
        SymbolClassifierLoader(
            SvmClassifierBackend { spec ->
                calls += spec.kind
                val label = when (spec.kind) {
                    SvmModelKind.REST -> RestSymbolLabel.EIGHTH
                    SvmModelKind.REST_ABOVE_EIGHTH -> RestSymbolLabel.SIXTEENTH
                    else -> error("Unexpected model ${spec.kind}")
                }
                SymbolClassifier {
                    SymbolClassification(spec.kind, spec.labels.indexOf(label), label, emptyList())
                }
            }
        )

    private fun wholeHalfLoader(): SymbolClassifierLoader = SymbolClassifierLoader(
        SvmClassifierBackend { spec ->
            require(spec.kind == SvmModelKind.REST)
            SymbolClassifier {
                SymbolClassification(
                    spec.kind,
                    0,
                    RestSymbolLabel.WHOLE_OR_HALF,
                    listOf(2.0f, 0.5f, -0.5f)
                )
            }
        }
    )

    private fun quarterLoader(): SymbolClassifierLoader = SymbolClassifierLoader(
        SvmClassifierBackend { spec ->
            require(spec.kind == SvmModelKind.REST)
            SymbolClassifier {
                SymbolClassification(
                    spec.kind,
                    spec.labels.indexOf(RestSymbolLabel.QUARTER),
                    RestSymbolLabel.QUARTER,
                    listOf(2.0f, 0.5f, -0.5f)
                )
            }
        }
    )

    private fun staffGrid(width: Int): List<List<AssignedStaff>> {
        val positions = StafflinePosition.entries
        val lines = positions.mapIndexed { index, position ->
            val y = 10 + index * 10
            Staffline(position, listOf(StafflinePoint(0, y), StafflinePoint(width - 1, y)))
        }
        return listOf(listOf(AssignedStaff(ZoneStaff(lines), track = 0, group = 0)))
    }

    private fun truncatedTrebleFullBassGrid(): List<List<AssignedStaff>> {
        fun assigned(top: Int, right: Int, track: Int): AssignedStaff {
            val lines = StafflinePosition.entries.mapIndexed { index, position ->
                val y = top + index * 10
                Staffline(position, listOf(StafflinePoint(0, y), StafflinePoint(right, y)))
            }
            return AssignedStaff(ZoneStaff(lines), track = track, group = 0)
        }
        return listOf(listOf(assigned(10, 50, 0), assigned(80, 79, 1)))
    }

    private fun rhythmEvidence(width: Int, height: Int): RhythmEvidenceMasks {
        val emptyMask = BooleanArray(width * height)
        return RhythmEvidenceMasks(
            width = width,
            height = height,
            staff = emptyMask,
            symbols = emptyMask,
            stems = emptyMask,
            noteheads = emptyMask,
            clefsKeys = emptyMask,
            staffGrid = staffGrid(width)
        )
    }

    private fun fill(
        mask: BooleanArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        for (y in top until bottom) {
            for (x in left until right) mask[y * width + x] = true
        }
    }
}
