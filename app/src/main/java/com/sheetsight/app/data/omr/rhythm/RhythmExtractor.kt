package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate

/**
 * Phase 4.7D framework boundary.
 *
 * `rhythm_extraction.py` is verified in the pinned oemer source, but its
 * dot morphology, rotated beam boxes, overlap-region refinement, and
 * beam/flag scans have not been ported in this phase. [prepareCandidates]
 * therefore creates only identity-bearing unresolved records. It does
 * not inspect evidence pixels and cannot silently become duration logic.
 */
object RhythmExtractor {

    fun prepareCandidates(
        noteheads: List<NoteheadCandidate>,
        chords: List<ChordCandidate>,
        evidence: RhythmEvidenceMasks
    ): List<RhythmCandidate> {
        val byId = noteheads.associateBy { it.id }
        return chords.mapIndexed { index, chord ->
            val members = chord.noteheads.mapNotNull { byId[it.id] }
            RhythmCandidate(
                id = index,
                chord = chord,
                noteheads = members,
                evidenceStatus = if (evidence.isComplete) {
                    RhythmEvidenceStatus.COMPLETE
                } else {
                    RhythmEvidenceStatus.INCOMPLETE
                },
                duration = null
            )
        }
    }

    /**
     * Explicit stop point required by Phase 4.7D. Callers must not replace
     * the missing verified port with a default duration or heuristic.
     */
    fun resolveDurations(candidates: List<RhythmCandidate>): List<RhythmCandidate> {
        throw NotImplementedError(
            "Exact oemer rhythm extraction has not been ported; " +
                    "${candidates.size} candidate(s) remain unresolved"
        )
    }
}
