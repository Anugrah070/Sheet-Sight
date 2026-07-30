package com.sheetsight.app.data.omr.notehead

import com.sheetsight.app.data.omr.track.BoundingBox

/**
 * The only notehead distinction made during oemer's notehead extraction.
 * Final rhythmic values are deliberately absent: `HALF_OR_WHOLE` is the
 * same intermediate label used by `notehead_extraction.py`, while a solid
 * notehead remains rhythmically unresolved.
 */
enum class NoteheadType {
    SOLID,
    HALF_OR_WHOLE
}

/** Reuses the pipeline's existing exclusive-right/bottom box convention. */
typealias NoteheadBoundingBox = BoundingBox

/**
 * Staff metadata assigned by `notehead_extraction.py::gen_notes`.
 *
 * [staffLinePosition] is not a pitch. Zero starts one half-space below the
 * bottom staff line and increases upward, exactly as in oemer.
 */
data class NoteheadStaffAssignment(
    val track: Int,
    val group: Int,
    val staffLinePosition: Int
)

/**
 * One extracted notehead, before chord grouping or rhythm recognition.
 *
 * [sourcePixelIndices] stores only the generic-symbol pixels inside this
 * notehead's box, matching oemer's `NoteHead.points`/`register_note_id()`.
 * It is intentionally not a page-sized id map or duplicated mask.
 */
data class NoteheadCandidate(
    val id: Int,
    val boundingBox: NoteheadBoundingBox,
    val type: NoteheadType,
    val staffAssignment: NoteheadStaffAssignment,
    val sourcePixelIndices: IntArray,
    val stemOnRight: Boolean?
)
