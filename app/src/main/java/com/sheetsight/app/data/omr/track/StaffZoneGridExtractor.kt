package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import com.sheetsight.app.data.omr.staffline.ZoneStafflineExtractor
import kotlin.math.roundToInt

/**
 * Extracts and aligns the horizontal staff zones used by oemer's
 * `staffline_extraction.extract()`/`align_staffs()`.
 *
 * A zone is a vertical slice of the page. The outer result is ordered
 * left-to-right; each inner list is the same set of physical staff rows,
 * ordered top-to-bottom.
 */
object StaffZoneGridExtractor {
    const val DEFAULT_SPLITS = 8
    private const val BOUNDS_PADDING_X = 50
    private const val BLANK_RUN = 10
    private const val MAX_MATCH_DISTANCE_UNITS = 3.0

    fun extract(
        staffMask: BooleanArray,
        width: Int,
        height: Int,
        splits: Int = DEFAULT_SPLITS
    ): List<List<ZoneStaff>> {
        require(staffMask.size == width * height) {
            "staffMask size ${staffMask.size} doesn't match ${width}x$height"
        }
        require(splits > 0) { "splits must be positive" }
        if (width <= 0 || height <= 0 || staffMask.none { it }) return emptyList()

        val ranges = zoneRanges(staffMask, width, height, splits)
        val extracted = ranges.mapNotNull { range ->
            val result = ZoneStafflineExtractor.extract(
                staffMask,
                width,
                height,
                zoneLeft = range.first,
                zoneRight = range.last + 1
            )
            result.staffs.takeIf { it.isNotEmpty() }
        }
        return align(extracted)
    }

    internal fun zoneRanges(
        staffMask: BooleanArray,
        width: Int,
        height: Int,
        splits: Int
    ): List<IntRange> {
        val columnCounts = IntArray(width)
        var minForegroundX = width
        var maxForegroundX = -1
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                if (!staffMask[rowBase + x]) continue
                columnCounts[x]++
                minForegroundX = minOf(minForegroundX, x)
                maxForegroundX = maxOf(maxForegroundX, x)
            }
        }
        if (maxForegroundX < 0) return emptyList()

        val mean = columnCounts.average()
        var leftBound = maxOf(minForegroundX - BOUNDS_PADDING_X, 0)
        var rightBound = minOf(maxForegroundX + BOUNDS_PADDING_X, width)
        if (mean > 0.0) {
            val half = (width / 2.0).roundToInt()
            for (x in minOf(half + BLANK_RUN, width) until width) {
                if (columnCounts.sliceArray(x - BLANK_RUN until x).average() / mean < 0.1) {
                    rightBound = x
                    break
                }
            }
            for (x in maxOf(half - BLANK_RUN, 0) downTo 1) {
                if (columnCounts.sliceArray(x until minOf(x + BLANK_RUN, width)).average() / mean < 0.1) {
                    leftBound = x
                    break
                }
            }
        }
        if (rightBound <= leftBound) return emptyList()

        val step = maxOf(1, ((rightBound - leftBound) / splits.toDouble()).roundToInt())
        val ranges = mutableListOf<IntRange>()
        var start = leftBound
        while (start < rightBound) {
            var end = minOf(start + step, rightBound)
            if (rightBound - end < step) end = rightBound
            ranges += start until end
            if (end == rightBound) break
            start += step
        }
        return ranges
    }

    internal fun align(zones: List<List<ZoneStaff>>): List<List<ZoneStaff>> {
        if (zones.isEmpty()) return emptyList()
        val maxRows = zones.maxOf { it.size }
        if (zones.all { it.size == maxRows }) return zones

        val grid = MutableList(zones.size) { MutableList<ZoneStaff?>(maxRows) { null } }
        zones.forEachIndexed { zoneIndex, staffs ->
            if (staffs.size == maxRows) {
                staffs.forEachIndexed { row, staff -> grid[zoneIndex][row] = staff }
            }
        }

        for (row in 0 until maxRows) {
            for (zoneIndex in zones.indices) {
                if (grid[zoneIndex][row] != null) continue
                val nearby = grid.indices
                    .mapNotNull { index -> grid[index][row]?.let { index to it } }
                    .sortedBy { (index, _) -> kotlin.math.abs(index - zoneIndex) }
                    .take(2)
                if (nearby.isEmpty()) continue

                val reference = nearby.first().second
                val existing = zones[zoneIndex].minByOrNull { kotlin.math.abs(it.yCenter - reference.yCenter) }
                if (existing != null &&
                    kotlin.math.abs(existing.yCenter - reference.yCenter) <
                    reference.unitSize * MAX_MATCH_DISTANCE_UNITS
                ) {
                    grid[zoneIndex][row] = existing
                    continue
                }

                grid[zoneIndex][row] = interpolate(zoneIndex, nearby)
            }
        }

        // At least one max-sized source zone seeded every row, so all cells
        // are fillable by the left/right interpolation above.
        return grid.map { zone -> zone.mapNotNull { it } }
    }

    private fun interpolate(zoneIndex: Int, nearby: List<Pair<Int, ZoneStaff>>): ZoneStaff {
        if (nearby.size == 1) {
            val (referenceIndex, reference) = nearby.single()
            val zoneWidth = reference.lines.maxOf { it.xRight } - reference.lines.minOf { it.xLeft }
            return shift(
                reference,
                xOffset = zoneWidth.toDouble() * (zoneIndex - referenceIndex),
                yOffset = 0.0
            )
        }

        val (left, right) = nearby.sortedBy { it.first }
        val (leftIndex, leftStaff) = left
        val (rightIndex, rightStaff) = right
        val span = (rightIndex - leftIndex).toDouble()
        return when {
            zoneIndex < leftIndex -> {
                val ratio = (leftIndex - zoneIndex) / span
                shift(
                    leftStaff,
                    xOffset = -(rightStaff.xCenter - leftStaff.xCenter) * ratio,
                    yOffset = -(rightStaff.yCenter - leftStaff.yCenter) * ratio
                )
            }
            zoneIndex < rightIndex -> {
                val ratio = (zoneIndex - leftIndex) / span
                shift(
                    leftStaff,
                    xOffset = (rightStaff.xCenter - leftStaff.xCenter) * ratio,
                    yOffset = (rightStaff.yCenter - leftStaff.yCenter) * ratio
                )
            }
            else -> {
                val ratio = (zoneIndex - rightIndex) / span
                shift(
                    rightStaff,
                    xOffset = (rightStaff.xCenter - leftStaff.xCenter) * ratio,
                    yOffset = (rightStaff.yCenter - leftStaff.yCenter) * ratio
                )
            }
        }
    }

    private val ZoneStaff.xCenter: Double
        get() = lines.sumOf { line -> line.xCenter } / lines.size

    private fun shift(staff: ZoneStaff, xOffset: Double, yOffset: Double): ZoneStaff =
        ZoneStaff(
            staff.lines.map { line ->
                Staffline(
                    position = line.position,
                    points = line.points.map { point ->
                        StafflinePoint(
                            x = (point.x + xOffset).roundToInt(),
                            y = (point.y + yOffset).roundToInt()
                        )
                    }
                )
            }
        )
}
