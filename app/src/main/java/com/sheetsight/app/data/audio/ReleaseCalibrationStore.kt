package com.sheetsight.app.data.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ReleaseCalibrationStatus { NOT_CALIBRATED, CALIBRATED, NEEDS_IMPROVEMENT }

@Singleton
class ReleaseCalibrationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ReleaseCalibrationProfile? {
        if (!preferences.contains(KEY_VERSION)) return null
        val profile = ReleaseCalibrationProfile(
            noiseFloorRms = preferences.getFloat(KEY_NOISE_FLOOR, 0.0015f).toDouble(),
            medianDecaySlopePerSecond = preferences.getFloat(KEY_DECAY_SLOPE, -0.01f).toDouble(),
            typicalPitchEvidenceDropoutMs = preferences.getLong(KEY_DROPOUT, 260L),
            typicalResidualEnergyMs = preferences.getLong(KEY_RESIDUAL, 500L),
            releaseDebounceMs = preferences.getLong(KEY_DEBOUNCE, 260L),
            noiseFloorMultiplier = preferences.getFloat(KEY_NOISE_MULTIPLIER, 2.2f).toDouble(),
            sustainedResonanceBaselineMs = preferences.getLong(KEY_SUSTAIN_BASELINE, -1L).takeIf { it >= 0L },
            quality = runCatching {
                ReleaseCalibrationQuality.valueOf(preferences.getString(KEY_QUALITY, null).orEmpty())
            }.getOrDefault(ReleaseCalibrationQuality.POOR),
            acceptedSampleCount = preferences.getInt(KEY_ACCEPTED, 0),
            rejectedSampleCount = preferences.getInt(KEY_REJECTED, 0),
            version = preferences.getInt(KEY_VERSION, 0),
            createdAtEpochMillis = preferences.getLong(KEY_CREATED_AT, 0L)
        )
        return profile.takeIf { it.isCompatible }
    }

    fun status(): ReleaseCalibrationStatus {
        if (!preferences.contains(KEY_VERSION)) return ReleaseCalibrationStatus.NOT_CALIBRATED
        val profile = load() ?: return ReleaseCalibrationStatus.NEEDS_IMPROVEMENT
        return if (profile.quality == ReleaseCalibrationQuality.POOR) {
            ReleaseCalibrationStatus.NEEDS_IMPROVEMENT
        } else ReleaseCalibrationStatus.CALIBRATED
    }

    fun save(profile: ReleaseCalibrationProfile) {
        preferences.edit()
            .putFloat(KEY_NOISE_FLOOR, profile.noiseFloorRms.toFloat())
            .putFloat(KEY_DECAY_SLOPE, profile.medianDecaySlopePerSecond.toFloat())
            .putLong(KEY_DROPOUT, profile.typicalPitchEvidenceDropoutMs)
            .putLong(KEY_RESIDUAL, profile.typicalResidualEnergyMs)
            .putLong(KEY_DEBOUNCE, profile.releaseDebounceMs)
            .putFloat(KEY_NOISE_MULTIPLIER, profile.noiseFloorMultiplier.toFloat())
            .putLong(KEY_SUSTAIN_BASELINE, profile.sustainedResonanceBaselineMs ?: -1L)
            .putString(KEY_QUALITY, profile.quality.name)
            .putInt(KEY_ACCEPTED, profile.acceptedSampleCount)
            .putInt(KEY_REJECTED, profile.rejectedSampleCount)
            .putInt(KEY_VERSION, profile.version)
            .putLong(KEY_CREATED_AT, profile.createdAtEpochMillis)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "practice_release_calibration"
        const val KEY_NOISE_FLOOR = "noise_floor"
        const val KEY_DECAY_SLOPE = "decay_slope"
        const val KEY_DROPOUT = "pitch_dropout_ms"
        const val KEY_RESIDUAL = "residual_ms"
        const val KEY_DEBOUNCE = "release_debounce_ms"
        const val KEY_NOISE_MULTIPLIER = "noise_multiplier"
        const val KEY_SUSTAIN_BASELINE = "sustain_baseline_ms"
        const val KEY_QUALITY = "quality"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_REJECTED = "rejected"
        const val KEY_VERSION = "version"
        const val KEY_CREATED_AT = "created_at"
    }
}
