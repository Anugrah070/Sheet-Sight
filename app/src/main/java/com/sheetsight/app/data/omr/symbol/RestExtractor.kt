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
    ): List<ClassifiedRestCandidate> = extractWithDiagnostics(
        groupMap,
        stemsRests,
        mergedSymbols,
        barlineBoxes,
        width,
        height,
        horizontalBounds,
        staffGrid
    ).candidates

    fun extractWithDiagnostics(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        mergedSymbols: BooleanArray,
        barlineBoxes: List<BoundingBox>,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): RestExtractionResult {
        require(groupMap.size == width * height)
        require(stemsRests.size == width * height)
        require(mergedSymbols.size == width * height)
        val globalUnitSize = StaffGeometryResolver.globalUnitSize(staffGrid)
        val restMask = buildRestMask(groupMap, stemsRests, barlineBoxes, width, height)
        val diagnostics = candidateBoxesWithDiagnostics(
            restMask,
            groupMap,
            width,
            height,
            horizontalBounds,
            staffGrid,
            globalUnitSize
        )
        val candidates = classify(
            diagnostics.acceptedBoxes,
            restMask,
            mergedSymbols,
            width,
            height,
            staffGrid
        )
        return RestExtractionResult(candidates, diagnostics)
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

    private fun candidateBoxesWithDiagnostics(
        mask: BooleanArray,
        groupMap: IntArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>,
        globalUnitSize: Double
    ): RestExtractionDiagnostics {
        val initial = ConnectedComponentBoxExtractor.extract(mask, width, height)
            .filter { it.center().first in horizontalBounds }
        if (initial.isEmpty()) return RestExtractionDiagnostics()
        val nearbyMerged = BoundingBoxMerger.mergeNearbyWard(
            initial,
            globalUnitSize * NEARBY_DISTANCE_UNIT_RATIO
        )
        val overlapFiltered = BoundingBoxMerger.resolveOverlaps(nearbyMerged)
        val accepted = mutableListOf<BoundingBox>()
        val rejected = linkedMapOf<BoundingBox, String>()
        overlapFiltered.forEach { box ->
            val center = box.center()
            val localUnit = StaffGeometryResolver.unitSizeAt(
                staffGrid,
                center.first,
                center.second
            )
            val reason = when {
                box.width * box.height <= localUnit * localUnit * MIN_AREA_UNIT_SQUARED -> "area"
                box.height > globalUnitSize * MAX_HEIGHT_UNITS -> "height"
                box.width < globalUnitSize * MIN_WIDTH_UNITS -> "width"
                !isInsideStaffHorizontalEnvelope(box, staffGrid, localUnit) -> "horizontal_staff_envelope"
                !isInsideStaffVerticalEnvelope(box, staffGrid, localUnit) -> "vertical_staff_envelope"
                isLikelyDetachedStemFragment(box, groupMap, width, height, localUnit) -> "detached_stem"
                else -> null
            }
            if (reason == null) accepted += box else rejected[box] = reason
        }
        return RestExtractionDiagnostics(
            initialBoxes = initial,
            nearbyMergedBoxes = nearbyMerged,
            overlapFilteredBoxes = overlapFiltered,
            acceptedBoxes = accepted,
            rejectedReasons = rejected
        )
    }

    /**
     * Rest glyphs live on or immediately beside their assigned staff. Page-edge
     * segmentation debris can otherwise look convincing to the crop classifier,
     * because the SVM has no knowledge of the crop's absolute page position.
     */
    private fun isInsideStaffVerticalEnvelope(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>,
        unitSize: Double
    ): Boolean {
        val center = box.center()
        val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
        val topLine = staff.staff.lines.minOf { it.yCenter }
        val bottomLine = staff.staff.lines.maxOf { it.yCenter }
        val distance = when {
            center.second < topLine -> topLine - center.second
            center.second > bottomLine -> center.second - bottomLine
            else -> 0.0
        }
        return distance <= unitSize * MAX_STAFF_ENVELOPE_DISTANCE_UNITS
    }

    /**
     * A final bar/double-bar fragment can be vertically perfect for a rest and
     * is therefore not rejected by the staff-relative y envelope. Require a
     * rest center to remain at least one local staff space inside the complete
     * system extent. A single staff-mask row or horizontal zone can end early
     * at a legitimate rest, so it must not define the right edge by itself.
     */
    private fun isInsideStaffHorizontalEnvelope(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>,
        unitSize: Double
    ): Boolean {
        val center = box.center()
        val assignment = StaffGeometryResolver.assignNote(
            staffGrid,
            center.first,
            center.second
        )
        val systemSegments = staffGrid.flatten().filter {
            it.group == assignment.staff.group
        }
        val left = systemSegments.minOf { it.staff.lines.minOf { line -> line.xLeft } }
        val right = systemSegments.maxOf { it.staff.lines.maxOf { line -> line.xRight } }
        return center.first >= left + unitSize * MIN_STAFF_EDGE_DISTANCE_UNITS &&
            center.first <= right - unitSize * MIN_STAFF_EDGE_DISTANCE_UNITS
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
            val localUnit = StaffGeometryResolver.unitSizeAt(
                staffGrid,
                box.center().first,
                box.center().second
            )
            val hasWholeHalfShape = box.height <= localUnit * MAX_WHOLE_HALF_HEIGHT_UNITS &&
                box.width >= localUnit * MIN_WHOLE_HALF_WIDTH_UNITS
            val placement = if (finalLabel == RestSymbolLabel.WHOLE_OR_HALF || hasWholeHalfShape) {
                resolveWholeHalfPlacement(box, staffGrid)
            } else {
                WholeHalfResolution(RestWholeHalfPlacement.NOT_APPLICABLE, null)
            }
            ClassifiedRestCandidate(
                boundingBox = box,
                label = finalLabel,
                assignment = assignment(box, staffGrid),
                hasAugmentationDot = hasDot(box, mergedSymbols, width, height, staffGrid),
                coarseClassification = coarse,
                refinedClassification = refined,
                wholeHalfPlacement = placement.placement,
                classificationMargin = decisionMargin(refined ?: coarse),
                placementConfidence = placement.confidence
            )
        }
    }

    /**
     * A thin vertical remainder next to an already-claimed note group is a
     * broken stem, not an isolated rest. This runs before the SVM so the
     * classifier never has to assign a rest label to known stem context.
     */
    private fun isLikelyDetachedStemFragment(
        box: BoundingBox,
        groupMap: IntArray,
        width: Int,
        height: Int,
        unitSize: Double
    ): Boolean {
        if (box.width > unitSize * MAX_STEM_WIDTH_UNITS) return false
        if (box.height < box.width * MIN_STEM_ASPECT_RATIO) return false
        val radius = maxOf(1, Math.rint(unitSize * NOTE_GROUP_ADJACENCY_UNITS).toInt())
        val left = (box.left - radius).coerceAtLeast(0)
        val right = (box.right + radius).coerceAtMost(width)
        val top = (box.top - radius).coerceAtLeast(0)
        val bottom = (box.bottom + radius).coerceAtMost(height)
        for (y in top until bottom) {
            for (x in left until right) {
                if (groupMap[y * width + x] >= 0) return true
            }
        }
        return false
    }

    /**
     * The coarse model intentionally combines whole and half rests. A
     * whole rest hangs from a staff line (top edge touches); a half rest
     * sits on one (bottom edge touches). Distances are normalized by the
     * locally assigned staff spacing and ambiguous crops stay unresolved.
     */
    private fun resolveWholeHalfPlacement(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>
    ): WholeHalfResolution {
        val center = box.center()
        val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first.staff
        val unit = staff.unitSize
        val lineYs = staff.lines.map { it.yCenter }
        val topDistance = lineYs.minOf { kotlin.math.abs(box.top - it) }
        val bottomDistance = lineYs.minOf { kotlin.math.abs(box.bottom - 1 - it) }
        val maximumTouchDistance = unit * WHOLE_HALF_LINE_TOUCH_UNITS
        val separation = unit * WHOLE_HALF_EDGE_SEPARATION_UNITS
        val placement = when {
            topDistance <= maximumTouchDistance && topDistance + separation < bottomDistance ->
                RestWholeHalfPlacement.WHOLE
            bottomDistance <= maximumTouchDistance && bottomDistance + separation < topDistance ->
                RestWholeHalfPlacement.HALF
            else -> RestWholeHalfPlacement.AMBIGUOUS
        }
        val closest = minOf(topDistance, bottomDistance)
        val confidence = if (placement == RestWholeHalfPlacement.AMBIGUOUS) {
            0.0
        } else {
            (1.0 - closest / maximumTouchDistance).coerceIn(0.0, 1.0)
        }
        return WholeHalfResolution(placement, confidence)
    }

    private fun decisionMargin(classification: SymbolClassification): Float? {
        if (classification.decisionScores.size < 2) return null
        val ordered = classification.decisionScores.sortedDescending()
        return ordered[0] - ordered[1]
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
        const val MAX_STEM_WIDTH_UNITS = 0.55
        const val MIN_STEM_ASPECT_RATIO = 2.5
        const val NOTE_GROUP_ADJACENCY_UNITS = 0.35
        const val MAX_STAFF_ENVELOPE_DISTANCE_UNITS = 2.0
        const val MIN_STAFF_EDGE_DISTANCE_UNITS = 1.0
        const val MAX_WHOLE_HALF_HEIGHT_UNITS = 0.75
        const val MIN_WHOLE_HALF_WIDTH_UNITS = 0.5
        const val WHOLE_HALF_LINE_TOUCH_UNITS = 0.35
        const val WHOLE_HALF_EDGE_SEPARATION_UNITS = 0.12
    }

    private data class WholeHalfResolution(
        val placement: RestWholeHalfPlacement,
        val confidence: Double?
    )
}

data class RestExtractionResult(
    val candidates: List<ClassifiedRestCandidate>,
    val diagnostics: RestExtractionDiagnostics
)

data class RestExtractionDiagnostics(
    val initialBoxes: List<BoundingBox> = emptyList(),
    val nearbyMergedBoxes: List<BoundingBox> = emptyList(),
    val overlapFilteredBoxes: List<BoundingBox> = emptyList(),
    val acceptedBoxes: List<BoundingBox> = emptyList(),
    val rejectedReasons: Map<BoundingBox, String> = emptyMap()
)
