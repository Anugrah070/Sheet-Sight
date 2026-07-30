package com.sheetsight.app.data.omr.dewarp

import com.sheetsight.app.data.omr.inference.OmrClassMasks

/**
 * Port of oemer's `dewarp.py::estimate_coords()`'s geometry-detection
 * half: staff-mask morphological preprocessing ([StaffMaskMorphology]),
 * grid detection ([StafflineGridDetector] — `build_grid`) and grid
 * grouping ([StafflineGridGrouper] — `build_grid_group`). Together these
 * estimate which pixels form staffline-shaped structure and group
 * touching segments into full-width line runs.
 *
 * Gap-bridging ([StafflineGridBridger]), the dense coordinate map
 * ([DewarpMappingBuilder] + [DewarpCoordinateInterpolator]) and the
 * actual remap ([DewarpRemapper]) are orchestrated separately by
 * [DewarpPipeline], which calls this class first.
 */
object StafflineGeometryEstimator {

    /** Convenience overload reading [masks].staff directly. */
    fun estimate(masks: OmrClassMasks): StafflineGeometryEstimate =
        estimate(masks.staff, masks.width, masks.height)

    /**
     * Runs the implemented prefix of `estimate_coords()` against
     * [staffMask]. [StafflineGeometryEstimate.isReliable] is false when no
     * staffline-shaped structure was found at all (e.g. a blank page or a
     * mask with no staff pixels) — the graceful-degradation signal at
     * this phase's scope. It does not yet reproduce oemer's own
     * `min_width_ratio` filter from `build_mapping()`, since that stage
     * isn't ported yet.
     */
    fun estimate(staffMask: BooleanArray, width: Int, height: Int): StafflineGeometryEstimate {
        require(staffMask.size == width * height) {
            "staffMask size ${staffMask.size} doesn't match ${width}x$height"
        }
        if (width <= 0 || height <= 0) {
            return StafflineGeometryEstimate(
                width = width,
                height = height,
                groupMap = IntArray(0),
                groups = emptyList(),
                gridMap = IntArray(0),
                grids = emptyList(),
                isReliable = false
            )
        }

        val thickened = StaffMaskMorphology.thickenStafflines(staffMask, width, height)
        val detection = StafflineGridDetector.detectGrids(thickened, width, height)
        val grouping = StafflineGridGrouper.groupGrids(detection, width, height)

        return StafflineGeometryEstimate(
            width = width,
            height = height,
            groupMap = grouping.groupMap,
            groups = grouping.groups,
            gridMap = detection.gridMap,
            grids = detection.grids,
            isReliable = grouping.groups.isNotEmpty()
        )
    }
}

/**
 * Result of the geometry-detection stage. [groupMap]/[groups] are the
 * (pre-bridging) grouped view; [gridMap]/[grids] are the underlying
 * ungrouped detection oemer's `connect_nearby_grid_group()` also needs
 * (it looks up individual grids' original ids, not just group ids). All
 * row-major over [width]x[height], same layout as [OmrClassMasks].
 * [isReliable] false means dewarping should be skipped and the page
 * passed through unmodified rather than forcing a transform onto noise.
 */
data class StafflineGeometryEstimate(
    val width: Int,
    val height: Int,
    val groupMap: IntArray,
    val groups: List<StafflineGridGroup>,
    val gridMap: IntArray,
    val grids: List<StafflineGrid>,
    val isReliable: Boolean
)