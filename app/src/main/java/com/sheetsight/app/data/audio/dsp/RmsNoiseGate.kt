package com.sheetsight.app.data.audio.dsp

import kotlin.math.sqrt

data class RmsGateResult(
    val rms: Double,
    val active: Boolean,
    val openThreshold: Double,
    val closeThreshold: Double,
    val noiseFloor: Double
)

/** Robust RMS gate with separate attack/release thresholds. */
class RmsNoiseGate(
    private val minimumOpenRms: Double = 0.0024,
    private val minimumCloseRms: Double = 0.0016,
    private val openNoiseMultiplier: Double = 2.6,
    private val closeNoiseMultiplier: Double = 1.7,
    private val historySize: Int = 63
) {
    private val quietHistory = ArrayDeque<Double>()
    private var open = false

    fun process(samples: FloatArray, allowNoiseLearning: Boolean = true): RmsGateResult {
        val rms = rms(samples)
        val noise = quietHistory.medianOrNull() ?: minimumCloseRms * 0.5
        val openThreshold = maxOf(minimumOpenRms, noise * openNoiseMultiplier)
        val closeThreshold = maxOf(minimumCloseRms, noise * closeNoiseMultiplier)
        open = if (open) rms >= closeThreshold else rms >= openThreshold
        if (!open && allowNoiseLearning && rms < openThreshold) {
            quietHistory.addLast(rms)
            while (quietHistory.size > historySize) quietHistory.removeFirst()
        }
        return RmsGateResult(rms, open, openThreshold, closeThreshold, noise)
    }

    fun reset() {
        quietHistory.clear()
        open = false
    }

    companion object {
        fun rms(samples: FloatArray): Double {
            if (samples.isEmpty()) return 0.0
            var energy = 0.0
            for (sample in samples) energy += sample * sample
            return sqrt(energy / samples.size)
        }
    }
}

private fun Collection<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) * 0.5
}
