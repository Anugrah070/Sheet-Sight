package com.sheetsight.app.data.omr.track

/**
 * Port of oemer's `staffline_extraction.py::naive_get_unit_size()`:
 * ```python
 * def naive_get_unit_size(staffs: ndarray, x: int, y: int) -> float:
 *     flat_staffs = staffs.reshape(-1, 1).squeeze()
 *     def dist(st: Staff) -> float:
 *         x_diff = st.x_center - x
 *         y_diff = st.y_center - y
 *         return x_diff ** 2 + y_diff ** 2
 *     dists = [(st.unit_size, dist(st)) for st in flat_staffs]
 *     dists = sorted(dists, key=lambda it: it[1])
 *     return dists[0][0]
 * ```
 *
 * **Distance is squared Euclidean, never square-rooted** — preserved exactly as
 * `x_diff**2 + y_diff**2` rather than "simplifying" to an equivalent-for-ranking
 * `sqrt` call, per this phase's no-reinterpretation instruction (irrelevant to the
 * *result*, since sqrt is monotonic, but the arithmetic itself should read the same
 * as source).
 *
 * **Tie-break is deterministic and order-dependent, not arbitrary.** oemer's
 * `staffs.reshape(-1, 1).squeeze()` flattens the `[num_zones, num_staffs_per_zone]`
 * grid in row-major (C) order — same "row outer, column inner" convention already
 * used by [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler]'s own
 * row-major tiling. Python's `sorted()` is a stable sort, so when two staffs are
 * *exactly* equidistant, the one appearing first in that row-major flatten order
 * is the one `dists[0]` returns. Kotlin's `sortedBy` is likewise a stable sort
 * (backed by a stable merge sort), so flattening [staffGrid] row-major and calling
 * `sortedBy` reproduces this tie-break exactly rather than leaving it
 * implementation-defined.
 *
 * [StaffCenterInfo] is a minimal stand-in for oemer's `Staff` — only the three
 * fields this function actually reads (`x_center`, `y_center`, `unit_size`) — since
 * the full staff-grid type doesn't exist in this codebase yet, matching the same
 * approach [StaffBounds] took for [BarlineCandidateFilter].
 */
object NearestStaffUnitSizeResolver {

    /**
     * Returns the `unit_size` of whichever staff in [staffGrid] (row-major:
     * outer list = zones/rows, inner list = staffs within that row) is nearest
     * to ([x], [y]) by squared Euclidean distance between centers. Ties resolve
     * to the first staff encountered in row-major flatten order.
     */
    fun resolve(staffGrid: List<List<StaffCenterInfo>>, x: Double, y: Double): Double {
        val flat = staffGrid.flatten()
        require(flat.isNotEmpty()) { "staffGrid must contain at least one staff" }

        return flat
            .map { st ->
                val dx = st.xCenter - x
                val dy = st.yCenter - y
                st.unitSize to (dx * dx + dy * dy)
            }
            .sortedBy { it.second }
            .first()
            .first
    }
}

/**
 * The minimal per-staff geometry [NearestStaffUnitSizeResolver.resolve] needs:
 * `x_center`/`y_center`/`unit_size` from oemer's `Staff`.
 */
data class StaffCenterInfo(val xCenter: Double, val yCenter: Double, val unitSize: Double)