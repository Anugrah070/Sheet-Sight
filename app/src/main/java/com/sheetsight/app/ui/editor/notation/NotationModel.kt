package com.sheetsight.app.ui.editor.notation

/** Immutable, renderer-only representation reconstructed from persisted MusicXML. */
data class NotationDocument(
    val systems: List<NotationSystem>,
    val statistics: NotationStatistics,
    val unsupportedElements: Map<String, Int>,
    /** First verified MusicXML tempo expressed as quarter notes per minute. */
    val detectedTempoBpm: Double? = null
) {
    val hasRenderableEvents: Boolean
        get() = statistics.noteCount > 0 || statistics.restCount > 0

    /** Number of measure cells handed to [NotationSystemCard] for drawing. */
    val renderedMeasureCount: Int
        get() = systems.sumOf { it.measures.size }
}

data class NotationSystem(
    val index: Int,
    val measures: List<NotationMeasure>,
    val staffCount: Int,
    val startsNewPage: Boolean
)

data class NotationMeasure(
    val number: String,
    val staffs: List<NotationStaff>,
    val startsNewSystem: Boolean,
    val startsNewPage: Boolean,
    /** Zero-based source order, retained so duplicate printed measure labels remain uniquely addressable. */
    val sourceIndex: Int = 0
)

data class NotationStaff(
    val number: Int,
    val clef: NotationClef,
    val keyFifths: Int?,
    val timeSignature: NotationTimeSignature?,
    val events: List<NotationEvent>
)

enum class NotationClef { TREBLE, BASS, UNSUPPORTED, UNKNOWN }

data class NotationTimeSignature(val beats: Int, val beatType: Int)

sealed interface NotationEvent {
    val durationType: NotationDurationType
    val dots: Int
    val voice: Int
    /** MusicXML cursor position, retained for deterministic non-scoring timelines. */
    val onsetDivisions: Int
    /** Numeric MusicXML duration, or null when it was absent/non-positive. */
    val durationDivisions: Int?
    /** Divisions per quarter note in force at this event. */
    val divisionsPerQuarter: Int?
    /** Stable order among events that share an onset. */
    val sourceOrder: Int
}

data class NotationChord(
    val pitches: List<NotationPitch>,
    override val durationType: NotationDurationType,
    override val dots: Int,
    override val voice: Int,
    val stem: NotationStem,
    override val onsetDivisions: Int = 0,
    override val sourceOrder: Int = 0,
    override val durationDivisions: Int? = null,
    override val divisionsPerQuarter: Int? = null,
    /** Per-pitch MusicXML semantics; rendering still uses the original pitch list. */
    val noteSemantics: List<NotationNoteSemantics> = List(pitches.size) { NotationNoteSemantics() }
) : NotationEvent

data class NotationRest(
    override val durationType: NotationDurationType,
    override val dots: Int,
    override val voice: Int,
    override val onsetDivisions: Int = 0,
    override val sourceOrder: Int = 0,
    override val durationDivisions: Int? = null,
    override val divisionsPerQuarter: Int? = null
) : NotationEvent

data class NotationPitch(
    val step: Char,
    val alteration: Int,
    val octave: Int,
    val displayedAccidental: NotationAccidental?
)

/** MusicXML note-level semantics kept separate from renderer geometry. */
data class NotationNoteSemantics(
    val tieStart: Boolean = false,
    val tieStop: Boolean = false,
    val articulations: Set<NotationArticulation> = emptySet(),
    val slurStart: Boolean = false,
    val slurStop: Boolean = false,
    val hasUnknownNotation: Boolean = false
)

enum class NotationArticulation {
    STACCATO,
    TENUTO,
    ACCENT,
    STRONG_ACCENT,
    STACCATISSIMO,
    FERMATA
}

enum class NotationAccidental { FLAT, NATURAL, SHARP }

enum class NotationStem { UP, DOWN, NONE, UNSPECIFIED }

enum class NotationDurationType {
    WHOLE, HALF, QUARTER, EIGHTH, SIXTEENTH, THIRTY_SECOND, SIXTY_FOURTH, UNKNOWN
}

data class NotationStatistics(
    val measureCount: Int,
    val staffCount: Int,
    val noteCount: Int,
    val chordCount: Int,
    val restCount: Int,
    val explicitBarlineCount: Int = 0,
    val explicitBarlineLocations: List<String> = emptyList()
)

internal data class ParsedNotationScore(
    val measures: List<NotationMeasure>,
    val statistics: NotationStatistics,
    val unsupportedElements: Map<String, Int>,
    val detectedTempoBpm: Double? = null
)
