package com.sheetsight.app.data.omr.staffline

import com.sheetsight.app.data.omr.dewarp.DewarpedPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Port of the single-zone core of oemer 0.1.8
 * `staffline_extraction.py` — `extract_line` + `filter_line_peaks` plus
 * per-line pixel assignment — operating on one vertical zone of the
 * dewarped staff mask ([DewarpedPage.masks].staff).
 *
 * Pipeline for a zone spanning columns `[zoneLeft, zoneRight)`:
 *  1. **Row-density histogram.** For each image row, count staff-mask
 *     foreground pixels within the zone (`np.sum(staff, axis=1)`).
 *  2. **Z-score normalization.** `(row - mean) / std` over the zone's
 *     rows. A zero-std (blank/uniform) zone yields no staff — the
 *     graceful-degradation path.
 *  3. **Peak detection.** [PeakFinder.findPeaks] with oemer's exact
 *     `height=0.8, distance=8, prominence=1`. This step is verified
 *     bit-for-bit against scipy (see [PeakFinder]).
 *  4. **`filter_line_peaks`.** Reject peaks above [MAX_PEAK_NORM];
 *     estimate `approx_unit` from the smallest ~20% of peak gaps; split
 *     into groups wherever a gap exceeds `1.5 * approx_unit`; drop groups
 *     with fewer than five lines; for groups with more than five, keep
 *     the head-five or tail-five with the greater summed peak strength.
 *  5. **Pixel assignment.** Each of the five peaks in a valid group
 *     collects the zone's foreground pixels whose nearest peak center is
 *     that peak and lies within [maxGap], forming a [Staffline]; lines
 *     are labelled FIRST..FIFTH top-to-bottom, matching oemer's LineLabel
 *     (FIRST = smallest y / topmost line, FIFTH = largest y / bottommost).
 *
 * **Fidelity note.** The peak-gap sample size is verified against the
 * official oemer 0.1.8 wheel (SHA-256
 * `713b9c06e1ade3fa0c5f9fa2c396cbb1492ce6be30d0bd25d57601af1eb32917`),
 * `staffline_extraction.py::filter_line_peaks()`: source uses
 * `max(5, round(len(peaks) * 0.2))`, so even a short piano fixture averages
 * at least five gaps before applying the 1.5× grouping threshold. Source
 * also selects the tail five peaks when head/tail strengths tie. Using
 * only one or two smallest gaps can discard one row of a grand staff when
 * close nuisance peaks are present; resolving a tie toward the head shifts
 * the retained staff geometry by one nuisance peak.
 *
 * The FIRST/FIFTH label direction *has* since been verified directly
 * against the real oemer 0.1.8 wheel (Phase 4.6E-A analysis): oemer sorts
 * lines ascending by `y_center` before assigning `LineLabel.FIRST..FIFTH`,
 * so FIRST is the topmost line. The step-5 assignment below was corrected
 * to match (previously it labelled bottom-to-top).
 */
object ZoneStafflineExtractor {

    const val PEAK_HEIGHT = 0.8f
    const val PEAK_DISTANCE = 8
    const val PEAK_PROMINENCE = 1.0f

    /** Reject peaks whose z-scored row density exceeds this (analysis: `norm > 15`). */
    const val MAX_PEAK_NORM = 15.0f

    /** Fraction of the smallest peak gaps averaged to estimate the staff unit size. */
    const val UNIT_GAP_FRACTION = 0.2

    /** Peaks separated by more than this multiple of the unit size start a new group. */
    const val GROUP_GAP_FACTOR = 1.5

    private const val LINES_PER_STAFF = 5

    /** Convenience overload extracting one zone from a [DewarpedPage]. */
    fun extract(page: DewarpedPage, zoneLeft: Int, zoneRight: Int): ZoneStafflineResult =
        extract(page.masks.staff, page.width, page.height, zoneLeft, zoneRight)

    fun extract(
        staffMask: BooleanArray,
        width: Int,
        height: Int,
        zoneLeft: Int,
        zoneRight: Int
    ): ZoneStafflineResult {
        require(staffMask.size == width * height) { "staffMask size ${staffMask.size} != ${width}x$height" }
        require(zoneLeft in 0..zoneRight && zoneRight <= width) { "invalid zone [$zoneLeft, $zoneRight) for width $width" }

        if (height <= 0 || zoneRight - zoneLeft <= 0) {
            return ZoneStafflineResult(zoneLeft, zoneRight, emptyList())
        }

        val norm = zScoredRowDensity(staffMask, width, height, zoneLeft, zoneRight)
            ?: return ZoneStafflineResult(zoneLeft, zoneRight, emptyList())

        val peaks = PeakFinder.findPeaks(norm, PEAK_HEIGHT, PEAK_DISTANCE, PEAK_PROMINENCE)
            .filter { norm[it] <= MAX_PEAK_NORM }

        if (peaks.size < LINES_PER_STAFF) return ZoneStafflineResult(zoneLeft, zoneRight, emptyList())

        val approxUnit = approxUnit(peaks)
        val groups = groupPeaks(peaks, approxUnit)
        val validCenters = groups.mapNotNull { pickFiveLines(it, norm) }

        val maxGap = max(1, (approxUnit / 2.0).roundToInt())
        val staffs = validCenters.map { centers ->
            buildStaff(centers, staffMask, width, height, zoneLeft, zoneRight, maxGap)
        }
        return ZoneStafflineResult(zoneLeft, zoneRight, staffs)
    }

    /** Per-row foreground counts in the zone, z-scored; null if the zone has zero variance. */
    private fun zScoredRowDensity(
        staffMask: BooleanArray,
        width: Int,
        height: Int,
        zoneLeft: Int,
        zoneRight: Int
    ): FloatArray? {
        val counts = DoubleArray(height)
        for (y in 0 until height) {
            val rowBase = y * width
            var c = 0
            for (x in zoneLeft until zoneRight) if (staffMask[rowBase + x]) c++
            counts[y] = c.toDouble()
        }
        val mean = counts.average()
        val variance = counts.sumOf { (it - mean) * (it - mean) } / counts.size // population std, matching numpy default
        val std = sqrt(variance)
        if (std == 0.0) return null
        return FloatArray(height) { ((counts[it] - mean) / std).toFloat() }
    }

    /**
     * Mean of the smallest consecutive peak gaps, using oemer 0.1.8
     * `filter_line_peaks()`'s exact `max(5, round(len(peaks) * 0.2))`
     * sample-count rule. `take()` intentionally accepts fewer than five
     * available gaps, matching NumPy slicing past the array length.
     */
    private fun approxUnit(peaks: List<Int>): Double {
        val gaps = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]).toDouble() }.sorted()
        val take = max(5, round(peaks.size * UNIT_GAP_FRACTION).toInt())
        return gaps.take(take).average()
    }

    /** Split ascending [peaks] wherever the gap to the previous peak exceeds `1.5 * approxUnit`. */
    private fun groupPeaks(peaks: List<Int>, approxUnit: Double): List<List<Int>> {
        val threshold = GROUP_GAP_FACTOR * approxUnit
        val groups = mutableListOf<MutableList<Int>>()
        for (p in peaks) {
            val last = groups.lastOrNull()
            if (last == null || p - last.last() > threshold) {
                groups.add(mutableListOf(p))
            } else {
                last.add(p)
            }
        }
        return groups
    }

    /**
     * Reduce a group to exactly five line centers, or null to drop it.
     * Fewer than five lines: dropped. Exactly five: kept. More than five:
     * keep whichever contiguous end run (head-five or tail-five) has the
     * greater summed peak strength ([norm] value at each peak).
     */
    private fun pickFiveLines(group: List<Int>, norm: FloatArray): List<Int>? = when {
        group.size < LINES_PER_STAFF -> null
        group.size == LINES_PER_STAFF -> group
        else -> {
            val head = group.take(LINES_PER_STAFF)
            val tail = group.takeLast(LINES_PER_STAFF)
            val headStrength = head.sumOf { norm[it].toDouble() }
            val tailStrength = tail.sumOf { norm[it].toDouble() }
            if (headStrength > tailStrength) head else tail
        }
    }

    /** Assign zone foreground pixels to their nearest of the five [centers] (within [maxGap]) and label the lines. */
    private fun buildStaff(
        centers: List<Int>,
        staffMask: BooleanArray,
        width: Int,
        height: Int,
        zoneLeft: Int,
        zoneRight: Int,
        maxGap: Int
    ): ZoneStaff {
        val sortedCenters = centers.sorted()
        val buckets = List(LINES_PER_STAFF) { mutableListOf<StafflinePoint>() }
        for (y in 0 until height) {
            var nearest = -1
            var bestDist = Int.MAX_VALUE
            for (i in sortedCenters.indices) {
                val d = abs(y - sortedCenters[i])
                if (d < bestDist) { bestDist = d; nearest = i }
            }
            if (nearest < 0 || bestDist > maxGap) continue
            val rowBase = y * width
            for (x in zoneLeft until zoneRight) {
                if (staffMask[rowBase + x]) buckets[nearest].add(StafflinePoint(x, y))
            }
        }
        // A center with no assigned pixels still yields a line anchored at its center row,
        // so the staff always has five lines (assignment can miss a thin line at zone edges).
        val pointsPerCenter = buckets.mapIndexed { i, pts ->
            pts.ifEmpty { listOf(StafflinePoint(zoneLeft, sortedCenters[i])) }
        }
        // oemer's LineLabel is top-to-bottom: FIRST = top line (smallest y), FIFTH = bottom.
        val positions = StafflinePosition.values()
        val lines = pointsPerCenter
            .sortedBy { pts -> pts.sumOf { it.y }.toDouble() / pts.size } // top (smallest y) first
            .mapIndexed { i, pts -> Staffline(positions[i], pts) }
        return ZoneStaff(lines)
    }
}
