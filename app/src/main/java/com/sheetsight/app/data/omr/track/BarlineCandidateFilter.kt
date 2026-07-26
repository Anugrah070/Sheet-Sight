package com.sheetsight.app.data.omr.track

import kotlin.math.atan2

/**
 * Port of oemer's `staffline_extraction.py::get_degree()` and `filter_lines()`:
 * ```python
 * def get_degree(line: BBox) -> float:
 *     return np.rad2deg(np.arctan2(line[3] - line[1], line[2] - line[0]))
 *
 * def filter_lines(lines: List[BBox], staffs: ndarray, min_degree: int = 75) -> List[BBox]:
 *     min_y = 9999999
 *     min_x = 9999999
 *     max_y = 0
 *     max_x = 0
 *     for st in staffs.reshape(-1, 1).squeeze():
 *         min_y = min(min_y, st.y_upper)
 *         min_x = min(min_x, st.x_left)
 *         max_y = max(max_y, st.y_lower)
 *         max_x = max(max_x, st.x_right)
 *
 *     cands = []
 *     for line in lines:
 *         degree = get_degree(line)
 *         if degree < min_degree:
 *             continue
 *         if line[1] < min_y or line[3] > max_y or line[0] < min_x or line[2] > max_x:
 *             continue
 *         cands.append(line)
 *     return cands
 * ```
 *
 * [line]/[lines] here are always [HoughLine]s from [HoughLineDetector.detect] — a
 * `(topX, topY, btX, btY)` tuple with `topX <= btX` and `topY <= btY` guaranteed by
 * that per-axis reorder. Since `bt - top` is therefore always `>= 0` on both axes,
 * [getDegree] always returns a value in `[0, 90]`: 0° for a perfectly horizontal
 * segment (dy=0), 90° for a perfectly vertical one (dx=0) — no further sign or
 * range normalization exists in source, and none is added here. [DEFAULT_MIN_DEGREE]
 * (75) therefore keeps only near-vertical candidates, consistent with filtering
 * for barlines.
 *
 * **The staff envelope is a single global bounding box over the entire staff
 * grid** — oemer flattens `staffs` (`reshape(-1, 1).squeeze()`) and reduces every
 * individual staff's bounds to one shared min/max, not a per-zone or per-row
 * envelope. [StaffBounds] is a deliberately minimal stand-in for oemer's `Staff`
 * (`y_upper`/`y_lower`/`x_left`/`x_right` only) — the not-yet-ported staff grid
 * (`StaffGridAssembler`, a later phase) will supply these; this filter doesn't
 * depend on that type existing yet.
 *
 * Both filters are **boundary-inclusive on the "keep" side**: oemer's rejection
 * tests are strict `<`/`>`, so a line landing exactly on `min_degree`, or exactly
 * flush against an envelope edge, survives. The sentinel envelope-init values
 * (`9999999`/`0`) are preserved exactly rather than special-cased, matching source.
 */
object BarlineCandidateFilter {

    const val DEFAULT_MIN_DEGREE: Int = 75

    private const val SENTINEL_MIN = 9_999_999
    private const val SENTINEL_MAX = 0

    /**
     * Angle, in degrees, of [line]'s reordered-endpoint vector:
     * `atan2(btY - topY, btX - topX)` converted via `np.rad2deg`'s equivalent.
     * Always in `[0, 90]` for a [HoughLine] — see the class KDoc.
     */
    fun getDegree(line: HoughLine): Double =
        Math.toDegrees(atan2((line.btY - line.topY).toDouble(), (line.btX - line.topX).toDouble()))

    /**
     * Filters [lines] to those whose [getDegree] is not less than [minDegree]
     * (so `== minDegree` passes) and whose bbox is not outside the combined
     * envelope of [staffBounds] on any edge (so exactly-flush passes).
     */
    fun filterLines(
        lines: List<HoughLine>,
        staffBounds: List<StaffBounds>,
        minDegree: Int = DEFAULT_MIN_DEGREE
    ): List<HoughLine> {
        var minY = SENTINEL_MIN
        var minX = SENTINEL_MIN
        var maxY = SENTINEL_MAX
        var maxX = SENTINEL_MAX
        for (st in staffBounds) {
            minY = minOf(minY, st.yUpper)
            minX = minOf(minX, st.xLeft)
            maxY = maxOf(maxY, st.yLower)
            maxX = maxOf(maxX, st.xRight)
        }

        return lines.filter { line ->
            val degree = getDegree(line)
            if (degree < minDegree) return@filter false
            if (line.topY < minY || line.btY > maxY || line.topX < minX || line.btX > maxX) return@filter false
            true
        }
    }
}

/**
 * The rectangular extent of a single staff — `y_upper`/`y_lower`/`x_left`/`x_right`
 * from oemer's `Staff` — the minimal shape [BarlineCandidateFilter.filterLines]
 * needs from the not-yet-ported staff grid. Kept independent of any specific
 * staff/grid type so this filter is testable in isolation, per this phase's scope.
 */
data class StaffBounds(val yUpper: Int, val yLower: Int, val xLeft: Int, val xRight: Int)