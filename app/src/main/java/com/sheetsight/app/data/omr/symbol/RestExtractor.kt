package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.BoundingBoxMerger
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import com.sheetsight.app.data.omr.track.StaffGeometryResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of oemer 0.1.8 `symbol_extraction.py::parse_rests()` and
 * `gen_rests()`.
 *
 * Note-group and barline pixels are removed before connected-component
 * extraction. Surviving crops run through `rests.model`; only its
 * `rest_8th` output runs through the required `rests_above8.model`
 * refinement. No heuristic label fallback exists.
 */
@Singleton
class RestExtractor @Inject constructor(
    private val classifierLoader: SymbolClassifierLoader
) {
    /** Extracts genuine two-stage classified rest candidates. */
    fun extract(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        mergedSymbols: BooleanArray,
        barlineBoxes: List<BoundingBox>,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): List<ClassifiedRestCandidate> {
        require(groupMap.size == width * height)
        require(stemsRests.size == width * height)
        require(mergedSymbols.size == width * height)
        val globalUnitSize = StaffGeometryResolver.globalUnitSize(staffGrid)
        val restMask = buildRestMask(groupMap, stemsRests, barlineBoxes, width, height)
        val boxes = candidateBoxes(
            restMask,
            width,
            height,
            horizontalBounds,
            staffGrid,
            globalUnitSize
        )
        return classify(boxes, restMask, mergedSymbols, width, height, staffGrid)
    }

    private fun buildRestMask(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        barlineBoxes: List<BoundingBox>,
        width: Int,
        height: Int
    ): BooleanArray {
        val barlineMask = BooleanArray(width * height)
        barlineBoxes.forEach { box ->
            for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(height)) {
                for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(width)) {
                    barlineMask[y * width + x] = true
                }
            }
        }
        return BooleanArray(width * height) { index ->
            stemsRests[index] && groupMap[index] < 0 && !barlineMask[index]
        }
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
        if (initial.isEmpty()) return emptyList()
        val nearbyMerged = BoundingBoxMerger.mergeNearbyWard(
            initial,
            globalUnitSize * NEARBY_DISTANCE_UNIT_RATIO
        )
        val overlapFiltered = BoundingBoxMerger.resolveOverlaps(nearbyMerged)
        return overlapFiltered.filter { box ->
            val center = box.center()
            val localUnit = StaffGeometryResolver.unitSizeAt(
                staffGrid,
                center.first,
                center.second
            )
            val areaAccepted = box.width * box.height > localUnit * localUnit * MIN_AREA_UNIT_SQUARED
            areaAccepted &&
                    box.height <= globalUnitSize * MAX_HEIGHT_UNITS &&
                    box.width >= globalUnitSize * MIN_WIDTH_UNITS
        }
    }

    private fun classify(
        boxes: List<BoundingBox>,
        restMask: BooleanArray,
        mergedSymbols: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<AssignedStaff>>
    ): List<ClassifiedRestCandidate> {
        val coarseSpec = SvmModelSpec.REST
        val refinedSpec = SvmModelSpec.REST_ABOVE_EIGHTH
        val coarseClassifier = classifierLoader.load(coarseSpec.kind)
        val refinedClassifier by lazy { classifierLoader.load(refinedSpec.kind) }
        return boxes.map { box ->
            val coarse = coarseClassifier.classify(
                SvmFeatureExtractor.extract(restMask, width, height, box)
            )
            val coarseLabel = coarse.restLabel()
            val refined = if (coarseLabel == RestSymbolLabel.EIGHTH) {
                refinedClassifier.classify(
                    SvmFeatureExtractor.extract(restMask, width, height, box)
                )
            } else {
                null
            }
            val finalLabel = refined?.restLabel() ?: coarseLabel
            ClassifiedRestCandidate(
                boundingBox = box,
                label = finalLabel,
                assignment = assignment(box, staffGrid),
                hasAugmentationDot = hasDot(box, mergedSymbols, width, height, staffGrid),
                coarseClassification = coarse,
                refinedClassification = refined
            )
        }
    }

    private fun hasDot(
        box: BoundingBox,
        mergedSymbols: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<AssignedStaff>>
    ): Boolean {
        val center = box.center()
        val unitSize = Math.rint(
            StaffGeometryResolver.unitSizeAt(staffGrid, center.first, center.second)
        ).toInt()
        val startX = box.right + 1
        val endX = minOf(box.right + unitSize, width - 1)
        var foregroundCount = 0
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(height)) {
            for (x in startX.coerceAtLeast(0) until endX.coerceAtMost(width)) {
                if (mergedSymbols[y * width + x]) foregroundCount++
            }
        }
        return foregroundCount > 0 &&
                foregroundCount < unitSize * unitSize / DOT_AREA_DIVISOR
    }

    private fun assignment(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>
    ): SymbolStaffAssignment {
        val center = box.center()
        val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
        return SymbolStaffAssignment(staff.track, staff.group)
    }

    private fun SymbolClassification.restLabel(): RestSymbolLabel =
        label as? RestSymbolLabel
            ?: throw IllegalStateException("$model emitted ${label.sourceName} for a rest crop")

    private companion object {
        const val NEARBY_DISTANCE_UNIT_RATIO = 1.2
        const val MIN_AREA_UNIT_SQUARED = 0.7
        const val MAX_HEIGHT_UNITS = 3.5
        const val MIN_WIDTH_UNITS = 0.5
        const val DOT_AREA_DIVISOR = 7
    }
}
