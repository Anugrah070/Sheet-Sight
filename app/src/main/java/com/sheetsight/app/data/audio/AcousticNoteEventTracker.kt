package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.AcousticReleaseCause
import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.DurationResult
import com.sheetsight.app.domain.practice.ExpectedDuration
import com.sheetsight.app.domain.practice.ExpectedDurationResolver
import com.sheetsight.app.domain.practice.ExpectedArticulation
import com.sheetsight.app.domain.practice.ObservedNoteEvent
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.domain.practice.SustainState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Centralized, forgiving policy for acoustic activity and duration comparison. */
data class ArticulationTrackingConfig(
    val maximumTrackedEvents: Int = 4,
    val targetActivityMinimumConfidence: Double = 0.45,
    val targetActivityCentsTolerance: Double = 65.0,
    val targetDropoutToleranceMillis: Long = 260L,
    val targetLossDebounceMillis: Long = 220L,
    val silenceDebounceMillis: Long = 180L,
    val transitionDebounceMillis: Long = 170L,
    val noiseFloorMultiplier: Double = 2.2,
    val noiseFloorSmoothing: Double = 0.08,
    val decayingEnergyRatio: Double = 0.36,
    val strongEnergyRatio: Double = 0.58,
    val tooShortRatio: Double = 0.55,
    val approximateToleranceRatio: Double = 0.35,
    val longToleranceRatio: Double = 0.45,
    val minimumAbsoluteToleranceMillis: Long = 120L,
    val maximumObservationRatio: Double = 2.6,
    val minimumObservationLimitMillis: Long = 1_200L
) {
    init {
        require(maximumTrackedEvents > 0)
        require(targetActivityMinimumConfidence in 0.0..1.0)
        require(targetDropoutToleranceMillis >= 0L)
        require(targetLossDebounceMillis > 0L && silenceDebounceMillis > 0L)
        require(noiseFloorSmoothing in 0.0..1.0)
        require(decayingEnergyRatio in 0.0..1.0 && strongEnergyRatio in decayingEnergyRatio..1.0)
        require(tooShortRatio in 0.0..1.0)
        require(maximumObservationRatio > 1.0)
    }
}

data class ArticulationTrackerUpdate(
    val completed: List<DurationResult> = emptyList(),
    val activeEventCount: Int = 0
)

/**
 * Cheap stateful analysis layered over the existing YIN/RMS frame stream.
 * It retains only bounded event summaries and never stores PCM.
 */
class AcousticNoteEventTracker(
    private val config: ArticulationTrackingConfig = ArticulationTrackingConfig(),
    private val pitchConfig: PitchDetectionConfig = PitchDetectionConfig()
) {
    private val active = mutableListOf<TrackedEvent>()
    private val recent = ArrayDeque<DurationResult>()
    private var noiseFloorRms = pitchConfig.releaseSignalRms * 0.5
    private var calibrationProfile: ReleaseCalibrationProfile? = null
    private var accumulatedPausedMillis = 0L
    private var pauseStartedRawMillis: Long? = null

    val activeEventCount: Int get() = active.size
    val activeEvents: List<ObservedNoteEvent>
        get() = active.map { tracked ->
            ObservedNoteEvent(
                stepIndex = tracked.step.index,
                pitch = tracked.pitch,
                onsetTimeMillis = tracked.onset,
                lastActiveTimeMillis = tracked.lastActive,
                observedDurationMillis = (tracked.lastActive - tracked.onset).coerceAtLeast(0L),
                sustainState = tracked.state,
                residualEnergy = tracked.residualEnergy,
                targetPitchEvidence = tracked.targetPitchEvidence,
                newOnsetsPresent = tracked.newOnsetsPresent
            )
        }
    val recentResults: List<DurationResult> get() = recent.toList()

    /** Applies only compatible derived features; calibration is optional and never affects progression. */
    fun applyCalibrationProfile(profile: ReleaseCalibrationProfile?) {
        calibrationProfile = profile?.takeIf { it.isCompatible }
        calibrationProfile?.noiseFloorRms?.let { noiseFloorRms = max(noiseFloorRms, it) }
    }

    internal fun effectivePolicy(): ReleaseTrackingPolicy {
        val profile = calibrationProfile
        if (profile == null || profile.quality == ReleaseCalibrationQuality.POOR) {
            return ReleaseTrackingPolicy(
                targetDropoutToleranceMillis = config.targetDropoutToleranceMillis,
                targetLossDebounceMillis = config.targetLossDebounceMillis,
                silenceDebounceMillis = config.silenceDebounceMillis,
                noiseFloorMultiplier = config.noiseFloorMultiplier,
                calibratedNoiseFloorRms = null,
                calibrationQuality = profile?.quality
            )
        }
        return ReleaseTrackingPolicy(
            targetDropoutToleranceMillis = max(
                config.targetDropoutToleranceMillis,
                profile.typicalPitchEvidenceDropoutMs + profile.releaseDebounceMs / 2
            ).coerceAtMost(1_500L),
            targetLossDebounceMillis = max(config.targetLossDebounceMillis, profile.releaseDebounceMs),
            silenceDebounceMillis = max(config.silenceDebounceMillis, profile.releaseDebounceMs),
            noiseFloorMultiplier = profile.noiseFloorMultiplier,
            calibratedNoiseFloorRms = profile.noiseFloorRms,
            calibrationQuality = profile.quality
        )
    }

    fun acceptNote(step: PracticeStep, pitch: PracticePitch, onsetRawMillis: Long, bpm: Int): ArticulationTrackerUpdate {
        val completed = mutableListOf<DurationResult>()
        while (active.size >= config.maximumTrackedEvents) {
            completed += finalize(active.first(), activeTime(onsetRawMillis), AcousticReleaseCause.ObservationLimit, 0.45)
            active.removeAt(0)
        }
        val onset = activeTime(onsetRawMillis)
        active += TrackedEvent(
            step = step,
            pitch = pitch,
            expected = ExpectedDurationResolver.resolve(step, bpm),
            onset = onset,
            lastActive = onset
        )
        return ArticulationTrackerUpdate(completed, active.size)
    }

    /** Records onset evidence independently of whether pitch matching accepts it. */
    fun onNewOnset(pitch: PracticePitch, onsetRawMillis: Long): ArticulationTrackerUpdate {
        val onset = activeTime(onsetRawMillis)
        val completed = mutableListOf<DurationResult>()
        val iterator = active.listIterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next()
            if (onset <= tracked.onset) continue
            tracked.newOnsetsPresent = true
            if (tracked.pitch.midiNumber == pitch.midiNumber) {
                completed += finalize(tracked, onset, AcousticReleaseCause.ReplacedByNewOnset, 0.78)
                iterator.remove()
            } else if (tracked.replacementOnset == null) {
                tracked.replacementOnset = onset
                tracked.replacementMidi = pitch.midiNumber
            }
        }
        return ArticulationTrackerUpdate(completed, active.size)
    }

    fun process(frame: PitchFrame): ArticulationTrackerUpdate {
        val now = activeTime(frame.timestampMillis)
        updateNoiseFloor(frame)
        val policy = effectivePolicy()
        val baselineNoise = max(noiseFloorRms, policy.calibratedNoiseFloorRms ?: 0.0)
        val releaseThreshold = max(pitchConfig.releaseSignalRms, baselineNoise * policy.noiseFloorMultiplier)
        val completed = mutableListOf<DurationResult>()
        val iterator = active.listIterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next()
            val targetPresent = frame.detectedPitch?.let { detected ->
                detected.nearestPitch.midiNumber == tracked.pitch.midiNumber &&
                    detected.confidence >= config.targetActivityMinimumConfidence &&
                    abs(detected.centsOffset) <= config.targetActivityCentsTolerance
            } == true
            val energyPresent = frame.signalLevel >= releaseThreshold

            tracked.peakEnergy = max(tracked.peakEnergy, frame.signalLevel)
            tracked.targetPitchEvidence = targetPresent
            if (targetPresent) {
                tracked.lastTargetEvidence = now
                tracked.lastActive = now
                tracked.targetLostSince = null
                tracked.quietSince = null
                tracked.strongTargetFrames++
                tracked.state = if (
                    tracked.peakEnergy > 0.0 && frame.signalLevel < tracked.peakEnergy * config.decayingEnergyRatio
                ) SustainState.Decaying else SustainState.Active
            } else {
                if (tracked.targetLostSince == null) tracked.targetLostSince = now
                if (energyPresent && now - tracked.lastTargetEvidence <= policy.targetDropoutToleranceMillis) {
                    tracked.lastActive = now
                }
                tracked.residualEnergy = tracked.residualEnergy || energyPresent
                tracked.state = SustainState.Decaying
                if (energyPresent) tracked.quietSince = null else if (tracked.quietSince == null) tracked.quietSince = now
            }

            val replacement = tracked.replacementOnset
            val transitioned = replacement != null && !targetPresent && now - replacement >= config.transitionDebounceMillis
            val quietLongEnough = tracked.quietSince?.let { now - it >= policy.silenceDebounceMillis } == true
            val targetGoneLongEnough = tracked.targetLostSince?.let { now - it >= policy.targetLossDebounceMillis } == true
            val observationLimit = observationLimit(tracked.expected)
            val exceededObservation = now - tracked.onset >= observationLimit

            val result = when {
                transitioned -> finalize(
                    tracked,
                    replacement!!,
                    AcousticReleaseCause.ReplacedByNewOnset,
                    if (energyPresent) 0.62 else 0.72
                )
                quietLongEnough && targetGoneLongEnough -> finalize(
                    tracked,
                    tracked.quietSince!!,
                    AcousticReleaseCause.SustainedSilence,
                    0.90
                )
                exceededObservation -> finalize(
                    tracked,
                    now,
                    AcousticReleaseCause.ObservationLimit,
                    if (targetPresent) 0.70 else 0.52
                )
                else -> null
            }
            if (result != null) {
                completed += result
                iterator.remove()
            }
        }
        return ArticulationTrackerUpdate(completed, active.size)
    }

    fun pause(rawMillis: Long) {
        if (pauseStartedRawMillis == null) pauseStartedRawMillis = rawMillis
    }

    fun resume(rawMillis: Long) {
        val pausedAt = pauseStartedRawMillis ?: return
        accumulatedPausedMillis += (rawMillis - pausedAt).coerceAtLeast(0L)
        pauseStartedRawMillis = null
    }

    fun reset() {
        active.clear()
        recent.clear()
        noiseFloorRms = pitchConfig.releaseSignalRms * 0.5
        accumulatedPausedMillis = 0L
        pauseStartedRawMillis = null
    }

    private fun finalize(
        tracked: TrackedEvent,
        releaseTime: Long,
        cause: AcousticReleaseCause,
        confidence: Double
    ): DurationResult {
        val observedMillis = (releaseTime - tracked.onset).coerceAtLeast(0L)
        val strongSustainedEvidence = tracked.peakEnergy > 0.0 &&
            tracked.lastSignalLevel >= tracked.peakEnergy * config.strongEnergyRatio &&
            tracked.targetPitchEvidence
        val sustainAmbiguous = when (cause) {
            AcousticReleaseCause.ReplacedByNewOnset -> tracked.residualEnergy &&
                observedMillis > (tracked.expected?.milliseconds ?: Long.MAX_VALUE) + config.minimumAbsoluteToleranceMillis
            AcousticReleaseCause.ObservationLimit -> !strongSustainedEvidence || tracked.newOnsetsPresent
            AcousticReleaseCause.SustainedSilence -> tracked.newOnsetsPresent && tracked.residualEnergy
        }
        val observed = ObservedNoteEvent(
            stepIndex = tracked.step.index,
            pitch = tracked.pitch,
            onsetTimeMillis = tracked.onset,
            lastActiveTimeMillis = tracked.lastActive,
            releaseTimeMillis = releaseTime,
            observedDurationMillis = observedMillis,
            releaseConfidence = confidence.coerceIn(0.0, 1.0),
            sustainState = SustainState.Released,
            releaseCause = cause,
            residualEnergy = tracked.residualEnergy,
            targetPitchEvidence = tracked.targetPitchEvidence,
            newOnsetsPresent = tracked.newOnsetsPresent
        )
        val result = DurationResult(
            stepIndex = tracked.step.index,
            pitch = tracked.pitch,
            expectedDuration = tracked.expected,
            observedEvent = observed,
            feedback = DurationClassifier.classify(
                tracked.expected,
                observedMillis,
                sustainAmbiguous,
                config,
                calibrationProfile?.quality
            )
        )
        recent.addLast(result)
        while (recent.size > config.maximumTrackedEvents) recent.removeFirst()
        return result
    }

    private fun updateNoiseFloor(frame: PitchFrame) {
        active.forEach { it.lastSignalLevel = frame.signalLevel }
        if (frame.detectedPitch != null || frame.signalLevel >= pitchConfig.minimumSignalRms) return
        noiseFloorRms += (frame.signalLevel - noiseFloorRms) * config.noiseFloorSmoothing
    }

    private fun observationLimit(expected: ExpectedDuration?): Long = expected?.milliseconds
        ?.let { max(config.minimumObservationLimitMillis, (it * config.maximumObservationRatio).roundToLong()) }
        ?: config.minimumObservationLimitMillis

    private fun activeTime(rawMillis: Long): Long {
        val currentPause = pauseStartedRawMillis?.let { (rawMillis - it).coerceAtLeast(0L) } ?: 0L
        return rawMillis - accumulatedPausedMillis - currentPause
    }

    private data class TrackedEvent(
        val step: PracticeStep,
        val pitch: PracticePitch,
        val expected: ExpectedDuration?,
        val onset: Long,
        var lastActive: Long,
        var lastTargetEvidence: Long = onset,
        var targetLostSince: Long? = null,
        var quietSince: Long? = null,
        var replacementOnset: Long? = null,
        var replacementMidi: Int? = null,
        var peakEnergy: Double = 0.0,
        var lastSignalLevel: Double = 0.0,
        var strongTargetFrames: Int = 0,
        var residualEnergy: Boolean = false,
        var targetPitchEvidence: Boolean = true,
        var newOnsetsPresent: Boolean = false,
        var state: SustainState = SustainState.Onset
    )
}

internal data class ReleaseTrackingPolicy(
    val targetDropoutToleranceMillis: Long,
    val targetLossDebounceMillis: Long,
    val silenceDebounceMillis: Long,
    val noiseFloorMultiplier: Double,
    val calibratedNoiseFloorRms: Double?,
    val calibrationQuality: ReleaseCalibrationQuality?
)

object DurationClassifier {
    fun classify(
        expected: ExpectedDuration?,
        observedMillis: Long,
        sustainAmbiguous: Boolean,
        config: ArticulationTrackingConfig = ArticulationTrackingConfig(),
        calibrationQuality: ReleaseCalibrationQuality? = null
    ): DurationFeedback {
        if (expected == null) return DurationFeedback.Unknown
        if (sustainAmbiguous) return DurationFeedback.SustainAmbiguous
        if (calibrationQuality == ReleaseCalibrationQuality.POOR) return DurationFeedback.Unknown
        if (expected.articulation == ExpectedArticulation.Fermata) return DurationFeedback.FermataFlexible
        if (expected.articulation == ExpectedArticulation.Unknown) return DurationFeedback.Unknown
        val expectedMillis = expected.milliseconds
        val approximateTolerance = max(
            config.minimumAbsoluteToleranceMillis,
            (expectedMillis * config.approximateToleranceRatio).roundToLong()
        )
        val longTolerance = max(
            config.minimumAbsoluteToleranceMillis,
            (expectedMillis * config.longToleranceRatio).roundToLong()
        )
        val shortBoundary = minOf(
            (expectedMillis * config.tooShortRatio).roundToLong(),
            (expectedMillis - approximateTolerance).coerceAtLeast(0L)
        )
        val clearlyShort = observedMillis < shortBoundary
        val clearlyLong = observedMillis > expectedMillis + longTolerance
        return when (expected.articulation) {
            ExpectedArticulation.Staccato, ExpectedArticulation.Staccatissimo -> when {
                clearlyLong -> DurationFeedback.ArticulationInconsistent
                clearlyShort -> DurationFeedback.StaccatoConsistent
                else -> DurationFeedback.ApproximatelyCorrect
            }
            ExpectedArticulation.Tenuto -> when {
                clearlyShort -> DurationFeedback.PossiblyShort
                clearlyLong && expected.legatoContext -> DurationFeedback.SustainAmbiguous
                clearlyLong -> DurationFeedback.Long
                else -> DurationFeedback.TenutoSustained
            }
            ExpectedArticulation.Normal,
            ExpectedArticulation.Accent,
            ExpectedArticulation.StrongAccent -> when {
                clearlyShort -> DurationFeedback.TooShort
                clearlyLong && expected.legatoContext -> DurationFeedback.SustainAmbiguous
                clearlyLong -> DurationFeedback.Long
                else -> DurationFeedback.ApproximatelyCorrect
            }
            ExpectedArticulation.Fermata -> DurationFeedback.FermataFlexible
            ExpectedArticulation.Unknown -> DurationFeedback.Unknown
        }
    }
}
