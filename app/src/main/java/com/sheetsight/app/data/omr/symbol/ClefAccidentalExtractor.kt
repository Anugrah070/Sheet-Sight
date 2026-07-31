package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.dewarp.StaffMaskMorphology
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.BoundingBoxMerger
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import com.sheetsight.app.data.omr.track.StaffGeometryResolver
import com.sheetsight.app.data.omr.track.yLower
import com.sheetsight.app.data.omr.track.yUpper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of oemer 0.1.8
 * `symbol_extraction.py::parse_clefs_keys()`/`filter_clef_box()`.
 *
 * The supplied mask is vertically closed, component boxes are filtered
 * and Ward-merged with the source thresholds, then the exact clef and
 * `sfn` ONNX exports classify the processed binary crops.
 */
@Singleton
class ClefAccidentalExtractor @Inject constructor(
    private val classifierLoader: SymbolClassifierLoader
) {
    /** Extracts and classifies clefs and sharp/flat/natural candidates. */
    fun extract(
        clefsKeys: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>,
        noteIdMap: IntArray
    ): ClefAccidentalResult {
        require(clefsKeys.size == width * height)
        require(noteIdMap.size == width * height)
        val globalUnitSize = StaffGeometryResolver.globalUnitSize(staffGrid)
        val processedMask = closeVertically(clefsKeys, width, height, globalUnitSize)
        val boxes = candidateBoxes(
            processedMask,
            width,
            height,
            horizontalBounds,
            staffGrid,
            globalUnitSize
        )
        val (clefBoxes, accidentalBoxes) = distinguish(boxes, processedMask, width, staffGrid)
        return ClefAccidentalResult(
            clefs = classifyClefs(clefBoxes, processedMask, width, height, staffGrid),
            accidentals = classifyAccidentals(
                accidentalBoxes,
                processedMask,
                width,
                height,
                staffGrid,
                noteIdMap
            )
        )
    }

    private fun candidateBoxes(
        mask: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>,
        globalUnitSize: Double
    ): List<BoundingBox> {
        val initial = ConnectedComponentBoxExtractor.extract(mask, width, height)
            .filter { it.center().first in horizontalBounds }
        val overlapMerged = BoundingBoxMerger.resolveOverlaps(
            initial,
            merge = true,
            overlapRatio = CLEF_OVERLAP_RATIO
        )
        val areaFiltered = overlapMerged.filter { box ->
            val center = box.center()
            val unitSize = StaffGeometryResolver.unitSizeAt(staffGrid, center.first, center.second)
            box.width * box.height > unitSize * unitSize
        }
        return BoundingBoxMerger.mergeNearbyWard(
            areaFiltered,
            globalUnitSize * NEARBY_DISTANCE_UNIT_RATIO
        )
    }

    private fun distinguish(
        boxes: List<BoundingBox>,
        mask: BooleanArray,
        width: Int,
        staffGrid: List<List<AssignedStaff>>
    ): Pair<List<BoundingBox>, List<BoundingBox>> {
        val clefs = mutableListOf<BoundingBox>()
        val accidentals = mutableListOf<BoundingBox>()
        boxes.forEach { box ->
            val center = box.center()
            val unitSize = StaffGeometryResolver.unitSizeAt(staffGrid, center.first, center.second)
            val boxArea = box.width * box.height
            val sizeRatio = boxArea / (unitSize * unitSize)
            val foregroundRatio = foregroundCount(mask, width, box).toDouble() / boxArea
            when {
                sizeRatio > CLEF_SIZE_RATIO && foregroundRatio < MAX_CLEF_FOREGROUND_RATIO ->
                    clefs += box
                box.width > unitSize / 2.0 && box.height > unitSize / 2.0 ->
                    accidentals += box
            }
        }
        return filterClefBoxes(clefs, staffGrid) to accidentals
    }

    private fun filterClefBoxes(
        boxes: List<BoundingBox>,
        staffGrid: List<List<AssignedStaff>>
    ): List<BoundingBox> = boxes.filter { box ->
        val center = box.center()
        val unitSize = StaffGeometryResolver.unitSizeAt(staffGrid, center.first, center.second)
        val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
        box.width >= unitSize * MIN_CLEF_DIMENSION_UNITS &&
                box.height >= unitSize * MIN_CLEF_DIMENSION_UNITS &&
                center.second.toDouble() in staff.yUpper..staff.yLower
    }

    private fun classifyClefs(
        boxes: List<BoundingBox>,
        mask: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<AssignedStaff>>
    ): List<ClefCandidate> {
        val spec = SvmModelSpec.CLEF
        val classifier = classifierLoader.load(spec.kind)
        return boxes.map { box ->
            val classification = classifier.classify(
                SvmFeatureExtractor.extract(mask, width, height, box)
            )
            val label = classification.label as? ClefSymbolLabel
                ?: throw IllegalStateException("CLEF emitted ${classification.label.sourceName}")
            ClefCandidate(box, label, assignment(box, staffGrid), classification)
        }
    }

    private fun classifyAccidentals(
        boxes: List<BoundingBox>,
        mask: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<AssignedStaff>>,
        noteIdMap: IntArray
    ): List<AccidentalCandidate> {
        val spec = SvmModelSpec.ACCIDENTAL
        val classifier = classifierLoader.load(spec.kind)
        return boxes.map { box ->
            val classification = classifier.classify(
                SvmFeatureExtractor.extract(mask, width, height, box)
            )
            val label = classification.label as? AccidentalSymbolLabel
                ?: throw IllegalStateException("ACCIDENTAL emitted ${classification.label.sourceName}")
            AccidentalCandidate(
                box,
                label,
                assignment(box, staffGrid),
                nearbyNoteId(box, noteIdMap, width, height, staffGrid),
                classification
            )
        }
    }

    private fun nearbyNoteId(
        box: BoundingBox,
        noteIdMap: IntArray,
        width: Int,
        height: Int,
        staffGrid: List<List<AssignedStaff>>
    ): Int? {
        val center = box.center()
        val unitSize = Math.rint(
            StaffGeometryResolver.unitSizeAt(staffGrid, center.first, center.second)
        ).toInt()
        for (x in box.right until box.right + unitSize) {
            if (center.second !in 0 until height || x !in 0 until width) continue
            val noteId = noteIdMap[center.second * width + x]
            if (noteId >= 0) return noteId
        }
        return null
    }

    private fun closeVertically(
        mask: BooleanArray,
        width: Int,
        height: Int,
        globalUnitSize: Double
    ): BooleanArray {
        val kernelSize = (globalUnitSize / 2.0).toInt()
        require(kernelSize > 0) { "oemer clef morphology requires unit_size//2 > 0" }
        val dilated = StaffMaskMorphology.slide(mask, width, height, kernelSize, true, false)
        return StaffMaskMorphology.slide(dilated, width, height, kernelSize, true, true)
    }

    private fun assignment(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>
    ): SymbolStaffAssignment {
        val center = box.center()
        val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
        return SymbolStaffAssignment(staff.track, staff.group)
    }

    private fun foregroundCount(mask: BooleanArray, width: Int, box: BoundingBox): Int {
        var count = 0
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                if (mask[y * width + x]) count++
            }
        }
        return count
    }

    private companion object {
        const val CLEF_SIZE_RATIO = 3.5
        const val MAX_CLEF_FOREGROUND_RATIO = 0.45
        const val MIN_CLEF_DIMENSION_UNITS = 1.5
        const val CLEF_OVERLAP_RATIO = 0.3
        const val NEARBY_DISTANCE_UNIT_RATIO = 1.2
    }
}

/** Classified output of [ClefAccidentalExtractor]. */
data class ClefAccidentalResult(
    val clefs: List<ClefCandidate>,
    val accidentals: List<AccidentalCandidate>
)
