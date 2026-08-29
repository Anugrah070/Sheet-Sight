package com.sheetsight.app.data.omr.track

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Port of oemer 0.1.8 `utils.py::find_closest_staffs()`,
 * `get_unit_size()`, and `get_global_unit_size()`.
 */
object StaffGeometryResolver {

    data class NoteAssignment(
        val staff: AssignedStaff,
        val staffLinePosition: Int,
        val localUnitSize: Double
    )

    /** Returns oemer's direction-aware nearest staff pair for ([x], [y]). */
    fun closestPair(
        staffGrid: List<List<AssignedStaff>>,
        x: Int,
        y: Int
    ): Pair<AssignedStaff, AssignedStaff> {
        val ordered = staffGrid.flatten().sortedBy { staff ->
            hypot(staff.xCenter - x, staff.yCenter - y)
        }
        require(ordered.isNotEmpty()) { "staffGrid must contain at least one staff" }
        if (ordered.size == 1) return ordered[0] to ordered[0]
        if (ordered.size == 2) return ordered[0] to ordered[1]
        return directionalPair(ordered.take(3), y)
    }

    /** Returns oemer's local, possibly interpolated staff-space size. */
    fun unitSizeAt(
        staffGrid: List<List<AssignedStaff>>,
        x: Int,
        y: Int
    ): Double {
        val (first, second) = closestPair(staffGrid, x, y)
        if (first.yCenter == second.yCenter || y.toDouble() in first.yUpper..first.yLower) {
            return first.staff.unitSize
        }
        val firstDistance = abs(y - first.yCenter)
        val secondDistance = abs(y - second.yCenter)
        val totalDistance = firstDistance + secondDistance
        if (totalDistance == 0.0) return first.staff.unitSize
        val firstWeight = secondDistance / totalDistance
        val secondWeight = firstDistance / totalDistance
        return firstWeight * first.staff.unitSize + secondWeight * second.staff.unitSize
    }

    /**
     * Assigns a note to one source-system band before choosing a staff. Each
     * physical staff row is represented by the horizontal-zone segment
     * nearest [x], and all y comparisons use that segment's interpolated line
     * geometry. This prevents a nearby segment from another system or a
     * shifted zone index from owning the note.
     */
    fun assignNote(
        staffGrid: List<List<AssignedStaff>>,
        x: Int,
        y: Int
    ): NoteAssignment {
        val localStaffs = staffGrid.flatten()
            .groupBy { it.group to it.track }
            .values
            .map { segments -> segments.minBy { horizontalDistance(x, it) } }
        require(localStaffs.isNotEmpty()) { "staffGrid must contain at least one staff" }

        val systems = localStaffs.groupBy(AssignedStaff::group)
            .toSortedMap()
            .map { (group, staffs) ->
                LocalSystem(group, staffs, staffs.map { localCenterY(it, x) }.average())
            }
        val system = systems.minBy { abs(y - it.centerY) }
        val staff = system.staffs.minBy { abs(y - localCenterY(it, x)) }
        val unit = localUnitSize(staff, x)
        val bottomLineY = localLineY(staff.staff.lines.last(), x)
        return NoteAssignment(
            staff = staff,
            staffLinePosition = ((bottomLineY - y) / (unit / 2.0)).roundToInt() + 1,
            localUnitSize = unit
        )
    }

    fun localLineY(line: com.sheetsight.app.data.omr.staffline.Staffline, x: Int): Double =
        line.yCenter + line.slope * (x - line.xCenter)

    fun localUnitSize(staff: AssignedStaff, x: Int): Double {
        val lineYs = staff.staff.lines.map { localLineY(it, x) }.sorted()
        return lineYs.zipWithNext { upper, lower -> lower - upper }.average()
            .takeIf { it > 0.0 }
            ?: staff.staff.unitSize
    }

    /** Mean staff-space size over the complete aligned grid. */
    fun globalUnitSize(staffGrid: List<List<AssignedStaff>>): Double =
        staffGrid.flatten().map { it.staff.unitSize }.average()

    private fun directionalPair(
        nearest: List<AssignedStaff>,
        y: Int
    ): Pair<AssignedStaff, AssignedStaff> {
        val first = nearest[0]
        val preferBelow = abs(first.yLower - y) <= abs(first.yUpper - y)
        val second = nearest[1]
        val third = nearest[2]
        val companion = when {
            preferBelow && second.yCenter > first.yCenter -> second
            preferBelow && third.yCenter > first.yCenter -> third
            !preferBelow && second.yCenter < first.yCenter -> second
            !preferBelow && third.yCenter < first.yCenter -> third
            else -> first
        }
        return first to companion
    }

    private fun localCenterY(staff: AssignedStaff, x: Int): Double =
        staff.staff.lines.map { localLineY(it, x) }.average()

    private fun horizontalDistance(x: Int, staff: AssignedStaff): Int = when {
        x < staff.xLeft -> staff.xLeft - x
        x > staff.xRight -> x - staff.xRight
        else -> 0
    }

    private data class LocalSystem(
        val group: Int,
        val staffs: List<AssignedStaff>,
        val centerY: Double
    )
}

/** Horizontal center of an assigned staff segment. */
val AssignedStaff.xCenter: Double
    get() = staff.lines.sumOf { it.xCenter } / staff.lines.size

/** Vertical center of an assigned staff segment. */
val AssignedStaff.yCenter: Double
    get() = staff.yCenter

/** Upper foreground bound of an assigned staff segment. */
val AssignedStaff.yUpper: Double
    get() = staff.lines.minOf { it.yUpper }.toDouble()

/** Lower foreground bound of an assigned staff segment. */
val AssignedStaff.yLower: Double
    get() = staff.lines.maxOf { it.yLower }.toDouble()

/** Left foreground bound of an assigned staff segment. */
val AssignedStaff.xLeft: Int
    get() = staff.lines.minOf { it.xLeft }

/** Right foreground bound of an assigned staff segment. */
val AssignedStaff.xRight: Int
    get() = staff.lines.maxOf { it.xRight }
