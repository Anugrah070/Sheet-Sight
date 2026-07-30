package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.dewarp.StaffMaskMorphology
import com.sheetsight.app.data.omr.inference.OmrClassMasks

/**
 * Source-faithful construction of oemer's track-inference barline map.
 */
object BarlineMaskBuilder {
    private const val CLOSE_HEIGHT = 5
    private const val CLOSE_WIDTH = 2

    fun houghInput(masks: OmrClassMasks): BooleanArray =
        BooleanArray(masks.width * masks.height) { i ->
            masks.symbols[i] &&
                !masks.stemsRests[i] &&
                !masks.noteheads[i] &&
                !masks.clefsKeys[i]
        }

    /**
     * Keeps generic-symbol pixels only inside accepted Hough rectangles,
     * then unions the complete stems/straight-lines prediction.
     */
    fun build(
        masks: OmrClassMasks,
        acceptedLines: List<HoughLine>
    ): BooleanArray {
        val result = masks.stemsRests.copyOf()
        for (line in acceptedLines) {
            val left = line.topX.coerceIn(0, masks.width)
            val right = (if (line.btX == line.topX) line.btX + 1 else line.btX)
                .coerceIn(0, masks.width)
            val top = line.topY.coerceIn(0, masks.height)
            val bottom = line.btY.coerceIn(0, masks.height)
            for (y in top until bottom) {
                val rowBase = y * masks.width
                for (x in left until right) {
                    val index = rowBase + x
                    if (masks.symbols[index]) result[index] = true
                }
            }
        }
        return close(result, masks.width, masks.height)
    }

    /** `erode(dilate(mask, ones(5, 2)), ones(5, 2))`. */
    internal fun close(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val dilatedVertically = StaffMaskMorphology.slide(
            mask, width, height, CLOSE_HEIGHT, vertical = true, erode = false
        )
        val dilated = StaffMaskMorphology.slide(
            dilatedVertically, width, height, CLOSE_WIDTH, vertical = false, erode = false
        )
        val erodedVertically = StaffMaskMorphology.slide(
            dilated, width, height, CLOSE_HEIGHT, vertical = true, erode = true
        )
        return StaffMaskMorphology.slide(
            erodedVertically, width, height, CLOSE_WIDTH, vertical = false, erode = true
        )
    }
}
