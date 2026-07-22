package com.sheetsight.app.data.omr.dewarp

import kotlin.math.roundToInt

/**
 * Faithful port of oemer's `dewarp.py::build_mapping()`: turns the
 * (post-[StafflineGridBridger]) group map into a sparse set of
 * [DewarpControlPoint]s — each one saying "output row [targetRow] at
 * column [column] should sample source row [sourceRow]".
 *
 * Deliberately re-runs [ConnectedComponents.label] on [groupMap] instead
 * of trusting group ids directly, matching oemer's own
 * `scipy.ndimage.label(gg_map+1)` call. This matters: bridging can leave
 * two different groups occupying adjacent, touching pixels without
 * unifying their ids (a bridge only repaints the *gap* it fills, not the
 * group it bridges into), so a fresh connectivity pass is the only way
 * to see them as one blob, same as the reference implementation relies
 * on.
 */
object DewarpMappingBuilder {

    const val DEFAULT_MIN_WIDTH_RATIO = 0.4
    const val DEFAULT_PERIOD = 10

    fun build(
        groupMap: IntArray,
        width: Int,
        height: Int,
        minWidthRatio: Double = DEFAULT_MIN_WIDTH_RATIO,
        period: Int = DEFAULT_PERIOD
    ): List<DewarpControlPoint> {
        val points = mutableListOf<DewarpControlPoint>()

        if (width > 0 && height > 0) {
            val minWidth = width * minWidthRatio
            val regions = ConnectedComponents.label(groupMap, width, height)
            val regionCount = regions.maxOrNull() ?: 0
            val columnsByRegion = groupColumnsByRegion(regions, width, height)

            for (region in 1..regionCount) {
                val columns = columnsByRegion[region] ?: continue
                val minX = columns.keys.minOrNull() ?: continue
                val maxX = columns.keys.maxOrNull() ?: continue
                if (maxX - minX < minWidth) continue

                val targetRow = columns.values.flatten().average().roundToInt()
                columns.keys.sorted().forEachIndexed { index, x ->
                    if (index % period == 0) {
                        val sourceRow = columns.getValue(x).average().roundToInt()
                        points.add(DewarpControlPoint(targetRow, x, sourceRow))
                    }
                }
            }
        }

        // Boundary rows: identity mapping at the very top and bottom of every column.
        for (x in 0 until width) {
            points.add(DewarpControlPoint(0, x, 0))
            if (height > 1) points.add(DewarpControlPoint(height - 1, x, height - 1))
        }

        return points
    }

    /** One pass building region -> column -> [rows touching that column] out of [regions]. */
    private fun groupColumnsByRegion(regions: IntArray, width: Int, height: Int): Map<Int, Map<Int, List<Int>>> {
        val result = HashMap<Int, HashMap<Int, MutableList<Int>>>()
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val region = regions[rowBase + x]
                if (region <= 0) continue
                result.getOrPut(region) { HashMap() }.getOrPut(x) { mutableListOf() }.add(y)
            }
        }
        return result
    }
}

/** A single correspondence: output row [targetRow], column [column], should sample source row [sourceRow]. */
data class DewarpControlPoint(val targetRow: Int, val column: Int, val sourceRow: Int)