package com.sheetsight.app.data.omr.dewarp

/**
 * Faithful port of oemer's `dewarp.py::connect_nearby_grid_group()`:
 * bridges [StafflineGridGroup]s that a real staffline was split into by
 * an occluding symbol (note, barline, ...). Starting from a group, it
 * fits a least-squares line through its own grids' y-centers and walks
 * leftward in [maxStep] probes of one [StafflineGrid.width] each. If a
 * probe lands on another group's territory, it inserts synthetic
 * [StafflineGrid]s along the interpolated line to bridge the gap, folds
 * that group in, and keeps extending from there; if [maxStep] is
 * exhausted first, that group is left as-is.
 *
 * Two deliberate, documented deviations from the Python source:
 *  - **Traversal order.** oemer picks the next unvisited group via
 *    Python's `set.pop()`, whose iteration order is unspecified. This
 *    port always picks the *lowest remaining id* instead — a
 *    deterministic stand-in that only affects which group gets processed
 *    when, not the geometry any individual bridge produces.
 *  - **Edge behavior.** Python's `arr[y:y+h, x-1:x]` silently *wraps
 *    around* to the far side of the array when `x-1` goes negative
 *    (ordinary Python slicing semantics) — almost certainly an
 *    unintended artifact of assuming non-negative bounds, not a
 *    deliberate part of the algorithm. This port instead treats any
 *    out-of-bounds probe as unclaimed background, matching how the rest
 *    of this dewarp package treats bounds.
 */
object StafflineGridBridger {

    private const val DEFAULT_REF_COUNT = 8
    private const val DEFAULT_MAX_STEP = 20

    fun bridge(
        groupMap: IntArray,
        groups: List<StafflineGridGroup>,
        gridMap: IntArray,
        grids: List<StafflineGrid>,
        width: Int,
        height: Int,
        refCount: Int = DEFAULT_REF_COUNT,
        maxStep: Int = DEFAULT_MAX_STEP
    ): GridBridgingResult {
        if (groups.isEmpty()) {
            return GridBridgingResult(groupMap.copyOf(), groups, grids)
        }

        val newGroupMap = groupMap.copyOf()
        val mutableGrids = grids.toMutableList()
        val groupsById = groups.associate { g ->
            g.id to MutableGroup(g.id, g.left, g.top, g.right, g.bottom, g.gridIds.toMutableList())
        }

        var currentGroupId = groups[0].id
        var refGids = groupsById.getValue(currentGroupId).gridIds.take(refCount)
        val remaining = groups.map { it.id }.toMutableList()

        while (remaining.isNotEmpty()) {
            if (currentGroupId !in remaining) {
                currentGroupId = remaining.minOrNull()!!
                refGids = groupsById.getValue(currentGroupId).gridIds.take(refCount)
            }
            remaining.remove(currentGroupId)

            if (refGids.size < 2) continue

            val outcome = extendLeftward(
                currentGroupId, refGids, groupsById, mutableGrids, newGroupMap, gridMap, width, height, maxStep, refCount
            )
            currentGroupId = outcome.nextGroupId
            refGids = outcome.nextRefGids
        }

        val finalGroups = groupsById.values.sortedBy { it.id }.map {
            StafflineGridGroup(it.id, it.left, it.top, it.right, it.bottom, it.gridIds.toList())
        }
        return GridBridgingResult(newGroupMap, finalGroups, mutableGrids.toList())
    }

    /** One leftward-extension attempt from [groupId]. Returns the (possibly unchanged) group/ref-gids to continue from. */
    private fun extendLeftward(
        groupId: Int,
        refGids: List<Int>,
        groupsById: Map<Int, MutableGroup>,
        grids: MutableList<StafflineGrid>,
        groupMap: IntArray,
        gridMap: IntArray,
        width: Int,
        height: Int,
        maxStep: Int,
        refCount: Int
    ): ExtendOutcome {
        val group = groupsById.getValue(groupId)
        val stepSize = grids[refGids[0]].width
        val centers = DoubleArray(refGids.size) { grids[refGids[it]].yCenter }
        val xs = DoubleArray(refGids.size) { (it * stepSize).toDouble() }
        val model = SimpleLinearRegression.fit(xs, centers)

        var endX = grids[refGids[0]].left
        val h = grids[refGids[0]].height
        val gapBoxes = mutableListOf<IntArray>() // each: [left, top, right, bottom]

        for (i in 0 until maxStep) {
            val targetX = (-i - 1) * stepSize
            val centerY = model.predict(targetX.toDouble())
            val y = Math.round(centerY - h / 2.0).toInt()
            val probeLeft = endX - stepSize

            val groupCounts = countsInRegion(groupMap, width, height, probeLeft, endX, y, y + h)
            gapBoxes.add(intArrayOf(probeLeft, y, endX, y + h))

            if (groupCounts.isEmpty()) {
                endX -= stepSize
                continue
            }

            gapBoxes.removeAt(gapBoxes.size - 1) // that box overlapped a real group, not a gap to fill
            val label = groupCounts.maxByOrNull { it.value }!!.key
            val targetGroup = groupsById[label] ?: return ExtendOutcome(groupId, refGids)
            if (targetGroup.right > endX) return ExtendOutcome(groupId, refGids)

            val foundGridId = majorityGridId(groupMap, gridMap, width, height, probeLeft, endX, y, y + h, label)
                ?: return ExtendOutcome(groupId, refGids)
            val foundGrid = grids[foundGridId]

            val x0 = (-i - 1).toDouble()
            val y0 = foundGrid.yCenter
            val y1 = centers[0] // x1 = 0.0
            val denom = 0.0 - x0 // x1 - x0; x0 is always <= -1 here (i >= 0), so never zero

            val newIds = mutableListOf<Int>()
            gapBoxes.forEachIndexed { bi, box ->
                val evalX = (-bi - 1).toDouble()
                val t = (evalX - x0) / denom
                val interpCenter = y0 + t * (y1 - y0)
                val interpTop = Math.round(interpCenter - h / 2.0).toInt()
                val newGrid = StafflineGrid(id = grids.size, left = box[0], top = interpTop, right = box[2], bottom = interpTop + h)
                newIds.add(newGrid.id)
                grids.add(newGrid)
                group.gridIds.add(newGrid.id)
                group.left = minOf(group.left, newGrid.left)
                group.top = minOf(group.top, newGrid.top)
                group.right = maxOf(group.right, newGrid.right)
                group.bottom = maxOf(group.bottom, newGrid.bottom)
                paintGrid(groupMap, width, height, newGrid, group.id)
            }

            val target = groupsById.getValue(label)
            val combinedGids = target.gridIds.toList() + newIds.asReversed()
            return ExtendOutcome(nextGroupId = label, nextRefGids = combinedGids.take(refCount))
        }

        return ExtendOutcome(groupId, refGids)
    }

    private fun paintGrid(groupMap: IntArray, width: Int, height: Int, grid: StafflineGrid, groupId: Int) {
        for (y in grid.top until grid.bottom) {
            if (y !in 0 until height) continue
            val rowBase = y * width
            for (x in grid.left until grid.right) {
                if (x !in 0 until width) continue
                groupMap[rowBase + x] = groupId
            }
        }
    }

    /** Counts occurrences of each non-background value of [map] within the (bounds-clamped) rectangle. */
    private fun countsInRegion(
        map: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (y in top until bottom) {
            if (y !in 0 until height) continue
            val rowBase = y * width
            for (x in left until right) {
                if (x !in 0 until width) continue
                val value = map[rowBase + x]
                if (value == -1) continue
                counts[value] = (counts[value] ?: 0) + 1
            }
        }
        return counts
    }

    /** Majority-vote original (ungrouped) grid id among pixels in the rectangle that belong to group [label]. */
    private fun majorityGridId(
        groupMap: IntArray,
        gridMap: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        label: Int
    ): Int? {
        val counts = HashMap<Int, Int>()
        for (y in top until bottom) {
            if (y !in 0 until height) continue
            val rowBase = y * width
            for (x in left until right) {
                if (x !in 0 until width) continue
                if (groupMap[rowBase + x] != label) continue
                val gridId = gridMap[rowBase + x]
                if (gridId == -1) continue
                counts[gridId] = (counts[gridId] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private class MutableGroup(
        val id: Int,
        var left: Int,
        var top: Int,
        var right: Int,
        var bottom: Int,
        val gridIds: MutableList<Int>
    )

    private class ExtendOutcome(val nextGroupId: Int, val nextRefGids: List<Int>)
}

/** [grids] is the original detection's grids plus any synthetic bridging grids appended during [StafflineGridBridger.bridge]. */
data class GridBridgingResult(
    val groupMap: IntArray,
    val groups: List<StafflineGridGroup>,
    val grids: List<StafflineGrid>
)