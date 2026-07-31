package com.sheetsight.app.data.omr.semantic

import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.rhythm.RestRhythmResult
import com.sheetsight.app.data.omr.rhythm.RhythmCandidate
import com.sheetsight.app.data.omr.rhythm.RhythmExtractionResult
import com.sheetsight.app.data.omr.rhythm.RhythmResolutionState
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.symbol.AccidentalCandidate
import com.sheetsight.app.data.omr.symbol.AccidentalSymbolLabel
import com.sheetsight.app.data.omr.symbol.ClefCandidate
import com.sheetsight.app.data.omr.symbol.ClefSymbolLabel
import com.sheetsight.app.data.omr.symbol.MusicalBarlineCandidate
import com.sheetsight.app.data.omr.symbol.SymbolExtractionResult
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recognition-to-semantics adapter. Semantic model classes import no image-processing types. */
object SemanticScoreConstructor {
    fun construct(
        staffGrid: List<List<AssignedStaff>>,
        symbols: SymbolExtractionResult,
        rhythm: RhythmExtractionResult
    ): SemanticScore {
        val staffSegments = staffGrid.flatten()
        if (staffSegments.isEmpty()) return SemanticScore(emptyList())

        val constructionWarnings = mutableListOf<SemanticValidationWarning>()
        val clefs = sourceClefs(symbols.clefs)
        val accidentals = sourceAccidentals(symbols.accidentals)
        val barlines = sourceBarlines(symbols.barlines)
        val activeClefs = mutableMapOf<Int, SemanticClef>()
        val activeKeys = mutableMapOf<Int, Map<PitchStep, AccidentalAlteration>>()
        var nextMeasureIndex = 0

        val systems = staffSegments.map { it.group }.distinct().sorted().mapIndexed { systemIndex, group ->
            val groupSegments = staffSegments.filter { it.group == group }
            val bounds = boundsOf(groupSegments)
            val systemId = "system-$systemIndex"
            val staffs = groupSegments.map { it.track }.distinct().sorted().mapIndexed { staffIndex, track ->
                val segments = groupSegments.filter { it.track == track }
                SemanticStaff(
                    id = staffId(systemIndex, track),
                    index = staffIndex,
                    systemId = systemId,
                    source = SemanticSourceRef(
                        SemanticSourceKind.STAFF_GRID,
                        "group-$group-track-$track",
                        boundsOf(segments)
                    )
                )
            }

            val groupBarlines = barlines.filter { it.candidate.group == group }
            val boundaries = MeasureConstructor.construct(
                bounds.left,
                bounds.right,
                groupBarlines.map { DetectedMeasureBarline(it.x, it.source) }
            )
            val emptyMeasures = boundaries.map {
                SemanticMeasure(
                    id = "measure-${nextMeasureIndex}",
                    index = nextMeasureIndex++,
                    systemId = systemId,
                    boundary = it,
                    events = emptyList()
                )
            }

            val groupClefs = clefs.filter { it.candidate.assignment.group == group }
            val groupAccidentals = accidentals.filter { it.candidate.assignment.group == group }
            val groupChords = rhythm.noteGroups.filter { it.chord.group == group }
            val groupRests = rhythm.rests.filter { it.group == group }
            val headerSignatures = headerKeySignatures(
                systemIndex,
                group,
                groupSegments,
                groupClefs,
                groupAccidentals,
                groupChords,
                groupRests,
                activeClefs
            )
            val headerSources = headerSignatures.flatMap { it.accidentals }.map { it.source }.toSet()
            groupAccidentals
                .filter { it.candidate.nearbyNoteheadId == null && it.source !in headerSources }
                .forEach {
                    constructionWarnings += SemanticValidationWarning(
                        SemanticValidationCode.UNASSIGNED_ACCIDENTAL,
                        "${it.source.id} is classified but cannot be safely assigned to a note or key signature",
                        sourceRefs = listOf(it.source)
                    )
                }

            val eventsByMeasure = emptyMeasures.associate { it.id to mutableListOf<SemanticEvent>() }
            val localAccidentals = groupAccidentals
                .filter { it.candidate.nearbyNoteheadId != null }
                .groupBy { it.candidate.nearbyNoteheadId!! }
            localAccidentals.filterValues { it.size > 1 }.forEach { (noteId, duplicates) ->
                constructionWarnings += SemanticValidationWarning(
                    SemanticValidationCode.DUPLICATE_ASSIGNMENT,
                    "notehead-$noteId has ${duplicates.size} classified local accidentals; the nearest one is used",
                    "note-$noteId",
                    duplicates.map { it.source }
                )
            }

            emptyMeasures.forEach { measure ->
                val inheritedClefsForMeasure = activeClefs.toMap()
                val stateByTrack = staffs.associate { staff ->
                    val track = trackFromStaffId(staff.id)
                    track to MeasureAccidentalState(activeKeys[track].orEmpty())
                }.toMutableMap()

                val clefEvents = groupClefs
                    .filter { measure.contains(it.x) }
                    .sortedBy { it.x }
                    .map { sourced ->
                        val track = sourced.candidate.assignment.track
                        val clef = sourced.candidate.label.toSemantic()
                        SemanticClefChange(
                            id = "clef-${sourced.source.id}",
                            measureId = measure.id,
                            staffId = staffId(systemIndex, track),
                            horizontalPosition = sourced.x,
                            sourceRefs = listOf(sourced.source),
                            clef = clef
                        )
                    }
                eventsByMeasure.getValue(measure.id) += clefEvents

                headerSignatures
                    .filter { measure.contains(it.x) }
                    .forEach { signature ->
                        val resolved = signature.accidentals.mapNotNull { accidental ->
                            val clef = activeClefAt(
                                signature.track,
                                accidental.x,
                                groupClefs,
                                inheritedClefsForMeasure[signature.track]
                            )
                            val position = staffPositionFor(
                                centerY(accidental.candidate.boundingBox),
                                accidental.x,
                                signature.track,
                                groupSegments
                            )
                            PitchAssigner.assign(position, clef)?.step?.let { step ->
                                step to accidental.candidate.label.toSemantic()
                            }
                        }.toMap()
                        activeKeys[signature.track] = resolved
                        stateByTrack.getValue(signature.track).updateKeySignature(resolved)
                        eventsByMeasure.getValue(measure.id) += SemanticKeySignature(
                            id = "key-system-$systemIndex-track-${signature.track}",
                            measureId = measure.id,
                            staffId = staffId(systemIndex, signature.track),
                            horizontalPosition = signature.x,
                            sourceRefs = signature.accidentals.map { it.source },
                            alterations = resolved.toSortedMap(compareBy { it.ordinal })
                        )
                    }

                groupChords
                    .filter { measure.contains(centerX(it.chord.boundingBox)) }
                    .sortedWith(compareBy<RhythmCandidate> { centerX(it.chord.boundingBox) }.thenBy { it.noteGroupId })
                    .forEach { candidate ->
                        val track = candidate.chord.track
                        val x = centerX(candidate.chord.boundingBox)
                        val clef = activeClefAt(track, x, groupClefs, inheritedClefsForMeasure[track])
                        val accidentalState = stateByTrack.getValue(track)
                        val notes = candidate.noteheads.sortedBy { it.id }.map { notehead ->
                            val basePitch = PitchAssigner.assign(
                                notehead.staffAssignment.staffLinePosition,
                                clef
                            )
                            val local = localAccidentals[notehead.id]
                                ?.minWithOrNull(compareBy<SourcedAccidental> {
                                    abs(it.candidate.boundingBox.right - notehead.boundingBox.left)
                                }.thenBy { it.source.id })
                            if (basePitch != null && local != null) {
                                accidentalState.applyLocal(
                                    basePitch.step,
                                    basePitch.octave,
                                    local.candidate.label.toSemantic()
                                )
                            }
                            val pitch = basePitch?.copy(
                                alteration = accidentalState.alterationFor(basePitch.step, basePitch.octave)
                            )
                            SemanticNote(
                                id = "note-${notehead.id}",
                                measureId = measure.id,
                                staffId = staffId(systemIndex, track),
                                horizontalPosition = centerX(notehead.boundingBox),
                                sourceRefs = listOf(noteheadSource(notehead.id, notehead.boundingBox)) +
                                    listOfNotNull(local?.source),
                                pitch = pitch,
                                activeClef = clef
                            )
                        }
                        eventsByMeasure.getValue(measure.id) += SemanticChord(
                            id = "chord-${candidate.noteGroupId}",
                            measureId = measure.id,
                            staffId = staffId(systemIndex, track),
                            horizontalPosition = x,
                            sourceRefs = listOf(
                                source(SemanticSourceKind.NOTE_GROUP, candidate.noteGroupId, candidate.chord.boundingBox),
                                source(SemanticSourceKind.RHYTHM, candidate.id, candidate.chord.boundingBox)
                            ),
                            notes = notes,
                            duration = candidate.dottedDuration?.let { SemanticDuration(it.numerator, it.denominator) },
                            rhythmState = candidate.resolutionState.toSemantic(),
                            stemDirection = candidate.stemDirection.toSemantic(),
                            beamInfo = SemanticBeamInfo(candidate.beamCount, candidate.flagCount),
                            augmentationDots = candidate.dotCount
                        )
                    }

                groupRests
                    .filter { measure.contains(centerX(it.boundingBox)) }
                    .sortedWith(compareBy<RestRhythmResult> { centerX(it.boundingBox) }.thenBy { it.restId })
                    .forEach { rest ->
                        eventsByMeasure.getValue(measure.id) += SemanticRest(
                            id = "rest-${rest.restId}",
                            measureId = measure.id,
                            staffId = staffId(systemIndex, rest.track),
                            horizontalPosition = centerX(rest.boundingBox),
                            sourceRefs = listOf(source(SemanticSourceKind.REST, rest.restId, rest.boundingBox)),
                            duration = rest.dottedDuration?.let { SemanticDuration(it.numerator, it.denominator) },
                            rhythmState = rest.resolutionState.toSemantic(),
                            augmentationDots = rest.dotCount
                        )
                    }

                groupClefs
                    .filter { measure.contains(it.x) }
                    .groupBy { it.candidate.assignment.track }
                    .forEach { (track, values) ->
                        activeClefs[track] = values.maxWith(
                            compareBy<SourcedClef> { it.x }.thenBy { it.source.id }
                        ).candidate.label.toSemantic()
                    }
            }

            groupBarlines.forEach { barline ->
                val measure = emptyMeasures.measureForBarline(barline.x) ?: return@forEach
                eventsByMeasure.getValue(measure.id) += SemanticBarline(
                    id = "barline-${barline.source.id}",
                    measureId = measure.id,
                    horizontalPosition = barline.x,
                    sourceRefs = listOf(barline.source)
                )
            }

            val measures = emptyMeasures.map { measure ->
                measure.copy(events = eventsByMeasure.getValue(measure.id).sortedWith(semanticEventComparator))
            }
            SemanticSystem(
                id = systemId,
                index = systemIndex,
                staffs = staffs,
                measures = measures,
                horizontalBounds = bounds,
                source = SemanticSourceRef(
                    SemanticSourceKind.STAFF_GRID,
                    "group-$group",
                    bounds
                )
            )
        }

        val raw = SemanticScore(listOf(SemanticPart("part-0", systems)))
        val warnings = (constructionWarnings + SemanticValidator.validate(raw))
            .distinct()
            .sortedWith(
                compareBy<SemanticValidationWarning> { it.code.ordinal }
                    .thenBy { it.semanticId ?: "" }
                    .thenBy { it.message }
            )
        return raw.copy(validationWarnings = warnings)
    }

    private fun headerKeySignatures(
        systemIndex: Int,
        group: Int,
        staffSegments: List<AssignedStaff>,
        clefs: List<SourcedClef>,
        accidentals: List<SourcedAccidental>,
        chords: List<RhythmCandidate>,
        rests: List<RestRhythmResult>,
        inheritedClefs: Map<Int, SemanticClef>
    ): List<HeaderKeySignature> = staffSegments.map { it.track }.distinct().sorted().mapNotNull { track ->
        val firstRhythmicX = buildList {
            chords.filter { it.chord.track == track }.forEach { add(centerX(it.chord.boundingBox)) }
            rests.filter { it.track == track }.forEach { add(centerX(it.boundingBox)) }
        }.minOrNull() ?: Int.MAX_VALUE
        val latestClefX = clefs
            .filter { it.candidate.assignment.track == track && it.x < firstRhythmicX }
            .maxOfOrNull { it.x }
        if (latestClefX == null && inheritedClefs[track] == null) return@mapNotNull null
        val header = accidentals.filter {
            it.candidate.assignment.group == group &&
                it.candidate.assignment.track == track &&
                it.candidate.nearbyNoteheadId == null &&
                it.x < firstRhythmicX &&
                (latestClefX == null || it.x > latestClefX)
        }.sortedBy { it.x }
        if (header.isEmpty()) null else HeaderKeySignature(systemIndex, track, header.first().x, header)
    }

    private fun activeClefAt(
        track: Int,
        x: Int,
        clefs: List<SourcedClef>,
        inherited: SemanticClef?
    ): SemanticClef? = clefs
        .filter { it.candidate.assignment.track == track && it.x <= x }
        .maxWithOrNull(compareBy<SourcedClef> { it.x }.thenBy { it.source.id })
        ?.candidate?.label?.toSemantic()
        ?: inherited

    private fun staffPositionFor(
        y: Int,
        x: Int,
        track: Int,
        segments: List<AssignedStaff>
    ): Int {
        val staff = segments.filter { it.track == track }.minBy { assigned ->
            abs(assigned.staff.lines.map { it.xCenter }.average() - x)
        }.staff
        val bottomLineY = staff.lines.maxOf { it.yCenter }
        return ((bottomLineY - y) / (staff.unitSize / 2.0)).roundToInt() + 1
    }

    private fun boundsOf(segments: List<AssignedStaff>): SemanticBounds = SemanticBounds(
        left = segments.minOf { staffLeft(it.staff) },
        top = segments.minOf { staffTop(it.staff) },
        right = segments.maxOf { staffRight(it.staff) } + 1,
        bottom = segments.maxOf { staffBottom(it.staff) } + 1
    )

    private fun staffLeft(staff: ZoneStaff) = staff.lines.minOf { it.xLeft }
    private fun staffRight(staff: ZoneStaff) = staff.lines.maxOf { it.xRight }
    private fun staffTop(staff: ZoneStaff) = staff.lines.minOf { it.yUpper }
    private fun staffBottom(staff: ZoneStaff) = staff.lines.maxOf { it.yLower }

    private fun SemanticMeasure.contains(x: Int): Boolean =
        x >= boundary.left && (x < boundary.right || boundary.rightEvidence == MeasureBoundaryEvidence.STAFF_EXTENT && x == boundary.right)

    private fun List<SemanticMeasure>.measureForBarline(x: Int): SemanticMeasure? =
        lastOrNull { it.boundary.right == x } ?: firstOrNull { it.contains(x) }

    private fun sourceClefs(values: List<ClefCandidate>) = values
        .sortedWith(compareBy<ClefCandidate> { it.assignment.group }.thenBy { it.assignment.track }.thenBy { it.boundingBox.left })
        .mapIndexed { index, value -> SourcedClef(value, source(SemanticSourceKind.CLEF, index, value.boundingBox)) }

    private fun sourceAccidentals(values: List<AccidentalCandidate>) = values
        .sortedWith(compareBy<AccidentalCandidate> { it.assignment.group }.thenBy { it.assignment.track }.thenBy { it.boundingBox.left })
        .mapIndexed { index, value -> SourcedAccidental(value, source(SemanticSourceKind.ACCIDENTAL, index, value.boundingBox)) }

    private fun sourceBarlines(values: List<MusicalBarlineCandidate>) = values
        .sortedWith(compareBy<MusicalBarlineCandidate> { it.group }.thenBy { it.boundingBox.left })
        .mapIndexed { index, value -> SourcedBarline(value, source(SemanticSourceKind.BARLINE, index, value.boundingBox)) }

    private fun source(kind: SemanticSourceKind, id: Int, box: BoundingBox) =
        SemanticSourceRef(kind, id.toString(), box.toSemantic())

    private fun noteheadSource(id: Int, box: BoundingBox) = source(SemanticSourceKind.NOTEHEAD, id, box)
    private fun BoundingBox.toSemantic() = SemanticBounds(left, top, right, bottom)
    private fun centerX(box: BoundingBox) = Math.floorDiv(box.left + box.right, 2)
    private fun centerY(box: BoundingBox) = Math.floorDiv(box.top + box.bottom, 2)
    private val SourcedClef.x get() = centerX(candidate.boundingBox)
    private val SourcedAccidental.x get() = centerX(candidate.boundingBox)
    private val SourcedBarline.x get() = centerX(candidate.boundingBox)
    private fun staffId(systemIndex: Int, track: Int) = "system-$systemIndex-staff-$track"
    private fun trackFromStaffId(id: String) = id.substringAfterLast('-').toInt()

    private fun ClefSymbolLabel.toSemantic() = when (this) {
        ClefSymbolLabel.G_CLEF -> SemanticClef.TREBLE
        ClefSymbolLabel.F_CLEF -> SemanticClef.BASS
    }

    private fun AccidentalSymbolLabel.toSemantic() = when (this) {
        AccidentalSymbolLabel.SHARP -> AccidentalAlteration.SHARP
        AccidentalSymbolLabel.FLAT -> AccidentalAlteration.FLAT
        AccidentalSymbolLabel.NATURAL -> AccidentalAlteration.NATURAL
    }

    private fun RhythmResolutionState.toSemantic() = when (this) {
        RhythmResolutionState.RESOLVED -> SemanticRhythmState.RESOLVED
        RhythmResolutionState.PARTIAL -> SemanticRhythmState.PARTIAL
        RhythmResolutionState.UNRESOLVED -> SemanticRhythmState.UNRESOLVED
    }

    private fun StemDirection.toSemantic() = when (this) {
        StemDirection.UP -> SemanticStemDirection.UP
        StemDirection.DOWN -> SemanticStemDirection.DOWN
        StemDirection.NONE -> SemanticStemDirection.NONE
        StemDirection.AMBIGUOUS -> SemanticStemDirection.AMBIGUOUS
    }

    private data class SourcedClef(val candidate: ClefCandidate, val source: SemanticSourceRef)
    private data class SourcedAccidental(val candidate: AccidentalCandidate, val source: SemanticSourceRef)
    private data class SourcedBarline(val candidate: MusicalBarlineCandidate, val source: SemanticSourceRef)
    private data class HeaderKeySignature(
        val systemIndex: Int,
        val track: Int,
        val x: Int,
        val accidentals: List<SourcedAccidental>
    )
}

data class SemanticScoreSummary(
    val systems: Int,
    val staffs: Int,
    val measures: Int,
    val notes: Int,
    val chords: Int,
    val rests: Int,
    val unresolvedEvents: Int,
    val validationWarnings: Int
)

fun SemanticScore.summary(): SemanticScoreSummary {
    val events = measures.flatMap { it.events }
    val chords = events.filterIsInstance<SemanticChord>()
    val rests = events.filterIsInstance<SemanticRest>()
    val notes = chords.flatMap { it.notes }
    return SemanticScoreSummary(
        systems = systems.size,
        staffs = staffs.size,
        measures = measures.size,
        notes = notes.size,
        chords = chords.size,
        rests = rests.size,
        unresolvedEvents = notes.count { it.pitch == null } +
            chords.count { it.duration == null } + rests.count { it.duration == null },
        validationWarnings = validationWarnings.size
    )
}
