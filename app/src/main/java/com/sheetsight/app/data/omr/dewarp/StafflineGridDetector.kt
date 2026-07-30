package com.sheetsight.app.data.omr.dewarp

/**
 * Faithful port of oemer's `dewarp.py::build_grid()`: scans the
 * (morphology-preprocessed) staff mask in narrow vertical strips of width
 * [DEFAULT_SPLIT_UNIT], and within each strip walks down row by row,
 * registering a [StafflineGrid] whenever it finds a contiguous run of
 * "mostly on" rows that's *shorter* than the strip width — i.e. a
 * staffline-height blob, not a tall solid feature like a barline or beam.
 */
object StafflineGridDetector {

    const val DEFAULT_SPLIT_UNIT = 11

    /**
     * Detects staffline-height grid cells in [mask] ([width]x[height],
     * row-major). Returns an empty [GridDetectionResult] (not an
     * exception) if [mask] has no staffline-shaped structure at all —
     * the graceful-degradation case for a blank or unreadable page.
     */
    fun detectGrids(
        mask: BooleanArray,
        width: Int,
        height: Int,
        splitUnit: Int = DEFAULT_SPLIT_UNIT
    ): GridDetectionResult {
        require(mask.size == width * height) { "mask size ${mask.size} doesn't match ${width}x$height" }

        val gridMap = IntArray(width * height) { -1 }
        val grids = mutableListOf<StafflineGrid>()

        var stripStart = 0
        while (stripStart < width) {
            val stripEnd = minOf(stripStart + splitUnit, width)
            scanStrip(mask, width, height, stripStart, stripEnd, splitUnit, gridMap, grids)
            stripStart += splitUnit
        }

        return GridDetectionResult(gridMap = gridMap, grids = grids)
    }

    private fun scanStrip(
        mask: BooleanArray,
        width: Int,
        height: Int,
        stripStart: Int,
        stripEnd: Int,
        splitUnit: Int,
        gridMap: IntArray,
        grids: MutableList<StafflineGrid>
    ) {
        var y = 0
        var lastY = 0
        var isOn = isStripRowOn(mask, width, 0, stripStart, stripEnd, splitUnit)
        while (y < height) {
            while (y < height && isOn == isStripRowOn(mask, width, y, stripStart, stripEnd, splitUnit)) {
                y++
            }
            if (isOn && (y - lastY < splitUnit)) {
                val gridId = grids.size
                for (gy in lastY until y) {
                    val rowBase = gy * width
                    for (gx in stripStart until stripEnd) {
                        gridMap[rowBase + gx] = gridId
                    }
                }
                grids.add(StafflineGrid(id = gridId, left = stripStart, top = lastY, right = stripEnd, bottom = y))
            }
            isOn = !isOn
            lastY = y
        }
    }

    /**
     * Reproduces `is_on`: true if more than `splitUnit/2` pixels in this
     * row's strip segment are set. The threshold denominator is always
     * `splitUnit` (integer-divided), even for a clipped final strip
     * narrower than that — preserved as-is, matching the original.
     */
    private fun isStripRowOn(
        mask: BooleanArray,
        width: Int,
        y: Int,
        stripStart: Int,
        stripEnd: Int,
        splitUnit: Int
    ): Boolean {
        var count = 0
        val rowBase = y * width
        for (x in stripStart until stripEnd) {
            if (mask[rowBase + x]) count++
        }
        return count > splitUnit / 2
    }
}

/**
 * One detected staffline-height blob within a single column strip.
 * Mirrors oemer's `Grid`: [right]/[bottom] are exclusive, matching the
 * Python slice-end bbox convention `(left, top, right, bottom)`.
 */
data class StafflineGrid(val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val yCenter: Double get() = (top + bottom) / 2.0
}

/** [gridMap] holds each pixel's owning [StafflineGrid.id], or -1 if it belongs to none. */
data class GridDetectionResult(val gridMap: IntArray, val grids: List<StafflineGrid>)