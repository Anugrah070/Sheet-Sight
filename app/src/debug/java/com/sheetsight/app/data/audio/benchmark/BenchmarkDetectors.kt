package com.sheetsight.app.data.audio.benchmark

import com.sheetsight.app.data.audio.PitchDetectionConfig
import com.sheetsight.app.data.audio.PitchFrame
import com.sheetsight.app.data.audio.StablePitchFilter
import com.sheetsight.app.data.audio.YinPitchDetector
import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.domain.practice.StablePitchEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The committed fixed-RMS YIN detector, reproduced only for benchmark comparison. */
class LegacyYinBenchmarkDetector : ScoreConstrainedBenchmarkDetector {
    private val config = PitchDetectionConfig(
        analysisMinimumSignalRms = 0.003,
        minimumSignalRms = 0.0045,
        releaseSignalRms = 0.003
    )
    private val yin = YinPitchDetector(config)
    private val filter = LegacyStableFilter(config)
    override val id: String = ID
    override val frameSize: Int = config.frameSize
    override val hopSize: Int = config.hopSize

    override fun reset() = filter.reset()

    override fun analyze(
        frame: FloatArray,
        frameEndMillis: Long,
        context: BenchmarkScoreContext
    ): BenchmarkDecision {
        val rms = sqrt(frame.fold(0.0) { total, sample -> total + sample * sample } / frame.size)
        val pitchFrame = if (rms < 0.0045) PitchFrame(null, rms, frameEndMillis)
        else yin.analyze(frame, frameEndMillis)
        return decision(filter.process(pitchFrame), context)
    }

    companion object { const val ID = "A-legacy-fixed-rms-yin" }
}

/** Current worktree YIN plus adaptive/register-aware stability filtering. */
class AdaptiveYinBenchmarkDetector : ScoreConstrainedBenchmarkDetector {
    private val config = PitchDetectionConfig()
    private val yin = YinPitchDetector(config)
    private val filter = StablePitchFilter(config)
    override val id: String = ID
    override val frameSize: Int = config.frameSize
    override val hopSize: Int = config.hopSize

    override fun reset() = filter.reset()

    override fun analyze(
        frame: FloatArray,
        frameEndMillis: Long,
        context: BenchmarkScoreContext
    ): BenchmarkDecision = decision(filter.process(yin.analyze(frame, frameEndMillis)), context)

    companion object { const val ID = "B-adaptive-yin" }
}

private fun decision(event: StablePitchEvent?, context: BenchmarkScoreContext): BenchmarkDecision = when (event) {
    is StablePitchEvent.Stable -> if (event.pitch.nearestPitch.midiNumber == context.expectedMidi) {
        BenchmarkDecision.AcceptedExpectedNote(event.pitch.confidence)
    } else {
        BenchmarkDecision.WrongNote(event.pitch.nearestPitch.midiNumber, event.pitch.confidence)
    }
    is StablePitchEvent.LowConfidence -> BenchmarkDecision.Ambiguous(
        expectedConfidence = event.pitch?.confidence ?: 0.0,
        competingConfidence = 0.0
    )
    is StablePitchEvent.NoteGroup -> if (context.expectedMidi in event.midiNumbers) {
        BenchmarkDecision.AcceptedExpectedNote(event.confidence)
    } else {
        val pitch = event.pitches.first()
        BenchmarkDecision.WrongNote(pitch.nearestPitch.midiNumber, pitch.confidence)
    }
    is StablePitchEvent.Wrong -> BenchmarkDecision.WrongNote(
        event.pitch?.nearestPitch?.midiNumber ?: -1,
        event.pitch?.confidence ?: 0.0
    )
    StablePitchEvent.Release, null -> BenchmarkDecision.NoEvidence
}

/** Exact pre-Phase-7.5 stability behavior, kept out of production. */
private class LegacyStableFilter(private val config: PitchDetectionConfig) {
    private var candidateMidi: Int? = null
    private var candidateCount = 0
    private var lastCandidateTimestamp = 0L
    private var stableMidi: Int? = null
    private var silenceFrames = 0
    private var releasedSinceStable = false
    private var previousSignalLevel = 0.0
    private var lastOnsetTimestamp = 0L
    private var lastLowConfidenceTimestamp: Long? = null

    fun process(frame: PitchFrame): StablePitchEvent? {
        if (frame.signalLevel < config.releaseSignalRms) {
            previousSignalLevel = frame.signalLevel
            candidateMidi = null
            candidateCount = 0
            silenceFrames++
            if (silenceFrames >= config.releaseFrameCount && stableMidi != null) {
                stableMidi = null
                releasedSinceStable = true
                return StablePitchEvent.Release
            }
            return null
        }
        val priorSignalLevel = previousSignalLevel
        previousSignalLevel = frame.signalLevel
        silenceFrames = 0
        val pitch = frame.detectedPitch
        if (pitch == null || pitch.confidence < config.minimumConfidence || frame.signalLevel < config.minimumSignalRms) {
            candidateMidi = null
            candidateCount = 0
            return if (lastLowConfidenceTimestamp == null ||
                frame.timestampMillis - requireNotNull(lastLowConfidenceTimestamp) >= config.lowConfidenceUiIntervalMillis
            ) {
                lastLowConfidenceTimestamp = frame.timestampMillis
                StablePitchEvent.LowConfidence(pitch)
            } else null
        }
        val midi = pitch.nearestPitch.midiNumber
        val amplitudeRetrigger = stableMidi == midi &&
            frame.timestampMillis - lastOnsetTimestamp >= config.minimumRetriggerIntervalMillis &&
            frame.signalLevel - priorSignalLevel >= config.minimumAmplitudeRise &&
            frame.signalLevel >= priorSignalLevel.coerceAtLeast(config.releaseSignalRms) * config.amplitudeRiseRatio
        if (amplitudeRetrigger) {
            lastOnsetTimestamp = frame.timestampMillis
            candidateMidi = midi
            candidateCount = config.stableFrameCount
            return StablePitchEvent.Stable(pitch, NoteOnsetEvidence.AmplitudeRise)
        }
        val continues = candidateMidi == midi &&
            frame.timestampMillis - lastCandidateTimestamp <= config.maximumStableGapMillis
        candidateCount = if (continues) candidateCount + 1 else 1
        candidateMidi = midi
        lastCandidateTimestamp = frame.timestampMillis
        if (candidateCount < config.stableFrameCount || stableMidi == midi) return null
        val evidence = when {
            releasedSinceStable -> NoteOnsetEvidence.AfterRelease
            stableMidi == null -> NoteOnsetEvidence.InitialAttack
            else -> NoteOnsetEvidence.PitchTransition
        }
        stableMidi = midi
        releasedSinceStable = false
        lastOnsetTimestamp = frame.timestampMillis
        return StablePitchEvent.Stable(pitch, evidence)
    }

    fun reset() {
        candidateMidi = null
        candidateCount = 0
        lastCandidateTimestamp = 0L
        stableMidi = null
        silenceFrames = 0
        releasedSinceStable = false
        previousSignalLevel = 0.0
        lastOnsetTimestamp = 0L
        lastLowConfidenceTimestamp = null
    }
}

internal class HarmonicBenchmarkDetector : TemporalEvidenceDetector(
    detectorId = ID,
    analysisFrameSize = 4_096,
    analysisHopSize = 512,
    scorer = HarmonicCandidateScorer()
) {
    companion object { const val ID = "C-score-constrained-harmonics" }
}

internal class LogFrequencyBenchmarkDetector : TemporalEvidenceDetector(
    detectorId = ID,
    analysisFrameSize = 8_192,
    analysisHopSize = 1_024,
    scorer = LogFrequencyCandidateScorer()
) {
    companion object { const val ID = "D-score-constrained-log-frequency" }
}

internal class HybridBenchmarkDetector : TemporalEvidenceDetector(
    detectorId = ID,
    analysisFrameSize = 4_096,
    analysisHopSize = 512,
    scorer = HybridCandidateScorer()
) {
    companion object { const val ID = "F-hybrid-yin-harmonic" }
}

internal data class CandidateEvidence(
    val midi: Int,
    val confidence: Double,
    val fundamentalStrength: Double,
    val harmonicCoverage: Int
)

internal data class ScoreEvidence(
    val rms: Double,
    val candidates: List<CandidateEvidence>
) {
    val ranked: List<CandidateEvidence> = candidates.sortedByDescending { it.confidence }
}

internal fun interface CandidateScorer {
    fun score(frame: FloatArray, sampleRateHz: Int, context: BenchmarkScoreContext): ScoreEvidence
}

/**
 * Multi-harmonic direct spectral matching. A weak fundamental is legal; octave candidates are
 * penalized when lower-octave energy explains their apparent fundamental.
 */
private class HarmonicCandidateScorer : CandidateScorer {
    override fun score(frame: FloatArray, sampleRateHz: Int, context: BenchmarkScoreContext): ScoreEvidence {
        val probe = SpectralProbe(frame, sampleRateHz)
        val candidates = candidateMidis(context).map { midi ->
            harmonicEvidence(midi, probe, maxHarmonics = 8, cqtStyle = false)
        }
        return ScoreEvidence(probe.rms, candidates)
    }
}

/** CQT-style variable-window projections on a 24-bin/octave log-frequency grid. */
private class LogFrequencyCandidateScorer : CandidateScorer {
    override fun score(frame: FloatArray, sampleRateHz: Int, context: BenchmarkScoreContext): ScoreEvidence {
        val probe = SpectralProbe(frame, sampleRateHz)
        val candidates = candidateMidis(context).map { midi ->
            harmonicEvidence(midi, probe, maxHarmonics = 6, cqtStyle = true)
        }
        return ScoreEvidence(probe.rms, candidates)
    }
}

private class HybridCandidateScorer : CandidateScorer {
    private val harmonic = HarmonicCandidateScorer()
    private val yinConfig = PitchDetectionConfig()
    private val yin = YinPitchDetector(yinConfig)

    override fun score(frame: FloatArray, sampleRateHz: Int, context: BenchmarkScoreContext): ScoreEvidence {
        val spectral = harmonic.score(frame, sampleRateHz, context)
        val detected = yin.analyze(frame, 0L).detectedPitch
        return spectral.copy(
            candidates = spectral.candidates.map { candidate ->
                val yinAgreement = if (detected?.nearestPitch?.midiNumber == candidate.midi) detected.confidence else 0.0
                candidate.copy(confidence = (candidate.confidence * 0.72 + yinAgreement * 0.28).coerceIn(0.0, 1.0))
            }
        )
    }
}

private fun candidateMidis(context: BenchmarkScoreContext): List<Int> = buildSet {
    add(context.expectedMidi)
    for (delta in listOf(-12, -2, -1, 1, 2, 12)) add(context.expectedMidi + delta)
    context.previousMidi?.let(::add)
    addAll(context.nextMidi)
}.filter { it in 21..108 }

private fun harmonicEvidence(
    midi: Int,
    probe: SpectralProbe,
    maxHarmonics: Int,
    cqtStyle: Boolean
): CandidateEvidence {
    val f0 = midiFrequency(midi)
    var weighted = 0.0
    var totalWeight = 0.0
    var coverage = 0
    var fundamental = 0.0
    for (harmonic in 1..maxHarmonics) {
        val frequency = f0 * harmonic
        if (frequency >= probe.sampleRateHz / 2.0) break
        val amplitude = if (cqtStyle) probe.cqtMagnitude(frequency) else probe.magnitudeAround(frequency)
        val normalized = amplitude / (probe.rms * sqrt(2.0) + 1e-9)
        if (harmonic == 1) fundamental = normalized.coerceIn(0.0, 1.0)
        val presence = ((normalized - 0.025) / 0.30).coerceIn(0.0, 1.0)
        if (presence >= 0.18) coverage++
        val weight = 1.0 / sqrt(harmonic.toDouble())
        weighted += weight * presence
        totalWeight += weight
    }
    var confidence = if (totalWeight == 0.0) 0.0 else weighted / totalWeight
    val lowerOctave = if (midi >= 33) {
        val amplitude = if (cqtStyle) probe.cqtMagnitude(f0 / 2.0) else probe.magnitudeAround(f0 / 2.0)
        (amplitude / (probe.rms * sqrt(2.0) + 1e-9)).coerceIn(0.0, 1.0)
    } else 0.0
    confidence *= 1.0 - 0.42 * lowerOctave
    if (coverage == 1) confidence *= 0.72
    return CandidateEvidence(midi, confidence.coerceIn(0.0, 1.0), fundamental, coverage)
}

/**
 * Bounded evidence fusion and repeated-note re-arm policy. The score narrows candidates but never
 * manufactures acoustic evidence. Decisions are Expected, Wrong, Ambiguous, or NoEvidence.
 */
internal open class TemporalEvidenceDetector(
    detectorId: String,
    analysisFrameSize: Int,
    analysisHopSize: Int,
    private val scorer: CandidateScorer
) : ScoreConstrainedBenchmarkDetector {
    override val id: String = detectorId
    override val frameSize: Int = analysisFrameSize
    override val hopSize: Int = analysisHopSize
    private var noiseFloorRms = 0.0008
    private var previousRms = 0.0
    private var silenceFrames = 0
    private var candidateMidi: Int? = null
    private var candidateFrames = 0
    private var lastAcceptedMidi: Int? = null
    private var lastAcceptedAt = Long.MIN_VALUE
    private var armed = true
    private var lastWrongMidi: Int? = null
    private var latestIndependentOnsetAt = Long.MIN_VALUE
    private val quietRmsHistory = ArrayDeque<Double>()
    private val spectralOnset = SpectralFluxOnsetDetector()

    override fun reset() {
        noiseFloorRms = 0.0008
        previousRms = 0.0
        silenceFrames = 0
        candidateMidi = null
        candidateFrames = 0
        lastAcceptedMidi = null
        lastAcceptedAt = Long.MIN_VALUE
        armed = true
        lastWrongMidi = null
        latestIndependentOnsetAt = Long.MIN_VALUE
        quietRmsHistory.clear()
        quietRmsHistory.addLast(noiseFloorRms)
        spectralOnset.reset()
    }

    override fun analyze(
        frame: FloatArray,
        frameEndMillis: Long,
        context: BenchmarkScoreContext
    ): BenchmarkDecision {
        val evidence = scorer.score(frame, SAMPLE_RATE_HZ, context)
        if (spectralOnset.hasOnset(frame, SAMPLE_RATE_HZ)) {
            latestIndependentOnsetAt = frameEndMillis
        }
        val ranked = evidence.ranked
        val top = ranked.firstOrNull() ?: return BenchmarkDecision.NoEvidence
        val runnerUp = ranked.getOrNull(1)?.confidence ?: 0.0
        val releaseThreshold = maxOf(0.0012, noiseFloorRms * 1.8)
        if (evidence.rms < releaseThreshold) {
            noiseFloorRms = robustNoiseUpdate(noiseFloorRms, evidence.rms)
            silenceFrames++
            if (silenceFrames >= 2) armed = true
            candidateMidi = null
            candidateFrames = 0
            lastWrongMidi = null
            previousRms = evidence.rms
            return BenchmarkDecision.NoEvidence
        }
        silenceFrames = 0
        if (top.confidence < 0.16) {
            noiseFloorRms = robustNoiseUpdate(noiseFloorRms, evidence.rms)
        }

        val expected = ranked.first { it.midi == context.expectedMidi }
        val competing = ranked.filter { it.midi != context.expectedMidi }.maxByOrNull { it.confidence }
        val amplitudeRise = frameEndMillis - lastAcceptedAt >= 140L &&
            evidence.rms - previousRms >= 0.0007 &&
            evidence.rms >= previousRms.coerceAtLeast(releaseThreshold) * 1.28
        val spectralRearm = frameEndMillis - lastAcceptedAt >= 140L &&
            frameEndMillis - latestIndependentOnsetAt in 0L..INDEPENDENT_ONSET_HOLD_MILLIS
        previousRms = evidence.rms

        if (top.midi != context.expectedMidi && top.confidence >= 0.34 && top.confidence - expected.confidence >= 0.07) {
            accumulate(top.midi)
            if (candidateFrames >= 2 && lastWrongMidi != top.midi) {
                lastWrongMidi = top.midi
                return BenchmarkDecision.WrongNote(top.midi, top.confidence)
            }
            return BenchmarkDecision.Ambiguous(expected.confidence, top.confidence)
        }

        val margin = expected.confidence - (competing?.confidence ?: 0.0)
        val snr = evidence.rms / noiseFloorRms.coerceAtLeast(1e-6)
        val enoughEvidence = expected.confidence >= 0.29 && margin >= 0.055 && expected.harmonicCoverage >= 2
        if (!enoughEvidence) {
            candidateMidi = null
            candidateFrames = 0
            return if (expected.confidence >= 0.16) {
                BenchmarkDecision.Ambiguous(expected.confidence, competing?.confidence ?: 0.0)
            } else BenchmarkDecision.NoEvidence
        }

        accumulate(context.expectedMidi)
        val requiredFrames = if (snr < 3.0 || evidence.rms < 0.0032) 3 else 2
        val pitchTransition = lastAcceptedMidi != null && context.expectedMidi != lastAcceptedMidi
        val canAccept = lastAcceptedMidi == null || pitchTransition || armed || amplitudeRise || spectralRearm
        if (candidateFrames >= requiredFrames && canAccept) {
            lastAcceptedMidi = context.expectedMidi
            lastAcceptedAt = frameEndMillis
            armed = false
            candidateMidi = null
            candidateFrames = 0
            lastWrongMidi = null
            return BenchmarkDecision.AcceptedExpectedNote(expected.confidence)
        }
        return BenchmarkDecision.Ambiguous(expected.confidence, competing?.confidence ?: 0.0)
    }

    private fun accumulate(midi: Int) {
        candidateFrames = if (candidateMidi == midi) candidateFrames + 1 else 1
        candidateMidi = midi
    }

    private fun robustNoiseUpdate(previous: Double, observed: Double): Double {
        quietRmsHistory.addLast(observed)
        if (quietRmsHistory.size > 31) quietRmsHistory.removeFirst()
        val sorted = quietRmsHistory.sorted()
        val median = sorted[sorted.size / 2]
        // A median rejects isolated transients; the upward cap keeps a note tail from rapidly
        // teaching the detector that residual piano energy is the new room floor.
        val cappedMedian = median.coerceAtMost(previous * 1.35 + 0.00015)
        return previous + (cappedMedian - previous) * 0.15
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 22_050
        const val INDEPENDENT_ONSET_HOLD_MILLIS = 180L
    }
}

/**
 * Independent positive spectral-flux onset evidence for repeated-note re-arm.
 *
 * The detector compares a fixed semitone-spaced spectrum over only the newest 1,024 samples.
 * A decaying sustained pitch has no positive flux, while a restrike produces a fresh broadband
 * increase even when its RMS never crosses the release threshold. This signal can only re-arm an
 * already acoustically supported expected pitch; it cannot accept a pitch or move the pointer.
 * Thresholds are deliberately debug-only and provisional until acoustic Android recordings exist.
 */
private class SpectralFluxOnsetDetector {
    private var previousSpectrum: DoubleArray? = null
    private val quietFluxHistory = ArrayDeque<Double>()

    fun reset() {
        previousSpectrum = null
        quietFluxHistory.clear()
    }

    fun hasOnset(frame: FloatArray, sampleRateHz: Int): Boolean {
        val spectrum = logFrequencySpectrum(frame, sampleRateHz)
        val previous = previousSpectrum
        previousSpectrum = spectrum
        if (previous == null) return false

        var positiveFlux = 0.0
        var previousEnergy = 0.0
        for (index in spectrum.indices) {
            positiveFlux += (spectrum[index] - previous[index]).coerceAtLeast(0.0)
            previousEnergy += previous[index]
        }
        val relativeFlux = positiveFlux / (previousEnergy + 1e-7)
        val sortedHistory = quietFluxHistory.sorted()
        val medianFlux = sortedHistory.getOrNull(sortedHistory.size / 2) ?: 0.0
        val threshold = maxOf(MINIMUM_RELATIVE_FLUX, medianFlux * ADAPTIVE_MULTIPLIER + ADAPTIVE_OFFSET)
        val onset = relativeFlux >= threshold && spectrum.sum() >= MINIMUM_SPECTRAL_ENERGY

        if (!onset || relativeFlux < threshold * 1.5) {
            quietFluxHistory.addLast(relativeFlux.coerceAtMost(1.0))
            if (quietFluxHistory.size > HISTORY_SIZE) quietFluxHistory.removeFirst()
        }
        return onset
    }

    private fun logFrequencySpectrum(frame: FloatArray, sampleRateHz: Int): DoubleArray {
        val length = minOf(512, frame.size)
        val offset = frame.size - length
        return DoubleArray((LAST_MIDI - FIRST_MIDI) / MIDI_STEP + 1) { bin ->
            val frequency = midiFrequency(FIRST_MIDI + bin * MIDI_STEP)
            val angularFrequency = 2.0 * PI * frequency / sampleRateHz
            val coefficient = 2.0 * cos(angularFrequency)
            var previous = 0.0
            var previousPrevious = 0.0
            var weightSum = 0.0
            for (local in 0 until length) {
                val weight = 0.5 - 0.5 * cos(2.0 * PI * local / (length - 1).coerceAtLeast(1))
                val sample = frame[offset + local] * weight
                val current = sample + coefficient * previous - previousPrevious
                previousPrevious = previous
                previous = current
                weightSum += weight
            }
            val power = previous * previous + previousPrevious * previousPrevious -
                coefficient * previous * previousPrevious
            2.0 * sqrt(power.coerceAtLeast(0.0)) / weightSum.coerceAtLeast(1e-9)
        }
    }

    private companion object {
        const val FIRST_MIDI = 24
        const val LAST_MIDI = 96
        const val MIDI_STEP = 4
        const val HISTORY_SIZE = 31
        const val MINIMUM_RELATIVE_FLUX = 0.16
        const val ADAPTIVE_MULTIPLIER = 3.5
        const val ADAPTIVE_OFFSET = 0.025
        const val MINIMUM_SPECTRAL_ENERGY = 0.005
    }
}

private class SpectralProbe(
    private val frame: FloatArray,
    val sampleRateHz: Int
) {
    val rms: Double = sqrt(frame.fold(0.0) { total, sample -> total + sample * sample } / frame.size)
    private val fullWindow = WindowedSignal(frame, 0, frame.size)
    private val cache = mutableMapOf<Pair<Int, Int>, Double>()

    fun magnitudeAround(frequency: Double): Double = listOf(-8.0, 0.0, 8.0).maxOf { cents ->
        magnitude(frequency * 2.0.pow(cents / 1_200.0), fullWindow)
    }

    fun cqtMagnitude(frequency: Double): Double {
        val q = 1.0 / (2.0.pow(1.0 / 24.0) - 1.0)
        val length = (q * sampleRateHz / frequency).roundToInt().coerceIn(256, frame.size)
        val window = WindowedSignal(frame, frame.size - length, length)
        return listOf(-6.0, 0.0, 6.0).maxOf { cents ->
            magnitude(frequency * 2.0.pow(cents / 1_200.0), window)
        }
    }

    private fun magnitude(frequency: Double, window: WindowedSignal): Double {
        val key = (frequency * 100).roundToInt() to window.length
        return cache.getOrPut(key) {
            val angularFrequency = 2.0 * PI * frequency / sampleRateHz
            val coefficient = 2.0 * cos(angularFrequency)
            var previous = 0.0
            var previousPrevious = 0.0
            var weightSum = 0.0
            for (local in 0 until window.length) {
                val weight = 0.5 - 0.5 * cos(2.0 * PI * local / (window.length - 1).coerceAtLeast(1))
                val sample = frame[window.offset + local] * weight
                val current = sample + coefficient * previous - previousPrevious
                previousPrevious = previous
                previous = current
                weightSum += weight
            }
            val power = previous * previous + previousPrevious * previousPrevious -
                coefficient * previous * previousPrevious
            2.0 * sqrt(power.coerceAtLeast(0.0)) / weightSum.coerceAtLeast(1e-9)
        }
    }

    private data class WindowedSignal(val samples: FloatArray, val offset: Int, val length: Int)
}

private fun midiFrequency(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
