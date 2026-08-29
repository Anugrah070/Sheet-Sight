package com.sheetsight.app.data.omr.semantic

data class DetectedMeasureBarline(
    val x: Int,
    val source: SemanticSourceRef,
    val confidence: Double = 1.0
)

/** Builds only evidence-backed intervals: staff extents plus detected barlines. */
object MeasureConstructor {
    fun construct(
        systemLeft: Int,
        systemRight: Int,
        barlines: List<DetectedMeasureBarline>,
        /** Local staff-space-relative tolerance supplied by the system assembler. */
        xTolerance: Double = 0.0
    ): List<SemanticMeasureBoundary> {
        if (systemLeft >= systemRight) return emptyList()
        require(xTolerance >= 0.0)

        val ordered = barlines
            .filter { it.x in systemLeft..systemRight }
            .sortedWith(compareBy<DetectedMeasureBarline> { it.x }.thenBy { it.source.id })
        val clustered = clusterByX(ordered, xTolerance)
        val edgeNormalized = clusterByX(
            clustered.map { barline ->
                when {
                    kotlin.math.abs(barline.x - systemLeft) <= xTolerance ->
                        barline.copy(x = systemLeft)
                    kotlin.math.abs(barline.x - systemRight) <= xTolerance ->
                        barline.copy(x = systemRight)
                    else -> barline
                }
            }.sortedWith(compareBy<DetectedMeasureBarline> { it.x }.thenBy { it.source.id }),
            0.0
        )

        val interior = edgeNormalized.filter { it.x > systemLeft && it.x < systemRight }
        val atLeft = edgeNormalized.lastOrNull { it.x == systemLeft }
        val atRight = edgeNormalized.firstOrNull { it.x == systemRight }
        val points = buildList {
            add(systemLeft to atLeft)
            interior.forEach { add(it.x to it) }
            add(systemRight to atRight)
        }

        return points.zipWithNext().map { (left, right) ->
            SemanticMeasureBoundary(
                left = left.first,
                right = right.first,
                leftEvidence = if (left.second == null) {
                    MeasureBoundaryEvidence.STAFF_EXTENT
                } else {
                    MeasureBoundaryEvidence.DETECTED_BARLINE
                },
                rightEvidence = if (right.second == null) {
                    MeasureBoundaryEvidence.STAFF_EXTENT
                } else {
                    MeasureBoundaryEvidence.DETECTED_BARLINE
                },
                leftSource = left.second?.source,
                rightSource = right.second?.source
            )
        }
    }

    /**
     * Consolidates fragmented/aligned treble+bass evidence without using a
     * raw-pixel constant. The strongest source owns provenance; ties are
     * deterministic and keep the left-most detection.
     */
    private fun clusterByX(
        ordered: List<DetectedMeasureBarline>,
        xTolerance: Double
    ): List<DetectedMeasureBarline> {
        if (ordered.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<DetectedMeasureBarline>>()
        ordered.forEach { candidate ->
            val current = clusters.lastOrNull()
            val anchor = current?.map { it.x }?.average()
            if (
                current == null ||
                (kotlin.math.abs(candidate.x - requireNotNull(anchor)) > xTolerance &&
                    current.none { samePhysicalBarline(it, candidate) })
            ) {
                clusters += mutableListOf(candidate)
            } else {
                current += candidate
            }
        }
        return clusters.map { cluster ->
            cluster.maxWith(
                compareBy<DetectedMeasureBarline> { it.confidence }
                    .thenBy { -it.x }
                    .thenByDescending { it.source.id }
            )
        }
    }

    /** Exact centers or overlapping source boxes are duplicate evidence; no distance threshold is guessed. */
    private fun samePhysicalBarline(
        left: DetectedMeasureBarline,
        right: DetectedMeasureBarline
    ): Boolean {
        if (left.x == right.x) return true
        val leftBounds = left.source.bounds ?: return false
        val rightBounds = right.source.bounds ?: return false
        return maxOf(leftBounds.left, rightBounds.left) < minOf(leftBounds.right, rightBounds.right) &&
            maxOf(leftBounds.top, rightBounds.top) < minOf(leftBounds.bottom, rightBounds.bottom)
    }
}
