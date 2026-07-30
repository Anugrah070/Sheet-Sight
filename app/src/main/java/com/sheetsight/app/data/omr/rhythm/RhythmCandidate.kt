package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate

/** Evidence-layer availability only; it is not a rhythmic prediction. */
enum class RhythmEvidenceStatus {
    COMPLETE,
    INCOMPLETE
}

/**
 * Framework output for Phase 4.7D.
 *
 * [duration] remains null until the exact oemer dot/beam/flag algorithm is
 * ported and verified. No default duration is implied by null.
 *
 * **Unverified required-model deviation:** oemer mutates `NoteHead.label`
 * in place and has no `RhythmCandidate` class. This immutable candidate is
 * the explicit output required by Phase 4.7D; it intentionally contains
 * no inferred dot, beam, flag, or duration values.
 */
data class RhythmCandidate(
    val id: Int,
    val chord: ChordCandidate,
    val noteheads: List<NoteheadCandidate>,
    val evidenceStatus: RhythmEvidenceStatus,
    val duration: RhythmDuration? = null
)

/** Final values used by oemer; declared for the framework but never assigned here. */
enum class RhythmDuration {
    WHOLE,
    HALF,
    QUARTER,
    EIGHTH,
    SIXTEENTH,
    THIRTY_SECOND,
    SIXTY_FOURTH,
    TRIPLET,
    OTHER
}

/**
 * Borrowed, row-major evidence masks. Construction and candidate
 * preparation do not copy any array.
 */
data class RhythmEvidenceMasks(
    val width: Int,
    val height: Int,
    val stems: BooleanArray,
    val beams: BooleanArray?,
    val flags: BooleanArray?,
    val dots: BooleanArray?
) {
    init {
        val expected = width * height
        require(width > 0 && height > 0)
        require(stems.size == expected) { "stems size must be $expected" }
        require(beams == null || beams.size == expected) { "beams size must be $expected" }
        require(flags == null || flags.size == expected) { "flags size must be $expected" }
        require(dots == null || dots.size == expected) { "dots size must be $expected" }
    }

    val isComplete: Boolean get() = beams != null && flags != null && dots != null
}
