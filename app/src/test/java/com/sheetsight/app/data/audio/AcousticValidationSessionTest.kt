package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.PracticePitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticValidationSessionTest {
    @Test
    fun `matrix covers required actions registers articulations rests repeated notes and ties`() {
        assertEquals(AcousticValidationTestCase.entries.toSet(), AcousticValidationTestMatrix.plans.map { it.testCase }.toSet())

        val low = AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.LOW_REGISTER)
        val mid = AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.MID_REGISTER)
        val high = AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.HIGH_REGISTER)
        assertTrue(low.sequence.steps.flatMap { it.expectedPitches }.all { it.acousticRegister() == AcousticRegister.LOW })
        assertTrue(mid.sequence.steps.flatMap { it.expectedPitches }.all { it.acousticRegister() == AcousticRegister.MID })
        assertTrue(high.sequence.steps.flatMap { it.expectedPitches }.all { it.acousticRegister() == AcousticRegister.HIGH })

        val tied = AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.TIED_NOTES).sequence.steps
        assertTrue(tied.first().tieStart)
        assertTrue(tied.last().tieContinuation)
        assertEquals("3", tied.first().tieSemantics.combinedExpectedDurationBeats.toString())
        assertTrue(AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.REST_TRANSITION).sequence.steps.last().isRest)
    }

    @Test
    fun `normal release records bounded derived diagnostics without raw audio`() {
        val session = session(AcousticValidationTestCase.NORMAL_QUARTER)
        session.process(frame(C4, 0L, 0.08))
        session.process(frame(C4, 50L, 0.08))
        session.process(frame(C4, 100L, 0.06))
        session.process(frame(null, 150L, 0.03))
        session.process(frame(C4, 200L, 0.04))
        session.process(frame(null, 300L, 0.0))
        session.process(frame(null, 500L, 0.0))
        val result = session.process(frame(null, 550L, 0.0)).newResults.single()

        assertEquals(C4, result.pitch)
        assertEquals(AcousticRegister.MID, result.register)
        assertEquals(InferredReleaseState.RELEASED, result.inferredReleaseState)
        assertTrue(result.passedPolicyExpectation)
        assertEquals(50L, result.onsetTimestampMillis)
        assertEquals(0.95, result.attackConfidence, 0.0001)
        assertEquals(0.08, result.initialEnergy, 0.0001)
        assertTrue(result.targetPitchConfidenceOverTime.size <= 64)
        assertTrue(result.dropoutDurationsMillis.any { it in 1L..100L })
        assertTrue(result.probableAcousticReleaseTimestampMillis != null)
    }

    @Test
    fun `transient detector dropout does not finalize validation event`() {
        val session = session(AcousticValidationTestCase.NORMAL_QUARTER)
        session.process(frame(C4, 0L, 0.08))
        session.process(frame(C4, 50L, 0.08))
        session.process(frame(null, 100L, 0.03))
        val update = session.process(frame(C4, 150L, 0.06))

        assertTrue(update.newResults.isEmpty())
        assertEquals(1, update.liveNotes.size)
        assertEquals(1, update.currentStepIndex)
    }

    @Test
    fun `untied repeated notes need verified restrikes`() {
        val session = session(AcousticValidationTestCase.REPEATED_IDENTICAL)
        session.process(frame(C4, 0L, 0.08))
        var update = session.process(frame(C4, 50L, 0.08))
        repeat(4) { index -> update = session.process(frame(C4, 100L + index * 25L, 0.07)) }
        assertEquals(1, update.currentStepIndex)
        assertEquals(1, update.acceptedOnsetCount)

        session.process(frame(C4, 200L, 0.04))
        val second = session.process(frame(C4, 250L, 0.11))
        assertEquals(2, second.currentStepIndex)
        assertEquals(2, second.acceptedOnsetCount)
        assertTrue(second.newResults.single().passedPolicyExpectation)

        session.process(frame(C4, 400L, 0.04))
        val third = session.process(frame(C4, 450L, 0.11))
        assertEquals(3, third.currentStepIndex)
        assertEquals(3, third.acceptedOnsetCount)
    }

    @Test
    fun `tied continuation completes from clock with one accepted onset`() {
        val session = session(AcousticValidationTestCase.TIED_NOTES)
        session.process(frame(C4, 0L, 0.08))
        session.process(frame(C4, 50L, 0.08))
        val afterTie = session.process(frame(C4, 3_100L, 0.04))

        assertEquals(2, afterTie.currentStepIndex)
        assertEquals(1, afterTie.acceptedOnsetCount)

        session.process(frame(null, 3_200L, 0.0))
        session.process(frame(null, 3_400L, 0.0))
        val result = session.process(frame(null, 3_450L, 0.0)).newResults.single()
        assertTrue(result.passedPolicyExpectation)
        assertEquals(3_000L, result.expectedDurationMillis)
    }

    @Test
    fun `legato transitions advance new pitches and avoid confident long feedback`() {
        val session = session(AcousticValidationTestCase.LEGATO)
        session.process(frame(C4, 0L, 0.08))
        session.process(frame(C4, 50L, 0.08))
        session.process(frame(E4, 700L, 0.08))
        session.process(frame(E4, 750L, 0.08))
        session.process(frame(E4, 950L, 0.06))
        session.process(frame(G4, 1_400L, 0.08))
        session.process(frame(G4, 1_450L, 0.08))
        val update = session.process(frame(G4, 1_650L, 0.06))

        assertEquals(3, update.currentStepIndex)
        assertEquals(3, update.acceptedOnsetCount)
        assertTrue(update.allResults.isNotEmpty())
        assertTrue(update.allResults.all { it.feedback != DurationFeedback.Long })
    }

    @Test
    fun `legato progression tolerates alternating old-note evidence`() {
        val session = session(AcousticValidationTestCase.LEGATO)
        session.process(frame(C4, 0L, 0.008))
        session.process(frame(C4, 50L, 0.008))
        session.process(frame(E4, 600L, 0.005))
        session.process(frame(C4, 650L, 0.005))
        var update = session.process(frame(E4, 700L, 0.005))
        assertEquals(2, update.currentStepIndex)

        session.process(frame(G4, 1_200L, 0.005))
        session.process(frame(E4, 1_250L, 0.005))
        update = session.process(frame(G4, 1_300L, 0.005))

        assertEquals(3, update.currentStepIndex)
        assertEquals(3, update.acceptedOnsetCount)
    }

    @Test
    fun `quiet low and high register legato plans tolerate alternating old-note evidence`() {
        listOf(
            AcousticValidationTestCase.LEGATO_LOW to listOf(C2, E2, G2),
            AcousticValidationTestCase.LEGATO_HIGH to listOf(C6, E6, G6)
        ).forEach { (testCase, pitches) ->
            val session = session(testCase)
            session.process(frame(pitches[0], 0L, 0.0029))
            session.process(frame(pitches[0], 50L, 0.0029))
            session.process(frame(pitches[1], 600L, 0.0029))
            session.process(frame(pitches[0], 650L, 0.0029))
            session.process(frame(pitches[1], 700L, 0.0029))
            session.process(frame(pitches[2], 1_200L, 0.0029))
            session.process(frame(pitches[1], 1_250L, 0.0029))
            val update = session.process(frame(pitches[2], 1_300L, 0.0029))

            assertEquals(testCase.displayName, 3, update.currentStepIndex)
            assertEquals(testCase.displayName, 3, update.acceptedOnsetCount)
        }
    }

    @Test
    fun `good moderate poor and no calibration policies remain explicit and conservative`() {
        fun result(profile: ReleaseCalibrationProfile?): AcousticValidationResult {
            val session = AcousticValidationSession(
                plan = AcousticValidationTestMatrix.planFor(AcousticValidationTestCase.NORMAL_QUARTER),
                calibrationProfile = profile
            )
            session.process(frame(C4, 0L, 0.08))
            session.process(frame(C4, 50L, 0.08))
            session.process(frame(null, 150L, 0.0))
            session.process(frame(null, 350L, 0.0))
            return session.process(frame(null, 450L, 0.0)).newResults.single()
        }

        val noProfile = result(null)
        val good = result(profile(ReleaseCalibrationQuality.GOOD))
        val moderate = result(profile(ReleaseCalibrationQuality.MODERATE))
        val poor = result(profile(ReleaseCalibrationQuality.POOR))

        assertEquals(null, noProfile.calibrationQuality)
        assertEquals(ReleaseCalibrationQuality.GOOD, good.calibrationQuality)
        assertEquals(ReleaseCalibrationQuality.MODERATE, moderate.calibrationQuality)
        assertTrue(good.feedback != DurationFeedback.Unknown)
        assertTrue(moderate.feedback != DurationFeedback.Unknown)
        assertEquals(DurationFeedback.Unknown, poor.feedback)
        assertTrue(!poor.passedPolicyExpectation)
        assertEquals(AcousticValidationVerdict.INCONCLUSIVE, poor.verdict)
        assertTrue(poor.notes.contains("Inconclusive"))
    }

    @Test
    fun `sustain-labeled observation limit avoids confident long`() {
        val session = session(AcousticValidationTestCase.SUSTAIN_PEDAL)
        session.process(frame(C4, 0L, 0.08))
        session.process(frame(C4, 50L, 0.08))
        var update = session.snapshot()
        for (timestamp in 200L..2_800L step 200) {
            update = session.process(frame(C4, timestamp, 0.05))
        }
        val result = update.allResults.single()

        assertEquals(DurationFeedback.SustainAmbiguous, result.feedback)
        assertTrue(result.passedPolicyExpectation)
        assertEquals(ExpectedPhysicalAction.PEDAL_SUSTAINED, result.expectedAction)
    }

    private fun session(testCase: AcousticValidationTestCase) = AcousticValidationSession(
        AcousticValidationTestMatrix.planFor(testCase)
    )

    private fun frame(pitch: PracticePitch?, timestamp: Long, level: Double) = PitchFrame(
        detectedPitch = pitch?.let {
            DetectedPitch(
                frequencyHz = 440.0,
                nearestPitch = it,
                centsOffset = 0.0,
                confidence = 0.95,
                timestampMillis = timestamp,
                signalLevel = level
            )
        },
        signalLevel = level,
        timestampMillis = timestamp
    )

    private fun profile(quality: ReleaseCalibrationQuality) = ReleaseCalibrationProfile(
        noiseFloorRms = 0.001,
        medianDecaySlopePerSecond = -0.04,
        typicalPitchEvidenceDropoutMs = 260L,
        typicalResidualEnergyMs = 500L,
        releaseDebounceMs = 260L,
        noiseFloorMultiplier = 2.2,
        quality = quality,
        acceptedSampleCount = 6,
        rejectedSampleCount = 0,
        createdAtEpochMillis = 1L
    )

    private companion object {
        val C2 = PracticePitch('C', 0, 2)
        val E2 = PracticePitch('E', 0, 2)
        val G2 = PracticePitch('G', 0, 2)
        val C4 = PracticePitch('C', 0, 4)
        val E4 = PracticePitch('E', 0, 4)
        val G4 = PracticePitch('G', 0, 4)
        val C6 = PracticePitch('C', 0, 6)
        val E6 = PracticePitch('E', 0, 6)
        val G6 = PracticePitch('G', 0, 6)
    }
}
