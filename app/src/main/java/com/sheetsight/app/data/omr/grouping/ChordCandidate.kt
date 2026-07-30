package com.sheetsight.app.data.omr.grouping

import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.track.BoundingBox

/** Stem state represented by oemer's `stem_up` plus `has_stem` fields. */
enum class StemDirection {
    UP,
    DOWN,
    NONE,
    AMBIGUOUS
}

/**
 * A simultaneous group of noteheads. No rhythmic duration is present or
 * implied; grouping and stem direction are the complete Phase 4.7B scope.
 */
data class ChordCandidate(
    val id: Int,
    val noteheads: List<NoteheadCandidate>,
    val boundingBox: BoundingBox,
    val stemDirection: StemDirection,
    val hasStem: Boolean,
    val track: Int,
    val group: Int
)
