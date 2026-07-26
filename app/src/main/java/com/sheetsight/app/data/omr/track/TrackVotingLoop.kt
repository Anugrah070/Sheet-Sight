package com.sheetsight.app.data.omr.track

/**
 * Port of oemer's `staffline_extraction.py::further_infer_track_nums()`
 * voting loop — decides how many tracks (staff systems stacked into one
 * brace group, e.g. 1 for a single staff, 2 for a piano grand staff) a
 * page uses, from how many staff centers each detected barline blob spans.
 *
 * **Source verification note.** The exact oemer 0.1.8 function body for
 * `further_infer_track_nums()` was not retrievable in this environment —
 * only `ete.py` and README-level descriptions of the algorithm ("parse
 * the barlines to infer possible track numbers") were fetchable, not
 * `staffline_extraction.py` itself. This port reproduces the *documented*
 * control flow (one vote per barline blob, mode wins, no cap) rather than
 * a verified line-by-line match — a documented, deliberate deviation in
 * the same spirit as [com.sheetsight.app.data.omr.dewarp.DewarpCoordinateInterpolator]'s
 * own caveat, and should be reconciled against the real source later.
 *
 * **What's reused, not reimplemented:**
 *  - [ConnectedComponentBoxExtractor] (Phase 4.6E-D) extracts barline
 *    blobs from the (Phase 4.6E-C-produced, not touched here) final
 *    barline mask.
 *  - [StaffCenterInfo] (Phase 4.6E-D, already defined for
 *    [NearestStaffUnitSizeResolver]) is reused as-is for staff-grid input
 *    rather than inventing a parallel type.
 *
 * **Voting.** For each blob, count how many staff centers in [staffGrid]
 * have `yCenter` inside `[box.top, box.bottom)` — a barline joining two
 * staves into a grand staff has a tall-enough box to contain both
 * centers, casting a vote of 2. A blob spanning zero centers (noise, or a
 * candidate that survived upstream filtering without aligning to any
 * known staff) casts no vote — "0 tracks" has no meaningful downstream
 * interpretation. **No candidate cap and no vote-value cap**: every blob
 * always gets a turn, and a (synthetic) 5-staff brace legitimately votes
 * 5, per this phase's "uncapped unless the source proves otherwise" rule.
 *
 * **Tie-break.** The result is the *mode* of all votes; ties resolve to
 * whichever value was cast first, matching Python's `Counter.most_common()`
 * insertion-order tie-break and this codebase's existing first-encountered
 * convention (e.g. [com.sheetsight.app.data.omr.inference.ClassMaskExtractor]'s argmax).
 */
object TrackVotingLoop {

    /**
     * @property trackNums Mode of [votes], or `1` (graceful default) if no
     *   blob spanned any staff center at all.
     * @property votes One entry per barline blob that spanned >=1 staff
     *   center, in [barlineBoxes] order — kept for later track/group
     *   assignment (out of scope for this phase).
     * @property barlineBoxes Every blob [ConnectedComponentBoxExtractor]
     *   found, including any that cast no vote.
     */
    data class TrackVoteResult(
        val trackNums: Int,
        val votes: List<Int>,
        val barlineBoxes: List<BoundingBox>
    )

    /**
     * Runs the voting loop over [barlineMask] ([width]x[height], row-major,
     * `true` = barline foreground) against the row-major [staffGrid]
     * (outer list = zones/rows, inner list = staffs within that row —
     * same convention [NearestStaffUnitSizeResolver.resolve] already uses).
     */
    fun infer(
        barlineMask: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<StaffCenterInfo>>
    ): TrackVoteResult {
        require(barlineMask.size == width * height) {
            "barlineMask size ${barlineMask.size} doesn't match ${width}x$height"
        }

        val boxes = ConnectedComponentBoxExtractor.extract(barlineMask, width, height)
        val centers = staffGrid.flatten()

        val votes = mutableListOf<Int>()
        for (box in boxes) {
            val spanned = centers.count { it.yCenter >= box.top && it.yCenter < box.bottom }
            if (spanned > 0) votes.add(spanned)
        }

        val trackNums = if (votes.isEmpty()) 1 else mostCommon(votes)
        return TrackVoteResult(trackNums = trackNums, votes = votes, barlineBoxes = boxes)
    }

    /** Mode of [votes], ties broken by first-cast value — matches `Counter.most_common()`. */
    private fun mostCommon(votes: List<Int>): Int {
        val counts = LinkedHashMap<Int, Int>()
        for (vote in votes) counts[vote] = (counts[vote] ?: 0) + 1

        var bestValue = votes.first()
        var bestCount = 0
        for (vote in votes) {
            val n = counts.getValue(vote)
            if (n > bestCount) {
                bestCount = n
                bestValue = vote
            }
        }
        return bestValue
    }
}