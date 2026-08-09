package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.dewarp.ConnectedComponents
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.BoundingBoxMerger
import com.sheetsight.app.data.omr.track.ConnectedComponentBoxExtractor
import com.sheetsight.app.data.omr.track.HoughLine
import com.sheetsight.app.data.omr.track.HoughLineDetector
import com.sheetsight.app.data.omr.track.StaffGeometryResolver
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Port of oemer 0.1.8
 * `symbol_extraction.py::parse_barlines()`/`filter_barlines()`.
 *
 * Barlines are geometric: no trained barline classifier exists in the
 * oemer distribution. Note-group pixels are removed, unused straight-line
 * candidates are overlapped with the independent model-one generic-symbol
 * components, and probabilistic Hough segments are
 * filtered and consolidated exactly before staff-group assignment.
 *
 * **Safe empty-input deviation:** oemer calls `np.max` on an empty line
 * set and raises. This port returns an empty result because absence of a
 * barline is valid page content and is not a classification guess.
 */
object MusicalBarlineExtractor {
    private const val MIN_LINE_DEGREES = 75.0
    private const val MIN_HEIGHT_UNIT_RATIO = 3.75
    private const val NORMALIZED_HEIGHT_THRESHOLD = 0.5
    private const val RENDER_PADDING = 10

    /** Extracts barlines from dewarped oemer masks. */
    fun extract(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): List<MusicalBarlineCandidate> = extractWithDiagnostics(
        groupMap,
        stemsRests,
        symbols,
        width,
        height,
        horizontalBounds,
        staffGrid
    ).candidates

    /** Same recognition path as [extract], plus small image-free filter evidence. */
    fun extractWithDiagnostics(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): MusicalBarlineExtractionResult {
        validateInputs(groupMap, stemsRests, symbols, width, height)
        val houghMask = selectOverlappingSymbolComponents(
            groupMap,
            stemsRests,
            symbols,
            width,
            height
        )
        val lines = HoughLineDetector.detect(houghMask, width, height)
        val diagnostics = filterLines(lines, horizontalBounds, staffGrid).copy(
            selectedOverlapPixelCount = houghMask.count { it }
        )
        val candidates = diagnostics.acceptedBoxes.map { box ->
            val center = box.center()
            val staff = StaffGeometryResolver.closestPair(staffGrid, center.first, center.second).first
            MusicalBarlineCandidate(box, staff.group)
        }
        return MusicalBarlineExtractionResult(candidates, diagnostics)
    }

    internal fun selectOverlappingSymbolComponents(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val stemForeground = IntArray(width * height) { index ->
            if (stemsRests[index] && groupMap[index] < 0) 0 else -1
        }
        val symbolForeground = IntArray(width * height) { index ->
            if (symbols[index] && groupMap[index] < 0) 0 else -1
        }
        val stemLabels = ConnectedComponents.label(stemForeground, width, height)
        val symbolLabels = ConnectedComponents.label(symbolForeground, width, height)
        val selectedSymbolLabels = mutableSetOf<Int>()
        stemLabels.forEachIndexed { index, stemLabel ->
            if (stemLabel > 0 && symbolLabels[index] > 0) {
                selectedSymbolLabels += symbolLabels[index]
            }
        }
        return BooleanArray(width * height) { symbolLabels[it] in selectedSymbolLabels }
    }

    private fun filterLines(
        lines: List<HoughLine>,
        horizontalBounds: IntRange,
        staffGrid: List<List<AssignedStaff>>
    ): MusicalBarlineDiagnostics {
        if (lines.isEmpty()) return MusicalBarlineDiagnostics()
        val inRange = lines.map(::toBox).filter { it.center().first in horizontalBounds }
        val merged = BoundingBoxMerger.resolveOverlaps(inRange, merge = true, overlapRatio = 0.0)
        val validLines = merged.filter { lineDegrees(it) >= MIN_LINE_DEGREES }
        if (validLines.isEmpty()) {
            return MusicalBarlineDiagnostics(
                rawHoughLines = lines,
                horizontallyAcceptedBoxes = inRange,
                mergedBoxes = merged
            )
        }
        val lastCenter = validLines.last().center()
        val unitSize = StaffGeometryResolver.unitSizeAt(
            staffGrid,
            lastCenter.first,
            lastCenter.second
        )
        val consolidated = renderAndExtract(validLines)
        val heightAccepted = consolidated
            .filter { it.height >= unitSize * MIN_HEIGHT_UNIT_RATIO }
            .sortedBy { it.height }
        if (heightAccepted.isEmpty()) {
            return MusicalBarlineDiagnostics(
                rawHoughLines = lines,
                horizontallyAcceptedBoxes = inRange,
                mergedBoxes = merged,
                angleAcceptedBoxes = validLines,
                consolidatedBoxes = consolidated
            )
        }
        val referenceHeight = heightAccepted.takeLast(5).map { it.height }.average()
        val accepted = heightAccepted.filter {
            it.height / referenceHeight > NORMALIZED_HEIGHT_THRESHOLD
        }
        return MusicalBarlineDiagnostics(
            rawHoughLines = lines,
            horizontallyAcceptedBoxes = inRange,
            mergedBoxes = merged,
            angleAcceptedBoxes = validLines,
            consolidatedBoxes = consolidated,
            heightAcceptedBoxes = heightAccepted,
            acceptedBoxes = accepted,
            referenceHeight = referenceHeight
        )
    }

    private fun renderAndExtract(lines: List<BoundingBox>): List<BoundingBox> {
        val width = lines.maxOf { it.right } + RENDER_PADDING
        val height = lines.maxOf { it.bottom } + RENDER_PADDING
        val rendered = Mat.zeros(height, width, CvType.CV_64FC1)
        try {
            lines.forEach { line ->
                Imgproc.line(
                    rendered,
                    Point(line.left.toDouble(), line.top.toDouble()),
                    Point(line.right.toDouble(), line.bottom.toDouble()),
                    Scalar(255.0),
                    1,
                    Imgproc.LINE_AA
                )
            }
            val mask = BooleanArray(width * height)
            val row = DoubleArray(width)
            for (y in 0 until height) {
                rendered.get(y, 0, row)
                for (x in 0 until width) mask[y * width + x] = row[x] > 0.0
            }
            return ConnectedComponentBoxExtractor.extract(mask, width, height)
        } finally {
            rendered.release()
        }
    }

    private fun lineDegrees(box: BoundingBox): Double =
        abs(Math.toDegrees(atan2((box.bottom - box.top).toDouble(), (box.right - box.left).toDouble())))

    private fun toBox(line: HoughLine): BoundingBox =
        BoundingBox(line.topX, line.topY, line.btX, line.btY)

    private fun validateInputs(
        groupMap: IntArray,
        stemsRests: BooleanArray,
        symbols: BooleanArray,
        width: Int,
        height: Int
    ) {
        val expectedSize = width * height
        require(groupMap.size == expectedSize)
        require(stemsRests.size == expectedSize)
        require(symbols.size == expectedSize)
        require(width > 0 && height > 0)
    }
}

data class MusicalBarlineExtractionResult(
    val candidates: List<MusicalBarlineCandidate>,
    val diagnostics: MusicalBarlineDiagnostics
)

/** Candidate counts/boxes after each official oemer filter; no page-sized data is retained. */
data class MusicalBarlineDiagnostics(
    val selectedOverlapPixelCount: Int = 0,
    val rawHoughLines: List<HoughLine> = emptyList(),
    val horizontallyAcceptedBoxes: List<BoundingBox> = emptyList(),
    val mergedBoxes: List<BoundingBox> = emptyList(),
    val angleAcceptedBoxes: List<BoundingBox> = emptyList(),
    val consolidatedBoxes: List<BoundingBox> = emptyList(),
    val heightAcceptedBoxes: List<BoundingBox> = emptyList(),
    val acceptedBoxes: List<BoundingBox> = emptyList(),
    val referenceHeight: Double? = null
)

internal fun BoundingBox.center(): Pair<Int, Int> =
    Math.rint((left + right) / 2.0).toInt() to
            Math.rint((top + bottom) / 2.0).toInt()
