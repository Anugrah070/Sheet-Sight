package com.sheetsight.app.data.omr.track

/**
 * Port of oemer's `bbox.py::get_bbox()`:
 * ```python
 * def get_bbox(data: ndarray) -> List[BBox]:
 *     contours, _ = cv2.findContours(data.astype(np.uint8), cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
 *     bboxes = []
 *     for cnt in contours:
 *         x, y, w, h = cv2.boundingRect(cnt)
 *         box = (x, y, x+w, y+h)
 *         bboxes.append(box)
 *     return bboxes
 * ```
 *
 * **Connectivity: 8-connected, not 4-connected.** `cv2.findContours`' border-following
 * treats foreground pixels touching only at a corner as part of the same object —
 * standard Moore-neighbor contour-tracing behavior, not something specific to
 * this port. This is deliberately **not** delegated to
 * [com.sheetsight.app.data.omr.dewarp.ConnectedComponents], which is 4-connected by
 * design (matching `scipy.ndimage.label`'s default, per its own KDoc) and is relied
 * on elsewhere in this codebase for that exact connectivity — reusing it here would
 * silently under-merge diagonally-touching barline candidates. This class instead
 * runs its own 8-connected flood fill, kept private to this file.
 *
 * **Not reproduced: hole contours.** `RETR_TREE` returns a separate contour (and
 * thus a separate bbox) for the boundary of a hole inside a solid blob, in addition
 * to the blob's own outer boundary — `get_bbox` would report both. Barline-shaped
 * blobs realistically never contain holes, so this extractor only ever produces one
 * box per 8-connected foreground blob (the outer-boundary case). This is a
 * documented, deliberate gap versus a truly exhaustive `findContours` port, not a
 * silent omission.
 *
 * **Box coordinates.** `cv2.boundingRect` returns `(x, y, w, h)`; oemer converts
 * that to `(x, y, x+w, y+h)` — i.e. exclusive right/bottom. [BoundingBox] uses that
 * same exclusive convention already established by
 * [com.sheetsight.app.data.omr.dewarp.StafflineGrid]/`StafflineGridGroup` in this
 * codebase, so `right - left` / `bottom - top` read the same way everywhere.
 *
 * **Output order is not claimed to match `cv2.findContours`' own traversal order.**
 * OpenCV's exact contour-discovery order is an internal algorithm detail this port
 * doesn't attempt to reproduce bit-for-bit; only each individual bbox's coordinates
 * are guaranteed faithful.
 */
object ConnectedComponentBoxExtractor {

    /** Extracts one [BoundingBox] per 8-connected foreground blob in [mask] ([width]x[height], row-major). */
    fun extract(mask: BooleanArray, width: Int, height: Int): List<BoundingBox> {
        require(mask.size == width * height) { "mask size ${mask.size} doesn't match ${width}x$height" }

        val visited = BooleanArray(mask.size)
        val boxes = mutableListOf<BoundingBox>()
        val stack = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY

            visited[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val cx = current % width
                val cy = current / width
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val neighbor = ny * width + nx
                        if (mask[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            stack.addLast(neighbor)
                        }
                    }
                }
            }

            boxes.add(BoundingBox(left = minX, top = minY, right = maxX + 1, bottom = maxY + 1))
        }

        return boxes
    }
}

/**
 * An axis-aligned bounding box with the same exclusive-right/bottom convention as
 * [com.sheetsight.app.data.omr.dewarp.StafflineGrid] — `right`/`bottom` are one past
 * the last included pixel. Distinct from [HoughLine]: a [HoughLine] is a Hough
 * *segment* whose endpoints happen to share this same 4-int shape (oemer itself
 * uses one generic `BBox` tuple type for both), but [ConnectedComponentBoxExtractor]'s
 * boxes come from contour/component extraction, not line detection, and shouldn't be
 * confused with one.
 */
data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}