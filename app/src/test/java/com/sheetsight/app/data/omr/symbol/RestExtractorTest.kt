package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.rhythm.RhythmDuration
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceMasks
import com.sheetsight.app.data.omr.rhythm.RhythmExtractor
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

    private fun staffGrid(width: Int): List<List<AssignedStaff>> {
        val positions = StafflinePosition.entries
        val lines = positions.mapIndexed { index, position ->
            val y = 10 + index * 10
            Staffline(position, listOf(StafflinePoint(0, y), StafflinePoint(width - 1, y)))
        }
        return listOf(listOf(AssignedStaff(ZoneStaff(lines), track = 0, group = 0)))
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
