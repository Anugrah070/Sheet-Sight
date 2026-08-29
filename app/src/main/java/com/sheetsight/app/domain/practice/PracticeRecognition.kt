package com.sheetsight.app.domain.practice

/** Acoustic evidence that a fresh physical attack occurred. */
enum class NoteOnsetEvidence {
    None,
    InitialAttack,
    AfterRelease,
    AmplitudeRise,
    PitchTransition
}

/**
 * Debounced recognition events accepted by [PracticeEngine].
 *
 * The audio layer owns PCM and DSP. The domain sees only immutable, confirmed evidence so a raw
 * analysis frame can never move the practice pointer directly.
 */
sealed interface StablePitchEvent {
    data class Stable(
        val pitch: DetectedPitch,
        val onsetEvidence: NoteOnsetEvidence = NoteOnsetEvidence.InitialAttack
    ) : StablePitchEvent {
        val isNewOnset: Boolean get() = onsetEvidence != NoteOnsetEvidence.None
    }

    data class NoteGroup(
        val pitches: List<DetectedPitch>,
        val onsetTimestampMillis: Long,
        val confidence: Double,
        val onsetEvidence: NoteOnsetEvidence = NoteOnsetEvidence.InitialAttack
    ) : StablePitchEvent {
        init {
            require(pitches.isNotEmpty()) { "A detected note group must contain at least one pitch." }
            require(confidence in 0.0..1.0)
        }

        val isNewOnset: Boolean get() = onsetEvidence != NoteOnsetEvidence.None
        val midiNumbers: Set<Int> get() = pitches.mapTo(linkedSetOf()) { it.nearestPitch.midiNumber }
    }

    data class Wrong(
        val pitch: DetectedPitch?,
        val timestampMillis: Long
    ) : StablePitchEvent

    data object Release : StablePitchEvent
    data class LowConfidence(val pitch: DetectedPitch?) : StablePitchEvent
}
