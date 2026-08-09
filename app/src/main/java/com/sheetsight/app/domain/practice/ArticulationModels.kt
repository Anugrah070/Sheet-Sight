package com.sheetsight.app.domain.practice

/** Why an expected notated duration may or may not be safe to compare acoustically. */
enum class DurationComparisonReliability {
    Reliable,
    UnresolvedDuration,
    UnresolvedTie,
    UnknownArticulation
}

enum class ExpectedArticulation {
    Normal,
    Staccato,
    Tenuto,
    Accent,
    StrongAccent,
    Staccatissimo,
    Fermata,
    Unknown
}

/** Normalized practice semantics; source notes remain separate for rendering and highlighting. */
data class PracticeTieSemantics(
    val groupId: String? = null,
    val tieStart: Boolean = false,
    val tieContinuation: Boolean = false,
    val tieEnd: Boolean = false,
    val combinedExpectedDurationBeats: MusicalBeat? = null,
    val resolved: Boolean = true
)

/** Notated musical duration and its tempo-dependent real-time equivalent. */
data class ExpectedDuration(
    val beats: MusicalBeat,
    val milliseconds: Long,
    val articulation: ExpectedArticulation = ExpectedArticulation.Normal,
    val legatoContext: Boolean = false
)

enum class SustainState { Onset, Active, Decaying, Released }

enum class DurationFeedback {
    Unknown,
    TooShort,
    ApproximatelyCorrect,
    Long,
    SustainAmbiguous,
    StaccatoConsistent,
    ArticulationInconsistent,
    TenutoSustained,
    PossiblyShort,
    FermataFlexible
}

enum class AcousticReleaseCause {
    SustainedSilence,
    ReplacedByNewOnset,
    ObservationLimit
}

/**
 * Microphone-observed acoustic activity. This never claims physical key state:
 * pedal resonance and room decay may remain audible after the key is released.
 */
data class ObservedNoteEvent(
    val stepIndex: Int,
    val pitch: PracticePitch,
    val onsetTimeMillis: Long,
    val lastActiveTimeMillis: Long,
    val releaseTimeMillis: Long? = null,
    val observedDurationMillis: Long? = null,
    val releaseConfidence: Double = 0.0,
    val sustainState: SustainState = SustainState.Onset,
    val releaseCause: AcousticReleaseCause? = null,
    val residualEnergy: Boolean = false,
    val targetPitchEvidence: Boolean = true,
    val newOnsetsPresent: Boolean = false
)

data class DurationResult(
    val stepIndex: Int,
    val pitch: PracticePitch,
    val expectedDuration: ExpectedDuration?,
    val observedEvent: ObservedNoteEvent,
    val feedback: DurationFeedback
)

object ExpectedDurationResolver {
    fun resolve(step: PracticeStep, bpm: Int): ExpectedDuration? {
        if (step.durationComparisonReliability != DurationComparisonReliability.Reliable) return null
        if (step.tieSemantics.tieContinuation) return null
        val beats = step.tieSemantics.combinedExpectedDurationBeats ?: step.durationBeats ?: return null
        return ExpectedDuration(
            beats = beats,
            milliseconds = PracticeClock.durationMillis(beats, bpm),
            articulation = step.expectedArticulation,
            legatoContext = step.hasSlur
        )
    }
}
