package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.data.audio.dsp.HannWindow
import com.sheetsight.app.data.audio.dsp.Radix2Fft
import com.sheetsight.app.data.audio.dsp.RmsNoiseGate
import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Score-constrained, piano-aware harmonic matcher. It never performs blind chord transcription. */
class ExpectedPitchMatcher(
    private val sampleRateHz: Int = 22_050,
    private val config: ScoreRecognitionConfig = ScoreRecognitionConfig()
) {
    fun requiredFrameSize(expected: Collection<PracticePitch>): Int {
        val minimum = expected.minOfOrNull(::frequency) ?: return MINIMUM_FRAME_SIZE
        val samples = ceil(PERIODS_PER_FRAME * sampleRateHz / minimum).toInt()
        return nextPowerOfTwo(samples).coerceIn(MINIMUM_FRAME_SIZE, MAXIMUM_FRAME_SIZE)
    }

    fun analyze(samples: FloatArray, expected: List<PracticePitch>, timestampMillis: Long): ScoreMatchFrame {
        require(expected.isNotEmpty())
        val frameSize = requiredFrameSize(expected)
        require(samples.size >= frameSize) { "Expected at least $frameSize samples, received ${samples.size}." }
        val frame = samples.copyOfRange(samples.size - frameSize, samples.size)
        val spectrum = Radix2Fft.powerSpectrum(HannWindow.apply(frame))
        val spectralFloor = spectrum.sorted()[spectrum.size / 2].coerceAtLeast(1e-16)
        val rms = RmsNoiseGate.rms(frame)
        val expectedMidi = expected.mapTo(hashSetOf()) { it.midiNumber }
        val chordFrame = expectedMidi.size > 1
        val candidates = buildCandidatePitches(expected)
        val scores = candidates.associateWith { pitch -> bestTemplate(pitch, spectrum, spectralFloor) }

        val evidence = expected.distinctBy { it.midiNumber }.map { pitch ->
            val best = requireNotNull(scores[pitch])
            val localCompetitors = COMPETITOR_DELTAS
                .map { pitch.midiNumber + it }
                .filterTo(hashSetOf()) { it !in expectedMidi }
            val competing = scores
                .filterKeys { it.midiNumber in localCompetitors }
                .values
                .maxOfOrNull(TemplateScore::score) ?: 0.0
            val bestFrequency = frequency(pitch) * 2.0.pow(best.cents / 1_200.0)
            val periodicity = periodicity(frame, bestFrequency)
            val spectralWeight = when {
                chordFrame -> CHORD_SPECTRAL_WEIGHT
                bestFrequency < BASS_CROSSOVER_HZ -> BASS_SPECTRAL_WEIGHT
                else -> MONOPHONIC_SPECTRAL_WEIGHT
            }
            val baseConfidence = best.score * spectralWeight + periodicity * (1.0 - spectralWeight)
            val marginFactor = if (chordFrame) {
                // A chord's other fundamentals and partials necessarily strengthen nearby templates.
                // Keep the margin as a weak ambiguity check; require all expected templates instead.
                ((baseConfidence - competing + CHORD_MARGIN_OFFSET) / CHORD_MARGIN_RANGE)
                    .coerceIn(CHORD_MINIMUM_MARGIN_FACTOR, 1.0)
            } else {
                ((baseConfidence - competing + MARGIN_OFFSET) / MARGIN_RANGE).coerceIn(0.35, 1.0)
            }
            val coverageFactor = when {
                best.coverage >= 2 -> 1.0
                periodicity >= 0.82 -> 0.90
                else -> 0.68
            }
            val confidence = (baseConfidence * marginFactor * coverageFactor).coerceIn(0.0, 1.0)
            ExpectedPitchEvidence(
                expectedPitch = pitch,
                detectedPitch = DetectedPitch(
                    frequencyHz = bestFrequency,
                    nearestPitch = pitch,
                    centsOffset = best.cents,
                    confidence = confidence,
                    timestampMillis = timestampMillis,
                    signalLevel = rms
                ),
                harmonicScore = best.score,
                periodicityScore = periodicity,
                competingScore = competing,
                harmonicCoverage = best.coverage,
                inharmonicityCoefficient = best.coefficient
            )
        }
        return ScoreMatchFrame(timestampMillis, rms, evidence)
    }

    private fun bestTemplate(pitch: PracticePitch, spectrum: DoubleArray, floor: Double): TemplateScore {
        var best = TemplateScore(0.0, 0.0, 0.0, 0)
        var cents = -config.centsTolerance
        while (cents <= config.centsTolerance + 1e-9) {
            val firstPartial = frequency(pitch) * 2.0.pow(cents / 1_200.0)
            for (coefficient in PianoPartialTemplate.coefficientBank) {
                val score = templateScore(firstPartial, coefficient, spectrum, floor)
                if (score.first > best.score) best = TemplateScore(score.first, cents, coefficient, score.second)
            }
            cents += config.centsSearchStep
        }
        return best
    }

    private fun templateScore(
        firstPartial: Double,
        coefficient: Double,
        spectrum: DoubleArray,
        floor: Double
    ): Pair<Double, Int> {
        var weighted = 0.0
        var totalWeight = 0.0
        var fundamental = 0.0
        var coverage = 0
        for (partial in 1..config.maximumHarmonics) {
            val targetFrequency = PianoPartialTemplate.partialFrequency(firstPartial, partial, coefficient)
            if (targetFrequency >= sampleRateHz * 0.5) break
            val target = interpolatedPower(spectrum, targetFrequency)
            val sidebands = doubleArrayOf(-115.0, -75.0, 75.0, 115.0).map { cents ->
                interpolatedPower(spectrum, targetFrequency * 2.0.pow(cents / 1_200.0))
            }.sorted()
            val localFloor = (sidebands[1] + sidebands[2]) * 0.5
            val ratio = target / (localFloor + floor * 4.0 + 1e-16)
            val salience = ((ln(ratio.coerceAtLeast(1e-9)) - SALIENCE_START) / SALIENCE_RANGE)
                .coerceIn(0.0, 1.0)
            if (partial == 1) fundamental = salience
            if (salience >= COVERAGE_THRESHOLD) coverage++
            val weight = 1.0 / sqrt(partial.toDouble())
            weighted += salience * weight
            totalWeight += weight
        }
        val harmonicMean = if (totalWeight == 0.0) 0.0 else weighted / totalWeight
        return (harmonicMean * 0.72 + fundamental * 0.28).coerceIn(0.0, 1.0) to coverage
    }

    private fun periodicity(samples: FloatArray, frequencyHz: Double): Double {
        val lowFrequency = frequencyHz * 2.0.pow(-config.centsTolerance / 1_200.0)
        val highFrequency = frequencyHz * 2.0.pow(config.centsTolerance / 1_200.0)
        val firstLag = floor(sampleRateHz / highFrequency).toInt().coerceAtLeast(2)
        val lastLag = ceil(sampleRateHz / lowFrequency).toInt().coerceAtMost(samples.size / 2)
        var best = -1.0
        for (lag in firstLag..lastLag) {
            var correlation = 0.0
            var energy = 0.0
            for (index in 0 until samples.size - lag) {
                val left = samples[index].toDouble()
                val right = samples[index + lag].toDouble()
                correlation += left * right
                energy += left * left + right * right
            }
            val nsdf = if (energy <= 1e-15) 0.0 else 2.0 * correlation / energy
            if (nsdf > best) best = nsdf
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun interpolatedPower(spectrum: DoubleArray, frequencyHz: Double): Double {
        val position = frequencyHz * (spectrum.size - 1) * 2.0 / sampleRateHz
        val low = floor(position).toInt().coerceIn(0, spectrum.lastIndex)
        val high = (low + 1).coerceAtMost(spectrum.lastIndex)
        val fraction = position - low
        return spectrum[low] * (1.0 - fraction) + spectrum[high] * fraction
    }

    private fun buildCandidatePitches(expected: List<PracticePitch>): List<PracticePitch> = buildSet {
        expected.forEach { pitch ->
            add(pitch)
            for (delta in COMPETITOR_DELTAS) {
                val midi = pitch.midiNumber + delta
                if (midi in PIANO_MIDI_RANGE) add(PracticePitch.fromMidi(midi))
            }
        }
    }.toList()

    private fun frequency(pitch: PracticePitch): Double =
        config.referenceA4Hz * 2.0.pow((pitch.midiNumber - 69) / 12.0)

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private data class TemplateScore(
        val score: Double,
        val cents: Double,
        val coefficient: Double,
        val coverage: Int
    )

    private companion object {
        const val MINIMUM_FRAME_SIZE = 2_048
        const val MAXIMUM_FRAME_SIZE = 8_192
        const val PERIODS_PER_FRAME = 6.0
        const val MONOPHONIC_SPECTRAL_WEIGHT = 0.72
        const val BASS_SPECTRAL_WEIGHT = 0.58
        const val CHORD_SPECTRAL_WEIGHT = 0.92
        const val BASS_CROSSOVER_HZ = 110.0
        const val MARGIN_OFFSET = 0.20
        const val MARGIN_RANGE = 0.32
        const val CHORD_MARGIN_OFFSET = 0.45
        const val CHORD_MARGIN_RANGE = 0.45
        const val CHORD_MINIMUM_MARGIN_FACTOR = 0.90
        const val COVERAGE_THRESHOLD = 0.24
        val SALIENCE_START = ln(1.8)
        val SALIENCE_RANGE = ln(35.0)
        val PIANO_MIDI_RANGE = 21..108
        val COMPETITOR_DELTAS = intArrayOf(-12, -2, -1, 1, 2, 12)
    }
}
