package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.StablePitchEvent

data class PracticeRecognitionContext(
    val groupId: Int,
    val expectedPitches: List<PracticePitch>
) {
    val expectedMidi: Set<Int> = expectedPitches.mapTo(linkedSetOf()) { it.midiNumber }
}

data class RecognitionEvidenceFrame(
    val context: PracticeRecognitionContext,
    val match: ScoreMatchFrame,
    val onset: Boolean,
    val released: Boolean
)

/** Pure hysteresis, exactly-once, and chord-assembly policy. */
class RecognitionDecisionGate(
    private val config: ScoreRecognitionConfig = ScoreRecognitionConfig()
) {
    private var groupId: Int? = null
    private var collecting = false
    private var locked = false
    private var onsetAt = 0L
    private var releaseFrames = 0
    private var releaseEmitted = false
    private var wrongFrames = 0
    private var incompleteFrames = 0
    private var lastAcceptedMidi: Set<Int> = emptySet()
    private var releasedSinceDecision = true
    private var attemptEvidence = NoteOnsetEvidence.InitialAttack
    private val pitchFrames = mutableMapOf<Int, Int>()
    private val latched = mutableMapOf<Int, DetectedPitch>()

    fun process(frame: RecognitionEvidenceFrame): StablePitchEvent? {
        if (groupId != frame.context.groupId) resetForGroup(frame.context.groupId)
        if (frame.released) {
            releaseFrames++
            if (releaseFrames >= config.releaseFrameCount && !releaseEmitted) {
                collecting = false
                locked = false
                releaseEmitted = true
                releasedSinceDecision = true
                pitchFrames.clear()
                latched.clear()
                return StablePitchEvent.Release
            }
            return null
        }
        releaseFrames = 0
        releaseEmitted = false

        if (frame.onset) beginAttempt(frame.context, frame.match.timestampMillis)
        if (!collecting || locked) return null

        updateExpected(frame)
        val expectedMidi = frame.context.expectedMidi
        if (expectedMidi.isNotEmpty() && expectedMidi.all(latched::containsKey)) {
            val pitches = frame.context.expectedPitches.distinctBy { it.midiNumber }.map { pitch ->
                requireNotNull(latched[pitch.midiNumber])
            }
            locked = true
            collecting = false
            lastAcceptedMidi = expectedMidi
            releasedSinceDecision = false
            return StablePitchEvent.NoteGroup(
                pitches = pitches,
                onsetTimestampMillis = onsetAt,
                confidence = pitches.minOf { it.confidence },
                onsetEvidence = attemptEvidence
            )
        }

        val unexpected = frame.match.unexpectedPitch?.takeIf { detected ->
            detected.nearestPitch.midiNumber !in expectedMidi &&
                detected.confidence >= config.strongUnexpectedConfidence
        }
        wrongFrames = if (unexpected != null) wrongFrames + 1 else 0
        val elapsed = frame.match.timestampMillis - onsetAt
        val chordCanFail = expectedMidi.size <= 1 || elapsed >= config.chordAssemblyMillis
        if (wrongFrames >= config.wrongFrameCount && chordCanFail) {
            return lockWrong(unexpected, frame.match.timestampMillis)
        }

        val incompleteChord = expectedMidi.size > 1 && elapsed >= config.chordAssemblyMillis && latched.isNotEmpty()
        incompleteFrames = if (incompleteChord) incompleteFrames + 1 else 0
        if (incompleteFrames >= config.wrongFrameCount) {
            return lockWrong(unexpected ?: latched.values.firstOrNull(), frame.match.timestampMillis)
        }

        if (elapsed >= config.episodeTimeoutMillis) {
            locked = true
            collecting = false
            return StablePitchEvent.LowConfidence(frame.match.unexpectedPitch)
        }
        return null
    }

    fun reset() {
        groupId = null
        collecting = false
        locked = false
        onsetAt = 0L
        releaseFrames = 0
        releaseEmitted = false
        wrongFrames = 0
        incompleteFrames = 0
        lastAcceptedMidi = emptySet()
        releasedSinceDecision = true
        attemptEvidence = NoteOnsetEvidence.InitialAttack
        pitchFrames.clear()
        latched.clear()
    }

    private fun beginAttempt(context: PracticeRecognitionContext, timestampMillis: Long) {
        collecting = true
        locked = false
        onsetAt = timestampMillis
        wrongFrames = 0
        incompleteFrames = 0
        pitchFrames.clear()
        latched.clear()
        attemptEvidence = when {
            releasedSinceDecision -> if (lastAcceptedMidi.isEmpty()) {
                NoteOnsetEvidence.InitialAttack
            } else NoteOnsetEvidence.AfterRelease
            context.expectedMidi == lastAcceptedMidi -> NoteOnsetEvidence.AmplitudeRise
            lastAcceptedMidi.isNotEmpty() -> NoteOnsetEvidence.PitchTransition
            else -> NoteOnsetEvidence.InitialAttack
        }
    }

    private fun updateExpected(frame: RecognitionEvidenceFrame) {
        for (evidence in frame.match.expected) {
            val midi = evidence.expectedPitch.midiNumber
            if (evidence.confidence >= config.minimumPresenceConfidence) {
                val count = (pitchFrames[midi] ?: 0) + 1
                pitchFrames[midi] = count
                if (count >= config.correctFrameCount) latched[midi] = evidence.detectedPitch
            } else {
                pitchFrames[midi] = 0
            }
        }
    }

    private fun lockWrong(pitch: DetectedPitch?, timestampMillis: Long): StablePitchEvent.Wrong {
        locked = true
        collecting = false
        releasedSinceDecision = false
        return StablePitchEvent.Wrong(pitch, timestampMillis)
    }

    private fun resetForGroup(newGroupId: Int) {
        groupId = newGroupId
        collecting = false
        locked = false
        onsetAt = 0L
        wrongFrames = 0
        incompleteFrames = 0
        pitchFrames.clear()
        latched.clear()
    }
}
