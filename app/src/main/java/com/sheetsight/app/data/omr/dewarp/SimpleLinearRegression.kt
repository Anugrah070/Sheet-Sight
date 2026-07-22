package com.sheetsight.app.data.omr.dewarp

/**
 * Ordinary least-squares fit for a single feature, matching what
 * `sklearn.linear_model.LinearRegression().fit(x, y).predict(...)` does
 * for the 1-D inputs [StafflineGridBridger] uses it for.
 */
internal class SimpleLinearRegression private constructor(
    private val slope: Double,
    private val intercept: Double
) {
    fun predict(x: Double): Double = slope * x + intercept

    companion object {
        /** Fits `y = slope*x + intercept`. Requires at least 2 points. */
        fun fit(xs: DoubleArray, ys: DoubleArray): SimpleLinearRegression {
            require(xs.size == ys.size && xs.size >= 2) {
                "Need at least 2 matching (x, y) points to fit a line, got ${xs.size} x's and ${ys.size} y's"
            }
            val meanX = xs.average()
            val meanY = ys.average()
            var numerator = 0.0
            var denominator = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - meanX
                numerator += dx * (ys[i] - meanY)
                denominator += dx * dx
            }
            // All x's identical (denominator=0) -> no meaningful slope; treat as flat.
            val slope = if (denominator == 0.0) 0.0 else numerator / denominator
            val intercept = meanY - slope * meanX
            return SimpleLinearRegression(slope, intercept)
        }
    }
}