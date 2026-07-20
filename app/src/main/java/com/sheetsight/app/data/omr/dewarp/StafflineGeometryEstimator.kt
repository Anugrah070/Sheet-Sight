package com.sheetsight.app.data.omr.dewarp

import com.sheetsight.app.data.omr.inference.OmrClassMasks

/**
 * Partial, Phase 4.5C port of oemer's `dewarp.py::estimate_coords()`:
 * covers the staff-mask morphological preprocessing
 * ([StaffMaskMorphology]), grid detection ([StafflineGridDetector] —
 * `build_grid`) and grid grouping ([StafflineGridGrouper] —
 * `build_grid_group`) stages. Together these estimate which pixels form
 * staffline-shaped structure and group touching segments into full-width
 * line runs — i.e. "use the staff mask to estimate the page's staff-line
 * geometry", this phase's scope.
 *
 * **Not yet implemented** (left for a following phase, and confirmed
 * against the real `oemer/dewarp.py` source rather than guessed):
 *  - `connect_nearby_grid_group()` — bridges groups across gaps (notes,
 *    barlines occluding a staffline) via per-group linear regression.
 *  - `build_mapping()` + `scipy.interpolate.griddata` — turns the sparse,
 *    possibly-still-gapped [StafflineGridGroup]s into a dense per-pixel
 *    `(coords_x, coords_y)` remap field.
 *  - `dewarp()` — the actual `cv2.remap` application of that field to the
 *    original image and all five masks.
 *
 * Nothing in this class applies any transformation to the image or masks
 * yet; it only estimates geometry.
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
            return StafflineGeometryEstimate(width, height, IntArray(0), emptyList(), isReliable = false)
        }

        val thickened = StaffMaskMorphology.thickenStafflines(staffMask, width, height)
        val detection = StafflineGridDetector.detectGrids(thickened, width, height)
        val grouping = StafflineGridGrouper.groupGrids(detection, width, height)

        return StafflineGeometryEstimate(
            width = width,
            height = height,
            groupMap = grouping.groupMap,
            groups = grouping.groups,
            isReliable = grouping.groups.isNotEmpty()
        )
    }
}

/**
 * Result of the geometry-estimation prefix currently ported. [groupMap]
 * holds each pixel's owning [StafflineGridGroup.id] (or -1), row-major
 * over [width]x[height], same layout as [OmrClassMasks]. [isReliable]
 * false means dewarping should be skipped and the page passed through
 * unmodified rather than forcing a transform onto noise.
 */
data class StafflineGeometryEstimate(
    val width: Int,
    val height: Int,
    val groupMap: IntArray,
    val groups: List<StafflineGridGroup>,
    val isReliable: Boolean
)