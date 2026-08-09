package com.sheetsight.app.data.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

const val RELEASE_CALIBRATION_PROFILE_VERSION = 1

enum class ReleaseCalibrationQuality { GOOD, MODERATE, POOR }

/** Compact features derived from the existing PitchFrame stream. Raw PCM is never retained. */
data class ReleaseCalibrationSample(
    val noiseFloor: Double,
    val onsetEnergy: Double,
    val peakLevel: Double,
    val medianDecaySlopePerSecond: Double,
    val targetPitchEvidenceDropoutMs: Long,
    val residualEnergyMs: Long,
    val shortDropoutFrequency: Double,
    val midiNumber: Int,
    val meanPitchConfidence: Double,
    val clipped: Boolean = false,
    val validOnset: Boolean = true,
    val backgroundDisturbance: Boolean = false,
    val completeCapture: Boolean = true,
    val repeatedOnset: Boolean = false
)

enum class CalibrationSampleRejection {
    CLIPPING,
    INVALID_ONSET,
    LOW_PITCH_CONFIDENCE,
    BACKGROUND_DISTURBANCE,
    INCOMPLETE_CAPTURE,
    REPEATED_ONSET,
    INVALID_DECAY
}

object ReleaseCalibrationSampleValidator {
    fun rejectionReason(
        sample: ReleaseCalibrationSample,
        config: ReleaseCalibrationConfig = ReleaseCalibrationConfig()
    ): CalibrationSampleRejection? = when {
        sample.clipped -> CalibrationSampleRejection.CLIPPING
        !sample.validOnset || sample.onsetEnergy <= sample.noiseFloor -> CalibrationSampleRejection.INVALID_ONSET
        sample.meanPitchConfidence < config.minimumPitchConfidence -> CalibrationSampleRejection.LOW_PITCH_CONFIDENCE
        sample.backgroundDisturbance -> CalibrationSampleRejection.BACKGROUND_DISTURBANCE
        !sample.completeCapture -> CalibrationSampleRejection.INCOMPLETE_CAPTURE
        sample.repeatedOnset -> CalibrationSampleRejection.REPEATED_ONSET
        !sample.medianDecaySlopePerSecond.isFinite() || sample.medianDecaySlopePerSecond >= 0.0 ->
            CalibrationSampleRejection.INVALID_DECAY
        else -> null
    }
}

data class ReleaseCalibrationProfile(
    val noiseFloorRms: Double,
    val medianDecaySlopePerSecond: Double,
    val typicalPitchEvidenceDropoutMs: Long,
    val typicalResidualEnergyMs: Long,
    val releaseDebounceMs: Long,
    val noiseFloorMultiplier: Double,
    val sustainedResonanceBaselineMs: Long? = null,
    val quality: ReleaseCalibrationQuality,
    val acceptedSampleCount: Int,
    val rejectedSampleCount: Int,
    val version: Int = RELEASE_CALIBRATION_PROFILE_VERSION,
    val createdAtEpochMillis: Long
) {
    val isCompatible: Boolean get() = version == RELEASE_CALIBRATION_PROFILE_VERSION
}

/** All capture counts and forgiving limits live here rather than in UI code. */
data class ReleaseCalibrationConfig(
    val targetSampleCount: Int = 6,
    val minimumGoodSampleCount: Int = 5,
    val minimumModerateSampleCount: Int = 3,
    val quietCaptureMillis: Long = 1_800L,
    val maximumSampleMillis: Long = 8_000L,
    val releaseDebounceMillis: Long = 260L,
    val minimumPitchConfidence: Double = 0.62,
    val onsetNoiseMultiplier: Double = 3.0,
    val releaseNoiseMultiplier: Double = 1.8,
    val clippingRms: Double = 0.92,
    val repeatedRiseRatio: Double = 1.7,
    val maximumRetainedQuietFrames: Int = 256,
    val minimumQuietFrames: Int = 4,
    val maximumRejectedSamples: Int = 12
)

object ReleaseCalibrationProfileBuilder {
    fun build(
        quietNoiseFrames: List<Double>,
        samples: List<ReleaseCalibrationSample>,
        createdAtEpochMillis: Long,
        config: ReleaseCalibrationConfig = ReleaseCalibrationConfig()
    ): ReleaseCalibrationProfile {
        val cleanNoise = quietNoiseFrames.filter { it.isFinite() && it >= 0.0 }
        val fallbackNoise = 0.0015
        val noiseFloor = cleanNoise.medianOrNull() ?: samples.map { it.noiseFloor }.medianOrNull() ?: fallbackNoise
        val accepted = samples.filter { ReleaseCalibrationSampleValidator.rejectionReason(it, config) == null }
        val rejectedCount = samples.size - accepted.size
        if (accepted.isEmpty()) {
            return ReleaseCalibrationProfile(
                noiseFloorRms = noiseFloor,
                medianDecaySlopePerSecond = -0.01,
                typicalPitchEvidenceDropoutMs = 260L,
                typicalResidualEnergyMs = 500L,
                releaseDebounceMs = 260L,
                noiseFloorMultiplier = 2.2,
                quality = ReleaseCalibrationQuality.POOR,
                acceptedSampleCount = 0,
                rejectedSampleCount = rejectedCount,
                createdAtEpochMillis = createdAtEpochMillis
            )
        }

        val slopes = accepted.map { it.medianDecaySlopePerSecond }
        val dropouts = accepted.map { it.targetPitchEvidenceDropoutMs.toDouble() }
        val residuals = accepted.map { it.residualEnergyMs.toDouble() }
        val noiseInstability = cleanNoise.robustSpreadRatio(noiseFloor)
        val slopeInstability = slopes.robustSpreadRatio(abs(slopes.medianOrNull() ?: -0.01))
        val residualInstability = residuals.robustSpreadRatio(max(1.0, residuals.medianOrNull() ?: 1.0))
        val quality = when {
            cleanNoise.size >= config.minimumQuietFrames && accepted.size >= config.minimumGoodSampleCount && noiseInstability <= 0.55 &&
                slopeInstability <= 0.85 && residualInstability <= 1.0 && rejectedCount <= accepted.size ->
                ReleaseCalibrationQuality.GOOD
            cleanNoise.size >= config.minimumQuietFrames && accepted.size >= config.minimumModerateSampleCount && noiseInstability <= 1.1 &&
                slopeInstability <= 1.6 -> ReleaseCalibrationQuality.MODERATE
            else -> ReleaseCalibrationQuality.POOR
        }
        val typicalDropout = dropouts.percentile(0.50).roundToLong().coerceIn(120L, 1_200L)
        val typicalResidual = residuals.percentile(0.75).roundToLong().coerceIn(180L, 2_500L)
        val derivedDebounce = (dropouts.percentile(0.75) * 0.45 + 120.0).roundToLong().coerceIn(180L, 650L)
        return ReleaseCalibrationProfile(
            noiseFloorRms = noiseFloor,
            medianDecaySlopePerSecond = slopes.medianOrNull() ?: -0.01,
            typicalPitchEvidenceDropoutMs = typicalDropout,
            typicalResidualEnergyMs = typicalResidual,
            releaseDebounceMs = derivedDebounce,
            noiseFloorMultiplier = (2.0 + noiseInstability.coerceIn(0.0, 1.2)).coerceIn(2.0, 3.2),
            quality = quality,
            acceptedSampleCount = accepted.size,
            rejectedSampleCount = rejectedCount,
            createdAtEpochMillis = createdAtEpochMillis
        )
    }
}

enum class ReleaseCalibrationStage { QUIET, NOTES, COMPLETE }

data class ReleaseCalibrationUpdate(
    val stage: ReleaseCalibrationStage,
    val acceptedSamples: Int,
    val targetSamples: Int,
    val profile: ReleaseCalibrationProfile? = null
)

/**
 * Lightweight feature collector over the same detector/RMS frames used by Practice 7.3.
 * It estimates probable acoustic decay only; it cannot observe physical key release.
 */
class ReleaseCalibrationSession(
    private val config: ReleaseCalibrationConfig = ReleaseCalibrationConfig(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis
) {
    private val quietFrames = ArrayDeque<Double>()
    private val samples = mutableListOf<ReleaseCalibrationSample>()
    private var quietStartedAt: Long? = null
    private var active: ActiveSample? = null
    private var stage = ReleaseCalibrationStage.QUIET

    fun process(frame: PitchFrame): ReleaseCalibrationUpdate {
        if (stage == ReleaseCalibrationStage.COMPLETE) return update()
        if (stage == ReleaseCalibrationStage.QUIET) {
            if (quietStartedAt == null) quietStartedAt = frame.timestampMillis
            if (frame.detectedPitch == null) {
                quietFrames.addLast(frame.signalLevel)
                while (quietFrames.size > config.maximumRetainedQuietFrames) quietFrames.removeFirst()
            }
            if (frame.timestampMillis - requireNotNull(quietStartedAt) >= config.quietCaptureMillis) {
                stage = ReleaseCalibrationStage.NOTES
            }
            return update()
        }

        val noiseFloor = quietFrames.toList().medianOrNull() ?: 0.0015
        val tracked = active
        if (tracked == null) {
            val pitch = frame.detectedPitch
            if (pitch != null && pitch.confidence >= config.minimumPitchConfidence &&
                frame.signalLevel >= noiseFloor * config.onsetNoiseMultiplier
            ) {
                active = ActiveSample(
                    midi = pitch.nearestPitch.midiNumber,
                    onset = frame.timestampMillis,
                    onsetEnergy = frame.signalLevel,
                    peak = frame.signalLevel,
                    lastEnergy = frame.signalLevel,
                    lastEnergyAt = frame.timestampMillis,
                    lastTargetAt = frame.timestampMillis,
                    confidenceSum = pitch.confidence,
                    confidenceCount = 1,
                    clipped = frame.signalLevel >= config.clippingRms
                )
            }
            return update()
        }

        tracked.frameCount++
        tracked.clipped = tracked.clipped || frame.signalLevel >= config.clippingRms
        if (frame.signalLevel > tracked.peak) tracked.peak = frame.signalLevel
        val pitch = frame.detectedPitch
        val targetPresent = pitch?.nearestPitch?.midiNumber == tracked.midi &&
            pitch.confidence >= config.minimumPitchConfidence
        if (targetPresent) {
            if (tracked.missingSince != null) tracked.shortDropouts++
            tracked.missingSince = null
            tracked.lastTargetAt = frame.timestampMillis
            tracked.confidenceSum += pitch!!.confidence
            tracked.confidenceCount++
        } else if (tracked.missingSince == null) {
            tracked.missingSince = frame.timestampMillis
        }
        if (pitch != null && pitch.confidence >= config.minimumPitchConfidence &&
            pitch.nearestPitch.midiNumber != tracked.midi
        ) tracked.backgroundDisturbance = true
        if (frame.timestampMillis - tracked.onset > 180L && frame.signalLevel > tracked.peakBeforeRise * config.repeatedRiseRatio) {
            tracked.repeatedOnset = true
        }
        tracked.peakBeforeRise = max(tracked.peakBeforeRise, frame.signalLevel)
        val elapsedSeconds = ((frame.timestampMillis - tracked.lastEnergyAt).coerceAtLeast(1L) / 1_000.0)
        tracked.slopes += (frame.signalLevel - tracked.lastEnergy) / elapsedSeconds
        if (tracked.slopes.size > 256) tracked.slopes.removeFirst()
        tracked.lastEnergy = frame.signalLevel
        tracked.lastEnergyAt = frame.timestampMillis

        val quiet = frame.signalLevel <= noiseFloor * config.releaseNoiseMultiplier
        if (quiet && tracked.quietSince == null) tracked.quietSince = frame.timestampMillis
        if (!quiet) tracked.quietSince = null
        val releaseObserved = tracked.quietSince?.let { frame.timestampMillis - it >= config.releaseDebounceMillis } == true &&
            tracked.missingSince?.let { frame.timestampMillis - it >= config.releaseDebounceMillis } == true
        val timedOut = frame.timestampMillis - tracked.onset >= config.maximumSampleMillis
        if (releaseObserved || timedOut) {
            val dropoutAt = tracked.lastTargetAt
            val quietAt = tracked.quietSince ?: frame.timestampMillis
            samples += ReleaseCalibrationSample(
                noiseFloor = noiseFloor,
                onsetEnergy = tracked.onsetEnergy,
                peakLevel = tracked.peak,
                medianDecaySlopePerSecond = tracked.slopes.filter { it < 0.0 }.medianOrNull() ?: Double.NaN,
                targetPitchEvidenceDropoutMs = (dropoutAt - tracked.onset).coerceAtLeast(0L),
                residualEnergyMs = (quietAt - dropoutAt).coerceAtLeast(0L),
                shortDropoutFrequency = tracked.shortDropouts.toDouble() / tracked.frameCount.coerceAtLeast(1),
                midiNumber = tracked.midi,
                meanPitchConfidence = tracked.confidenceSum / tracked.confidenceCount.coerceAtLeast(1),
                clipped = tracked.clipped,
                backgroundDisturbance = tracked.backgroundDisturbance,
                completeCapture = !timedOut,
                repeatedOnset = tracked.repeatedOnset
            )
            active = null
            val accepted = acceptedSamples()
            if (accepted >= config.targetSampleCount || samples.size >= config.targetSampleCount + config.maximumRejectedSamples) {
                stage = ReleaseCalibrationStage.COMPLETE
            }
        }
        return update()
    }

    private fun acceptedSamples(): Int = samples.count {
        ReleaseCalibrationSampleValidator.rejectionReason(it, config) == null
    }

    private fun update(): ReleaseCalibrationUpdate {
        val profile = if (stage == ReleaseCalibrationStage.COMPLETE) {
            ReleaseCalibrationProfileBuilder.build(quietFrames.toList(), samples, wallClockMillis(), config)
        } else null
        return ReleaseCalibrationUpdate(stage, acceptedSamples(), config.targetSampleCount, profile)
    }

    private data class ActiveSample(
        val midi: Int,
        val onset: Long,
        val onsetEnergy: Double,
        var peak: Double,
        var peakBeforeRise: Double = peak,
        var lastEnergy: Double,
        var lastEnergyAt: Long,
        var lastTargetAt: Long,
        var missingSince: Long? = null,
        var quietSince: Long? = null,
        var confidenceSum: Double,
        var confidenceCount: Int,
        var frameCount: Int = 1,
        var shortDropouts: Int = 0,
        var clipped: Boolean,
        var backgroundDisturbance: Boolean = false,
        var repeatedOnset: Boolean = false,
        val slopes: ArrayDeque<Double> = ArrayDeque()
    )
}

internal fun List<Double>.medianOrNull(): Double? = percentileOrNull(0.5)

internal fun List<Double>.percentile(fraction: Double): Double = percentileOrNull(fraction) ?: 0.0

private fun List<Double>.percentileOrNull(fraction: Double): Double? {
    val sorted = filter(Double::isFinite).sorted()
    if (sorted.isEmpty()) return null
    val position = fraction.coerceIn(0.0, 1.0) * (sorted.lastIndex)
    val lower = position.toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

private fun List<Double>.robustSpreadRatio(centerMagnitude: Double): Double {
    if (size < 2) return 0.0
    return (percentile(0.75) - percentile(0.25)) / max(centerMagnitude, 1e-9)
}
