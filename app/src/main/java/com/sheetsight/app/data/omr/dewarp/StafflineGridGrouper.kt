package com.sheetsight.app.data.omr.dewarp

/**
 * Faithful port of oemer's `dewarp.py::build_grid_group()`: groups
 * spatially-touching [StafflineGrid] cells (from adjacent column strips)
 * into wider [StafflineGridGroup] runs, each approximating one detected
 * staffline across a span of the page.
 *
 * Connectivity mirrors `scipy.ndimage.label(grid_map+1)`'s default:
 * plain 4-connected connected-component labeling over "does this pixel
 * belong to any grid at all", ignoring which specific grid id — so two
 * different grid cells that are merely touching still merge into one
 * region. A group's bounding box is deliberately the union of only its
 * *lowest-* and *highest-id* member grids' boxes (not every member) —
 * preserved exactly as oemer computes it, since grid ids are assigned
 * left-to-right/top-to-bottom by [StafflineGridDetector], so id order
 * tracks spatial order for well-formed input.
 *
 * One minor, documented deviation: oemer's `sorted(groups, reverse=True)`
 * reverses tie order for equal-width groups (Python sorts ascending then
 * reverses the whole list). This port uses [sortedByDescending], which is
 * stable and keeps original order for ties. This only affects which of
 * several *equal-width* groups gets a lower id — not detection
 * correctness — so it wasn't worth the extra complexity to replicate.
 */
object StafflineGridGrouper {

    /**
     * Groups [detection]'s grids. Returns an empty [GridGroupingResult]
     * (not an exception) if [detection] found no grids at all.
     */
    fun groupGrids(detection: GridDetectionResult, width: Int, height: Int): GridGroupingResult {
        if (detection.grids.isEmpty()) {
            return GridGroupingResult(groupMap = IntArray(width * height) { -1 }, groups = emptyList())
        }

        val regionIds = ConnectedComponents.label(detection.gridMap, width, height)
        val rawGroups = buildRawGroups(detection, regionIds)
        val sorted = rawGroups.sortedByDescending { it.right - it.left }

        val groupMap = IntArray(width * height) { -1 }
        val groups = sorted.mapIndexed { newId, raw ->
            for (pixelIndex in regionIds.indices) {
                if (regionIds[pixelIndex] == raw.regionId) groupMap[pixelIndex] = newId
            }
            StafflineGridGroup(
                id = newId,
                left = raw.left,
                top = raw.top,
                right = raw.right,
                bottom = raw.bottom,
                gridIds = raw.gridIds
            )
        }

        return GridGroupingResult(groupMap = groupMap, groups = groups)
    }

    private data class RawGroup(
        val regionId: Int,
        val gridIds: List<Int>,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun buildRawGroups(detection: GridDetectionResult, regionIds: IntArray): List<RawGroup> {
        val gridIdsByRegion = HashMap<Int, MutableSet<Int>>()
        for (pixelIndex in detection.gridMap.indices) {
            val region = regionIds[pixelIndex]
            if (region <= 0) continue
            gridIdsByRegion.getOrPut(region) { mutableSetOf() }.add(detection.gridMap[pixelIndex])
        }

        return gridIdsByRegion.map { (regionId, gridIdSet) ->
            val gridIds = gridIdSet.sorted()
            val lowest = detection.grids[gridIds.first()]
            val highest = detection.grids[gridIds.last()]
            RawGroup(
                regionId = regionId,
                gridIds = gridIds,
                left = minOf(lowest.left, highest.left),
                top = minOf(lowest.top, highest.top),
                right = maxOf(lowest.right, highest.right),
                bottom = maxOf(lowest.bottom, highest.bottom)
            )
        }
    }
}

/**
 * One merged run of spatially-touching [StafflineGrid]s, approximating a
 * single detected staffline across [gridIds]' combined span. [right]/[bottom]
 * are exclusive, same convention as [StafflineGrid].
 */
data class StafflineGridGroup(
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val gridIds: List<Int>
) {
    val width: Int get() = right - left
    val yCenter: Double get() = (top + bottom) / 2.0
}

/** [groupMap] holds each pixel's owning [StafflineGridGroup.id], or -1 if it belongs to none. */
data class GridGroupingResult(val groupMap: IntArray, val groups: List<StafflineGridGroup>)