package com.sheetsight.app.data.omr.rhythm

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.symbol.ClassifiedRestCandidate
import com.sheetsight.app.data.omr.symbol.RestSymbolLabel
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox

enum class RhythmEvidenceStatus {
    COMPLETE,
    INCOMPLETE
}

/**
 * A resolution state rather than a fabricated numeric confidence.
 *
 * [PARTIAL] means verified evidence such as the base duration is present,
 * but the final dotted value cannot be concluded.
 */
enum class RhythmResolutionState {
    RESOLVED,
    PARTIAL,
    UNRESOLVED
}

enum class StemAssociationStatus {
    ASSIGNED,
    NONE,
    AMBIGUOUS,
    SHARED_BETWEEN_GROUPS
}

enum class RhythmUnresolvedReason {
    INCOMPLETE_MASK_EVIDENCE,
    NO_STAFF_GEOMETRY,
    EMPTY_NOTE_GROUP,
    STEM_NOT_ASSOCIATED,
    STEM_SHARED_BETWEEN_GROUPS,
    AMBIGUOUS_STEM_DIRECTION,
    SOLID_STEMLESS_NOTEHEAD,
    MIXED_NOTEHEAD_TYPES,
    DOT_SCAN_OUT_OF_BOUNDS,
    MIXED_DOT_EVIDENCE,
    BEAM_FLAG_KIND_AMBIGUOUS,
    BEAM_RASTER_THRESHOLD_AMBIGUOUS,
    BEAM_SCAN_OUT_OF_BOUNDS,
    BEAM_REGION_WITH_ZERO_COUNT,
    UNSUPPORTED_BEAM_FLAG_COUNT,
    REST_WHOLE_HALF_AMBIGUOUS
}

/**
 * Final note values present in oemer 0.1.8. The reference rhythm extractor
 * infers through [THIRTY_SECOND]; [SIXTY_FOURTH], [TRIPLET], and [OTHER]
 * remain representable for later verified producers.
 */
enum class RhythmDuration {
    WHOLE,
    HALF,
    QUARTER,
    EIGHTH,
    SIXTEENTH,
    THIRTY_SECOND,
    SIXTY_FOURTH,
    TRIPLET,
    OTHER
}

/** Exact duration expressed as a reduced fraction of one whole note. */
data class RhythmValue(
    val numerator: Int,
    val denominator: Int
) {
    init {
        require(numerator > 0)
        require(denominator > 0)
        require(gcd(numerator, denominator) == 1) {
            "RhythmValue must be reduced; use RhythmValue.of()"
        }
    }

    companion object {
        fun of(numerator: Int, denominator: Int): RhythmValue {
            require(numerator > 0)
            require(denominator > 0)
            val divisor = gcd(numerator, denominator)
            return RhythmValue(numerator / divisor, denominator / divisor)
        }

        private tailrec fun gcd(a: Int, b: Int): Int =
            if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * Exact scan evidence retained for an augmentation dot. [scanRegion] uses
 * the repository's exclusive-right/bottom box convention.
 */
data class AugmentationDotEvidence(
    val noteheadId: Int,
    val scanRegion: BoundingBox?,
    val foregroundPixelCount: Int?,
    val minimumPixelCount: Int?,
    val maximumPixelCount: Int?,
    val detected: Boolean?,
    val unresolvedReason: RhythmUnresolvedReason? = null
)

data class StemAssociation(
    val status: StemAssociationStatus,
    val direction: StemDirection,
    val boundingBox: BoundingBox? = null
)

/**
 * Structured rhythm output for one existing [ChordCandidate].
 *
 * [dottedDuration] is the final value after augmentation dots; for an
 * undotted note it equals the base duration's fraction. Every notehead in
 * [noteheads] shares this one group-level duration.
 */
data class RhythmCandidate(
    val id: Int,
    val noteGroupId: Int,
    val chord: ChordCandidate,
    val noteheads: List<NoteheadCandidate>,
    val evidenceStatus: RhythmEvidenceStatus,
    val stemDirection: StemDirection,
    val stemAssociation: StemAssociation,
    val beamCount: Int?,
    val flagCount: Int?,
    val dotCount: Int?,
    val dotEvidence: List<AugmentationDotEvidence>,
    val baseDuration: RhythmDuration?,
    val dottedDuration: RhythmValue?,
    val resolutionState: RhythmResolutionState,
    val unresolvedReasons: List<RhythmUnresolvedReason>
) {
    /** Compatibility alias for the former framework contract. */
    val duration: RhythmDuration? get() = baseDuration
}

/**
 * Borrowed, row-major source masks plus the existing validated staff grid.
 * Construction does not copy any full-page array.
 *
 * oemer creates `symbols_pred` by unioning generic symbols, stems/rests,
 * and clefs/keys. [RhythmExtractor] reproduces that union arithmetically
 * while scanning and does not retain another merged full-page mask.
 */
data class RhythmEvidenceMasks(
    val width: Int,
    val height: Int,
    val staff: BooleanArray,
    val symbols: BooleanArray,
    val stems: BooleanArray,
    val noteheads: BooleanArray,
    val clefsKeys: BooleanArray,
    val staffGrid: List<List<AssignedStaff>>,
    val barlines: List<BoundingBox> = emptyList()
) {
    init {
        val expected = width * height
        require(width > 0 && height > 0)
        require(staff.size == expected) { "staff size must be $expected" }
        require(symbols.size == expected) { "symbols size must be $expected" }
        require(stems.size == expected) { "stems size must be $expected" }
        require(noteheads.size == expected) { "noteheads size must be $expected" }
        require(clefsKeys.size == expected) { "clefsKeys size must be $expected" }
    }

    val isComplete: Boolean
        get() = staffGrid.isNotEmpty() && staffGrid.any { it.isNotEmpty() }

    companion object {
        fun from(
            masks: OmrClassMasks,
            staffGrid: List<List<AssignedStaff>>,
            barlines: List<BoundingBox> = emptyList()
        ): RhythmEvidenceMasks =
            RhythmEvidenceMasks(
                width = masks.width,
                height = masks.height,
                staff = masks.staff,
                symbols = masks.symbols,
                stems = masks.stemsRests,
                noteheads = masks.noteheads,
                clefsKeys = masks.clefsKeys,
                staffGrid = staffGrid,
                barlines = barlines
            )
    }
}

/**
 * Immutable rhythm interpretation of one verified classifier-stage rest.
 *
 * [source] retains the exact model classifications and spatial evidence.
 * The `rest_whole`/whole-or-half class remains unresolved because the
 * shipped oemer classifier does not distinguish those durations.
 */
data class RestRhythmResult(
    val restId: Int,
    val source: ClassifiedRestCandidate,
    val dotCount: Int,
    val baseDuration: RhythmDuration?,
    val dottedDuration: RhythmValue?,
    val resolutionState: RhythmResolutionState,
    val unresolvedReasons: List<RhythmUnresolvedReason>
) {
    /** Original exclusive-right/bottom classifier crop. */
    val boundingBox: BoundingBox get() = source.boundingBox

    /** Assigned staff track. */
    val track: Int get() = source.assignment.track

    /** Assigned staff/system group. */
    val group: Int get() = source.assignment.group

    /** Exact typed label emitted by the verified rest classifier route. */
    val label: RestSymbolLabel get() = source.label
}

/** Complete immutable rhythm-stage output for note groups and classified rests. */
data class RhythmExtractionResult(
    val noteGroups: List<RhythmCandidate>,
    val rests: List<RestRhythmResult>
)
