package com.sheetsight.app.data.omr.semantic

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadStaffAssignment
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.rhythm.AugmentationDotEvidence
import com.sheetsight.app.data.omr.rhythm.RestRhythmResult
import com.sheetsight.app.data.omr.rhythm.RhythmCandidate
import com.sheetsight.app.data.omr.rhythm.RhythmDuration
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceStatus
import com.sheetsight.app.data.omr.rhythm.RhythmResolutionState
import com.sheetsight.app.data.omr.rhythm.RhythmValue
import com.sheetsight.app.data.omr.rhythm.StemAssociation
import com.sheetsight.app.data.omr.rhythm.StemAssociationStatus
import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.symbol.AccidentalCandidate
import com.sheetsight.app.data.omr.symbol.AccidentalSymbolLabel
import com.sheetsight.app.data.omr.symbol.ClassifiedRestCandidate
import com.sheetsight.app.data.omr.symbol.ClefCandidate
import com.sheetsight.app.data.omr.symbol.ClefSymbolLabel
import com.sheetsight.app.data.omr.symbol.RestSymbolLabel
import com.sheetsight.app.data.omr.symbol.SvmModelKind
import com.sheetsight.app.data.omr.symbol.SvmModelSpec
import com.sheetsight.app.data.omr.symbol.SymbolClassification
import com.sheetsight.app.data.omr.symbol.SymbolStaffAssignment
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox

internal object SemanticTestFixtures {
    fun staffGrid(width: Int = 200): List<List<AssignedStaff>> = listOf(
        listOf(AssignedStaff(staff(width), track = 0, group = 0))
    )

    fun note(id: Int, x: Int, staffPosition: Int): NoteheadCandidate = NoteheadCandidate(
        id = id,
        boundingBox = BoundingBox(x - 4, 55, x + 5, 63),
        type = NoteheadType.SOLID,
        staffAssignment = NoteheadStaffAssignment(0, 0, staffPosition),
        sourcePixelIndices = intArrayOf(id, id + 1),
        stemOnRight = true
    )

    fun rhythmChord(
        id: Int,
        x: Int,
        notes: List<NoteheadCandidate>,
        duration: RhythmValue? = RhythmValue.of(1, 4)
    ): RhythmCandidate {
        val box = BoundingBox(x - 5, 30, x + 6, 65)
        val chord = ChordCandidate(id, notes, box, StemDirection.UP, true, 0, 0)
        return RhythmCandidate(
            id = id,
            noteGroupId = id,
            chord = chord,
            noteheads = notes,
            evidenceStatus = RhythmEvidenceStatus.COMPLETE,
            stemDirection = StemDirection.UP,
            stemAssociation = StemAssociation(StemAssociationStatus.ASSIGNED, StemDirection.UP, box),
            beamCount = 0,
            flagCount = 0,
            dotCount = 0,
            dotEvidence = emptyList<AugmentationDotEvidence>(),
            baseDuration = duration?.let { RhythmDuration.QUARTER },
            dottedDuration = duration,
            resolutionState = if (duration == null) RhythmResolutionState.UNRESOLVED else RhythmResolutionState.RESOLVED,
            unresolvedReasons = emptyList()
        )
    }

    fun clef(x: Int, label: ClefSymbolLabel): ClefCandidate = ClefCandidate(
        BoundingBox(x - 3, 35, x + 4, 85),
        label,
        SymbolStaffAssignment(0, 0),
        classification(SvmModelKind.CLEF, label)
    )

    fun accidental(
        x: Int,
        y: Int,
        label: AccidentalSymbolLabel,
        noteId: Int?
    ): AccidentalCandidate = AccidentalCandidate(
        BoundingBox(x - 2, y - 5, x + 3, y + 6),
        label,
        SymbolStaffAssignment(0, 0),
        noteId,
        classification(SvmModelKind.ACCIDENTAL, label)
    )

    fun rest(id: Int, x: Int, duration: RhythmValue? = RhythmValue.of(1, 4)): RestRhythmResult {
        val source = ClassifiedRestCandidate(
            BoundingBox(x - 3, 50, x + 4, 65),
            RestSymbolLabel.QUARTER,
            SymbolStaffAssignment(0, 0),
            false,
            classification(SvmModelKind.REST, RestSymbolLabel.QUARTER),
            null
        )
        return RestRhythmResult(
            restId = id,
            source = source,
            dotCount = 0,
            baseDuration = duration?.let { RhythmDuration.QUARTER },
            dottedDuration = duration,
            resolutionState = if (duration == null) RhythmResolutionState.UNRESOLVED else RhythmResolutionState.RESOLVED,
            unresolvedReasons = emptyList()
        )
    }

    private fun staff(width: Int): ZoneStaff = ZoneStaff(
        StafflinePosition.entries.mapIndexed { index, position ->
            Staffline(position, (0 until width).map { x -> StafflinePoint(x, 40 + index * 10) })
        }
    )

    private fun classification(
        model: SvmModelKind,
        label: com.sheetsight.app.data.omr.symbol.OemerSymbolLabel
    ): SymbolClassification {
        val spec = SvmModelSpec.forKind(model)
        return SymbolClassification(model, spec.labels.indexOf(label), label, emptyList())
    }
}

