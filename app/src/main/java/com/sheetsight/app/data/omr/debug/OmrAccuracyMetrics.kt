package com.sheetsight.app.data.omr.debug

import kotlin.math.hypot

data class OmrDetectionMetrics(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int
) {
    val precision: Double
        get() = if (truePositives + falsePositives == 0) 0.0
        else truePositives.toDouble() / (truePositives + falsePositives)
    val recall: Double
        get() = if (truePositives + falseNegatives == 0) 0.0
        else truePositives.toDouble() / (truePositives + falseNegatives)
    val f1: Double
        get() = if (precision + recall == 0.0) 0.0
        else 2.0 * precision * recall / (precision + recall)
}

data class OmrLabeledDetectionMetrics(
    val detection: OmrDetectionMetrics,
    val correctlyTypedMatches: Int,
    val matchedCount: Int
) {
    val typeAccuracy: Double
        get() = if (matchedCount == 0) 0.0 else correctlyTypedMatches.toDouble() / matchedCount
}

/** Deterministic one-to-one matcher used by the device evaluation corpus. */
object OmrAccuracyMetrics {
    fun detection(
        expected: List<OmrLocatedDetection>,
        actual: List<OmrLocatedDetection>,
        staffSpacing: Double,
        toleranceInStaffSpaces: Double
    ): OmrDetectionMetrics = labeled(
        expected,
        actual,
        staffSpacing,
        toleranceInStaffSpaces
    ).detection

    fun labeled(
        expected: List<OmrLocatedDetection>,
        actual: List<OmrLocatedDetection>,
        staffSpacing: Double,
        toleranceInStaffSpaces: Double
    ): OmrLabeledDetectionMetrics {
        require(staffSpacing > 0.0)
        require(toleranceInStaffSpaces > 0.0)
        val maximumDistance = staffSpacing * toleranceInStaffSpaces
        val matches = minimumCostMaximumMatching(expected, actual, maximumDistance)
        return OmrLabeledDetectionMetrics(
            detection = OmrDetectionMetrics(
                truePositives = matches.size,
                falsePositives = actual.size - matches.size,
                falseNegatives = expected.size - matches.size
            ),
            correctlyTypedMatches = matches.count {
                expected[it.expectedIndex].label == actual[it.actualIndex].label
            },
            matchedCount = matches.size
        )
    }

    private data class Match(
        val expectedIndex: Int,
        val actualIndex: Int,
        val distance: Double
    )

    /**
     * Finds a maximum-cardinality matching and, among those matchings, the
     * minimum total geometric distance. A nearest-edge greedy pass can lose
     * a true positive in dense chords when one detection is compatible with
     * two references but a second detection is compatible with only one.
     *
     * The square assignment matrix includes one dummy row/column per real
     * detection. Its unmatched penalty is larger than the maximum possible
     * total distance of every real match, so cardinality always dominates
     * distance. The Hungarian solve is deterministic because rows and
     * columns retain source-list order and ties select the lowest column.
     */
    private fun minimumCostMaximumMatching(
        expected: List<OmrLocatedDetection>,
        actual: List<OmrLocatedDetection>,
        maximumDistance: Double
    ): List<Match> {
        if (expected.isEmpty() || actual.isEmpty()) return emptyList()
        val size = expected.size + actual.size
        val unmatchedPenalty = (size + 1) * (maximumDistance + 1.0)
        val forbiddenCost = unmatchedPenalty * (size + 1) * 4.0
        val costs = Array(size) { row ->
            DoubleArray(size) { column ->
                when {
                    row < expected.size && column < actual.size -> {
                        val reference = expected[row]
                        val detected = actual[column]
                        val sameGroup = reference.group == null || detected.group == reference.group
                        val sameTrack = reference.track == null || detected.track == reference.track
                        val distance = hypot(
                            (reference.x - detected.x).toDouble(),
                            (reference.y - detected.y).toDouble()
                        )
                        if (sameGroup && sameTrack && distance <= maximumDistance) {
                            distance
                        } else {
                            forbiddenCost
                        }
                    }
                    row < expected.size || column < actual.size -> unmatchedPenalty
                    else -> 0.0
                }
            }
        }
        val assignedColumnByRow = hungarian(costs)
        return expected.indices.mapNotNull { expectedIndex ->
            val actualIndex = assignedColumnByRow[expectedIndex]
            if (actualIndex !in actual.indices) return@mapNotNull null
            val distance = costs[expectedIndex][actualIndex]
            if (distance >= forbiddenCost) null
            else Match(expectedIndex, actualIndex, distance)
        }
    }

    /** Minimum-cost square assignment; returns the selected column per row. */
    private fun hungarian(costs: Array<DoubleArray>): IntArray {
        val size = costs.size
        require(costs.all { it.size == size }) { "Hungarian cost matrix must be square" }
        val rowPotential = DoubleArray(size + 1)
        val columnPotential = DoubleArray(size + 1)
        val rowForColumn = IntArray(size + 1)
        val previousColumn = IntArray(size + 1)

        for (row in 1..size) {
            rowForColumn[0] = row
            var currentColumn = 0
            val minimumReducedCost = DoubleArray(size + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(size + 1)
            do {
                used[currentColumn] = true
                val currentRow = rowForColumn[currentColumn]
                var delta = Double.POSITIVE_INFINITY
                var nextColumn = 0
                for (column in 1..size) {
                    if (used[column]) continue
                    val reduced = costs[currentRow - 1][column - 1] -
                        rowPotential[currentRow] - columnPotential[column]
                    if (reduced < minimumReducedCost[column]) {
                        minimumReducedCost[column] = reduced
                        previousColumn[column] = currentColumn
                    }
                    if (minimumReducedCost[column] < delta) {
                        delta = minimumReducedCost[column]
                        nextColumn = column
                    }
                }
                for (column in 0..size) {
                    if (used[column]) {
                        rowPotential[rowForColumn[column]] += delta
                        columnPotential[column] -= delta
                    } else {
                        minimumReducedCost[column] -= delta
                    }
                }
                currentColumn = nextColumn
            } while (rowForColumn[currentColumn] != 0)

            do {
                val nextColumn = previousColumn[currentColumn]
                rowForColumn[currentColumn] = rowForColumn[nextColumn]
                currentColumn = nextColumn
            } while (currentColumn != 0)
        }

        val assignedColumnByRow = IntArray(size) { -1 }
        for (column in 1..size) {
            val row = rowForColumn[column]
            if (row != 0) assignedColumnByRow[row - 1] = column - 1
        }
        return assignedColumnByRow
    }
}
