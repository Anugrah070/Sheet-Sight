package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.ExpectedDuration
import com.sheetsight.app.domain.practice.MusicalBeat
import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticePitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCalibrationTest {
    @Test
    fun `session completes from quiet reference and multiple decays without retaining audio`() {
        val config = ReleaseCalibrationConfig(
            targetSampleCount = 2,
            minimumGoodSampleCount = 2,
            minimumModerateSampleCount = 1,
            quietCaptureMillis = 100L,
            releaseDebounceMillis = 100L,
            minimumQuietFrames = 2
        )
        val session = ReleaseCalibrationSession(config) { 99L }
        session.process(frame(0L, 0.001, null))
        session.process(frame(50L, 0.0011, null))
        session.process(frame(100L, 0.001, null))

        fun decay(start: Long) {
            session.process(frame(start, 0.08, detected(start, 0.08)))
            session.process(frame(start + 100L, 0.05, detected(start + 100L, 0.05)))
            session.process(frame(start + 200L, 0.01, null))
            session.process(frame(start + 300L, 0.0012, null))
            session.process(frame(start + 400L, 0.0011, null))
        }
        decay(200L)
        decay(800L)
        val completed = session.process(frame(1_300L, 0.001, null))

        assertEquals(ReleaseCalibrationStage.COMPLETE, completed.stage)
        assertEquals(2, completed.acceptedSamples)
        assertEquals(ReleaseCalibrationQuality.GOOD, completed.profile?.quality)
        assertEquals(99L, completed.profile?.createdAtEpochMillis)
    }

    @Test
    fun `sample validation accepts clean sample and rejects invalid evidence`() {
        assertNull(ReleaseCalibrationSampleValidator.rejectionReason(sample()))
        assertEquals(CalibrationSampleRejection.CLIPPING, rejection(sample(clipped = true)))
        assertEquals(CalibrationSampleRejection.LOW_PITCH_CONFIDENCE, rejection(sample(meanPitchConfidence = 0.2)))
        assertEquals(CalibrationSampleRejection.BACKGROUND_DISTURBANCE, rejection(sample(backgroundDisturbance = true)))
        assertEquals(CalibrationSampleRejection.INVALID_ONSET, rejection(sample(validOnset = false)))
        assertEquals(CalibrationSampleRejection.INCOMPLETE_CAPTURE, rejection(sample(completeCapture = false)))
        assertEquals(CalibrationSampleRejection.REPEATED_ONSET, rejection(sample(repeatedOnset = true)))
    }

    @Test
    fun `robust profile resists one long resonance outlier`() {
        val stable = (0 until 6).map { sample(residualEnergyMs = 480L + it * 10L) }
        val profile = ReleaseCalibrationProfileBuilder.build(
            quietNoiseFrames = listOf(0.0010, 0.0011, 0.0012, 0.0011, 0.0010),
            samples = stable + sample(residualEnergyMs = 20_000L),
            createdAtEpochMillis = 7L
        )

        assertEquals(ReleaseCalibrationQuality.GOOD, profile.quality)
        assertTrue(profile.typicalResidualEnergyMs < 1_000L)
        assertEquals(7L, profile.createdAtEpochMillis)
    }

    @Test
    fun `stable variable and unusable samples map to explicit qualities`() {
        val good = profile((0 until 6).map { sample(medianDecaySlopePerSecond = -0.04 - it * 0.001) })
        val moderate = profile(
            listOf(
                sample(medianDecaySlopePerSecond = -0.02),
                sample(medianDecaySlopePerSecond = -0.04),
                sample(medianDecaySlopePerSecond = -0.07)
            )
        )
        val poor = profile(List(6) { sample(backgroundDisturbance = true) })

        assertEquals(ReleaseCalibrationQuality.GOOD, good.quality)
        assertEquals(ReleaseCalibrationQuality.MODERATE, moderate.quality)
        assertEquals(ReleaseCalibrationQuality.POOR, poor.quality)
    }

    @Test
    fun `tracker applies calibrated dropout and noise threshold while missing profile keeps defaults`() {
        val defaults = AcousticNoteEventTracker().effectivePolicy()
        val tracker = AcousticNoteEventTracker()
        tracker.applyCalibrationProfile(profile(List(6) { sample(targetPitchEvidenceDropoutMs = 600L) }))
        val calibrated = tracker.effectivePolicy()

        assertEquals(260L, defaults.targetDropoutToleranceMillis)
        assertTrue(calibrated.targetDropoutToleranceMillis >= 600L)
        assertTrue(calibrated.calibratedNoiseFloorRms != null)
        assertTrue(calibrated.noiseFloorMultiplier >= 2.0)
    }

    @Test
    fun `poor profile favors unknown over aggressive duration judgment`() {
        val expected = ExpectedDuration(MusicalBeat.of(1), 1_000L)
        assertEquals(
            DurationFeedback.Unknown,
            DurationClassifier.classify(
                expected = expected,
                observedMillis = 100L,
                sustainAmbiguous = false,
                calibrationQuality = ReleaseCalibrationQuality.POOR
            )
        )
    }

    private fun rejection(sample: ReleaseCalibrationSample) =
        ReleaseCalibrationSampleValidator.rejectionReason(sample)

    private fun profile(samples: List<ReleaseCalibrationSample>) = ReleaseCalibrationProfileBuilder.build(
        quietNoiseFrames = listOf(0.0010, 0.0011, 0.0010, 0.0011),
        samples = samples,
        createdAtEpochMillis = 1L
    )

    private fun sample(
        residualEnergyMs: Long = 500L,
        medianDecaySlopePerSecond: Double = -0.04,
        targetPitchEvidenceDropoutMs: Long = 700L,
        meanPitchConfidence: Double = 0.9,
        clipped: Boolean = false,
        validOnset: Boolean = true,
        backgroundDisturbance: Boolean = false,
        completeCapture: Boolean = true,
        repeatedOnset: Boolean = false
    ) = ReleaseCalibrationSample(
        noiseFloor = 0.0011,
        onsetEnergy = 0.08,
        peakLevel = 0.12,
        medianDecaySlopePerSecond = medianDecaySlopePerSecond,
        targetPitchEvidenceDropoutMs = targetPitchEvidenceDropoutMs,
        residualEnergyMs = residualEnergyMs,
        shortDropoutFrequency = 0.05,
        midiNumber = 60,
        meanPitchConfidence = meanPitchConfidence,
        clipped = clipped,
        validOnset = validOnset,
        backgroundDisturbance = backgroundDisturbance,
        completeCapture = completeCapture,
        repeatedOnset = repeatedOnset
    )

    private fun frame(timestamp: Long, level: Double, pitch: DetectedPitch?) =
        PitchFrame(pitch, level, timestamp)

    private fun detected(timestamp: Long, level: Double) = DetectedPitch(
        frequencyHz = 261.63,
        nearestPitch = PracticePitch('C', 0, 4),
        centsOffset = 0.0,
        confidence = 0.95,
        timestampMillis = timestamp,
        signalLevel = level
    )
}
