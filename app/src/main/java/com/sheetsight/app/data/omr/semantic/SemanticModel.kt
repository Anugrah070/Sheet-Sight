package com.sheetsight.app.data.omr.semantic

/** Immutable, image-independent representation of one recognized score. */
data class SemanticScore(
    val parts: List<SemanticPart>,
    val validationWarnings: List<SemanticValidationWarning> = emptyList()
) {
    val systems: List<SemanticSystem> get() = parts.flatMap { it.systems }
    val staffs: List<SemanticStaff> get() = systems.flatMap { it.staffs }
    val measures: List<SemanticMeasure> get() = systems.flatMap { it.measures }
}

data class SemanticPart(
    val id: String,
    val systems: List<SemanticSystem>
)

data class SemanticSystem(
    val id: String,
    val index: Int,
    val staffs: List<SemanticStaff>,
    val measures: List<SemanticMeasure>,
    val horizontalBounds: SemanticBounds,
    val source: SemanticSourceRef
)

data class SemanticStaff(
    val id: String,
    val index: Int,
    val systemId: String,
    val source: SemanticSourceRef,
    /**
     * Staff-space size retained only for horizontal symbol alignment.
     *
     * Verified against oemer 0.1.8 `staffline_extraction.py::Staff.unit_size`
     * and `build_system.py::Measure.align_symbols`, which clusters symbols
     * whose x centers differ by less than the global staff unit size.
     */
    val alignmentUnitSize: Double? = null
)

data class SemanticMeasure(
    val id: String,
    val index: Int,
    val systemId: String,
    val boundary: SemanticMeasureBoundary,
    val events: List<SemanticEvent>
)

data class SemanticMeasureBoundary(
    val left: Int,
    val right: Int,
    val leftEvidence: MeasureBoundaryEvidence,
    val rightEvidence: MeasureBoundaryEvidence,
    val leftSource: SemanticSourceRef? = null,
    val rightSource: SemanticSourceRef? = null
) {
    init {
        require(left < right) { "measure boundary must have positive width: [$left, $right)" }
    }
}

enum class MeasureBoundaryEvidence {
    STAFF_EXTENT,
    DETECTED_BARLINE
}

data class SemanticBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(left <= right)
        require(top <= bottom)
    }

    val centerX: Int get() = Math.floorDiv(left + right, 2)
    val centerY: Int get() = Math.floorDiv(top + bottom, 2)
}

enum class SemanticSourceKind {
    STAFF_GRID,
    BARLINE,
    CLEF,
    ACCIDENTAL,
    NOTEHEAD,
    NOTE_GROUP,
    RHYTHM,
    REST
}

/** Stable primitive provenance; it never retains a mask, bitmap, or image-processing object. */
data class SemanticSourceRef(
    val kind: SemanticSourceKind,
    val id: String,
    val bounds: SemanticBounds? = null
)

enum class SemanticClef {
    TREBLE,
    BASS
}

enum class PitchStep {
    C, D, E, F, G, A, B
}

enum class AccidentalAlteration(val semitones: Int) {
    FLAT(-1),
    NATURAL(0),
    SHARP(1)
}

/** Diatonic identity and accidental alteration are deliberately separate. */
data class SemanticPitch(
    val step: PitchStep,
    val octave: Int,
    val staffPosition: Int,
    val alteration: AccidentalAlteration
)

data class SemanticDuration(
    val numerator: Int,
    val denominator: Int
) {
    init {
        require(numerator > 0)
        require(denominator > 0)
    }
}

enum class SemanticStemDirection {
    UP,
    DOWN,
    NONE,
    AMBIGUOUS
}

data class SemanticBeamInfo(
    val beamCount: Int?,
    val flagCount: Int?
)

enum class SemanticRhythmState {
    RESOLVED,
    PARTIAL,
    UNRESOLVED
}

sealed interface SemanticEvent {
    val id: String
    val measureId: String
    val staffId: String?
    val horizontalPosition: Int
    val sourceRefs: List<SemanticSourceRef>
}

data class SemanticChord(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val notes: List<SemanticNote>,
    val duration: SemanticDuration?,
    val rhythmState: SemanticRhythmState,
    val stemDirection: SemanticStemDirection,
    val beamInfo: SemanticBeamInfo,
    val augmentationDots: Int?
) : SemanticEvent

/** A note is a semantic event nested in its simultaneous chord. */
data class SemanticNote(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val pitch: SemanticPitch?,
    val activeClef: SemanticClef?
) : SemanticEvent

data class SemanticRest(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val duration: SemanticDuration?,
    val rhythmState: SemanticRhythmState,
    val augmentationDots: Int
) : SemanticEvent

data class SemanticClefChange(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val clef: SemanticClef
) : SemanticEvent

data class SemanticKeySignature(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val alterations: Map<PitchStep, AccidentalAlteration>
) : SemanticEvent

/** Representable now, but the current recognition stage supplies no time-signature candidate. */
data class SemanticTimeSignature(
    override val id: String,
    override val measureId: String,
    override val staffId: String,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>,
    val beats: Int,
    val beatUnit: Int
) : SemanticEvent

data class SemanticBarline(
    override val id: String,
    override val measureId: String,
    override val staffId: String? = null,
    override val horizontalPosition: Int,
    override val sourceRefs: List<SemanticSourceRef>
) : SemanticEvent
