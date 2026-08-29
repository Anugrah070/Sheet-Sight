package com.sheetsight.app.domain.practice

import com.sheetsight.app.data.audio.AcousticNoteEventTracker
import com.sheetsight.app.data.audio.DurationClassifier
import com.sheetsight.app.data.audio.PitchFrame
import com.sheetsight.app.data.audio.ReleaseCalibrationProfile
import com.sheetsight.app.data.audio.ReleaseCalibrationQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticulationProgressionRegressionTest {
    @Test
    fun `release calibration and articulation tracking never advance practice`() {
        val engine = startedEngine()
        val tracker = AcousticNoteEventTracker()
        tracker.applyCalibrationProfile(
            ReleaseCalibrationProfile(
                noiseFloorRms = 0.001,
                medianDecaySlopePerSecond = -0.04,
                typicalPitchEvidenceDropoutMs = 500L,
                typicalResidualEnergyMs = 600L,
                releaseDebounceMs = 300L,
                noiseFloorMultiplier = 2.1,
                quality = ReleaseCalibrationQuality.GOOD,
                acceptedSampleCount = 6,
                rejectedSampleCount = 0,
                createdAtEpochMillis = 1L
            )
        )
        tracker.process(PitchFrame(detected(C4, 0L), 0.1, 0L))

        assertEquals(0, engine.progress.currentStepIndex)
        assertEquals(MatchState.Waiting, engine.progress.matchState)
    }

    @Test
    fun `too short feedback cannot roll back or advance a completed step`() {
        val engine = startedEngine()
        val tracker = AcousticNoteEventTracker()
        val c = detected(C4, 0L)

        engine.onPitchEvent(StablePitchEvent.Stable(c))
        tracker.acceptNote(step(0, C4), C4, 0L, 60)
        tracker.process(PitchFrame(c, 0.1, 50L))
        tracker.process(PitchFrame(null, 0.0, 100L))
        tracker.process(PitchFrame(null, 0.0, 250L))
        val feedback = tracker.process(PitchFrame(null, 0.0, 350L)).completed.single().feedback

        assertEquals(DurationFeedback.TooShort, feedback)
        assertEquals(1, engine.progress.currentStepIndex)
        engine.onPitchEvent(StablePitchEvent.Stable(detected(D4, 400L), NoteOnsetEvidence.AfterRelease))
        assertEquals(PracticePhase.Completed, engine.progress.phase)
    }

    @Test
    fun `long and sustain ambiguous classifications have no progression authority`() {
        val engine = startedEngine()
        engine.onPitchEvent(StablePitchEvent.Stable(detected(C4, 0L)))
        val expected = ExpectedDuration(MusicalBeat.of(1), 1_000L)

        assertEquals(DurationFeedback.Long, DurationClassifier.classify(expected, 1_700L, false))
        assertEquals(DurationFeedback.SustainAmbiguous, DurationClassifier.classify(expected, 1_700L, true))
        assertEquals(1, engine.progress.currentStepIndex)

        engine.onPitchEvent(StablePitchEvent.Stable(detected(D4, 1_800L), NoteOnsetEvidence.PitchTransition))
        assertEquals(2, engine.progress.currentStepIndex)
    }

    private fun startedEngine() = PracticeEngine().apply {
        load(PracticeSequence(PracticeSource("test.xml", 1), listOf(step(0, C4), step(1, D4))))
        setTempo(60)
        start()
    }

    private fun step(index: Int, pitch: PracticePitch) = PracticeStep(
        index = index,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = listOf(pitch),
        sourceNoteIds = listOf("$index"),
        onsetDivisions = index,
        startBeat = MusicalBeat.of(index.toLong()),
        durationBeats = MusicalBeat.of(1),
        measureBeat = MusicalBeat.of(index.toLong())
    )

    private fun detected(pitch: PracticePitch, timestamp: Long) = DetectedPitch(
        frequencyHz = 261.63,
        nearestPitch = pitch,
        centsOffset = 0.0,
        confidence = 0.95,
        timestampMillis = timestamp,
        signalLevel = 0.1
    )

    private companion object {
        val C4 = PracticePitch('C', 0, 4)
        val D4 = PracticePitch('D', 0, 4)
    }
}
