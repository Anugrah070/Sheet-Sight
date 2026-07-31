package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.dewarp.ConnectedComponents
import com.sheetsight.app.data.omr.dewarp.StaffMaskMorphology
import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadExtractor
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.symbol.ClassifiedRestCandidate
import com.sheetsight.app.data.omr.symbol.RestSymbolLabel
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Pure-Kotlin port of the production rhythm path in oemer 0.1.8
 * `rhythm_extraction.py`.
 *
 * Verified source behavior retained here includes:
 *
 *  * the 4x4 elliptical opening and 0.08..0.20 unit-area dot test;
 *  * the exact staff/symbol/group/stem arithmetic and morphology used to
 *    isolate beams and flags;
 *  * rotated-component area, thickness, and true-positive filtering;
 *  * overlap-based beam-region correlation and the vertical beam scan;
 *  * oemer's 0/1/2/3/4 count mapping, including its unusual 4 -> sixteenth
 *    compatibility mapping.
 *
 * Unlike oemer's in-place mutation, results are immutable and ambiguous
 * groups remain unresolved. Multi-voice groups are not silently split or
 * assigned different durations because [ChordCandidate] is the current
 * repository's established note-group contract. OpenCV's slanted-edge
 * `fillPoly` tie pixels are conservatively bracketed; a component that
 * could cross the 0.4 threshold under those raster ties is returned as
 * [RhythmUnresolvedReason.BEAM_RASTER_THRESHOLD_AMBIGUOUS].
 */
object RhythmExtractor {
    private const val DOT_MIN_AREA_RATIO = 0.08
    private const val DOT_MAX_AREA_RATIO = 0.20
    private const val BEAM_MIN_AREA_RATIO = 0.07
    private const val BEAM_MIN_TRUE_POSITIVE_RATIO = 0.40
    private const val BEAM_MIN_WIDTH_RATIO = 0.20
    private const val BEAM_SCAN_AGREEMENT = 0.15
    private const val BEAM_SCAN_MIN_WIDTH_RATIO = 0.25
    private const val BEAM_SCAN_MAX_WIDTH_RATIO = 0.90
    private const val BARLINE_MIN_HEIGHT_UNIT_RATIO = 3.75

    /**
     * Extracts note-group rhythm and maps verified classifier-stage [rests]
     * directly into deterministic rest rhythm results.
     */
    fun extract(
        noteheads: List<NoteheadCandidate>,
        chords: List<ChordCandidate>,
        evidence: RhythmEvidenceMasks,
        rests: List<ClassifiedRestCandidate> = emptyList()
    ): RhythmExtractionResult {
        val sortedChords = chords.sortedWith(
            compareBy<ChordCandidate> { it.id }
                .thenBy { it.boundingBox.left }
                .thenBy { it.boundingBox.top }
        )
        val notesById = noteheads.associateBy { it.id }
        val staffGeometry = buildStaffGeometry(evidence)
        val groupEvidence = buildGroupEvidence(sortedChords, notesById, evidence)

        val dotMask = if (staffGeometry.isNotEmpty()) buildDotMask(evidence) else null
        val noteIdMap = buildNoteIdMap(noteheads, evidence.width, evidence.height)
        val beamAnalysis = if (staffGeometry.isNotEmpty()) {
            val beamMaps = buildFilteredBeamMaps(
                evidence,
                groupEvidence.groupMap,
                staffGeometry
            )
            val verified = buildBeamRegions(
                beamMaps.verified,
                evidence,
                groupEvidence.groupMap,
                sortedChords,
                staffGeometry
            )
            val ambiguous = buildBeamRegions(
                beamMaps.rasterThresholdAmbiguous,
                evidence,
                groupEvidence.groupMap,
                sortedChords,
                staffGeometry
            )
            verified.copy(
                rasterThresholdAmbiguousGroups =
                    ambiguous.regions.flatMapTo(linkedSetOf()) { it.groupIndices }
            )
        } else {
            BeamAnalysis(
                BooleanArray(evidence.width * evidence.height),
                emptyList(),
                emptySet()
            )
        }

        val groupResults = sortedChords.mapIndexed { groupIndex, chord ->
            val members = chord.noteheads.mapNotNull { notesById[it.id] }
            val reasons = linkedSetOf<RhythmUnresolvedReason>()
            if (!evidence.isComplete) {
                reasons += RhythmUnresolvedReason.INCOMPLETE_MASK_EVIDENCE
            }
            if (staffGeometry.isEmpty()) {
                reasons += RhythmUnresolvedReason.NO_STAFF_GEOMETRY
            }
            if (members.isEmpty()) {
                reasons += RhythmUnresolvedReason.EMPTY_NOTE_GROUP
            }

            val stem = groupEvidence.stems[groupIndex]
            when (stem.status) {
                StemAssociationStatus.AMBIGUOUS ->
                    reasons += RhythmUnresolvedReason.STEM_NOT_ASSOCIATED
                StemAssociationStatus.SHARED_BETWEEN_GROUPS ->
                    reasons += RhythmUnresolvedReason.STEM_SHARED_BETWEEN_GROUPS
                else -> Unit
            }

            val dots = if (dotMask != null) {
                members.map { note ->
                    scanDot(
                        dotMask = dotMask,
                        noteIdMap = noteIdMap,
                        note = note,
                        groupBox = chord.boundingBox,
                        staffGeometry = staffGeometry,
                        width = evidence.width,
                        height = evidence.height
                    )
                }
            } else {
                members.map { note ->
                    AugmentationDotEvidence(
                        noteheadId = note.id,
                        scanRegion = null,
                        foregroundPixelCount = null,
                        minimumPixelCount = null,
                        maximumPixelCount = null,
                        detected = null,
                        unresolvedReason = RhythmUnresolvedReason.NO_STAFF_GEOMETRY
                    )
                }
            }
            dots.mapNotNullTo(reasons) { it.unresolvedReason }
            val dotCount = resolveGroupDotCount(dots, chord.stemDirection, reasons)

            val beamEvidence = resolveBeamEvidence(
                groupIndex = groupIndex,
                chord = chord,
                notes = members,
                analysis = beamAnalysis,
                staffGeometry = staffGeometry,
                width = evidence.width,
                height = evidence.height
            )
            reasons += beamEvidence.reasons

            val baseDuration = inferNoteDuration(
                chord = chord,
                notes = members,
                stem = stem,
                beamEvidence = beamEvidence,
                reasons = reasons
            )
            val dottedDuration = durationValue(baseDuration, dotCount)
            val state = when {
                baseDuration == null -> RhythmResolutionState.UNRESOLVED
                dottedDuration == null -> RhythmResolutionState.PARTIAL
                else -> RhythmResolutionState.RESOLVED
            }

            RhythmCandidate(
                id = chord.id,
                noteGroupId = chord.id,
                chord = chord,
                noteheads = members,
                evidenceStatus = if (evidence.isComplete) {
                    RhythmEvidenceStatus.COMPLETE
                } else {
                    RhythmEvidenceStatus.INCOMPLETE
                },
                stemDirection = chord.stemDirection,
                stemAssociation = stem,
                beamCount = beamEvidence.beamCount,
                flagCount = beamEvidence.flagCount,
                dotCount = dotCount,
                dotEvidence = dots,
                baseDuration = baseDuration,
                dottedDuration = dottedDuration,
                resolutionState = state,
                unresolvedReasons = reasons.sortedBy { it.ordinal }
            )
        }

        return RhythmExtractionResult(
            noteGroups = groupResults,
            rests = rests
                .sortedWith(
                    compareBy<ClassifiedRestCandidate> { it.assignment.group }
                        .thenBy { it.assignment.track }
                        .thenBy { it.boundingBox.left }
                        .thenBy { it.boundingBox.top }
                        .thenBy { it.boundingBox.right }
                        .thenBy { it.boundingBox.bottom }
                        .thenBy { it.label.ordinal }
                )
                .mapIndexed(::resolveRest)
        )
    }

    /** Compatibility entry point retained for existing callers. */
    fun prepareCandidates(
        noteheads: List<NoteheadCandidate>,
        chords: List<ChordCandidate>,
        evidence: RhythmEvidenceMasks
    ): List<RhythmCandidate> = extract(noteheads, chords, evidence).noteGroups

    /**
     * Candidates returned by [extract] are already resolved as far as their
     * evidence permits. This method now preserves that explicit state.
     */
    fun resolveDurations(candidates: List<RhythmCandidate>): List<RhythmCandidate> =
        candidates.sortedBy { it.noteGroupId }

    private data class StaffGeometry(
        val xCenter: Double,
        val yCenter: Double,
        val yUpper: Double,
        val yLower: Double,
        val unitSize: Double
    )

    private data class GroupEvidence(
        val groupMap: IntArray,
        val stems: List<StemAssociation>
    )

    private data class ComponentBounds(
        var left: Int = Int.MAX_VALUE,
        var top: Int = Int.MAX_VALUE,
        var right: Int = Int.MIN_VALUE,
        var bottom: Int = Int.MIN_VALUE,
        var containsStem: Boolean = false
    ) {
        fun include(x: Int, y: Int, stem: Boolean) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
            containsStem = containsStem || stem
        }

        fun toBoundingBox(): BoundingBox? =
            if (left == Int.MAX_VALUE) null else BoundingBox(left, top, right + 1, bottom + 1)
    }

    private data class PixelBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun union(other: PixelBox): PixelBox =
            PixelBox(
                minOf(left, other.left),
                minOf(top, other.top),
                maxOf(right, other.right),
                maxOf(bottom, other.bottom)
            )
    }

    private data class BeamRegion(
        var box: PixelBox,
        val groupIndices: MutableSet<Int>,
        val symbolLabels: MutableSet<Int>
    )

    private data class BeamAnalysis(
        val map: BooleanArray,
        val regions: List<BeamRegion>,
        val rasterThresholdAmbiguousGroups: Set<Int> = emptySet()
    )

    private data class FilteredBeamMaps(
        val verified: BooleanArray,
        val rasterThresholdAmbiguous: BooleanArray
    )

    private data class BeamEvidence(
        val beamCount: Int?,
        val flagCount: Int?,
        val totalCount: Int?,
        val hasRegion: Boolean,
        val reasons: List<RhythmUnresolvedReason>
    )

    private data class Point(val x: Double, val y: Double)

    private data class IntPoint(val x: Int, val y: Int)

    private data class RotatedRectangle(
        val width: Double,
        val height: Double,
        val corners: List<Point>
    )

    private fun buildStaffGeometry(evidence: RhythmEvidenceMasks): List<StaffGeometry> =
        evidence.staffGrid.flatten().map { assigned ->
            val staff = assigned.staff
            StaffGeometry(
                xCenter = staff.lines.sumOf { it.xCenter } / staff.lines.size,
                yCenter = staff.yCenter,
                yUpper = staff.lines.minOf { it.yUpper }.toDouble(),
                yLower = staff.lines.maxOf { it.yLower }.toDouble(),
                unitSize = staff.unitSize
            )
        }

    private fun buildGroupEvidence(
        chords: List<ChordCandidate>,
        notesById: Map<Int, NoteheadCandidate>,
        evidence: RhythmEvidenceMasks
    ): GroupEvidence {
        val dilatedStems = rectMorph(
            evidence.stems,
            evidence.width,
            evidence.height,
            kernelHeight = 3,
            kernelWidth = 2,
            erode = false
        )
        val foreground = IntArray(evidence.width * evidence.height) { index ->
            if (evidence.noteheads[index] || dilatedStems[index]) 0 else -1
        }
        val labels = ConnectedComponents.label(foreground, evidence.width, evidence.height)
        val maxLabel = labels.maxOrNull() ?: 0
        val componentOwners = Array(maxLabel + 1) { linkedSetOf<Int>() }

        chords.forEachIndexed { groupIndex, chord ->
            chord.noteheads.mapNotNull { notesById[it.id] }.forEach { note ->
                val left = note.boundingBox.left.coerceIn(0, evidence.width)
                val right = note.boundingBox.right.coerceIn(0, evidence.width)
                val top = note.boundingBox.top.coerceIn(0, evidence.height)
                val bottom = note.boundingBox.bottom.coerceIn(0, evidence.height)
                for (y in top until bottom) {
                    for (x in left until right) {
                        val index = y * evidence.width + x
                        if (!evidence.noteheads[index]) continue
                        val label = labels[index]
                        if (label > 0) componentOwners[label] += groupIndex
                    }
                }
            }
        }

        val componentBounds = Array(maxLabel + 1) { ComponentBounds() }
        labels.forEachIndexed { index, label ->
            if (label <= 0 || componentOwners[label].isEmpty()) return@forEachIndexed
            componentBounds[label].include(
                x = index % evidence.width,
                y = index / evidence.width,
                stem = evidence.stems[index]
            )
        }

        val groupMap = IntArray(labels.size) { -1 }
        labels.forEachIndexed { index, label ->
            if (label > 0 && componentOwners[label].size == 1) {
                groupMap[index] = componentOwners[label].first()
            }
        }

        val stemAssociations = chords.mapIndexed { groupIndex, chord ->
            if (!chord.hasStem) {
                StemAssociation(StemAssociationStatus.NONE, chord.stemDirection)
            } else {
                val labelsForGroup = (1..maxLabel).filter {
                    groupIndex in componentOwners[it] && componentBounds[it].containsStem
                }
                val shared = labelsForGroup.filter { componentOwners[it].size > 1 }
                val unique = labelsForGroup.filter { componentOwners[it].size == 1 }
                when {
                    shared.isNotEmpty() ->
                        StemAssociation(
                            StemAssociationStatus.SHARED_BETWEEN_GROUPS,
                            chord.stemDirection,
                            shared.mapNotNull { componentBounds[it].toBoundingBox() }.unionBox()
                        )
                    unique.isNotEmpty() ->
                        StemAssociation(
                            StemAssociationStatus.ASSIGNED,
                            chord.stemDirection,
                            unique.mapNotNull { componentBounds[it].toBoundingBox() }.unionBox()
                        )
                    else ->
                        StemAssociation(StemAssociationStatus.AMBIGUOUS, chord.stemDirection)
                }
            }
        }
        return GroupEvidence(groupMap, stemAssociations)
    }

    private fun buildDotMask(evidence: RhythmEvidenceMasks): BooleanArray {
        val withoutStemsAndClefs = BooleanArray(evidence.width * evidence.height) { index ->
            evidence.symbols[index] && !evidence.stems[index] && !evidence.clefsKeys[index]
        }
        val eroded = NoteheadExtractor.morphEllipse(
            withoutStemsAndClefs,
            evidence.width,
            evidence.height,
            kernelWidth = 4,
            kernelHeight = 4,
            erode = true
        )
        return NoteheadExtractor.morphEllipse(
            eroded,
            evidence.width,
            evidence.height,
            kernelWidth = 4,
            kernelHeight = 4,
            erode = false
        )
    }

    private fun buildNoteIdMap(
        noteheads: List<NoteheadCandidate>,
        width: Int,
        height: Int
    ): IntArray {
        val map = IntArray(width * height) { -1 }
        noteheads.sortedBy { it.id }.forEach { note ->
            note.sourcePixelIndices.forEach { index ->
                if (index in map.indices) map[index] = note.id
            }
        }
        return map
    }

    private fun scanDot(
        dotMask: BooleanArray,
        noteIdMap: IntArray,
        note: NoteheadCandidate,
        groupBox: BoundingBox,
        staffGeometry: List<StaffGeometry>,
        width: Int,
        height: Int
    ): AugmentationDotEvidence {
        val unitSize = unitSizeAt(
            staffGeometry,
            pythonRound((groupBox.left + groupBox.right) / 2.0),
            pythonRound((groupBox.top + groupBox.bottom) / 2.0)
        )
        if (unitSize == null) {
            return unresolvedDot(note.id, RhythmUnresolvedReason.NO_STAFF_GEOMETRY)
        }

        val adjustedRight = maxOf(note.boundingBox.right, groupBox.right)
        val startY = note.boundingBox.top - pythonRound(unitSize / 2.0)
        val endY = note.boundingBox.bottom
        if (startY !in 0 until height || endY !in 0..height || startY > endY) {
            return unresolvedDot(note.id, RhythmUnresolvedReason.DOT_SCAN_OUT_OF_BOUNDS)
        }

        var rightBound = adjustedRight + 1
        while (true) {
            if (rightBound !in 0 until width) {
                return unresolvedDot(note.id, RhythmUnresolvedReason.DOT_SCAN_OUT_OF_BOUNDS)
            }
            var touchesNearbyNote = false
            for (y in startY until endY) {
                if (noteIdMap[y * width + rightBound] != -1) {
                    touchesNearbyNote = true
                    break
                }
            }
            if (touchesNearbyNote) break
            rightBound += 1
            if (rightBound >= adjustedRight + unitSize) break
        }

        val leftBound = adjustedRight + pythonRound(unitSize * 0.4)
        if (leftBound < 0 || leftBound > width || rightBound < 0 || rightBound > width) {
            return unresolvedDot(note.id, RhythmUnresolvedReason.DOT_SCAN_OUT_OF_BOUNDS)
        }
        val scanRight = maxOf(leftBound, rightBound)
        var pixels = 0
        for (y in startY until endY) {
            for (x in leftBound until rightBound) {
                if (dotMask[y * width + x]) pixels++
            }
        }
        val minimum = pythonRound(unitSize * unitSize * DOT_MIN_AREA_RATIO)
        val maximum = pythonRound(unitSize * unitSize * DOT_MAX_AREA_RATIO)
        return AugmentationDotEvidence(
            noteheadId = note.id,
            scanRegion = BoundingBox(leftBound, startY, scanRight, endY),
            foregroundPixelCount = pixels,
            minimumPixelCount = minimum,
            maximumPixelCount = maximum,
            detected = pixels in minimum..maximum
        )
    }

    private fun unresolvedDot(
        noteheadId: Int,
        reason: RhythmUnresolvedReason
    ): AugmentationDotEvidence =
        AugmentationDotEvidence(
            noteheadId = noteheadId,
            scanRegion = null,
            foregroundPixelCount = null,
            minimumPixelCount = null,
            maximumPixelCount = null,
            detected = null,
            unresolvedReason = reason
        )

    private fun resolveGroupDotCount(
        dots: List<AugmentationDotEvidence>,
        stemDirection: StemDirection,
        reasons: MutableSet<RhythmUnresolvedReason>
    ): Int? {
        if (dots.isEmpty()) return null
        val detected = dots.map { it.detected }
        if (detected.any { it == null }) return null
        val values = detected.filterNotNull()
        if (values.all { it } || values.none { it }) {
            return if (values.first()) 1 else 0
        }
        if (stemDirection == StemDirection.UP || stemDirection == StemDirection.DOWN) {
            val trueCount = values.count { it }
            return if (trueCount >= values.size - trueCount) 1 else 0
        }
        reasons += RhythmUnresolvedReason.MIXED_DOT_EVIDENCE
        return null
    }

    private fun buildFilteredBeamMaps(
        evidence: RhythmEvidenceMasks,
        groupMap: IntArray,
        staffGeometry: List<StaffGeometry>
    ): FilteredBeamMaps {
        val beamsInStaff = openRect(
            evidence.staff,
            evidence.width,
            evidence.height,
            kernelHeight = 5,
            kernelWidth = 1
        )
        var mixed = BooleanArray(evidence.width * evidence.height) { index ->
            val mergedSymbol =
                evidence.symbols[index] || evidence.stems[index] || evidence.clefsKeys[index]
            val score =
                (if (mergedSymbol) 1 else 0) +
                    (if (beamsInStaff[index]) 1 else 0) -
                    (if (groupMap[index] >= 0) 1 else 0) -
                    (if (evidence.stems[index]) 1 else 0) -
                    (if (evidence.clefsKeys[index]) 1 else 0)
            score > 0
        }
        mixed = openRect(
            mixed,
            evidence.width,
            evidence.height,
            kernelHeight = 2,
            kernelWidth = 3
        )
        val extendedStems = closeRect(
            evidence.stems,
            evidence.width,
            evidence.height,
            kernelHeight = 5,
            kernelWidth = 1
        )
        val cleaned = BooleanArray(mixed.size) { index ->
            val beamUnion = mixed[index] || extendedStems[index] || groupMap[index] >= 0
            beamUnion && groupMap[index] < 0 && !evidence.stems[index]
        }

        val valid = BooleanArray(cleaned.size)
        val rasterThresholdAmbiguous = BooleanArray(cleaned.size)
        ConnectedComponentBoxExtractor
            .extractComponents(cleaned, evidence.width, evidence.height)
            .forEach { component ->
                val rectangle = minimumAreaRectangle(
                    component.sourcePixelIndices,
                    evidence.width
                ) ?: return@forEach
                if (rectangle.corners.any { it.x < 0.0 || it.y < 0.0 }) return@forEach

                val centerX = pythonRound(rectangle.corners.sumOf { it.x } / 4.0)
                val centerY = pythonRound(rectangle.corners.sumOf { it.y } / 4.0)
                val unitSize = unitSizeAt(staffGeometry, centerX, centerY) ?: return@forEach
                val integerCorners = rectangle.corners.map {
                    IntPoint(it.x.toInt(), it.y.toInt())
                }
                val area = polygonArea(integerCorners)
                if (area < unitSize * unitSize * BEAM_MIN_AREA_RATIO) return@forEach
                if (min(rectangle.width, rectangle.height) <
                    unitSize * BEAM_MIN_WIDTH_RATIO
                ) {
                    return@forEach
                }

                val minimumPolygonPixels = maxOf(1, floor(area).toInt())
                val perimeter = polygonPerimeter(integerCorners)
                val maximumPolygonPixels = ceil(area + perimeter + 1.0).toInt()
                val minimumTruePixels = component.sourcePixelIndices.count { index ->
                    pointInConvexPolygon(
                        IntPoint(index % evidence.width, index / evidence.width),
                        integerCorners
                    )
                }
                val definitelyValidRatio =
                    minimumTruePixels.toDouble() / maximumPolygonPixels
                val possiblyValidRatio =
                    component.sourcePixelIndices.size.toDouble() / minimumPolygonPixels
                when {
                    definitelyValidRatio >= BEAM_MIN_TRUE_POSITIVE_RATIO ->
                        component.sourcePixelIndices.forEach { valid[it] = true }
                    possiblyValidRatio >= BEAM_MIN_TRUE_POSITIVE_RATIO ->
                        component.sourcePixelIndices.forEach {
                            rasterThresholdAmbiguous[it] = true
                        }
                }
            }
        return FilteredBeamMaps(valid, rasterThresholdAmbiguous)
    }

    private fun buildBeamRegions(
        validBeamMap: BooleanArray,
        evidence: RhythmEvidenceMasks,
        groupMap: IntArray,
        chords: List<ChordCandidate>,
        staffGeometry: List<StaffGeometry>
    ): BeamAnalysis {
        var beamMap = openRect(
            validBeamMap,
            evidence.width,
            evidence.height,
            kernelHeight = 3,
            kernelWidth = 3
        )
        var mixed = BooleanArray(validBeamMap.size) { index ->
            val mergedSymbol =
                evidence.symbols[index] || evidence.stems[index] || evidence.clefsKeys[index]
            validBeamMap[index] || mergedSymbol
        }
        mixed = openRect(
            mixed,
            evidence.width,
            evidence.height,
            kernelHeight = 3,
            kernelWidth = 3
        )
        val verifiedBarlines = evidence.barlines.filter { box ->
            val unitSize = unitSizeAt(
                staffGeometry,
                pythonRound((box.left + box.right) / 2.0),
                pythonRound((box.top + box.bottom) / 2.0)
            )
            unitSize != null && box.height >= unitSize * BARLINE_MIN_HEIGHT_UNIT_RATIO
        }
        for (box in verifiedBarlines) {
            val left = box.left.coerceIn(0, evidence.width)
            val right = box.right.coerceIn(0, evidence.width)
            val top = box.top.coerceIn(0, evidence.height)
            val bottom = box.bottom.coerceIn(0, evidence.height)
            for (y in top until bottom) {
                for (x in left until right) {
                    val index = y * evidence.width + x
                    mixed[index] = false
                    beamMap[index] = false
                }
            }
        }

        val beamLabels = label4(beamMap, evidence.width, evidence.height)
        val symbolLabels = label4(mixed, evidence.width, evidence.height)
        val maxBeamLabel = beamLabels.maxOrNull() ?: 0
        val maxSymbolLabel = symbolLabels.maxOrNull() ?: 0
        val beamToSymbols = Array(maxBeamLabel + 1) { linkedSetOf<Int>() }
        val symbolBounds = Array(maxSymbolLabel + 1) { ComponentBounds() }
        val symbolGroups = Array(maxSymbolLabel + 1) { linkedSetOf<Int>() }

        symbolLabels.forEachIndexed { index, symbolLabel ->
            if (symbolLabel <= 0) return@forEachIndexed
            symbolBounds[symbolLabel].include(
                index % evidence.width,
                index / evidence.width,
                stem = false
            )
            val group = groupMap[index]
            if (group >= 0) symbolGroups[symbolLabel] += group
            val beamLabel = beamLabels[index]
            if (beamLabel > 0) beamToSymbols[beamLabel] += symbolLabel
        }

        val acceptedBeamLabels = linkedSetOf<Int>()
        val regions = mutableListOf<BeamRegion>()
        for (beamLabel in 1..maxBeamLabel) {
            val symbolSet = beamToSymbols[beamLabel]
            val groups = linkedSetOf<Int>()
            var box: PixelBox? = null
            symbolSet.forEach { symbolLabel ->
                groups += symbolGroups[symbolLabel]
                symbolBounds[symbolLabel].toBoundingBox()?.let { bounds ->
                    val next = bounds.toPixelBox()
                    box = box?.union(next) ?: next
                }
            }
            if (groups.isEmpty() || box == null) continue
            acceptedBeamLabels += beamLabel
            regions += BeamRegion(box!!, groups, symbolSet.toMutableSet())
        }

        var outputMap = BooleanArray(beamMap.size) { index ->
            beamLabels[index] in acceptedBeamLabels
        }
        outputMap = closeRect(
            outputMap,
            evidence.width,
            evidence.height,
            kernelHeight = 3,
            kernelWidth = 3
        )

        regions.forEach { region ->
            region.groupIndices.toList().forEach { groupIndex ->
                region.box = region.box.union(chords[groupIndex].boundingBox.toPixelBox())
            }
            val left = region.box.left.coerceIn(0, evidence.width)
            val right = region.box.right.coerceIn(0, evidence.width)
            val top = region.box.top.coerceIn(0, evidence.height)
            val bottom = region.box.bottom.coerceIn(0, evidence.height)
            for (y in top until bottom) {
                for (x in left until right) {
                    val group = groupMap[y * evidence.width + x]
                    if (group >= 0) region.groupIndices += group
                }
            }
        }
        mergeRegions(regions)

        return BeamAnalysis(
            map = outputMap,
            regions = regions.sortedWith(
                compareBy<BeamRegion> { it.box.left }
                    .thenBy { it.box.top }
                    .thenBy { it.groupIndices.minOrNull() ?: Int.MAX_VALUE }
            ),
            rasterThresholdAmbiguousGroups = emptySet()
        )
    }

    private fun mergeRegions(regions: MutableList<BeamRegion>) {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (first in regions.indices) {
                for (second in first + 1 until regions.size) {
                    val a = regions[first]
                    val b = regions[second]
                    if (
                        a.groupIndices.intersect(b.groupIndices).isEmpty() &&
                        a.symbolLabels.intersect(b.symbolLabels).isEmpty()
                    ) {
                        continue
                    }
                    a.box = a.box.union(b.box)
                    a.groupIndices += b.groupIndices
                    a.symbolLabels += b.symbolLabels
                    regions.removeAt(second)
                    changed = true
                    break@outer
                }
            }
        }
    }

    private fun resolveBeamEvidence(
        groupIndex: Int,
        chord: ChordCandidate,
        notes: List<NoteheadCandidate>,
        analysis: BeamAnalysis,
        staffGeometry: List<StaffGeometry>,
        width: Int,
        height: Int
    ): BeamEvidence {
        if (staffGeometry.isEmpty()) {
            return BeamEvidence(null, null, null, false, emptyList())
        }
        if (groupIndex in analysis.rasterThresholdAmbiguousGroups) {
            return BeamEvidence(
                null,
                null,
                null,
                true,
                listOf(RhythmUnresolvedReason.BEAM_RASTER_THRESHOLD_AMBIGUOUS)
            )
        }
        val regions = analysis.regions.filter { groupIndex in it.groupIndices }
        if (regions.isEmpty()) {
            return BeamEvidence(0, 0, 0, false, emptyList())
        }
        if (notes.isEmpty()) {
            return BeamEvidence(null, null, null, true, emptyList())
        }

        val regionBox = regions.map { it.box }.reduce(PixelBox::union)
        val groupBox = chord.boundingBox.toPixelBox()
        val scanBox = PixelBox(
            left = groupBox.left,
            top = minOf(groupBox.top, regionBox.top),
            right = groupBox.right,
            bottom = maxOf(groupBox.bottom, regionBox.bottom)
        )
        val unitSize = unitSizeAt(
            staffGeometry,
            pythonRound((scanBox.left + scanBox.right) / 2.0),
            pythonRound((scanBox.top + scanBox.bottom) / 2.0)
        ) ?: return BeamEvidence(null, null, null, true, emptyList())
        val halfScanWidth = pythonRound(unitSize / 2.0)
        val stemUp = chord.stemDirection == StemDirection.UP
        val stemDown = chord.stemDirection == StemDirection.DOWN
        if (!stemUp && !stemDown) {
            return BeamEvidence(null, null, null, true, emptyList())
        }

        val stemX = getStemX(groupBox, notes, unitSize, isRight = stemUp)
        val startY = if (stemUp) {
            notes.minOf { it.boundingBox.top }
        } else {
            notes.maxOf { it.boundingBox.bottom }
        }
        val endY = if (stemUp) scanBox.top else scanBox.bottom
        val startX = maxOf(regionBox.left, stemX - halfScanWidth)
        val endX = minOf(regionBox.right, stemX + halfScanWidth)
        val count = scanBeamFlag(
            analysis.map,
            startX,
            startY,
            endX,
            endY,
            unitSize,
            width,
            height
        )
        if (count == null) {
            return BeamEvidence(
                null,
                null,
                null,
                true,
                listOf(RhythmUnresolvedReason.BEAM_SCAN_OUT_OF_BOUNDS)
            )
        }

        val hasBeamRegion = regions.any { it.groupIndices.size > 1 }
        val hasFlagRegion = regions.any { it.groupIndices.size == 1 }
        if (hasBeamRegion && hasFlagRegion) {
            return BeamEvidence(
                null,
                null,
                count,
                true,
                listOf(RhythmUnresolvedReason.BEAM_FLAG_KIND_AMBIGUOUS)
            )
        }
        return if (hasBeamRegion) {
            BeamEvidence(count, 0, count, true, emptyList())
        } else {
            BeamEvidence(0, count, count, true, emptyList())
        }
    }

    private fun getStemX(
        groupBox: PixelBox,
        notes: List<NoteheadCandidate>,
        unitSize: Double,
        isRight: Boolean
    ): Int {
        val allSameSide = notes.all {
            abs(it.boundingBox.right - groupBox.right) < unitSize / 3.0
        }
        val stemAtCenter = !allSameSide
        return when {
            stemAtCenter -> pythonRound((groupBox.left + groupBox.right) / 2.0)
            isRight -> groupBox.right
            else -> groupBox.left
        }
    }

    private fun scanBeamFlag(
        map: BooleanArray,
        rawStartX: Int,
        rawStartY: Int,
        rawEndX: Int,
        rawEndY: Int,
        unitSize: Double,
        width: Int,
        height: Int
    ): Int? {
        val startX = rawStartX
        val endX = rawEndX
        var startY = rawStartY
        var endY = rawEndY
        if (endY < startY) {
            val swap = startY
            startY = endY
            endY = swap
        }
        if (
            startX !in 0 until width ||
            endX !in 0..width ||
            startY !in 0 until height ||
            endY !in 0..height ||
            endX <= startX ||
            endY <= startY
        ) {
            return null
        }

        val minimumWidth = unitSize * BEAM_SCAN_MIN_WIDTH_RATIO
        val maximumWidth = unitSize * BEAM_SCAN_MAX_WIDTH_RATIO
        val counters = IntArray(endX - startX)
        for ((offset, x) in (startX until endX).withIndex()) {
            var currentY = startY
            var lastValue = map[currentY * width + x]
            var hit = false
            var runWidth = 0
            while (currentY < endY) {
                hit = false
                runWidth = 0
                while (currentY < endY) {
                    val currentValue = map[currentY * width + x]
                    if (lastValue != currentValue) {
                        hit = lastValue && !currentValue
                        lastValue = currentValue
                        currentY += 1
                        break
                    }
                    currentY += 1
                    runWidth += 1
                }
                if (hit && runWidth >= minimumWidth) {
                    counters[offset] += ceil(runWidth / maximumWidth).toInt()
                }
            }
            if (lastValue) {
                counters[offset] += if (hit) {
                    ceil(runWidth / maximumWidth).toInt()
                } else {
                    1
                }
            }
        }

        val stats = counters.asIterable()
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.first }
        var accumulated = 0
        val minimumAgreement = counters.size * BEAM_SCAN_AGREEMENT
        for ((count, occurrences) in stats) {
            accumulated += occurrences
            if (accumulated > minimumAgreement) return count
        }
        return 0
    }

    private fun inferNoteDuration(
        chord: ChordCandidate,
        notes: List<NoteheadCandidate>,
        stem: StemAssociation,
        beamEvidence: BeamEvidence,
        reasons: MutableSet<RhythmUnresolvedReason>
    ): RhythmDuration? {
        if (notes.isEmpty()) return null
        val types = notes.map { it.type }.toSet()
        if (types.size != 1) {
            reasons += RhythmUnresolvedReason.MIXED_NOTEHEAD_TYPES
            return null
        }
        val type = types.single()

        if (!chord.hasStem || chord.stemDirection == StemDirection.NONE) {
            return if (type == NoteheadType.HALF_OR_WHOLE) {
                RhythmDuration.WHOLE
            } else {
                reasons += RhythmUnresolvedReason.SOLID_STEMLESS_NOTEHEAD
                null
            }
        }
        if (chord.stemDirection == StemDirection.AMBIGUOUS) {
            reasons += RhythmUnresolvedReason.AMBIGUOUS_STEM_DIRECTION
            return null
        }
        if (stem.status != StemAssociationStatus.ASSIGNED) return null
        if (type == NoteheadType.HALF_OR_WHOLE) return RhythmDuration.HALF
        if (beamEvidence.reasons.isNotEmpty()) return null

        val count = beamEvidence.totalCount ?: return null
        if (beamEvidence.hasRegion && count == 0) {
            reasons += RhythmUnresolvedReason.BEAM_REGION_WITH_ZERO_COUNT
            return null
        }
        return when (count) {
            0 -> RhythmDuration.QUARTER
            1 -> RhythmDuration.EIGHTH
            2 -> RhythmDuration.SIXTEENTH
            3 -> RhythmDuration.THIRTY_SECOND
            // Exact oemer 0.1.8 compatibility mapping.
            4 -> RhythmDuration.SIXTEENTH
            else -> {
                reasons += RhythmUnresolvedReason.UNSUPPORTED_BEAM_FLAG_COUNT
                null
            }
        }
    }

    private fun durationValue(
        duration: RhythmDuration?,
        dotCount: Int?
    ): RhythmValue? {
        if (duration == null || dotCount == null) return null
        val base = when (duration) {
            RhythmDuration.WHOLE -> RhythmValue.of(1, 1)
            RhythmDuration.HALF -> RhythmValue.of(1, 2)
            RhythmDuration.QUARTER -> RhythmValue.of(1, 4)
            RhythmDuration.EIGHTH -> RhythmValue.of(1, 8)
            RhythmDuration.SIXTEENTH -> RhythmValue.of(1, 16)
            RhythmDuration.THIRTY_SECOND -> RhythmValue.of(1, 32)
            RhythmDuration.SIXTY_FOURTH -> RhythmValue.of(1, 64)
            RhythmDuration.TRIPLET,
            RhythmDuration.OTHER -> return null
        }
        return when (dotCount) {
            0 -> base
            1 -> RhythmValue.of(base.numerator * 3, base.denominator * 2)
            else -> null
        }
    }

    /**
     * Maps `symbol_extraction.py::gen_rests()` output into rhythm evidence.
     * Dot detection is not repeated: [ClassifiedRestCandidate] already
     * contains that exact source-stage spatial result.
     */
    private fun resolveRest(
        restId: Int,
        rest: ClassifiedRestCandidate
    ): RestRhythmResult {
        val reasons = linkedSetOf<RhythmUnresolvedReason>()
        val duration = restDuration(rest.label, reasons)
        val dotCount = if (rest.hasAugmentationDot) 1 else 0
        val dotted = durationValue(duration, dotCount)
        val state = when {
            duration == null -> RhythmResolutionState.UNRESOLVED
            dotted == null -> RhythmResolutionState.PARTIAL
            else -> RhythmResolutionState.RESOLVED
        }
        return RestRhythmResult(
            restId = restId,
            source = rest,
            dotCount = dotCount,
            baseDuration = duration,
            dottedDuration = dotted,
            resolutionState = state,
            unresolvedReasons = reasons.sortedBy { it.ordinal }
        )
    }

    private fun restDuration(
        label: RestSymbolLabel,
        reasons: MutableSet<RhythmUnresolvedReason>
    ): RhythmDuration? = when (label) {
        RestSymbolLabel.WHOLE_OR_HALF -> {
            reasons += RhythmUnresolvedReason.REST_WHOLE_HALF_AMBIGUOUS
            null
        }
        RestSymbolLabel.QUARTER -> RhythmDuration.QUARTER
        RestSymbolLabel.EIGHTH -> RhythmDuration.EIGHTH
        RestSymbolLabel.SIXTEENTH -> RhythmDuration.SIXTEENTH
        RestSymbolLabel.THIRTY_SECOND -> RhythmDuration.THIRTY_SECOND
        RestSymbolLabel.SIXTY_FOURTH -> RhythmDuration.SIXTY_FOURTH
    }

    private fun unitSizeAt(
        geometry: List<StaffGeometry>,
        x: Int,
        y: Int
    ): Double? {
        if (geometry.isEmpty()) return null
        val sorted = geometry.sortedBy { staff ->
            val xDistance = x - staff.xCenter
            val yDistance = y - staff.yCenter
            kotlin.math.sqrt(xDistance * xDistance + yDistance * yDistance)
        }
        val first: StaffGeometry
        val second: StaffGeometry
        when (sorted.size) {
            1 -> {
                first = sorted[0]
                second = sorted[0]
            }
            2 -> {
                first = sorted[0]
                second = sorted[1]
            }
            else -> {
                first = sorted[0]
                val candidate2 = sorted[1]
                val candidate3 = sorted[2]
                second = if (abs(first.yLower - y) <= abs(first.yUpper - y)) {
                    when {
                        candidate2.yCenter > first.yCenter -> candidate2
                        candidate3.yCenter > first.yCenter -> candidate3
                        else -> first
                    }
                } else {
                    when {
                        candidate2.yCenter < first.yCenter -> candidate2
                        candidate3.yCenter < first.yCenter -> candidate3
                        else -> first
                    }
                }
            }
        }
        if (first.yCenter == second.yCenter) return first.unitSize
        if (y.toDouble() in first.yUpper..first.yLower) return first.unitSize
        val distance1 = abs(y - first.yCenter)
        val distance2 = abs(y - second.yCenter)
        val total = distance1 + distance2
        if (total == 0.0) return first.unitSize
        val weight1 = distance1 / total
        val weight2 = distance2 / total
        return weight1 * first.unitSize + weight2 * second.unitSize
    }

    private fun openRect(
        source: BooleanArray,
        width: Int,
        height: Int,
        kernelHeight: Int,
        kernelWidth: Int
    ): BooleanArray {
        val eroded = rectMorph(
            source, width, height, kernelHeight, kernelWidth, erode = true
        )
        return rectMorph(
            eroded, width, height, kernelHeight, kernelWidth, erode = false
        )
    }

    private fun closeRect(
        source: BooleanArray,
        width: Int,
        height: Int,
        kernelHeight: Int,
        kernelWidth: Int
    ): BooleanArray {
        val dilated = rectMorph(
            source, width, height, kernelHeight, kernelWidth, erode = false
        )
        return rectMorph(
            dilated, width, height, kernelHeight, kernelWidth, erode = true
        )
    }

    /** Rectangular OpenCV morphology is separable; reuse the shared 1-D primitive. */
    private fun rectMorph(
        source: BooleanArray,
        width: Int,
        height: Int,
        kernelHeight: Int,
        kernelWidth: Int,
        erode: Boolean
    ): BooleanArray {
        var current = source
        if (kernelHeight > 1) {
            current = StaffMaskMorphology.slide(
                current,
                width,
                height,
                kernelHeight,
                vertical = true,
                erode = erode
            )
        }
        if (kernelWidth > 1) {
            current = StaffMaskMorphology.slide(
                current,
                width,
                height,
                kernelWidth,
                vertical = false,
                erode = erode
            )
        }
        return if (current === source) source.copyOf() else current
    }

    private fun label4(mask: BooleanArray, width: Int, height: Int): IntArray {
        val foreground = IntArray(mask.size) { if (mask[it]) 0 else -1 }
        return ConnectedComponents.label(foreground, width, height)
    }

    private fun minimumAreaRectangle(
        pixelIndices: IntArray,
        width: Int
    ): RotatedRectangle? {
        if (pixelIndices.size < 3) return null
        val points = pixelIndices.map { Point((it % width).toDouble(), (it / width).toDouble()) }
        val hull = convexHull(points)
        if (hull.size < 3) return null

        var best: RotatedRectangle? = null
        var bestArea = Double.POSITIVE_INFINITY
        for (index in hull.indices) {
            val start = hull[index]
            val end = hull[(index + 1) % hull.size]
            val angle = atan2(end.y - start.y, end.x - start.x)
            val cosine = cos(angle)
            val sine = sin(angle)
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            hull.forEach { point ->
                val rotatedX = point.x * cosine + point.y * sine
                val rotatedY = -point.x * sine + point.y * cosine
                minX = minOf(minX, rotatedX)
                maxX = maxOf(maxX, rotatedX)
                minY = minOf(minY, rotatedY)
                maxY = maxOf(maxY, rotatedY)
            }
            val rectangleWidth = maxX - minX
            val rectangleHeight = maxY - minY
            val area = rectangleWidth * rectangleHeight
            if (area >= bestArea) continue
            bestArea = area
            val rotatedCorners = listOf(
                Point(minX, minY),
                Point(maxX, minY),
                Point(maxX, maxY),
                Point(minX, maxY)
            )
            val corners = rotatedCorners.map { point ->
                Point(
                    x = point.x * cosine - point.y * sine,
                    y = point.x * sine + point.y * cosine
                )
            }
            best = RotatedRectangle(rectangleWidth, rectangleHeight, corners)
        }
        return best
    }

    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points
            .distinct()
            .sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        if (sorted.size <= 1) return sorted
        val lower = mutableListOf<Point>()
        sorted.forEach { point ->
            while (
                lower.size >= 2 &&
                cross(lower[lower.lastIndex - 1], lower.last(), point) <= 0.0
            ) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = mutableListOf<Point>()
        sorted.asReversed().forEach { point ->
            while (
                upper.size >= 2 &&
                cross(upper[upper.lastIndex - 1], upper.last(), point) <= 0.0
            ) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun cross(origin: Point, first: Point, second: Point): Double =
        (first.x - origin.x) * (second.y - origin.y) -
            (first.y - origin.y) * (second.x - origin.x)

    private fun polygonArea(points: List<IntPoint>): Double {
        var sum = 0L
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x.toLong() * next.y - next.x.toLong() * current.y
        }
        return abs(sum.toDouble()) / 2.0
    }

    /**
     * Brackets OpenCV `fillPoly`'s boundary rasterization for the 0.4
     * true-positive test. If the threshold falls inside the bracket, the
     * component is routed to an explicit unresolved result instead of
     * relying on an unverified one-pixel slanted-edge tie.
     */
    private fun polygonPerimeter(points: List<IntPoint>): Double =
        points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            hypot(
                (next.x - current.x).toDouble(),
                (next.y - current.y).toDouble()
            )
        }

    private fun pointInConvexPolygon(point: IntPoint, polygon: List<IntPoint>): Boolean {
        var hasPositive = false
        var hasNegative = false
        for (index in polygon.indices) {
            val first = polygon[index]
            val second = polygon[(index + 1) % polygon.size]
            val cross =
                (second.x - first.x).toLong() * (point.y - first.y) -
                    (second.y - first.y).toLong() * (point.x - first.x)
            if (cross > 0) hasPositive = true
            if (cross < 0) hasNegative = true
            if (hasPositive && hasNegative) return false
        }
        return true
    }

    private fun List<BoundingBox>.unionBox(): BoundingBox? {
        if (isEmpty()) return null
        return BoundingBox(
            left = minOf { it.left },
            top = minOf { it.top },
            right = maxOf { it.right },
            bottom = maxOf { it.bottom }
        )
    }

    private fun BoundingBox.toPixelBox(): PixelBox =
        PixelBox(left, top, right - 1, bottom - 1)

    private fun pythonRound(value: Double): Int = round(value).toInt()
}
