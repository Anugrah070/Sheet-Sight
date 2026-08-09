package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.ZoneStaff

/**
 * Conservative fallback for pages whose system-joining barlines are too
 * broken to satisfy [TrackVotingLoop]. Repeated multi-staff systems have a
 * distinctive vertical rhythm: gaps inside a system are consistently much
 * smaller than the gaps between systems.
 *
 * A candidate is accepted only when the typical inter-system gap is clearly
 * larger than the typical intra-system gap. Medians are deliberate: page
 * breaks and one unusually tight system must not hide an otherwise repeated
 * grand-staff pattern. The final system may be incomplete because a globally
 * missed last staff is exactly one of the failure modes this fallback must
 * tolerate. Ambiguous layouts remain single-track.
 */
object StaffGeometryTrackInferer {
    private const val MAX_TRACKS = 9
    private const val MIN_GAP_SEPARATION = 1.25

    fun infer(staffGrid: List<List<ZoneStaff>>): Int {
        val rowCount = staffGrid.maxOfOrNull { it.size } ?: return 1
        if (rowCount < 4) return 1

        val centers = DoubleArray(rowCount) { row ->
            median(staffGrid.mapNotNull { it.getOrNull(row)?.yCenter })
        }
        val gaps = List(rowCount - 1) { index -> centers[index + 1] - centers[index] }
        if (gaps.any { it <= 0.0 }) return 1

        for (tracks in 2..minOf(MAX_TRACKS, rowCount / 2)) {
            val intra = gaps.filterIndexed { index, _ -> (index + 1) % tracks != 0 }
            val inter = gaps.filterIndexed { index, _ -> (index + 1) % tracks == 0 }
            if (intra.isEmpty() || inter.isEmpty()) continue
            val separation = median(inter) / median(intra)
            // Prefer the smallest repeating grouping that explains the page.
            // Larger candidates have fewer boundaries and can otherwise
            // overfit a single page-break outlier in a two-staff score.
            if (separation > MIN_GAP_SEPARATION) return tracks
        }
        return 1
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
