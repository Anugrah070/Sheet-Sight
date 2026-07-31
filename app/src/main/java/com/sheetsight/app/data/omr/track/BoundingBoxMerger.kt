package com.sheetsight.app.data.omr.track

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Shared bounding-box operations ported from oemer 0.1.8 `bbox.py`.
 *
 * [mergeNearbyWard] reproduces `AgglomerativeClustering(linkage="ward",
 * distance_threshold=...)` over box centers. [resolveOverlaps] reproduces
 * `rm_merge_overlap_bbox()` without allocating its page-sized label mask;
 * rectangular intersection area is equivalent to counting that mask.
 */
object BoundingBoxMerger {

    /** Ward-merges box centers while the next linkage distance is below [distance]. */
    fun mergeNearbyWard(
        boxes: List<BoundingBox>,
        distance: Double
    ): List<BoundingBox> {
        if (boxes.size < 2) return boxes
        val clusters = boxes.mapIndexed { index, box -> Cluster(listOf(index), box.centerX, box.centerY) }
            .toMutableList()
        while (clusters.size > 1) {
            val pair = closestPair(clusters)
            if (pair.distance >= distance) break
            val right = clusters.removeAt(pair.rightIndex)
            val left = clusters.removeAt(pair.leftIndex)
            clusters += left.merge(right)
        }
        return clusters.sortedBy { it.members.min() }.map { cluster ->
            union(cluster.members.map(boxes::get))
        }
    }

    /**
     * Removes smaller overlapping boxes, or unions them into the earlier
     * larger box when [merge] is true.
     */
    fun resolveOverlaps(
        boxes: List<BoundingBox>,
        merge: Boolean = false,
        overlapRatio: Double = 0.5
    ): List<BoundingBox> {
        val accepted = mutableListOf<BoundingBox>()
        for (candidate in boxes.sortedByDescending { it.area }) {
            val matchIndex = accepted.indexOfFirst { acceptedBox ->
                intersectionArea(candidate, acceptedBox).toDouble() / candidate.area > overlapRatio
            }
            when {
                matchIndex < 0 -> accepted += candidate
                merge -> accepted[matchIndex] = union(listOf(accepted[matchIndex], candidate))
            }
        }
        return accepted
    }

    private fun closestPair(clusters: List<Cluster>): ClusterPair {
        var result = ClusterPair(0, 1, Double.POSITIVE_INFINITY)
        for (leftIndex in 0 until clusters.lastIndex) {
            for (rightIndex in leftIndex + 1 until clusters.size) {
                val distance = clusters[leftIndex].wardDistance(clusters[rightIndex])
                if (distance < result.distance) {
                    result = ClusterPair(leftIndex, rightIndex, distance)
                }
            }
        }
        return result
    }

    private fun intersectionArea(left: BoundingBox, right: BoundingBox): Int =
        maxOf(0, minOf(left.right, right.right) - maxOf(left.left, right.left)) *
                maxOf(0, minOf(left.bottom, right.bottom) - maxOf(left.top, right.top))

    private fun union(boxes: List<BoundingBox>): BoundingBox =
        BoundingBox(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom }
        )

    private data class Cluster(
        val members: List<Int>,
        val centerX: Double,
        val centerY: Double
    ) {
        val size: Int get() = members.size

        fun wardDistance(other: Cluster): Double {
            val scale = sqrt(2.0 * size * other.size / (size + other.size))
            return scale * hypot(centerX - other.centerX, centerY - other.centerY)
        }

        fun merge(other: Cluster): Cluster {
            val total = size + other.size
            return Cluster(
                members = members + other.members,
                centerX = (centerX * size + other.centerX * other.size) / total,
                centerY = (centerY * size + other.centerY * other.size) / total
            )
        }
    }

    private data class ClusterPair(
        val leftIndex: Int,
        val rightIndex: Int,
        val distance: Double
    )
}

private val BoundingBox.area: Int
    get() = width * height

private val BoundingBox.centerX: Double
    get() = (left + right) / 2.0

private val BoundingBox.centerY: Double
    get() = (top + bottom) / 2.0
