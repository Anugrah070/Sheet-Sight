package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Focused YIN-style fundamental detector; avoids selecting a piano harmonic as the largest FFT bin. */
class YinPitchDetector(
    private val config: PitchDetectionConfig = PitchDetectionConfig()
) {
    fun analyze(samples: FloatArray, timestampMillis: Long): PitchFrame {
        require(samples.size >= config.frameSize) { "Pitch frame must contain at least ${config.frameSize} samples." }
        var energy = 0.0
        var energyIndex = 0
        while (energyIndex < config.frameSize) {
            val sample = samples[energyIndex].toDouble()
            energy += sample * sample
            energyIndex++
        }
        val rms = sqrt(energy / config.frameSize)
        // Keep quiet but analyzable frames for the stability filter. That filter combines
        // confidence, repeated evidence, register, and its ambient-noise estimate before an
        // onset can advance practice. Cutting them here made soft/legato edge-register notes
        // impossible to recover on the following frame.
        if (rms < config.analysisMinimumSignalRms) return PitchFrame(null, rms, timestampMillis)

        val minTau = (config.sampleRateHz / config.maximumFrequencyHz).toInt().coerceAtLeast(2)
        val maxTau = (config.sampleRateHz / config.minimumFrequencyHz).toInt()
            .coerceAtMost(config.frameSize / 2)
        if (maxTau <= minTau) return PitchFrame(null, rms, timestampMillis)

        val difference = DoubleArray(maxTau + 1)
        for (tau in 1..maxTau) {
            var sum = 0.0
            val limit = config.frameSize - tau
            var index = 0
            while (index < limit) {
                val delta = (samples[index] - samples[index + tau]).toDouble()
                sum += delta * delta
                index++
            }
            difference[tau] = sum
        }

        val normalized = DoubleArray(maxTau + 1)
        normalized[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..maxTau) {
            runningSum += difference[tau]
            normalized[tau] = if (runningSum == 0.0) 1.0 else difference[tau] * tau / runningSum
        }

        var tau = minTau
        var selected = -1
        while (tau <= maxTau) {
            if (normalized[tau] < config.yinThreshold) {
                while (tau < maxTau && normalized[tau + 1] < normalized[tau]) tau++
                selected = tau
                break
            }
            tau++
        }
        if (selected < 0) {
            selected = (minTau..maxTau).minByOrNull { normalized[it] } ?: return PitchFrame(null, rms, timestampMillis)
        }

        val refinedTau = parabolicTau(normalized, selected)
        if (refinedTau <= 0.0) return PitchFrame(null, rms, timestampMillis)
        val frequency = config.sampleRateHz / refinedTau
        if (frequency !in config.minimumFrequencyHz..config.maximumFrequencyHz) {
            return PitchFrame(null, rms, timestampMillis)
        }
        val nearest = PracticePitch.nearestToFrequency(frequency)
            ?.takeIf { it.midiNumber in PIANO_MIDI_RANGE }
            ?: return PitchFrame(null, rms, timestampMillis)
        val nearestFrequency = 440.0 * Math.pow(2.0, (nearest.midiNumber - 69) / 12.0)
        val cents = 1200.0 * ln(frequency / nearestFrequency) / ln(2.0)
        val confidence = (1.0 - normalized[selected]).coerceIn(0.0, 1.0)
        val detected = DetectedPitch(
            frequencyHz = frequency,
            nearestPitch = nearest,
            centsOffset = if (abs(cents) < 0.000_001) 0.0 else cents,
            confidence = confidence,
            timestampMillis = timestampMillis,
            signalLevel = rms
        )
        return PitchFrame(detected, rms, timestampMillis)
    }

    private fun parabolicTau(values: DoubleArray, tau: Int): Double {
        if (tau <= 1 || tau >= values.lastIndex) return tau.toDouble()
        val left = values[tau - 1]
        val center = values[tau]
        val right = values[tau + 1]
        val denominator = 2.0 * (2.0 * center - right - left)
        return if (abs(denominator) < 1e-12) tau.toDouble()
        else (tau + (right - left) / denominator).coerceIn((tau - 1).toDouble(), (tau + 1).toDouble())
    }

    private companion object {
        val PIANO_MIDI_RANGE = 21..108
    }
}
