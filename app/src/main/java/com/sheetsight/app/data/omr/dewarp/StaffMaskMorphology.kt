package com.sheetsight.app.data.omr.dewarp

/**
 * Pure-Kotlin port of the two OpenCV morphological calls that open
 * `estimate_coords()` in oemer's `dewarp.py`, run on the staff mask
 * before any grid detection:
 * ```
 * ker = np.ones((6, 1), dtype=np.uint8)
 * pred = cv2.dilate(staff_pred.astype(np.uint8), ker)
 * pred = morph_open(pred, (1, 6))   # cv2.morphologyEx(..., MORPH_OPEN, ones((1,6)))
 * ```
 * i.e. dilate 6px tall (vertical), then open 6px wide (horizontal) — open
 * being erode-then-dilate with the same kernel.
 *
 * Reimplemented directly on a flat row-major [BooleanArray] (matching
 * [com.sheetsight.app.data.omr.inference.OmrClassMasks]'s layout) rather
 * than via OpenCV: both kernels are 1-dimensional, so a hand-rolled
 * sliding-window pass is simpler to keep pure-JVM-testable than round
 * tripping through a native `Mat`.
 *
 * Two details matter for faithfulness and are verified, not assumed:
 *  - **Anchor asymmetry.** OpenCV anchors an even-length kernel of size K
 *    at index `K/2` (integer division), so a window covers offsets
 *    `-anchor..(K-1-anchor)` — for K=6 that's `-3..2`, not the symmetric
 *    `-2..3` one might otherwise assume.
 *  - **Border handling.** Out-of-bounds neighbors contribute the
 *    operation's own identity element — `true` for erode's AND (so a
 *    missing neighbor never blocks erosion at an edge) and `false` for
 *    dilate's OR (so a missing neighbor never manufactures a dilated
 *    pixel out of nothing at an edge). This is OpenCV's documented
 *    default morphology border convention. Exact behavior should still be
 *    cross-checked against a real `Mat`-backed instrumented test once
 *    this stage is wired into the OpenCV-backed pipeline.
 */
object StaffMaskMorphology {

    private const val VERTICAL_DILATE_SIZE = 6
    private const val HORIZONTAL_OPEN_SIZE = 6

    /** Reproduces `estimate_coords()`'s `dilate((6,1))` then `morph_open((1,6))` preprocessing. */
    fun thickenStafflines(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val dilatedVertically = slide(mask, width, height, VERTICAL_DILATE_SIZE, vertical = true, erode = false)
        val eroded = slide(dilatedVertically, width, height, HORIZONTAL_OPEN_SIZE, vertical = false, erode = true)
        return slide(eroded, width, height, HORIZONTAL_OPEN_SIZE, vertical = false, erode = false)
    }

    /**
     * One 1-D morphological pass along a column ([vertical]=true) or row
     * ([vertical]=false): [erode]=true is erosion (AND over the window),
     * [erode]=false is dilation (OR over the window). See the class KDoc
     * for the anchor and border-handling conventions this follows.
     */
    internal fun slide(
        mask: BooleanArray,
        width: Int,
        height: Int,
        kernelSize: Int,
        vertical: Boolean,
        erode: Boolean
    ): BooleanArray {
        val anchor = kernelSize / 2
        val result = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = erode // AND starts true (identity), OR starts false (identity)
                for (offset in -anchor..(kernelSize - 1 - anchor)) {
                    val sy = if (vertical) y + offset else y
                    val sx = if (vertical) x else x + offset
                    val inBounds = sy in 0 until height && sx in 0 until width
                    val on = if (inBounds) mask[sy * width + sx] else erode
                    acc = if (erode) acc && on else acc || on
                    if (erode && !acc) break
                    if (!erode && acc) break
                }
                result[y * width + x] = acc
            }
        }
        return result
    }
}