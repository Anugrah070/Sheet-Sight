package com.sheetsight.app.data.omr.track

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Port of oemer's `bbox.py::find_lines()`:
 * ```python
 * def find_lines(data: ndarray, min_len: int = 10, max_gap: int = 20) -> List[BBox]:
 *     lines = cv2.HoughLinesP(data.astype(np.uint8), 1, np.pi/180, 50, None, min_len, max_gap)
 *     ...
 *     top_x, bt_x = (line[0], line[2]) if line[0] < line[2] else (line[2], line[0])
 *     top_y, bt_y = (line[1], line[3]) if line[1] < line[3] else (line[3], line[1])
 *     new_line.append((top_x, top_y, bt_x, bt_y))
 * ```
 *
 * [RHO]/[THETA]/[THRESHOLD] are oemer's hardcoded, positionally-passed
 * constants (`1`, `π/180`, `50`) — never exposed as parameters, since
 * oemer itself never varies them. [minLineLength]/[maxGap] mirror
 * oemer's own `min_len`/`max_gap` **defaults** (10/20); oemer's signature
 * allows overriding them, so this does too.
 *
 * **Endpoint reordering is per-axis, not per-segment** — see
 * [reorderEndpoints]'s own KDoc for the (unusual, but faithfully
 * preserved) consequence of that.
 *
 * Following this project's existing OpenCV-boundary convention (see
 * [com.sheetsight.app.data.omr.preprocessing.CanonicalImageResizer] and
 * [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler], both of
 * which split a pure-math helper out from the `Mat`-touching entry point
 * so the math stays JVM-unit-testable without OpenCV's native library),
 * [reorderEndpoints] is exposed separately from [detect] for the same
 * reason — [detect] itself is not covered by a JVM unit test here, same
 * as those two classes' own `Mat`-touching methods.
 */
object HoughLineDetector {

    private const val RHO = 1.0
    private const val THETA = Math.PI / 180.0
    private const val THRESHOLD = 50
    private const val DEFAULT_MIN_LINE_LENGTH = 10
    private const val DEFAULT_MAX_GAP = 20

    /**
     * Runs `cv2.HoughLinesP` over [mask] ([width]x[height], row-major,
     * `true` = foreground) and returns each detected segment with its
     * endpoints reordered via [reorderEndpoints]. Mirrors oemer's
     * `data.astype(np.uint8)` cast: any `true` pixel is foreground, any
     * `false` pixel is background.
     */
    fun detect(
        mask: BooleanArray,
        width: Int,
        height: Int,
        minLineLength: Int = DEFAULT_MIN_LINE_LENGTH,
        maxGap: Int = DEFAULT_MAX_GAP
    ): List<HoughLine> {
        require(mask.size == width * height) { "mask size ${mask.size} doesn't match ${width}x$height" }

        val input = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) row[x] = if (mask[rowBase + x]) 1 else 0
            input.put(y, 0, row)
        }

        val linesMat = Mat()
        try {
            Imgproc.HoughLinesP(input, linesMat, RHO, THETA, THRESHOLD, minLineLength.toDouble(), maxGap.toDouble())
            return readLines(linesMat)
        } finally {
            input.release()
            linesMat.release()
        }
    }

    private fun readLines(linesMat: Mat): List<HoughLine> {
        val result = mutableListOf<HoughLine>()
        val coords = IntArray(4)
        for (i in 0 until linesMat.rows()) {
            linesMat.get(i, 0, coords)
            result.add(reorderEndpoints(coords[0], coords[1], coords[2], coords[3]))
        }
        return result
    }

    /**
     * Reproduces oemer's endpoint reorder exactly: `(x1, x2)` is
     * reordered so the smaller comes first, and `(y1, y2)` is reordered
     * so the smaller comes first — **computed independently per axis**.
     *
     * For a segment whose x and y don't both increase (or both decrease)
     * together — e.g. running bottom-left to top-right — this does
     * **not** reconstruct either real endpoint. It produces a synthetic
     * `(topX, topY)`/`(btX, btY)` pair that may not correspond to any
     * actual detected point. This is intentional fidelity to oemer's
     * source, not a bug in this port — see the class KDoc.
     */
    internal fun reorderEndpoints(x1: Int, y1: Int, x2: Int, y2: Int): HoughLine {
        val (topX, btX) = if (x1 < x2) x1 to x2 else x2 to x1
        val (topY, btY) = if (y1 < y2) y1 to y2 else y2 to y1
        return HoughLine(topX, topY, btX, btY)
    }
}

/**
 * One Hough-detected line segment after oemer's per-axis endpoint
 * reorder. See [HoughLineDetector.reorderEndpoints] for why [topX]/[topY]
 * and [btX]/[btY] are not necessarily a real endpoint pair.
 */
data class HoughLine(val topX: Int, val topY: Int, val btX: Int, val btY: Int)