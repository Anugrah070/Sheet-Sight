package com.sheetsight.app.data.omr.grouping

/**
 * Chords plus oemer-compatible page occupancy.
 *
 * [groupMap] is row-major, uses `-1` for background, and a non-negative
 * occupancy value for pixels belonging to a note group. Consumers must
 * test `>= 0`, matching oemer's
 * `group_map > -1` contract.
 */
data class NoteGroupingResult(
    val chords: List<ChordCandidate>,
    val groupMap: IntArray,
    val width: Int,
    val height: Int
) {
    init {
        require(groupMap.size == width * height) {
            "groupMap size ${groupMap.size} doesn't match ${width}x$height"
        }
    }
}
