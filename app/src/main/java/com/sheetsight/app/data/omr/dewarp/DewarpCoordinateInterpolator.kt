package com.sheetsight.app.data.omr.dewarp

/**
 * Builds the dense per-pixel `coords_y` map oemer gets from
 * `scipy.interpolate.griddata(points, vals, (grid_x, grid_y), method='linear')`,
 * from [DewarpMappingBuilder]'s sparse [DewarpControlPoint]s.
 *
 * **This is not a general 2-D Delaunay-triangulation interpolator** —
 * that's what `griddata(method='linear')` actually does for an arbitrary
 * scattered point cloud. Implementing a robust, correct Delaunay
 * triangulation from scratch, with no way to validate it against real
 * image data in this environment, was judged too risky: a subtly-wrong
 * triangulation could silently produce a plausible-looking but wrong
 * warp, which is worse than an honestly-labeled simpler method.
 *
 * [DewarpMappingBuilder]'s control points are never a truly irregular
 * cloud — they're always structured as one dense "row" of samples per
 * detected staffline (all sharing one `targetRow`) plus two fully-dense
 * boundary rows (0 and height-1, present at every column). This exploits
 * that structure directly:
 *  1. Within each known `targetRow`, linearly interpolate along columns
 *     between its sparse samples, flat-extrapolating past that line's own
 *     sampled span.
 *  2. For every output pixel, linearly interpolate vertically between the
 *     two bracketing `targetRow`s (the always-present boundary rows
 *     guarantee every output row has a bracket).
 *
 * This is exact when the point set has this row-per-line structure; it
 * is a documented, deliberate deviation from generic Delaunay-based
 * `griddata` for irregular inputs.
 */
object DewarpCoordinateInterpolator {

    fun interpolate(points: List<DewarpControlPoint>, width: Int, height: Int): FloatArray {
        val coordsY = FloatArray(width * height)
        if (width <= 0 || height <= 0) return coordsY

        if (points.isEmpty()) {
            // No usable control points at all: identity mapping (no dewarp).
            for (y in 0 until height) {
                for (x in 0 until width) coordsY[y * width + x] = y.toFloat()
            }
            return coordsY
        }

        val byRow: Map<Int, List<DewarpControlPoint>> = points.groupBy { it.targetRow }
        val targetRows = byRow.keys.sorted()
        val denseByRow = targetRows.associateWith { row -> denseColumnValues(byRow.getValue(row), width) }

        for (x in 0 until width) {
            var lowerIdx = 0
            for (y in 0 until height) {
                while (lowerIdx < targetRows.size - 1 && targetRows[lowerIdx + 1] <= y) lowerIdx++
                val rowLow = targetRows[lowerIdx]
                val valueLow = denseByRow.getValue(rowLow)[x]
                coordsY[y * width + x] = if (lowerIdx == targetRows.size - 1 || y <= rowLow) {
                    valueLow
                } else {
                    val rowHigh = targetRows[lowerIdx + 1]
                    val valueHigh = denseByRow.getValue(rowHigh)[x]
                    val t = (y - rowLow).toFloat() / (rowHigh - rowLow).toFloat()
                    valueLow + t * (valueHigh - valueLow)
                }
            }
        }
        return coordsY
    }

    /** Linear interpolation across a sparse (column -> sourceRow) sample set, flat past the sampled span. */
    private fun denseColumnValues(samples: List<DewarpControlPoint>, width: Int): FloatArray {
        val sorted = samples.sortedBy { it.column }
        val dense = FloatArray(width)
        var sampleIdx = 0
        for (x in 0 until width) {
            while (sampleIdx < sorted.size - 2 && sorted[sampleIdx + 1].column <= x) sampleIdx++
            val a = sorted[sampleIdx]
            val b = sorted[minOf(sampleIdx + 1, sorted.size - 1)]
            dense[x] = when {
                a.column == b.column -> a.sourceRow.toFloat()
                x <= a.column -> a.sourceRow.toFloat()
                x >= b.column -> b.sourceRow.toFloat()
                else -> {
                    val t = (x - a.column).toFloat() / (b.column - a.column).toFloat()
                    a.sourceRow + t * (b.sourceRow - a.sourceRow)
                }
            }
        }
        return dense
    }
}