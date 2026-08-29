package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.dewarp.ConnectedComponents
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.BoundingBoxMerger
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import com.sheetsight.app.data.omr.track.HoughLine
import com.sheetsight.app.data.omr.track.HoughLineDetector
import com.sheetsight.app.data.omr.track.StaffGeometryResolver
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Port of oemer 0.1.8
 * `symbol_extraction.py::parse_barlines()`/`filter_barlines()`.
 *
 * Barlines are geometric: no trained barline classifier exists in the
 * oemer distribution. Note-group pixels are removed, unused straight-line
 * candidates are overlapped with the independent model-one generic-symbol
 * components, and probabilistic Hough segments are
 * filtered and consolidated exactly before staff-group assignment.
 *
 * **Safe empty-input deviation:** oemer calls `np.max` on an empty line
 * set and raises. This port returns an empty result because absence of a
 * barline is valid page content and is not a classification guess.
 */
object MusicalBarlineExtractor {
    private const val MIN_LINE_DEGREES = 75.0
    private const val MIN_HEIGHT_UNIT_RATIO = 3.75
    private const val NORMALIZED_HEIGHT_THRESHOLD = 0.5
    private const val RENDER_PADDING = 10

    /** Extracts barlines from dewarped oemer masks. */
    fun extract(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): List<MusicalBarlineCandidate> = extractWithDiagnostics(
        groupMap,
        stemsRests,
        symbols,
        width,
        height,
        horizontalBounds,
        staffGrid
    ).candidates

    /** Same recognition path as [extract], plus small image-free filter evidence. */
    fun extractWithDiagnostics(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): MusicalBarlineExtractionResult {
        validateInputs(groupMap, stemsRests, symbols, width, height)
        val houghMask = selectOverlappingSymbolComponents(
            groupMap,
            stemsRests,
            symbols,
            width,
            height
        )
        val lines = HoughLineDetector.detect(houghMask, width, height)
        val houghDiagnostics = filterLines(lines, horizontalBounds, staffGrid).copy(
            selectedOverlapPixelCount = houghMask.count { it }
        )
        val houghCandidates = houghDiagnostics.acceptedBoxes.map { box ->
            val center = box.center()
            val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
            MusicalBarlineCandidate(
                boundingBox = box,
                group = staff.group,
                confidence = structuralConfidence(box, staff.group, staffGrid)
            )
        }
        val structuralCandidates = detectStructuralCandidates(
            groupMap,
            stemsRests,
            symbols,
            width,
            height,
            horizontalBounds,
            staffGrid
        )
        val candidates = consolidateCandidates(houghCandidates + structuralCandidates, staffGrid)
        val diagnostics = houghDiagnostics.copy(
            structuralBoxes = structuralCandidates.map { it.boundingBox },
            acceptedBoxes = candidates.map { it.boundingBox }
        )
        return MusicalBarlineExtractionResult(candidates, diagnostics)
    }

    /**
     * Recovers narrow staff-crossing components directly from the unclaimed
     * stem/rest channel. This is independent of the generic-symbol overlap
     * required by the oemer Hough path, which can omit otherwise clear bars.
     */
    internal fun detectStructuralCandidates(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray = BooleanArray(stemsRests.size),
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): List<MusicalBarlineCandidate> {
        if (staffGrid.flatten().isEmpty()) return emptyList()
        val unclaimedStemMask = BooleanArray(width * height) { index ->
            stemsRests[index] && groupMap[index] < 0
        }
        // Note grouping can absorb a real barline when dense notation touches it.
        // Keep a grouping-independent fallback, but only for whole model-two
        // symbol components that are independently corroborated by model one's
        // stem/rest channel. The same strict staff-crossing and two-track rules
        // below still apply, so an ordinary note stem cannot validate itself.
        val corroboratedSymbolMask = selectCorroboratedSymbolComponents(
            stemsRests,
            symbols,
            width,
            height
        )
        val rawBoxes = (
            ConnectedComponentBoxExtractor.extract(unclaimedStemMask, width, height) +
                ConnectedComponentBoxExtractor.extract(corroboratedSymbolMask, width, height)
            ).distinct()
        val rawEvidence = rawBoxes
            .mapNotNull { box ->
                val center = box.center()
                if (center.first !in horizontalBounds) return@mapNotNull null
                val assigned = StaffGeometryResolver.closestPair(
                    staffGrid,
                    center.first,
                    center.second
                ).first
                val unit = assigned.staff.unitSize
                val narrowEnough = box.width <= maxOf(
                    MIN_STRUCTURAL_WIDTH_PIXELS,
                    unit * MAX_STRUCTURAL_WIDTH_UNITS
                )
                val tallEnough = box.height >= unit * MIN_HEIGHT_UNIT_RATIO &&
                    box.height >= box.width * MIN_STRUCTURAL_ASPECT_RATIO
                if (!narrowEnough || !tallEnough || !crossesMostStaffLines(box, staffGrid)) {
                    return@mapNotNull null
                }
                StructuralBarlineEvidence(
                    box = box,
                    group = assigned.group,
                    crossedTracks = crossedTrackIds(box, assigned.group, staffGrid)
                )
            }
        return consolidateStructuralEvidence(rawEvidence, staffGrid)
    }

    internal fun selectCorroboratedSymbolComponents(
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        require(stemsRests.size == width * height)
        require(symbols.size == width * height)
        val stemForeground = IntArray(width * height) { index ->
            if (stemsRests[index]) 0 else -1
        }
        val symbolForeground = IntArray(width * height) { index ->
            if (symbols[index]) 0 else -1
        }
        val stemLabels = ConnectedComponents.label(stemForeground, width, height)
        val symbolLabels = ConnectedComponents.label(symbolForeground, width, height)
        val corroboratedLabels = mutableSetOf<Int>()
        stemLabels.forEachIndexed { index, stemLabel ->
            if (stemLabel > 0 && symbolLabels[index] > 0) {
                corroboratedLabels += symbolLabels[index]
            }
        }
        return BooleanArray(width * height) { symbolLabels[it] in corroboratedLabels }
    }

    private fun consolidateStructuralEvidence(
        evidence: List<StructuralBarlineEvidence>,
        staffGrid: List<List<AssignedStaff>>
    ): List<MusicalBarlineCandidate> = evidence.groupBy { it.group }.flatMap { (group, items) ->
        val groupUnit = staffGrid.flatten()
            .filter { it.group == group }
            .map { it.staff.unitSize }
            .average()
        val clusters = mutableListOf<MutableList<StructuralBarlineEvidence>>()
        items.sortedBy { it.box.center().first }.forEach { item ->
            val current = clusters.lastOrNull()
            val currentX = current?.map { it.box.center().first }?.average()
            if (current != null && currentX != null &&
                kotlin.math.abs(item.box.center().first - currentX) <=
                groupUnit * STRUCTURAL_TRACK_ALIGNMENT_UNITS
            ) {
                current += item
            } else {
                clusters += mutableListOf(item)
            }
        }
        val requiredTracks = staffGrid.flatten()
            .filter { it.group == group }
            .map { it.track }
            .toSet()
        clusters.mapNotNull { cluster ->
            if (!cluster.flatMap { it.crossedTracks }.toSet().containsAll(requiredTracks)) {
                return@mapNotNull null
            }
            val boxes = selectTightestTrackEvidence(cluster, requiredTracks).map { it.box }
            val merged = BoundingBox(
                boxes.minOf { it.left },
                boxes.minOf { it.top },
                boxes.maxOf { it.right },
                boxes.maxOf { it.bottom }
            )
            MusicalBarlineCandidate(
                boundingBox = merged,
                group = group,
                confidence = structuralConfidence(merged, group, staffGrid)
            )
        }
    }

    /**
     * Keeps nearby note stems from pulling a structural barline away from its
     * cross-staff alignment. Single-staff groups retain the old behavior: with
     * no independent track agreement, discarding one of two double-bar strokes
     * would be arbitrary.
     */
    private fun selectTightestTrackEvidence(
        cluster: List<StructuralBarlineEvidence>,
        requiredTracks: Set<Int>
    ): List<StructuralBarlineEvidence> {
        if (requiredTracks.size <= 1 || cluster.size <= 1) return cluster

        val solutions = cluster.mapNotNull { anchor ->
            val selected = mutableListOf(anchor)
            val covered = anchor.crossedTracks.toMutableSet()
            while (!covered.containsAll(requiredTracks)) {
                val centers = selected.map { it.box.center().first }
                val next = cluster
                    .asSequence()
                    .filterNot { it in selected }
                    .map { candidate ->
                        val newlyCovered = (candidate.crossedTracks intersect requiredTracks) - covered
                        candidate to newlyCovered.size
                    }
                    .filter { (_, newTrackCount) -> newTrackCount > 0 }
                    .minWithOrNull(
                        compareBy<Pair<StructuralBarlineEvidence, Int>> { (candidate, _) ->
                            val allCenters = centers + candidate.box.center().first
                            allCenters.max() - allCenters.min()
                        }.thenByDescending { (_, newTrackCount) -> newTrackCount }
                            .thenBy { (candidate, _) -> candidate.box.width }
                            .thenBy { (candidate, _) -> candidate.box.center().first }
                    )
                    ?.first
                    ?: return@mapNotNull null
                selected += next
                covered += next.crossedTracks
            }
            selected
        }

        return solutions.minWithOrNull(
            compareBy<List<StructuralBarlineEvidence>> { selection ->
                val centers = selection.map { it.box.center().first }
                centers.max() - centers.min()
            }.thenBy { it.size }
                .thenBy { selection ->
                    selection.maxOf { it.box.right } - selection.minOf { it.box.left }
                }.thenBy { selection -> selection.sumOf { it.box.width } }
                .thenBy { selection -> selection.minOf { it.box.center().first } }
        ) ?: cluster
    }

    private fun crossedTrackIds(
        box: BoundingBox,
        group: Int,
        staffGrid: List<List<AssignedStaff>>
    ): Set<Int> = staffGrid.flatten()
        .filter { it.group == group }
        .filter { assigned ->
            val padding = assigned.staff.unitSize * STAFF_LINE_CROSSING_PADDING_UNITS
            assigned.staff.lines.count { line ->
                line.yCenter in (box.top - padding)..(box.bottom + padding)
            } >= MIN_CROSSED_STAFF_LINES
        }
        .map { it.track }
        .toSet()

    internal fun consolidateCandidates(
        candidates: List<MusicalBarlineCandidate>,
        staffGrid: List<List<AssignedStaff>>
    ): List<MusicalBarlineCandidate> {
        if (candidates.size < 2) return candidates
        return candidates.groupBy { it.group }.flatMap { (group, groupCandidates) ->
            val groupUnit = staffGrid.flatten()
                .filter { it.group == group }
                .map { it.staff.unitSize }
                .average()
            val tolerance = groupUnit * BARLINE_X_MERGE_UNITS
            val clusters = mutableListOf<MutableList<MusicalBarlineCandidate>>()
            groupCandidates.sortedBy { it.boundingBox.center().first }.forEach { candidate ->
                val current = clusters.lastOrNull()
                val currentX = current?.map { it.boundingBox.center().first }?.average()
                if (current != null && currentX != null &&
                    kotlin.math.abs(candidate.boundingBox.center().first - currentX) <= tolerance
                ) {
                    current += candidate
                } else {
                    clusters += mutableListOf(candidate)
                }
            }
            clusters.map { cluster ->
                val boxes = cluster.map { it.boundingBox }
                MusicalBarlineCandidate(
                    boundingBox = BoundingBox(
                        boxes.minOf { it.left },
                        boxes.minOf { it.top },
                        boxes.maxOf { it.right },
                        boxes.maxOf { it.bottom }
                    ),
                    group = group,
                    confidence = cluster.maxOf { it.confidence }
                )
            }
        }.sortedWith(compareBy<MusicalBarlineCandidate> { it.group }.thenBy {
            it.boundingBox.center().first
        })
    }

    internal fun selectOverlappingSymbolComponents(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val stemForeground = IntArray(width * height) { index ->
            if (stemsRests[index] && groupMap[index] < 0) 0 else -1
        }
        val symbolForeground = IntArray(width * height) { index ->
            if (symbols[index] && groupMap[index] < 0) 0 else -1
        }
        val stemLabels = ConnectedComponents.label(stemForeground, width, height)
        val symbolLabels = ConnectedComponents.label(symbolForeground, width, height)
        val selectedSymbolLabels = mutableSetOf<Int>()
        stemLabels.forEachIndexed { index, stemLabel ->
            if (stemLabel > 0 && symbolLabels[index] > 0) {
                selectedSymbolLabels += symbolLabels[index]
            }
        }
        return BooleanArray(width * height) { symbolLabels[it] in selectedSymbolLabels }
    }

    private fun filterLines(
        lines: List<HoughLine>,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): MusicalBarlineDiagnostics {
        if (lines.isEmpty()) return MusicalBarlineDiagnostics()
        val inRange = lines.map(::toBox).filter { it.center().first in horizontalBounds }
        val merged = BoundingBoxMerger.resolveOverlaps(inRange, merge = true, overlapRatio = 0.0)
        val validLines = merged.filter { lineDegrees(it) >= MIN_LINE_DEGREES }
        if (validLines.isEmpty()) {
            return MusicalBarlineDiagnostics(
                rawHoughLines = lines,
                horizontallyAcceptedBoxes = inRange,
                mergedBoxes = merged
            )
        }
        val consolidated = renderAndExtract(validLines)
        val heightAccepted = consolidated
            .filter { box ->
                val center = box.center()
                val localUnitSize = StaffGeometryResolver.unitSizeAt(
                    staffGrid,
                    center.first,
                    center.second
                )
                box.height >= localUnitSize * MIN_HEIGHT_UNIT_RATIO &&
                    crossesMostStaffLines(box, staffGrid)
            }
            .sortedBy { it.height }
        if (heightAccepted.isEmpty()) {
            return MusicalBarlineDiagnostics(
                rawHoughLines = lines,
                horizontallyAcceptedBoxes = inRange,
                mergedBoxes = merged,
                angleAcceptedBoxes = validLines,
                consolidatedBoxes = consolidated
            )
        }
        val referenceHeight = heightAccepted.takeLast(5).map { it.height }.average()
        val accepted = heightAccepted.filter {
            it.height / referenceHeight > NORMALIZED_HEIGHT_THRESHOLD
        }
        return MusicalBarlineDiagnostics(
            rawHoughLines = lines,
            horizontallyAcceptedBoxes = inRange,
            mergedBoxes = merged,
            angleAcceptedBoxes = validLines,
            consolidatedBoxes = consolidated,
            heightAcceptedBoxes = heightAccepted,
            acceptedBoxes = accepted,
            referenceHeight = referenceHeight
        )
    }

    private fun renderAndExtract(lines: List<BoundingBox>): List<BoundingBox> {
        val width = lines.maxOf { it.right } + RENDER_PADDING
        val height = lines.maxOf { it.bottom } + RENDER_PADDING
        val rendered = Mat.zeros(height, width, CvType.CV_64FC1)
        try {
            lines.forEach { line ->
                Imgproc.line(
                    rendered,
                    Point(line.left.toDouble(), line.top.toDouble()),
                    Point(line.right.toDouble(), line.bottom.toDouble()),
                    Scalar(255.0),
                    1,
                    Imgproc.LINE_AA
                )
            }
            val mask = BooleanArray(width * height)
            val row = DoubleArray(width)
            for (y in 0 until height) {
                rendered.get(y, 0, row)
                for (x in 0 until width) mask[y * width + x] = row[x] > 0.0
            }
            return ConnectedComponentBoxExtractor.extract(mask, width, height)
        } finally {
            rendered.release()
        }
    }

    private fun lineDegrees(box: BoundingBox): Double =
        abs(Math.toDegrees(atan2((box.bottom - box.top).toDouble(), (box.right - box.left).toDouble())))

    private fun toBox(line: HoughLine): BoundingBox =
        BoundingBox(line.topX, line.topY, line.btX, line.btY)

    /** Staff-relative structural score; deliberately not presented as a probability. */
    private fun structuralConfidence(
        box: BoundingBox,
        group: Int,
        staffGrid: List<List<AssignedStaff>>
    ): Double {
        val staffs = staffGrid.flatten().filter { it.group == group }
        if (staffs.isEmpty()) return 0.0
        val staffLines = staffs.flatMap { it.staff.lines }
        val crossedLines = staffLines.count { it.yCenter in box.top.toDouble()..box.bottom.toDouble() }
        val crossingScore = crossedLines.toDouble() / staffLines.size
        val localUnit = staffs.map { it.staff.unitSize }.average()
        val heightScore = (box.height / (localUnit * 4.0)).coerceIn(0.0, 1.0)
        val verticalityScore = (1.0 - box.width.toDouble() / maxOf(1, box.height)).coerceIn(0.0, 1.0)
        return (crossingScore * 0.5 + heightScore * 0.3 + verticalityScore * 0.2)
            .coerceIn(0.0, 1.0)
    }

    /** A normal barline spans at least four lines of its nearest five-line staff. */
    internal fun crossesMostStaffLines(
        box: BoundingBox,
        staffGrid: List<List<AssignedStaff>>
    ): Boolean {
        if (staffGrid.flatten().isEmpty()) return false
        val center = box.center()
        val assigned = StaffGeometryResolver.closestPair(
            staffGrid,
            center.first,
            center.second
        ).first
        val padding = assigned.staff.unitSize * STAFF_LINE_CROSSING_PADDING_UNITS
        return assigned.staff.lines.count { line ->
            line.yCenter in (box.top - padding)..(box.bottom + padding)
        } >= MIN_CROSSED_STAFF_LINES
    }

    private fun validateInputs(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int
    ) {
        val expectedSize = width * height
        require(groupMap.size == expectedSize)
        require(stemsRests.size == expectedSize)
        require(symbols.size == expectedSize)
        require(width > 0 && height > 0)
    }

    private const val MIN_CROSSED_STAFF_LINES = 4
    private const val STAFF_LINE_CROSSING_PADDING_UNITS = 0.2
    private const val MIN_STRUCTURAL_WIDTH_PIXELS = 2.0
    private const val MAX_STRUCTURAL_WIDTH_UNITS = 0.8
    private const val MIN_STRUCTURAL_ASPECT_RATIO = 3.0
    private const val STRUCTURAL_TRACK_ALIGNMENT_UNITS = 2.25
    // Adjacent strokes in a double/final bar can span almost one staff space.
    // Measure boundaries cannot plausibly be this close, so one local space is
    // a geometry-derived upper bound rather than a page-layout assumption.
    private const val BARLINE_X_MERGE_UNITS = 1.0

    private data class StructuralBarlineEvidence(
        val box: BoundingBox,
        val group: Int,
        val crossedTracks: Set<Int>
    )
}

data class MusicalBarlineExtractionResult(
    val candidates: List<MusicalBarlineCandidate>,
    val diagnostics: MusicalBarlineDiagnostics
)

/** Candidate counts/boxes after each official oemer filter; no page-sized data is retained. */
data class MusicalBarlineDiagnostics(
    val selectedOverlapPixelCount: Int = 0,
    val rawHoughLines: List<HoughLine> = emptyList(),
    val horizontallyAcceptedBoxes: List<BoundingBox> = emptyList(),
    val mergedBoxes: List<BoundingBox> = emptyList(),
    val angleAcceptedBoxes: List<BoundingBox> = emptyList(),
    val consolidatedBoxes: List<BoundingBox> = emptyList(),
    val heightAcceptedBoxes: List<BoundingBox> = emptyList(),
    val structuralBoxes: List<BoundingBox> = emptyList(),
    val acceptedBoxes: List<BoundingBox> = emptyList(),
    val referenceHeight: Double? = null
)

internal fun BoundingBox.center(): Pair<Int, Int> =
    Math.rint((left + right) / 2.0).toInt() to
            Math.rint((top + bottom) / 2.0).toInt()
