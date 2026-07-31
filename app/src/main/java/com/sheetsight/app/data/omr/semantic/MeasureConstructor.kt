package com.sheetsight.app.data.omr.semantic

data class DetectedMeasureBarline(
    val x: Int,
    val source: SemanticSourceRef
)

/** Builds only evidence-backed intervals: staff extents plus detected barlines. */
object MeasureConstructor {
    fun construct(
        systemLeft: Int,
        systemRight: Int,
        barlines: List<DetectedMeasureBarline>
    ): List<SemanticMeasureBoundary> {
        if (systemLeft >= systemRight) return emptyList()

        val ordered = barlines
            .filter { it.x in systemLeft..systemRight }
            .sortedWith(compareBy<DetectedMeasureBarline> { it.x }.thenBy { it.source.id })
            .distinctBy { it.x }

        val interior = ordered.filter { it.x > systemLeft && it.x < systemRight }
        val atLeft = ordered.lastOrNull { it.x == systemLeft }
        val atRight = ordered.firstOrNull { it.x == systemRight }
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
}

