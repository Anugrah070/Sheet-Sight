package com.sheetsight.app.data.omr.staffline

import com.sheetsight.app.data.omr.dewarp.SimpleLinearRegression

/**
 * Position of a line within its five-line staff, matching oemer's
 * `LineLabel`: FIRST is the **bottom** line (largest y in image
 * coordinates) and FIFTH is the **top** line (smallest y). oemer stores a
 * staff's lines bottom-to-top, so [ZoneStaff.lines]`[0]` is FIRST/bottom.
 */
enum class StafflinePosition { FIRST, SECOND, THIRD, FOURTH, FIFTH }

/** A single foreground pixel assigned to a staffline, in zone-local image coordinates. */
data class StafflinePoint(val x: Int, val y: Int)

/**
 * One detected staffline — oemer's `staffline_extraction.Line`. Holds the
 * ordered foreground [points] assigned to it, with geometry derived
 * lazily the same way oemer does: [yCenter] as the mean y, [slope] via
 * least-squares of y on x (reusing the dewarp package's
 * [SimpleLinearRegression]), and axis-aligned bounds. A line with fewer
 * than two distinct x values has no meaningful slope and reports 0.0.
 */
data class Staffline(
    val position: StafflinePosition,
    val points: List<StafflinePoint>
) {
    val yCenter: Double get() = if (points.isEmpty()) 0.0 else points.sumOf { it.y }.toDouble() / points.size
    val xCenter: Double get() = if (points.isEmpty()) 0.0 else points.sumOf { it.x }.toDouble() / points.size
    val yUpper: Int get() = points.minOf { it.y }
    val yLower: Int get() = points.maxOf { it.y }
    val xLeft: Int get() = points.minOf { it.x }
    val xRight: Int get() = points.maxOf { it.x }

    /** Least-squares slope of y as a function of x; 0.0 when x is degenerate. */
    val slope: Double
        get() {
            val distinctX = points.map { it.x }.distinct()
            if (distinctX.size < 2) return 0.0
            val xs = DoubleArray(points.size) { points[it].x.toDouble() }
            val ys = DoubleArray(points.size) { points[it].y.toDouble() }
            val model = SimpleLinearRegression.fit(xs, ys)
            return model.predict(1.0) - model.predict(0.0) // slope from two evaluations, no new API surface
        }
}

/**
 * A five-line staff detected within a single zone — oemer's
 * `staffline_extraction.Staff`, minus the `track`/`group`/`is_interp`
 * fields, which belong to the deferred `extract_part`/`align_staffs`/
 * track-inference sub-phases. [unitSize] is the mean vertical gap between
 * consecutive line centers (the staff-space in pixels).
 */
data class ZoneStaff(val lines: List<Staffline>) {
    init { require(lines.size == 5) { "a staff must have exactly 5 lines, got ${lines.size}" } }

    val yCenter: Double get() = lines.sumOf { it.yCenter } / lines.size

    val unitSize: Double
        get() {
            val centers = lines.map { it.yCenter }.sorted()
            val gaps = (1 until centers.size).map { centers[it] - centers[it - 1] }
            return gaps.average()
        }
}

/**
 * Result of [ZoneStafflineExtractor.extract] for one zone: every valid
 * five-line [staffs] found in the column range `[zoneLeft, zoneRight)`.
 * Empty when the zone has no detectable staff structure (blank/uniform
 * column density, or no peak group reaching five lines) — the
 * graceful-degradation signal, mirroring the dewarp package's
 * `isReliable=false` convention.
 */
data class ZoneStafflineResult(
    val zoneLeft: Int,
    val zoneRight: Int,
    val staffs: List<ZoneStaff>
)