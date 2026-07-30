package com.sheetsight.app.data.omr.dewarp

import com.sheetsight.app.data.omr.inference.OmrClassMasks

/**
 * Completes the Phase 4.5C/D port of oemer's dewarping: runs
 * [StafflineGeometryEstimator] (staff-mask morphology + grid
 * detect/group), [StafflineGridBridger] (gap-bridging),
 * [DewarpMappingBuilder] + [DewarpCoordinateInterpolator] (the dense
 * coordinate map), then [DewarpRemapper] applied to every layer — matching
 * `ete.py`'s own sequence: `staff`, `symbols`, `stems_rests`, `clefs_keys`,
 * `notehead`, then every channel of the original image, all remapped with
 * the *same* `coords_y`, preserving alignment across all six.
 *
 * If [StafflineGeometryEstimate.isReliable] is false (no staffline
 * structure detected at all), everything is passed through unchanged
 * rather than forcing a transform onto noise — the graceful-degradation
 * path this phase's requirements call for.
 *
 * Staffline extraction, notehead extraction, symbol classification,
 * rhythm extraction and MusicXML generation are later phases and are not
 * touched here.
 */
object DewarpPipeline {

    /**
     * [imageChannels] are the original (canonical-resolution) image's
     * per-channel pixel buffers, each [OmrClassMasks.width] x
     * [OmrClassMasks.height], row-major, one `FloatArray` per channel —
     * matching `ete.py`'s own `for i in range(image.shape[2])` loop.
     * [OmrPageDewarpRunner] wires this up to a real decoded page (via
     * [com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner]'s
     * canonical image + [com.sheetsight.app.data.omr.inference.ClassMaskExtractor]'s
     * masks); this function itself stays decoupled from where its inputs
     * came from. Still unwired to [com.sheetsight.app.data.omr.OnnxOmrEngine].
     */
    fun run(imageChannels: List<FloatArray>, masks: OmrClassMasks): DewarpedPage {
        val width = masks.width
        val height = masks.height
        imageChannels.forEach {
            require(it.size == width * height) { "each image channel must be $width x $height" }
        }

        val geometry = StafflineGeometryEstimator.estimate(masks)
        if (!geometry.isReliable) {
            return DewarpedPage(width, height, imageChannels, masks, wasDewarped = false)
        }

        val bridging = StafflineGridBridger.bridge(
            groupMap = geometry.groupMap,
            groups = geometry.groups,
            gridMap = geometry.gridMap,
            grids = geometry.grids,
            width = width,
            height = height
        )
        val controlPoints = DewarpMappingBuilder.build(bridging.groupMap, width, height)
        val coordsY = DewarpCoordinateInterpolator.interpolate(controlPoints, width, height)

        val dewarpedChannels = imageChannels.map { DewarpRemapper.remap(it, width, height, coordsY) }
        val dewarpedMasks = OmrClassMasks(
            width = width,
            height = height,
            staff = DewarpRemapper.remapMask(masks.staff, width, height, coordsY),
            symbols = DewarpRemapper.remapMask(masks.symbols, width, height, coordsY),
            stemsRests = DewarpRemapper.remapMask(masks.stemsRests, width, height, coordsY),
            noteheads = DewarpRemapper.remapMask(masks.noteheads, width, height, coordsY),
            clefsKeys = DewarpRemapper.remapMask(masks.clefsKeys, width, height, coordsY)
        )

        return DewarpedPage(width, height, dewarpedChannels, dewarpedMasks, wasDewarped = true)
    }
}

/**
 * Result of [DewarpPipeline.run]. [wasDewarped] false means [imageChannels]
 * and [masks] are exactly the inputs, untouched (the graceful-degradation
 * path for a page with no detectable staffline structure).
 */
data class DewarpedPage(
    val width: Int,
    val height: Int,
    val imageChannels: List<FloatArray>,
    val masks: OmrClassMasks,
    val wasDewarped: Boolean
)