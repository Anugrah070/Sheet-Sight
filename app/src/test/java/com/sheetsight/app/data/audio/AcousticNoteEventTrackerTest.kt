package com.sheetsight.app.data.audio

import com.sheetsight.app.domain.practice.DurationFeedback
import com.sheetsight.app.domain.practice.ExpectedDuration
import com.sheetsight.app.domain.practice.MusicalBeat
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.domain.practice.SustainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticNoteEventTrackerTest {
    @Test
    fun `stable target remains active and one detector dropout does not release`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)

        assertTrue(tracker.process(frame(C4, 50L)).completed.isEmpty())
        assertTrue(tracker.process(frame(null, 100L, level = 0.03)).completed.isEmpty())
        assertTrue(tracker.process(frame(C4, 150L)).completed.isEmpty())
        assertEquals(1, tracker.activeEventCount)
        assertEquals(SustainState.Active, tracker.activeEvents.single().sustainState)
    }

    @Test
    fun `short confidence dropout does not release`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 50L))

        assertTrue(tracker.process(frame(C4, 100L, confidence = 0.2, level = 0.02)).completed.isEmpty())
        assertTrue(tracker.process(frame(C4, 200L)).completed.isEmpty())
    }

    @Test
    fun `sustained silence produces a debounced release`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 50L))

        assertTrue(tracker.process(frame(null, 100L, 0.0)).completed.isEmpty())
        assertTrue(tracker.process(frame(null, 250L, 0.0)).completed.isEmpty())
        val update = tracker.process(frame(null, 350L, 0.0))

        assertEquals(1, update.completed.size)
        assertEquals(0, tracker.activeEventCount)
        assertEquals(100L, update.completed.single().observedEvent.releaseTimeMillis)
    }

    @Test
    fun `new onset can transition a prior event without corrupting the new pitch`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 500L))
        tracker.onNewOnset(E4, 800L)

        val update = tracker.process(frame(E4, 1_000L))

        assertEquals(1, update.completed.size)
        assertEquals(C4, update.completed.single().pitch)
        assertEquals(DurationFeedback.ApproximatelyCorrect, update.completed.single().feedback)
    }

    @Test
    fun `prolonged residual energy with later onsets is sustain ambiguous`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 1_400L, level = 0.025))
        tracker.onNewOnset(E4, 1_600L)

        val result = tracker.process(frame(E4, 1_800L, level = 0.03)).completed.single()

        assertEquals(DurationFeedback.SustainAmbiguous, result.feedback)
    }

    @Test
    fun `observation limit with persistent target is sustain ambiguous rather than confident long`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)

        tracker.process(frame(C4, 1_000L, level = 0.08))
        tracker.process(frame(C4, 2_000L, level = 0.06))
        val result = tracker.process(frame(C4, 2_600L, level = 0.05)).completed.single()

        assertEquals(DurationFeedback.SustainAmbiguous, result.feedback)
    }

    @Test
    fun `genuine debounced silence after a long hold can still report long`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 1_700L, level = 0.05))
        tracker.process(frame(null, 1_750L, level = 0.0))
        tracker.process(frame(null, 1_950L, level = 0.0))
        val result = tracker.process(frame(null, 2_000L, level = 0.0)).completed.single()

        assertEquals(DurationFeedback.Long, result.feedback)
    }

    @Test
    fun `pause interval is excluded from observed duration`() {
        val tracker = AcousticNoteEventTracker()
        tracker.acceptNote(step(), C4, 0L, 60)
        tracker.process(frame(C4, 400L))
        tracker.pause(500L)
        tracker.resume(10_500L)
        tracker.process(frame(null, 10_600L, 0.0))
        tracker.process(frame(null, 10_800L, 0.0))
        val result = tracker.process(frame(null, 10_900L, 0.0)).completed.single()

        assertEquals(600L, result.observedEvent.observedDurationMillis)
    }

    @Test
    fun `duration classifier is forgiving and represents ambiguity and unknown`() {
        val expected = ExpectedDuration(MusicalBeat.of(1), 1_000L)

        assertEquals(DurationFeedback.TooShort, DurationClassifier.classify(expected, 300L, false))
        assertEquals(DurationFeedback.ApproximatelyCorrect, DurationClassifier.classify(expected, 850L, false))
        assertEquals(DurationFeedback.Long, DurationClassifier.classify(expected, 1_700L, false))
        assertEquals(DurationFeedback.SustainAmbiguous, DurationClassifier.classify(expected, 1_700L, true))
        assertEquals(DurationFeedback.Unknown, DurationClassifier.classify(null, 800L, false))
    }

    @Test
    fun `tracked event summaries remain bounded`() {
        val tracker = AcousticNoteEventTracker(ArticulationTrackingConfig(maximumTrackedEvents = 2))
        repeat(4) { index ->
            tracker.acceptNote(step(index), PracticePitch('C' + index, 0, 4), index * 500L, 60)
        }

        assertTrue(tracker.activeEventCount <= 2)
        assertTrue(tracker.recentResults.size <= 2)
    }

    private fun step(index: Int = 0) = PracticeStep(
        index = index,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = listOf(C4),
        sourceNoteIds = listOf("$index"),
        onsetDivisions = index,
        startBeat = MusicalBeat.of(index.toLong()),
        durationBeats = MusicalBeat.of(1),
        measureBeat = MusicalBeat.of(index.toLong())
    )

    private fun frame(
        pitch: PracticePitch?,
        timestamp: Long,
        level: Double = 0.1,
        confidence: Double = 0.95
    ): PitchFrame = PitchFrame(
        detectedPitch = pitch?.let {
            com.sheetsight.app.domain.practice.DetectedPitch(261.63, it, 0.0, confidence, timestamp, level)
        },
        signalLevel = level,
        timestampMillis = timestamp
    )

    private companion object {
        val C4 = PracticePitch('C', 0, 4)
        val E4 = PracticePitch('E', 0, 4)
    }
}
