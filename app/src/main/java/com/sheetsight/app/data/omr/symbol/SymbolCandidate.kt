package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.BoundingBox

/** Staff ownership shared by classified symbols. */
data class SymbolStaffAssignment(
    val track: Int,
    val group: Int
)

/** A geometrically verified barline; oemer has no barline SVM. */
data class MusicalBarlineCandidate(
    val boundingBox: BoundingBox,
    val group: Int
)

/** One trained-SVM clef result. */
data class ClefCandidate(
    val boundingBox: BoundingBox,
    val label: ClefSymbolLabel,
    val assignment: SymbolStaffAssignment,
    val classification: SymbolClassification
)

/** One trained-SVM sharp, flat, or natural result. */
data class AccidentalCandidate(
    val boundingBox: BoundingBox,
    val label: AccidentalSymbolLabel,
    val assignment: SymbolStaffAssignment,
    val nearbyNoteheadId: Int?,
    val classification: SymbolClassification
)

/**
 * One real two-stage rest classification.
 *
 * [hasAugmentationDot] reproduces oemer's symbol-pixel count beside the
 * rest. Construction enforces the exact coarse/refined model route so a
 * rhythm consumer cannot mistake an unrelated or synthetic symbol result
 * for a verified rest classification.
 */
data class ClassifiedRestCandidate(
    val boundingBox: BoundingBox,
    val label: RestSymbolLabel,
    val assignment: SymbolStaffAssignment,
    val hasAugmentationDot: Boolean,
    val coarseClassification: SymbolClassification,
    val refinedClassification: SymbolClassification?
) {
    init {
        val coarseLabel = validatedLabel(coarseClassification, SvmModelKind.REST)
        validateRefinement(coarseLabel)
    }

    private fun validateRefinement(coarseLabel: RestSymbolLabel) {
        if (coarseLabel == RestSymbolLabel.EIGHTH) {
            require(refinedClassification != null) {
                "coarse eighth rests require above-eighth refinement"
            }
            val refinedLabel = validatedLabel(
                refinedClassification,
                SvmModelKind.REST_ABOVE_EIGHTH
            )
            require(refinedLabel == label) {
                "the final rest label must match above-eighth refinement"
            }
        } else {
            require(refinedClassification == null) {
                "only coarse eighth rests may have refinement"
            }
            require(coarseLabel == label) {
                "the final rest label must match coarse classification"
            }
        }
    }

    private fun validatedLabel(
        classification: SymbolClassification,
        expectedModel: SvmModelKind
    ): RestSymbolLabel {
        require(classification.model == expectedModel) {
            "classification must come from $expectedModel"
        }
        val restLabel = classification.label as? RestSymbolLabel
        require(restLabel != null) { "classification must emit a rest label" }
        require(SvmModelSpec.forKind(expectedModel).labelFor(classification.classId) == restLabel) {
            "classification label must match its model class id"
        }
        return restLabel
    }
}

/** Immutable result of oemer-compatible symbol extraction. */
data class SymbolExtractionResult(
    val barlines: List<MusicalBarlineCandidate>,
    val clefs: List<ClefCandidate>,
    val accidentals: List<AccidentalCandidate>,
    val rests: List<ClassifiedRestCandidate>,
    val barlineDiagnostics: MusicalBarlineDiagnostics? = null
)
