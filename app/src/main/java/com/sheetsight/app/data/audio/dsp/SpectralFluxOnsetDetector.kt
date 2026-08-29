package com.sheetsight.app.data.audio.dsp

import kotlin.math.abs
import kotlin.math.ln1p

data class OnsetResult(val onset: Boolean, val relativeFlux: Double, val threshold: Double)

/** Positive log-spectral-flux onset detector with a robust adaptive threshold. */
class SpectralFluxOnsetDetector(
    private val minimumFlux: Double = 0.075,
    private val madMultiplier: Double = 4.0,
    private val refractoryMillis: Long = 80L,
    private val historySize: Int = 63
) {
    private var previous: DoubleArray? = null
    private val fluxHistory = ArrayDeque<Double>()
    private var lastOnsetMillis = Long.MIN_VALUE

    fun process(powerSpectrum: DoubleArray, signalActive: Boolean, timestampMillis: Long): OnsetResult {
        val current = DoubleArray(powerSpectrum.size) { ln1p(powerSpectrum[it] * LOG_GAIN) }
        val prior = previous
        previous = current
        if (prior == null) return OnsetResult(false, 0.0, minimumFlux)

        var positive = 0.0
        var priorEnergy = 0.0
        for (index in current.indices) {
            positive += (current[index] - prior[index]).coerceAtLeast(0.0)
            priorEnergy += prior[index]
        }
        val flux = positive / (priorEnergy + 1e-9)
        val median = fluxHistory.percentile(0.5)
        val mad = fluxHistory.map { abs(it - median) }.percentile(0.5)
        val threshold = maxOf(minimumFlux, median + madMultiplier * mad + ADAPTIVE_OFFSET)
        val outsideRefractory = lastOnsetMillis == Long.MIN_VALUE ||
            timestampMillis - lastOnsetMillis >= refractoryMillis
        val onset = signalActive && outsideRefractory && flux >= threshold
        if (onset) lastOnsetMillis = timestampMillis
        if (!onset || flux < threshold * 1.5) {
            fluxHistory.addLast(flux.coerceIn(0.0, 4.0))
            while (fluxHistory.size > historySize) fluxHistory.removeFirst()
        }
        return OnsetResult(onset, flux, threshold)
    }

    fun reset() {
        previous = null
        fluxHistory.clear()
        lastOnsetMillis = Long.MIN_VALUE
    }

    private fun Collection<Double>.percentile(fraction: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val position = fraction.coerceIn(0.0, 1.0) * sorted.lastIndex
        val low = position.toInt()
        val high = kotlin.math.ceil(position).toInt()
        if (low == high) return sorted[low]
        return sorted[low] * (high - position) + sorted[high] * (position - low)
    }

    private companion object {
        const val LOG_GAIN = 1_000_000.0
        const val ADAPTIVE_OFFSET = 0.012
    }
}
