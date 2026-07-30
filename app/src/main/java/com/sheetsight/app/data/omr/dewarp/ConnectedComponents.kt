package com.sheetsight.app.data.omr.dewarp

/**
 * 4-connected component labeling over an `IntArray` where `-1` means
 * background and any other value means foreground — mirroring
 * `scipy.ndimage.label`'s default connectivity, which oemer's `dewarp.py`
 * relies on twice: once in `build_grid_group()` (via [StafflineGridGrouper])
 * and again in `build_mapping()` (via [DewarpMappingBuilder]) to re-label
 * the *bridged* group map, since two adjacent groups that
 * [StafflineGridBridger] left touching but under different ids should
 * still be treated as one connected blob at that point.
 */
internal object ConnectedComponents {

    /** Returns region ids `1..N` per pixel of [map] ([width]x[height]); `0` marks background. */
    fun label(map: IntArray, width: Int, height: Int): IntArray {
        val regions = IntArray(map.size)
        var nextRegion = 0
        val stack = ArrayDeque<Int>()
        for (start in map.indices) {
            if (map[start] == -1 || regions[start] != 0) continue
            nextRegion++
            regions[start] = nextRegion
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val cx = current % width
                val cy = current / width
                if (cx > 0) tryVisit(current - 1, map, regions, nextRegion, stack)
                if (cx < width - 1) tryVisit(current + 1, map, regions, nextRegion, stack)
                if (cy > 0) tryVisit(current - width, map, regions, nextRegion, stack)
                if (cy < height - 1) tryVisit(current + width, map, regions, nextRegion, stack)
            }
        }
        return regions
    }

    private fun tryVisit(index: Int, map: IntArray, regions: IntArray, regionId: Int, stack: ArrayDeque<Int>) {
        if (map[index] != -1 && regions[index] == 0) {
            regions[index] = regionId
            stack.addLast(index)
        }
    }
}