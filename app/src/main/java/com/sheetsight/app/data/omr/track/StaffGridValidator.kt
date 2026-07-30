package com.sheetsight.app.data.omr.track

import kotlin.math.abs

object StaffGridValidator {

    const val DEFAULT_Y_CENTER_TOLERANCE_RATIO: Double = 0.1
    const val DEFAULT_UNIT_SIZE_TOLERANCE_RATIO: Double = 0.1

    fun validate(
        assignedGrid: List<List<AssignedStaff>>,
        yCenterToleranceRatio: Double = DEFAULT_Y_CENTER_TOLERANCE_RATIO,
        unitSizeToleranceRatio: Double = DEFAULT_UNIT_SIZE_TOLERANCE_RATIO
    ): List<List<AssignedStaff>> {
        if (assignedGrid.isEmpty()) return emptyList()

        val columnCount = assignedGrid.maxOf { it.size }
        val validColumn = BooleanArray(columnCount) { col ->
            isRowValid(rowAt(assignedGrid, col), yCenterToleranceRatio, unitSizeToleranceRatio)
        }

        return assignedGrid.map { zone ->
            zone.filterIndexed { col, _ -> validColumn[col] }
        }
    }

    /** The transpose: every zone's entry at column [col], skipping zones with no staff there. */
    private fun rowAt(assignedGrid: List<List<AssignedStaff>>, col: Int): List<AssignedStaff> =
        assignedGrid.mapNotNull { zone -> zone.getOrNull(col) }

    private fun isRowValid(row: List<AssignedStaff>, yTolRatio: Double, unitTolRatio: Double): Boolean {
        if (row.isEmpty()) return false

        if (row.any { it.staff.lines.size != 5 }) return false

        val unitSizes = row.map { it.staff.unitSize }
        val meanUnitSize = unitSizes.average()
        if (meanUnitSize <= 0.0) return false
        if (unitSizes.any { abs(it / meanUnitSize - 1.0) >= unitTolRatio }) return false

        val yCenters = row.map { it.staff.yCenter }
        val meanYCenter = yCenters.average()
        if (meanYCenter == 0.0) return false
        if (yCenters.any { abs(it / meanYCenter - 1.0) >= yTolRatio }) return false

        return true
    }
}
