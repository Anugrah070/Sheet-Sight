package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.data.audio.PitchFrame
import com.sheetsight.app.data.audio.dsp.AudioAnalysisConfig
import com.sheetsight.app.data.audio.dsp.BiquadHighPassFilter
import com.sheetsight.app.data.audio.dsp.HannWindow
import com.sheetsight.app.data.audio.dsp.Radix2Fft
import com.sheetsight.app.data.audio.dsp.RmsNoiseGate
import com.sheetsight.app.data.audio.dsp.SpectralFluxOnsetDetector
import com.sheetsight.app.domain.practice.StablePitchEvent

data class PracticeAudioResult(
    val pitchFrame: PitchFrame,
    val recognitionEvent: StablePitchEvent?,
    val onsetDetected: Boolean
)

/** Stateful data-layer orchestrator from PCM chunks to debounced domain evidence. */
class PracticeAudioRecognizer(
    private val analysisConfig: AudioAnalysisConfig = AudioAnalysisConfig(),
    private val recognitionConfig: ScoreRecognitionConfig = ScoreRecognitionConfig()
) {
    private val highPass = BiquadHighPassFilter(
        analysisConfig.sampleRateHz,
        analysisConfig.highPassCutoffHz
    )
    private val ring = SampleRing(analysisConfig.maximumFrameSize)
    private val rmsGate = RmsNoiseGate()
    private val onsetDetector = SpectralFluxOnsetDetector()
    private val matcher = ExpectedPitchMatcher(analysisConfig.sampleRateHz, recognitionConfig)
    private val mpm = MpmPitchDetector(MpmPitchConfig(sampleRateHz = analysisConfig.sampleRateHz))
    private val decisionGate = RecognitionDecisionGate(recognitionConfig)
    private var samplesSinceAnalysis = 0
    private var previousSignalActive = false
    private var analysisActive = false
    private var contextId: Int? = null

    fun process(
        pcm: FloatArray,
        timestampMillis: Long,
        context: PracticeRecognitionContext
    ): PracticeAudioResult? {
        require(pcm.isNotEmpty())
        if (contextId != context.groupId) {
            contextId = context.groupId
            analysisActive = false
        }
        val filtered = highPass.process(pcm)
        ring.append(filtered)
        samplesSinceAnalysis += filtered.size

        val onsetFrame = ring.latest(analysisConfig.onsetFrameSize)
        val gate = rmsGate.process(onsetFrame, allowNoiseLearning = !analysisActive)
        val onsetSpectrum = Radix2Fft.powerSpectrum(HannWindow.apply(onsetFrame))
        val spectralOnset = onsetDetector.process(onsetSpectrum, gate.active, timestampMillis).onset
        val gateAttack = gate.active && !previousSignalActive
        val onset = spectralOnset || gateAttack
        previousSignalActive = gate.active
        if (onset) analysisActive = true

        val releaseDue = !gate.active
        val analysisDue = samplesSinceAnalysis >= analysisConfig.recognitionHopSize
        if (!analysisDue && !onset && !releaseDue) return null
        if (analysisDue) samplesSinceAnalysis %= analysisConfig.recognitionHopSize

        val match = when {
            analysisActive && gate.active && context.expectedPitches.isNotEmpty() -> {
                val expectedMatch = matcher.analyze(
                    ring.latest(matcher.requiredFrameSize(context.expectedPitches)),
                    context.expectedPitches,
                    timestampMillis
                )
                val allPresent = expectedMatch.expected.all {
                    it.confidence >= recognitionConfig.minimumPresenceConfidence
                }
                if (allPresent) expectedMatch else expectedMatch.copy(
                    unexpectedPitch = mpm.analyze(ring.latest(analysisConfig.maximumFrameSize), timestampMillis)
                        .detectedPitch
                )
            }
            analysisActive && gate.active -> ScoreMatchFrame(
                timestampMillis = timestampMillis,
                signalRms = gate.rms,
                expected = emptyList(),
                unexpectedPitch = mpm.analyze(ring.latest(analysisConfig.maximumFrameSize), timestampMillis)
                    .detectedPitch
            )
            else -> ScoreMatchFrame(timestampMillis, gate.rms, emptyList())
        }
        val event = decisionGate.process(
            RecognitionEvidenceFrame(
                context = context,
                match = match,
                onset = onset,
                released = releaseDue
            )
        )
        if (event is StablePitchEvent.NoteGroup || event is StablePitchEvent.Wrong ||
            event is StablePitchEvent.LowConfidence
        ) {
            analysisActive = false
        }
        val representative = match.expected.maxByOrNull { it.confidence }?.detectedPitch
            ?: match.unexpectedPitch
        return PracticeAudioResult(
            pitchFrame = PitchFrame(representative, gate.rms, timestampMillis),
            recognitionEvent = event,
            onsetDetected = onset
        )
    }

    fun reset() {
        highPass.reset()
        ring.clear()
        rmsGate.reset()
        onsetDetector.reset()
        decisionGate.reset()
        samplesSinceAnalysis = 0
        previousSignalActive = false
        analysisActive = false
        contextId = null
    }

    private class SampleRing(private val capacity: Int) {
        private val samples = FloatArray(capacity)
        private var writeIndex = 0
        private var count = 0

        fun append(values: FloatArray) {
            for (value in values) {
                samples[writeIndex] = value
                writeIndex = (writeIndex + 1) % capacity
                count = (count + 1).coerceAtMost(capacity)
            }
        }

        fun latest(size: Int): FloatArray {
            require(size in 1..capacity)
            val output = FloatArray(size)
            val available = minOf(size, count)
            val start = (writeIndex - available + capacity) % capacity
            val outputOffset = size - available
            for (index in 0 until available) output[outputOffset + index] = samples[(start + index) % capacity]
            return output
        }

        fun clear() {
            samples.fill(0f)
            writeIndex = 0
            count = 0
        }
    }
}
