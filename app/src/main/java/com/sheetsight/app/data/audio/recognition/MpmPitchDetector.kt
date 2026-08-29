package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.data.audio.PitchFrame
import com.sheetsight.app.data.audio.dsp.RmsNoiseGate
import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

data class MpmPitchConfig(
    val sampleRateHz: Int = 22_050,
    val minimumFrequencyHz: Double = 27.5,
    val maximumFrequencyHz: Double = 4_186.01,
    val peakCutoff: Double = 0.93,
    val minimumClarity: Double = 0.72,
    val minimumRms: Double = 0.0018,
    val targetPeriods: Double = 8.0
) {
    init {
        require(sampleRateHz > 0)
        require(minimumFrequencyHz > 0.0 && maximumFrequencyHz > minimumFrequencyHz)
        require(peakCutoff in 0.8..1.0 && minimumClarity in 0.0..1.0)
        require(minimumRms >= 0.0 && targetPeriods >= 2.0)
    }
}

/** Clean-room McLeod Pitch Method using NSDF key maxima and parabolic interpolation. */
class MpmPitchDetector(private val config: MpmPitchConfig = MpmPitchConfig()) {
    fun analyze(samples: FloatArray, timestampMillis: Long): PitchFrame {
        val rms = RmsNoiseGate.rms(samples)
        if (rms < config.minimumRms) return PitchFrame(null, rms, timestampMillis)
        var fallback: Estimate? = null
        for (windowSize in WINDOW_SIZES) {
            if (samples.size < windowSize) continue
            val window = samples.copyOfRange(samples.size - windowSize, samples.size)
            val estimate = analyzeWindow(window) ?: continue
            fallback = estimate
            val periods = windowSize * estimate.frequencyHz / config.sampleRateHz
            if (periods >= config.targetPeriods || windowSize == WINDOW_SIZES.last()) {
                return PitchFrame(estimate.toPitch(timestampMillis, rms), rms, timestampMillis)
            }
        }
        return PitchFrame(fallback?.toPitch(timestampMillis, rms), rms, timestampMillis)
    }

    internal fun analyzeWindow(samples: FloatArray): Estimate? {
        val minimumLag = floor(config.sampleRateHz / config.maximumFrequencyHz).toInt().coerceAtLeast(2)
        val maximumLag = ceil(config.sampleRateHz / config.minimumFrequencyHz).toInt()
            .coerceAtMost(samples.size / 2)
        if (maximumLag <= minimumLag) return null

        val nsdf = DoubleArray(maximumLag + 1)
        nsdf[0] = 1.0
        for (lag in 1..maximumLag) {
            var correlation = 0.0
            var energy = 0.0
            for (index in 0 until samples.size - lag) {
                val left = samples[index].toDouble()
                val right = samples[index + lag].toDouble()
                correlation += left * right
                energy += left * left + right * right
            }
            nsdf[lag] = if (energy <= 1e-15) 0.0 else 2.0 * correlation / energy
        }

        val maxima = keyMaxima(nsdf, minimumLag)
        if (maxima.isEmpty()) return null
        val strongest = maxima.maxOf { nsdf[it] }
        val selected = maxima.firstOrNull { nsdf[it] >= strongest * config.peakCutoff } ?: return null
        val refined = parabolicPeak(nsdf, selected)
        if (refined.second < config.minimumClarity || refined.first <= 0.0) return null
        val frequency = config.sampleRateHz / refined.first
        if (frequency !in config.minimumFrequencyHz..config.maximumFrequencyHz) return null
        return Estimate(frequency, refined.second.coerceIn(0.0, 1.0))
    }

    private fun keyMaxima(nsdf: DoubleArray, minimumLag: Int): List<Int> {
        val maxima = mutableListOf<Int>()
        var index = 1
        while (index < nsdf.size && nsdf[index] > 0.0) index++
        while (index < nsdf.size) {
            while (index < nsdf.size && nsdf[index] <= 0.0) index++
            if (index >= nsdf.size) break
            var maximum = index
            while (index < nsdf.size && nsdf[index] > 0.0) {
                if (nsdf[index] > nsdf[maximum]) maximum = index
                index++
            }
            if (maximum >= minimumLag) maxima += maximum
        }
        return maxima
    }

    private fun parabolicPeak(values: DoubleArray, index: Int): Pair<Double, Double> {
        if (index <= 0 || index >= values.lastIndex) return index.toDouble() to values[index]
        val left = values[index - 1]
        val center = values[index]
        val right = values[index + 1]
        val denominator = left - 2.0 * center + right
        if (abs(denominator) < 1e-12) return index.toDouble() to center
        val offset = (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
        val height = center - 0.25 * (left - right) * offset
        return index + offset to height
    }

    internal data class Estimate(val frequencyHz: Double, val clarity: Double) {
        fun toPitch(timestampMillis: Long, rms: Double): DetectedPitch? {
            val pitch = PracticePitch.nearestToFrequency(frequencyHz) ?: return null
            val reference = 440.0 * 2.0.pow((pitch.midiNumber - 69) / 12.0)
            val cents = 1_200.0 * ln(frequencyHz / reference) / ln(2.0)
            return DetectedPitch(frequencyHz, pitch, cents, clarity, timestampMillis, rms)
        }
    }

    private companion object {
        val WINDOW_SIZES = intArrayOf(1_024, 2_048, 4_096, 8_192)
    }
}
