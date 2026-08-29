package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.grouping.NoteGroupingResult
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.StaffZoneGridExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production symbol-stage coordinator for oemer 0.1.8
 * `symbol_extraction.py::extract()`.
 *
 * The generic symbol mask is merged with clef/key and stem/rest masks as
 * in `ete.py` after dewarping. Page-sized working arrays are local and
 * released after extraction; no additional full-resolution debug image
 * is retained.
 *
 * Rest output is consumed directly by the Phase 5 rhythm contract after
 * the classifier parity gate completed.
 */
@Singleton
class SymbolExtractor @Inject constructor(
    private val clefAccidentalExtractor: ClefAccidentalExtractor,
    private val restExtractor: RestExtractor
) {
    /** Extracts geometric barlines and all four trained-SVM symbol roles. */
    fun extract(
        masks: OmrClassMasks,
        grouping: NoteGroupingResult,
        staffGrid: List<List<AssignedStaff>>,
        noteheads: List<NoteheadCandidate>
    ): SymbolExtractionResult {
        require(grouping.width == masks.width && grouping.height == masks.height)
        require(staffGrid.flatten().isNotEmpty()) { "symbol extraction requires a staff grid" }
        val horizontalBounds = horizontalBounds(masks)
        val mergedSymbols = mergeSymbols(masks)
        val noteIdMap = buildNoteIdMap(noteheads, mergedSymbols, masks.width, masks.height)
        val barlineResult = MusicalBarlineExtractor.extractWithDiagnostics(
            grouping.groupMap,
            masks.stemsRests,
            // oemer overlaps unused straight-line candidates from model two
            // with the independent generic-symbol prediction from model one.
            // Passing mergedSymbols here includes stemsRests in both operands,
            // allowing every unclaimed note stem to validate itself.
            masks.symbols,
            masks.width,
            masks.height,
            horizontalBounds,
            staffGrid
        )
        val clefsAndAccidentals = clefAccidentalExtractor.extract(
            masks.clefsKeys,
            masks.width,
            masks.height,
            horizontalBounds,
            staffGrid,
            noteIdMap
        )
        val restResult = restExtractor.extractWithDiagnostics(
            grouping.groupMap,
            masks.stemsRests,
            mergedSymbols,
            barlineResult.candidates.map { it.boundingBox },
            masks.width,
            masks.height,
            horizontalBounds,
            staffGrid
        )
        return SymbolExtractionResult(
            barlines = barlineResult.candidates,
            clefs = clefsAndAccidentals.clefs,
            accidentals = clefsAndAccidentals.accidentals,
            rests = restResult.candidates,
            barlineDiagnostics = barlineResult.diagnostics,
            restDiagnostics = restResult.diagnostics
        )
    }

    private fun horizontalBounds(masks: OmrClassMasks): IntRange {
        val ranges = StaffZoneGridExtractor.zoneRanges(
            masks.staff,
            masks.width,
            masks.height,
            StaffZoneGridExtractor.DEFAULT_SPLITS
        )
        require(ranges.isNotEmpty()) { "symbol extraction requires staff-zone bounds" }
        return ranges.first().first..ranges.last().last
    }

    private fun mergeSymbols(masks: OmrClassMasks): BooleanArray =
        BooleanArray(masks.width * masks.height) { index ->
            masks.symbols[index] || masks.clefsKeys[index] || masks.stemsRests[index]
        }

    private fun buildNoteIdMap(
        noteheads: List<NoteheadCandidate>,
        mergedSymbols: BooleanArray,
        width: Int,
        height: Int
    ): IntArray {
        val noteIdMap = IntArray(width * height) { -1 }
        noteheads.sortedBy { it.id }.forEach { note ->
            val box = note.boundingBox
            for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(height)) {
                for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(width)) {
                    val index = y * width + x
                    if (mergedSymbols[index]) noteIdMap[index] = note.id
                }
            }
        }
        return noteIdMap
    }
}
