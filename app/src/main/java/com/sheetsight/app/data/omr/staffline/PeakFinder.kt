package com.sheetsight.app.data.omr.staffline

/**
 * Pure-Kotlin port of `scipy.signal.find_peaks` restricted to the three
 * conditions oemer's `staffline_extraction.py::extract_line` actually
 * uses: `height`, `distance`, and `prominence`. `threshold`, `width` and
 * `plateau_size` are intentionally not ported (oemer never passes them).
 *
 * The port reproduces scipy's *exact* internals, in scipy's own order:
 *  1. `_local_maxima_1d` — local maxima, with plateaus reported at their
 *     midpoint (`(left+right)/2`, integer division). The first and last
 *     samples can never be peaks.
 *  2. **height** filter (`x[peak] >= height`).
 *  3. **distance** filter (`_select_by_peak_distance`) — highest peak
 *     first, remove any surviving neighbour strictly within
 *     `ceil(distance)`, iterate down by height.
 *  4. **prominence** filter (`_peak_prominences` then `>= prominence`).
 *
 * Verified against scipy 1.17.1: 0 mismatches over 5000 randomised
 * continuous inputs with `height=0.8, distance=8, prominence=1` (the
 * exact call oemer makes). See [PeakFinderTest] for scipy-derived golden
 * vectors baked in as regression fixtures.
 *
 * **One documented deviation — distance tie-break.** scipy's distance
 * stage orders peaks by `np.argsort(height)`, whose tie order for
 * *exactly equal* heights is unstable (numpy quicksort, implementation
 * defined). This port uses a deterministic stable order instead (by
 * height ascending, then peak index ascending, processed highest-first).
 * The two only ever differ when two peaks have bit-identical heights —
 * a measure-zero event on the z-scored, continuous row-density profile
 * oemer feeds this function, which is why the 5000-case continuous fuzz
 * matches exactly. On synthetic integer data with manufactured ties the
 * orders can diverge; that input never occurs in the real pipeline.
 */
object PeakFinder {

    /**
     * Returns the indices of peaks in [x] satisfying all supplied
     * conditions, in ascending index order (same as scipy). [height] and
     * [prominence] are ignored when null; [distance] must be `>= 1` and
     * is compared as `ceil(distance)` exactly like scipy (so an integer
     * [distance] is already ceil-ed).
     */
    fun findPeaks(
        x: FloatArray,
        height: Float? = null,
        distance: Int = 1,
        prominence: Float? = null
    ): IntArray {
        require(distance >= 1) { "distance must be >= 1, was $distance" }

        var peaks = localMaxima1d(x)
        if (height != null) peaks = peaks.filter { x[it] >= height }
        if (distance > 1) peaks = selectByDistance(peaks, x, distance)
        if (prominence != null) {
            val proms = prominences(x, peaks)
            peaks = peaks.filterIndexed { i, _ -> proms[i] >= prominence }
        }
        return peaks.toIntArray()
    }

    /** Prominence of every peak in [peaks], same definition as `scipy.signal.peak_prominences` (wlen unbounded). */
    fun prominences(x: FloatArray, peaks: List<Int>): FloatArray = FloatArray(peaks.size) { idx ->
        val peak = peaks[idx]
        val peakVal = x[peak]

        var leftMin = peakVal
        var i = peak
        while (i >= 0 && x[i] <= peakVal) {
            if (x[i] < leftMin) leftMin = x[i]
            i--
        }

        var rightMin = peakVal
        i = peak
        while (i < x.size && x[i] <= peakVal) {
            if (x[i] < rightMin) rightMin = x[i]
            i++
        }

        peakVal - maxOf(leftMin, rightMin)
    }

    /** `scipy._local_maxima_1d`: strict local maxima, plateaus at their integer midpoint, edges excluded. */
    internal fun localMaxima1d(x: FloatArray): List<Int> {
        val peaks = mutableListOf<Int>()
        val n = x.size
        var i = 1
        while (i < n - 1) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < n - 1 && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    peaks.add((i + ahead - 1) / 2) // plateau midpoint (left..ahead-1)
                    i = ahead
                }
            }
            i++
        }
        return peaks
    }

    /**
     * `scipy._select_by_peak_distance`. Keeps the highest peak, drops any
     * other peak strictly within [distance] of a kept peak, iterating from
     * highest to lowest. See the class KDoc for the tie-break deviation.
     */
    internal fun selectByDistance(peaks: List<Int>, x: FloatArray, distance: Int): List<Int> {
        val n = peaks.size
        if (n == 0) return peaks
        val keep = BooleanArray(n) { true }
        // Ascending by (height, index); process highest-priority (end) first.
        val order = (0 until n).sortedWith(compareBy({ x[peaks[it]] }, { it }))
        for (oi in order.indices.reversed()) {
            val j = order[oi]
            if (!keep[j]) continue
            var k = j - 1
            while (k >= 0 && peaks[j] - peaks[k] < distance) {
                keep[k] = false
                k--
            }
            k = j + 1
            while (k < n && peaks[k] - peaks[j] < distance) {
                keep[k] = false
                k++
            }
        }
        return (0 until n).filter { keep[it] }.map { peaks[it] }
    }
}