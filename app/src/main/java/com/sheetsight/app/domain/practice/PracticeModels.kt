package com.sheetsight.app.domain.practice

import kotlin.math.ln
import kotlin.math.roundToInt

/** Exact quarter-note beat units used as the score-timing source of truth. */
data class MusicalBeat private constructor(
    val numerator: Long,
    val denominator: Long
) : Comparable<MusicalBeat> {
    init {
        require(denominator > 0L) { "MusicalBeat denominator must be positive." }
    }

    operator fun plus(other: MusicalBeat): MusicalBeat =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    operator fun minus(other: MusicalBeat): MusicalBeat =
        of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    override fun compareTo(other: MusicalBeat): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun toString(): String = if (denominator == 1L) numerator.toString() else "$numerator/$denominator"

    companion object {
        val ZERO: MusicalBeat = MusicalBeat(0L, 1L)

        fun of(numerator: Long, denominator: Long = 1L): MusicalBeat {
            require(denominator != 0L) { "MusicalBeat denominator cannot be zero." }
            if (numerator == 0L) return ZERO
            val sign = if (denominator < 0L) -1L else 1L
            val normalizedNumerator = numerator * sign
            val normalizedDenominator = denominator * sign
            val divisor = greatestCommonDivisor(kotlin.math.abs(normalizedNumerator), normalizedDenominator)
            return MusicalBeat(normalizedNumerator / divisor, normalizedDenominator / divisor)
        }

        private tailrec fun greatestCommonDivisor(left: Long, right: Long): Long =
            if (right == 0L) left.coerceAtLeast(1L) else greatestCommonDivisor(right, left % right)
    }
}

/** A notated pitch with a canonical sounding-pitch identity. */
data class PracticePitch(
    val step: Char,
    val alteration: Int,
    val octave: Int
) {
    init {
        require(step.uppercaseChar() in 'A'..'G') { "Pitch step must be A-G." }
    }

    val midiNumber: Int = (octave + 1) * 12 + STEP_TO_SEMITONE.getValue(step.uppercaseChar()) + alteration

    /** Original MusicXML spelling, retained independently of enharmonic comparison. */
    val displayName: String = buildString {
        append(step.uppercaseChar())
        when {
            alteration > 0 -> repeat(alteration) { append('#') }
            alteration < 0 -> repeat(-alteration) { append('b') }
        }
        append(octave)
    }

    companion object {
        private val STEP_TO_SEMITONE = mapOf(
            'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11
        )
        private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun fromMidi(midiNumber: Int): PracticePitch {
            require(midiNumber in 0..127) { "MIDI-equivalent note must be in 0..127." }
            val name = SHARP_NAMES[midiNumber % 12]
            return PracticePitch(
                step = name.first(),
                alteration = if (name.length == 2) 1 else 0,
                octave = midiNumber / 12 - 1
            )
        }

        fun nearestToFrequency(frequencyHz: Double): PracticePitch? {
            if (!frequencyHz.isFinite() || frequencyHz <= 0.0) return null
            val midi = (69.0 + 12.0 * (ln(frequencyHz / 440.0) / ln(2.0))).roundToInt()
            return midi.takeIf { it in 0..127 }?.let(::fromMidi)
        }
    }
}

data class PracticeSource(
    val fileName: String,
    val measureCount: Int,
    /** Verified first MusicXML tempo, or null when the score supplied none. */
    val detectedTempoBpm: Int? = null,
    val initialMeter: PracticeMeter? = null,
    val timingWarnings: List<String> = emptyList()
)

data class PracticeMeter(val beats: Int, val beatType: Int) {
    init {
        require(beats > 0 && beatType > 0)
    }

    /** Length of one displayed count-in pulse in quarter-note beats. */
    val pulseBeats: MusicalBeat = MusicalBeat.of(4L, beatType.toLong())
}

enum class PracticeTempoSource { Detected, UserDefault }

data class PracticeTempo(
    val bpm: Int = DEFAULT_PRACTICE_BPM,
    val source: PracticeTempoSource = PracticeTempoSource.UserDefault
) {
    init {
        require(bpm in MIN_PRACTICE_BPM..MAX_PRACTICE_BPM)
    }
}

enum class PracticeTimingResolution { Resolved, UnresolvedDuration, UnresolvedPosition }

/** One score onset in deterministic musical order, including rests. */
data class PracticeStep(
    val index: Int,
    val measureNumber: String,
    val staffs: List<Int>,
    val expectedPitches: List<PracticePitch>,
    val sourceNoteIds: List<String>,
    val onsetDivisions: Int,
    val startBeat: MusicalBeat? = null,
    val durationBeats: MusicalBeat? = null,
    val measureBeat: MusicalBeat? = null,
    val isRest: Boolean = false,
    val timingResolution: PracticeTimingResolution = when {
        startBeat == null || measureBeat == null -> PracticeTimingResolution.UnresolvedPosition
        durationBeats == null -> PracticeTimingResolution.UnresolvedDuration
        else -> PracticeTimingResolution.Resolved
    },
    val unresolvedTimingReason: String? = null,
    val durationComparisonReliability: DurationComparisonReliability = if (durationBeats == null) {
        DurationComparisonReliability.UnresolvedDuration
    } else {
        DurationComparisonReliability.Reliable
    },
    val tieSemantics: PracticeTieSemantics = PracticeTieSemantics(),
    val expectedArticulation: ExpectedArticulation = ExpectedArticulation.Normal,
    val hasSlur: Boolean = false
) {
    init {
        require(!isRest || expectedPitches.isEmpty()) { "A rest step cannot contain expected pitches." }
    }

    val displayText: String = if (isRest) "Rest" else expectedPitches.joinToString(" + ") { it.displayName }
    val isPitchResolved: Boolean = !isRest && expectedPitches.isNotEmpty() &&
        expectedPitches.all { it.midiNumber in 0..127 }
    val isTimingResolved: Boolean = timingResolution == PracticeTimingResolution.Resolved
    val requiresPolyphonicRecognition: Boolean = expectedPitches.size > 1
    val tieStart: Boolean get() = tieSemantics.tieStart
    val tieContinuation: Boolean get() = tieSemantics.tieContinuation
    val tieEnd: Boolean get() = tieSemantics.tieEnd
    val tieGroupId: String? get() = tieSemantics.groupId
}

data class PracticeSequence(
    val source: PracticeSource,
    val steps: List<PracticeStep>
) {
    val totalSteps: Int = steps.size
}

data class DetectedPitch(
    val frequencyHz: Double,
    val nearestPitch: PracticePitch,
    /** Signed difference from the nearest equal-tempered pitch. */
    val centsOffset: Double,
    val confidence: Double,
    val timestampMillis: Long,
    val signalLevel: Double
)

enum class MatchState {
    Waiting,
    WrongPitch,
    CorrectEarly,
    CorrectOnTime,
    CorrectLate,
    CorrectPitchOnly,
    LowConfidence,
    Missed,
    Unsupported,
    RestViolation,
    RestComplete,
    TieContinuation;

    val advancesPlayableNote: Boolean
        get() = this in setOf(CorrectEarly, CorrectOnTime, CorrectLate, CorrectPitchOnly)
}

data class PracticeMatchResult(
    val state: MatchState,
    /** Actual onset minus expected onset; negative is early. */
    val timingOffsetMillis: Long? = null
)

enum class PracticePhase { NoScore, Loading, Ready, CountIn, Listening, Paused, Completed, Error }

data class PracticeProgress(
    val phase: PracticePhase = PracticePhase.NoScore,
    val sequence: PracticeSequence? = null,
    val currentStepIndex: Int = 0,
    val lastDetectedPitch: DetectedPitch? = null,
    val matchState: MatchState = MatchState.Waiting,
    val timingOffsetMillis: Long? = null,
    val tempo: PracticeTempo = PracticeTempo(),
    val countInEnabled: Boolean = true,
    val countInRemaining: Int? = null,
    val restViolationCount: Int = 0,
    val errorMessage: String? = null
) {
    val currentStep: PracticeStep? get() = sequence?.steps?.getOrNull(currentStepIndex)
    val completedSteps: Int get() = currentStepIndex.coerceAtMost(sequence?.totalSteps ?: 0)
    val totalSteps: Int get() = sequence?.totalSteps ?: 0
}

const val MIN_PRACTICE_BPM = 40
const val MAX_PRACTICE_BPM = 200
const val DEFAULT_PRACTICE_BPM = 80
