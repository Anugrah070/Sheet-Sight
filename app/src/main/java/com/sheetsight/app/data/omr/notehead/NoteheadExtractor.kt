package com.sheetsight.app.data.omr.notehead

import com.sheetsight.app.data.omr.dewarp.ConnectedComponents
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 4.7A port of oemer
 * `notehead_extraction.py` at commit
 * `dbe2a933d630d0f74805d717960eb259473f5978`.
 *
 * The port preserves oemer's ellipse morphology, size splitting, density
 * filtering, hollow-note test, staff assignment, staff-line position, and
 * stem-side detection. It does not infer rhythm, chords, pitch, measures,
 * or MusicXML.
 *
 * **Unverified Android adaptations:**
 *
 * 1. oemer obtains contours through `cv2.findContours(RETR_TREE)` and then
 *    removes overlapping inner boxes. This port is required to reuse
 *    [ConnectedComponentBoxExtractor], whose documented 8-connected outer
 *    component boxes omit hole contours. Inner contours should have been
 *    removed by oemer's subsequent overlap pass, but equivalence for every
 *    pathological mask has not been verified.
 * 2. oemer's horizontal bounds come from its separate `zones` layer. The
 *    current validated Kotlin grid has no zone-range object, so this port
 *    uses the minimum/maximum staffline x extents. This is the nearest
 *    existing representation and may be stricter at damaged page edges.
 * 3. oemer uses sklearn Ward clustering in `merge_nearby_bbox`. The small
 *    local Ward implementation below follows the published Ward distance
 *    formula and oemer's scaled box centers, but sklearn tie ordering has
 *    not been verified for exactly equal merge distances.
 * 4. oemer chooses one intersecting stem label through Python `set.pop()`.
 *    This port chooses the lowest label deterministically; equivalence is
 *    unverified only when one note box intersects multiple stem components.
 */
object NoteheadExtractor {

    private const val NOTEHEAD_MORPH_WIDTH_FACTOR = 0.5
    private const val NOTEHEAD_MORPH_HEIGHT_FACTOR = 0.4
    private const val NOTEHEAD_SIZE_RATIO = 1.285714
    private const val CLEF_ZONE_WIDTH_UNIT_RATIO = 4.5406916

    fun extract(
        masks: OmrClassMasks,
        validatedStaffGrid: List<List<AssignedStaff>>
    ): List<NoteheadCandidate> =
        extract(
            noteheadMask = masks.noteheads,
            symbolsMask = masks.symbols,
            stemMask = masks.stemsRests,
            width = masks.width,
            height = masks.height,
            validatedStaffGrid = validatedStaffGrid
        )

    /**
     * Array-based entry point used by focused JVM tests and by the smoke
     * test. All masks are borrowed read-only and never copied wholesale.
     */
    fun extract(
        noteheadMask: BooleanArray,
        symbolsMask: BooleanArray,
        stemMask: BooleanArray,
        width: Int,
        height: Int,
        validatedStaffGrid: List<List<AssignedStaff>>
    ): List<NoteheadCandidate> {
        require(width > 0 && height > 0) { "width and height must be positive" }
        val expectedSize = width * height
        require(noteheadMask.size == expectedSize) { "noteheadMask size must be $expectedSize" }
        require(symbolsMask.size == expectedSize) { "symbolsMask size must be $expectedSize" }
        require(stemMask.size == expectedSize) { "stemMask size must be $expectedSize" }
        val staffs = validatedStaffGrid.flatten()
        if (staffs.isEmpty()) return emptyList()

        val globalUnitSize = staffs.map { it.staff.unitSize }.average()
        val morphed = morphNoteheads(noteheadMask, width, height, globalUnitSize)
        val componentBoxes = ConnectedComponentBoxExtractor.extract(morphed, width, height)
        val splitBoxes = componentBoxes.flatMap { box ->
            checkBoundingBoxSize(
                box = box,
                noteheadMask = noteheadMask,
                width = width,
                height = height,
                unitSize = unitSizeAt(box.centerX(), box.centerY(), staffs)
            )
        }
        val filtered = filterBoxes(splitBoxes, morphed, width, height, staffs)
        val merged = mergeNearbyBoxes(
            filtered,
            distance = globalUnitSize * 1.5,
            xFactor = 1.0,
            yFactor = 5.0
        )

        val enhancedStemLabels = labelEnhancedStems(stemMask, width, height)
        val stemStats = componentXStats(enhancedStemLabels, width)
        val candidates = ArrayList<NoteheadCandidate>(merged.size)

        for (box in merged) {
            // oemer subtracts one from all four coordinates after morphology.
            val shifted = BoundingBox(
                left = (box.left - 1).coerceAtLeast(0),
                top = (box.top - 1).coerceAtLeast(0),
                right = (box.right - 1).coerceIn(1, width),
                bottom = (box.bottom - 1).coerceIn(1, height)
            )
            if (shifted.width <= 0 || shifted.height <= 0) continue

            val sourcePixels = collectForegroundPixels(symbolsMask, width, shifted)
            if (sourcePixels.isEmpty()) continue

            val filledCount = fillHoleCount(symbolsMask, width, shifted)
            val filledRatio = filledCount.toDouble() / sourcePixels.size
            val type = if (filledRatio > 1.3) {
                NoteheadType.HALF_OR_WHOLE
            } else {
                NoteheadType.SOLID
            }
            val assignment = assignStaff(shifted.centerX(), shifted.centerY(), staffs)
            val stemOnRight = stemSide(
                shifted,
                enhancedStemLabels,
                stemStats,
                width
            )
            candidates += NoteheadCandidate(
                id = candidates.size,
                boundingBox = shifted,
                type = type,
                staffAssignment = assignment,
                sourcePixelIndices = sourcePixels,
                stemOnRight = stemOnRight
            )
        }
        return candidates
    }

    /**
     * Pure-Kotlin reproduction of oemer's `morph_notehead()` using
     * OpenCV-compatible ellipse kernels and morphology border identities.
     */
    internal fun morphNoteheads(
        mask: BooleanArray,
        width: Int,
        height: Int,
        unitSize: Double
    ): BooleanArray {
        val smallSize = pythonRound(unitSize / 3.0)
        val morphWidth = pythonRound(unitSize * NOTEHEAD_MORPH_WIDTH_FACTOR)
        val morphHeight = pythonRound(unitSize * NOTEHEAD_MORPH_HEIGHT_FACTOR)
        require(smallSize > 0 && morphWidth > 0 && morphHeight > 0) {
            "staff unit size $unitSize produces an invalid oemer morphology kernel"
        }

        var current = morph(mask, width, height, smallSize, smallSize, erode = false)
        current = morph(current, width, height, smallSize, smallSize, erode = true)
        current = morph(current, width, height, morphWidth, morphHeight, erode = true)
        return morph(current, width, height, morphWidth + 1, morphHeight + 1, erode = false)
    }

    /** OpenCV `MORPH_ELLIPSE` kernel generation, including even-size anchor asymmetry. */
    internal fun ellipseKernel(kernelWidth: Int, kernelHeight: Int): BooleanArray {
        require(kernelWidth > 0 && kernelHeight > 0)
        val kernel = BooleanArray(kernelWidth * kernelHeight)
        val radius = kernelHeight / 2
        val centerX = kernelWidth / 2
        for (y in 0 until kernelHeight) {
            val dy = y - radius
            val halfWidth = if (abs(dy) <= radius && radius > 0) {
                centerX * sqrt((radius * radius - dy * dy).toDouble() / (radius * radius))
            } else {
                0.0
            }
            val left = (centerX - halfWidth).roundToInt()
            val rightExclusive = (centerX + halfWidth).roundToInt() + 1
            for (x in left.coerceAtLeast(0) until rightExclusive.coerceAtMost(kernelWidth)) {
                kernel[y * kernelWidth + x] = true
            }
        }
        return kernel
    }

    private fun morph(
        source: BooleanArray,
        width: Int,
        height: Int,
        kernelWidth: Int,
        kernelHeight: Int,
        erode: Boolean
    ): BooleanArray {
        val kernel = ellipseKernel(kernelWidth, kernelHeight)
        val anchorX = kernelWidth / 2
        val anchorY = kernelHeight / 2
        val output = BooleanArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var result = erode
                loop@ for (ky in 0 until kernelHeight) {
                    for (kx in 0 until kernelWidth) {
                        if (!kernel[ky * kernelWidth + kx]) continue
                        val sx = x + kx - anchorX
                        val sy = y + ky - anchorY
                        val value = if (sx in 0 until width && sy in 0 until height) {
                            source[sy * width + sx]
                        } else {
                            erode
                        }
                        result = if (erode) result && value else result || value
                        if ((erode && !result) || (!erode && result)) break@loop
                    }
                }
                output[y * width + x] = result
            }
        }
        return output
    }

    private fun checkBoundingBoxSize(
        box: BoundingBox,
        noteheadMask: BooleanArray,
        width: Int,
        height: Int,
        unitSize: Double
    ): List<BoundingBox> {
        val noteWidth = NOTEHEAD_SIZE_RATIO * unitSize
        val splitHorizontally =
            abs(box.width - noteWidth) > abs(box.width - noteWidth * 2.0) &&
                    box.centerX() > box.left && box.centerX() < box.right
        val horizontal = if (splitHorizontally) {
            listOfNotNull(
                adjustBox(BoundingBox(box.left, box.top, box.centerX(), box.bottom), noteheadMask, width, height),
                adjustBox(BoundingBox(box.centerX(), box.top, box.right, box.bottom), noteheadMask, width, height)
            ).flatMap { checkBoundingBoxSize(it, noteheadMask, width, height, unitSize) }
        } else {
            emptyList()
        }
        if (horizontal.isNotEmpty()) {
            return horizontal.flatMap { checkHeight(it, unitSize) }
        }
        return checkHeight(box, unitSize)
    }

    private fun checkHeight(box: BoundingBox, unitSize: Double): List<BoundingBox> {
        val numberOfNotes = pythonRound(box.height / unitSize)
        if (numberOfNotes <= 0) return emptyList()
        val subHeight = box.height / numberOfNotes
        if (subHeight <= 0) return emptyList()
        return (0 until numberOfNotes).map { index ->
            BoundingBox(
                left = box.left,
                top = box.top + index * subHeight,
                right = box.right,
                bottom = box.top + (index + 1) * subHeight
            )
        }
    }

    private fun adjustBox(
        box: BoundingBox,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): BoundingBox? {
        var minY = height
        var maxY = -1
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(height)) {
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(width)) {
                if (mask[y * width + x]) {
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxY < minY) return null
        return BoundingBox(
            left = box.left,
            top = (minY - 1).coerceAtLeast(0),
            right = box.right,
            bottom = (maxY + 1).coerceAtMost(height)
        )
    }

    private fun filterBoxes(
        boxes: List<BoundingBox>,
        morphed: BooleanArray,
        width: Int,
        height: Int,
        staffs: List<AssignedStaff>
    ): List<BoundingBox> {
        val minX = staffs.minOf { assigned -> assigned.staff.lines.minOf { it.xLeft } }
        val maxX = staffs.maxOf { assigned -> assigned.staff.lines.maxOf { it.xRight } }
        return boxes.filter { box ->
            val centerX = box.centerX()
            val centerY = box.centerY()
            val unitSize = unitSizeAt(centerX, centerY, staffs)
            if (centerX < minX + CLEF_ZONE_WIDTH_UNIT_RATIO * unitSize || centerX > maxX) {
                false
            } else if (box.height < unitSize * 0.4 || box.height > unitSize * 5.0) {
                false
            } else if (
                box.width < unitSize * 0.3 * NOTEHEAD_SIZE_RATIO ||
                box.width > unitSize * 3.0 * NOTEHEAD_SIZE_RATIO
            ) {
                false
            } else {
                foregroundCount(morphed, width, height, box) >= box.width * box.height * 0.5
            }
        }
    }

    private fun foregroundCount(
        mask: BooleanArray,
        width: Int,
        height: Int,
        box: BoundingBox
    ): Int {
        var count = 0
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(height)) {
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(width)) {
                if (mask[y * width + x]) count++
            }
        }
        return count
    }

    private data class WardCluster(
        val members: MutableList<Int>,
        var count: Int,
        var meanX: Double,
        var meanY: Double
    )

    private fun mergeNearbyBoxes(
        boxes: List<BoundingBox>,
        distance: Double,
        xFactor: Double,
        yFactor: Double
    ): List<BoundingBox> {
        if (boxes.size < 2) return boxes
        val clusters = boxes.mapIndexed { index, box ->
            WardCluster(
                members = mutableListOf(index),
                count = 1,
                meanX = (box.left + box.right) / 2.0 * xFactor,
                meanY = (box.top + box.bottom) / 2.0 * yFactor
            )
        }.toMutableList()

        while (clusters.size > 1) {
            var bestA = -1
            var bestB = -1
            var bestDistance = Double.POSITIVE_INFINITY
            for (a in 0 until clusters.lastIndex) {
                for (b in a + 1 until clusters.size) {
                    val left = clusters[a]
                    val right = clusters[b]
                    val dx = left.meanX - right.meanX
                    val dy = left.meanY - right.meanY
                    val ward = sqrt(
                        2.0 * left.count * right.count / (left.count + right.count) *
                                (dx * dx + dy * dy)
                    )
                    if (ward < bestDistance) {
                        bestDistance = ward
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (bestDistance >= distance || bestA < 0) break
            val a = clusters[bestA]
            val b = clusters[bestB]
            val total = a.count + b.count
            a.meanX = (a.meanX * a.count + b.meanX * b.count) / total
            a.meanY = (a.meanY * a.count + b.meanY * b.count) / total
            a.count = total
            a.members += b.members
            clusters.removeAt(bestB)
        }

        return clusters.map { cluster ->
            val members = cluster.members.map(boxes::get)
            BoundingBox(
                left = members.minOf { it.left },
                top = members.minOf { it.top },
                right = members.maxOf { it.right },
                bottom = members.maxOf { it.bottom }
            )
        }
    }

    private fun collectForegroundPixels(
        mask: BooleanArray,
        width: Int,
        box: BoundingBox
    ): IntArray {
        val pixels = ArrayList<Int>()
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val index = y * width + x
                if (mask[index]) pixels += index
            }
        }
        return pixels.toIntArray()
    }

    /**
     * Preserves the indentation of oemer's `fill_hole`: the column scan is
     * performed after each processed row, not only once after all rows.
     */
    internal fun fillHoleCount(mask: BooleanArray, width: Int, box: BoundingBox): Int {
        val regionWidth = box.width
        val regionHeight = box.height
        val region = BooleanArray(regionWidth * regionHeight)
        for (y in 0 until regionHeight) {
            for (x in 0 until regionWidth) {
                region[y * regionWidth + x] = mask[(box.top + y) * width + box.left + x]
            }
        }

        for (y in 0 until regionHeight) {
            var cursor = 0
            while (cursor < regionWidth && !region[y * regionWidth + cursor]) cursor++
            while (cursor < regionWidth && region[y * regionWidth + cursor]) cursor++
            val gapStart = cursor
            while (cursor < regionWidth && !region[y * regionWidth + cursor]) cursor++
            if (cursor < regionWidth) {
                for (x in gapStart until cursor) region[y * regionWidth + x] = true
            }

            for (x in 0 until regionWidth) {
                cursor = 0
                while (cursor < regionHeight && !region[cursor * regionWidth + x]) cursor++
                while (cursor < regionHeight && region[cursor * regionWidth + x]) cursor++
                val verticalGapStart = cursor
                while (cursor < regionHeight && !region[cursor * regionWidth + x]) cursor++
                if (cursor < regionHeight) {
                    for (fillY in verticalGapStart until cursor) {
                        region[fillY * regionWidth + x] = true
                    }
                }
            }
        }
        return region.count { it }
    }

    private fun labelEnhancedStems(mask: BooleanArray, width: Int, height: Int): IntArray {
        val enhanced = rectangularDilate(mask, width, height, kernelWidth = 2, kernelHeight = 3)
        val map = IntArray(enhanced.size) { if (enhanced[it]) 0 else -1 }
        return ConnectedComponents.label(map, width, height)
    }

    private fun rectangularDilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        kernelWidth: Int,
        kernelHeight: Int
    ): BooleanArray {
        val output = BooleanArray(source.size)
        val anchorX = kernelWidth / 2
        val anchorY = kernelHeight / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var found = false
                loop@ for (ky in 0 until kernelHeight) {
                    for (kx in 0 until kernelWidth) {
                        val sx = x + kx - anchorX
                        val sy = y + ky - anchorY
                        if (sx in 0 until width && sy in 0 until height && source[sy * width + sx]) {
                            found = true
                            break@loop
                        }
                    }
                }
                output[y * width + x] = found
            }
        }
        return output
    }

    private data class ComponentXStat(val sumX: Long, val count: Int)

    private fun componentXStats(labels: IntArray, width: Int): Map<Int, ComponentXStat> {
        val sums = mutableMapOf<Int, Long>()
        val counts = mutableMapOf<Int, Int>()
        labels.forEachIndexed { index, label ->
            if (label > 0) {
                sums[label] = (sums[label] ?: 0L) + index % width
                counts[label] = (counts[label] ?: 0) + 1
            }
        }
        return counts.mapValues { (label, count) -> ComponentXStat(sums.getValue(label), count) }
    }

    private fun stemSide(
        box: BoundingBox,
        labels: IntArray,
        stats: Map<Int, ComponentXStat>,
        width: Int
    ): Boolean? {
        var selectedLabel = Int.MAX_VALUE
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val label = labels[y * width + x]
                if (label > 0 && label < selectedLabel) selectedLabel = label
            }
        }
        if (selectedLabel == Int.MAX_VALUE) return null
        val stat = stats[selectedLabel] ?: return null
        val stemCenterX = stat.sumX.toDouble() / stat.count
        return stemCenterX > (box.left + box.right) / 2.0
    }

    private fun assignStaff(
        centerX: Int,
        centerY: Int,
        staffs: List<AssignedStaff>
    ): NoteheadStaffAssignment {
        val (first, second) = closestStaffs(centerX, centerY, staffs)
        val master = if (
            first.staff.yCenter == second.staff.yCenter ||
            centerY in first.staff.yUpper()..first.staff.yLower()
        ) {
            first
        } else {
            val upper = if (first.staff.yCenter < second.staff.yCenter) first else second
            val lower = if (upper === first) second else first
            if (centerY < (upper.staff.yCenter + lower.staff.yCenter) / 2.0) upper else lower
        }
        return NoteheadStaffAssignment(
            track = master.track,
            group = master.group,
            staffLinePosition = staffLinePosition(centerY, master)
        )
    }

    private fun staffLinePosition(centerY: Int, assigned: AssignedStaff): Int {
        val staff = assigned.staff
        val step = staff.unitSize / 2.0
        val lineCenters = staff.lines.map { it.yCenter }.reversed()
        val centers = ArrayList<Double>(11)
        centers += lineCenters.first() + step
        for (index in lineCenters.indices) {
            centers += lineCenters[index]
            if (index < lineCenters.lastIndex) {
                centers += (lineCenters[index] + lineCenters[index + 1]) / 2.0
            }
        }
        centers += lineCenters.last() - step

        val closestIndex = centers.indices.minBy { abs(centers[it] - centerY) }
        return when (closestIndex) {
            0 -> -pythonRound(abs(centers.first() - centerY) / step)
            centers.lastIndex ->
                pythonRound(abs(centers.last() - centerY) / step) + centers.lastIndex
            else -> closestIndex
        }
    }

    private fun unitSizeAt(x: Int, y: Int, staffs: List<AssignedStaff>): Double {
        val (first, second) = closestStaffs(x, y, staffs)
        if (first.staff.yCenter == second.staff.yCenter) return first.staff.unitSize
        if (y in first.staff.yUpper()..first.staff.yLower()) return first.staff.unitSize
        val distance1 = abs(y - first.staff.yCenter)
        val distance2 = abs(y - second.staff.yCenter)
        val weight1 = distance1 / (distance1 + distance2)
        val weight2 = distance2 / (distance1 + distance2)
        return weight1 * first.staff.unitSize + weight2 * second.staff.unitSize
    }

    private fun closestStaffs(
        x: Int,
        y: Int,
        staffs: List<AssignedStaff>
    ): Pair<AssignedStaff, AssignedStaff> {
        val sorted = staffs.sortedBy {
            val dx = x - it.staff.xCenter()
            val dy = y - it.staff.yCenter
            sqrt(dx * dx + dy * dy)
        }
        if (sorted.size == 1) return sorted[0] to sorted[0]
        if (sorted.size == 2) return sorted[0] to sorted[1]

        val first = sorted[0]
        val second = sorted[1]
        val third = sorted[2]
        return if (abs(first.staff.yLower() - y) <= abs(first.staff.yUpper() - y)) {
            when {
                second.staff.yCenter > first.staff.yCenter -> first to second
                third.staff.yCenter > first.staff.yCenter -> first to third
                else -> first to first
            }
        } else {
            when {
                second.staff.yCenter < first.staff.yCenter -> first to second
                third.staff.yCenter < first.staff.yCenter -> first to third
                else -> first to first
            }
        }
    }

    private fun BoundingBox.centerX(): Int = pythonRound((left + right) / 2.0)
    private fun BoundingBox.centerY(): Int = pythonRound((top + bottom) / 2.0)
    private fun com.sheetsight.app.data.omr.staffline.ZoneStaff.xCenter(): Double =
        lines.map { it.xCenter }.average()
    private fun com.sheetsight.app.data.omr.staffline.ZoneStaff.yUpper(): Int =
        lines.minOf { it.yUpper }
    private fun com.sheetsight.app.data.omr.staffline.ZoneStaff.yLower(): Int =
        lines.maxOf { it.yLower }
    private fun pythonRound(value: Double): Int = round(value).toInt()
}
