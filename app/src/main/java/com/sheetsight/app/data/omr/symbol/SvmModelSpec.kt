package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.inference.OnnxAssetSpec

/** The four trained classifiers shipped by oemer 0.1.8. */
enum class SvmModelKind {
    CLEF,
    ACCIDENTAL,
    REST,
    REST_ABOVE_EIGHTH
}

/** A typed symbol label emitted by one of oemer's trained SVMs. */
sealed interface OemerSymbolLabel {
    val sourceName: String
}

/** Labels emitted by `clef.model`. */
enum class ClefSymbolLabel(override val sourceName: String) : OemerSymbolLabel {
    G_CLEF("gclef"),
    F_CLEF("fclef")
}

/** Labels emitted by `sfn.model`. */
enum class AccidentalSymbolLabel(override val sourceName: String) : OemerSymbolLabel {
    SHARP("sharp"),
    FLAT("flat"),
    NATURAL("natural")
}

/**
 * Labels emitted by the two rest models.
 *
 * oemer deliberately keeps the coarse model's `rest_whole` prediction
 * unresolved between whole and half rests until staff-position analysis.
 */
enum class RestSymbolLabel(override val sourceName: String) : OemerSymbolLabel {
    WHOLE_OR_HALF("rest_whole"),
    QUARTER("rest_quarter"),
    EIGHTH("rest_8th"),
    SIXTEENTH("rest_16th"),
    THIRTY_SECOND("rest_32nd"),
    SIXTY_FOURTH("rest_64th")
}

/**
 * Tensor and label metadata for ONNX exports of oemer 0.1.8's four
 * sklearn `SVC` artifacts.
 *
 * The exports retain `ai.onnx.ml::SVMClassifier`; input values are the
 * same raw 40x70 intensity vectors consumed by
 * `oemer/classifier.py::predict()`.
 */
enum class SvmModelSpec(
    val kind: SvmModelKind,
    override val assetPath: String,
    val labels: List<OemerSymbolLabel>
) : OnnxAssetSpec {
    CLEF(
        SvmModelKind.CLEF,
        "models/svm/oemer_clef_svc.onnx",
        ClefSymbolLabel.entries
    ),
    ACCIDENTAL(
        SvmModelKind.ACCIDENTAL,
        "models/svm/oemer_sfn_svc.onnx",
        AccidentalSymbolLabel.entries
    ),
    REST(
        SvmModelKind.REST,
        "models/svm/oemer_rests_svc.onnx",
        listOf(
            RestSymbolLabel.WHOLE_OR_HALF,
            RestSymbolLabel.QUARTER,
            RestSymbolLabel.EIGHTH
        )
    ),
    REST_ABOVE_EIGHTH(
        SvmModelKind.REST_ABOVE_EIGHTH,
        "models/svm/oemer_rests_above8_svc.onnx",
        listOf(
            RestSymbolLabel.EIGHTH,
            RestSymbolLabel.SIXTEENTH,
            RestSymbolLabel.THIRTY_SECOND,
            RestSymbolLabel.SIXTY_FOURTH
        )
    );

    /** Resolves an ONNX integer class id without accepting unknown ids. */
    fun labelFor(classId: Int): OemerSymbolLabel =
        labels.getOrNull(classId)
            ?: throw IllegalStateException("$name emitted unsupported class id $classId")

    companion object {
        const val INPUT_TENSOR_NAME = "input"
        const val LABEL_OUTPUT_TENSOR_NAME = "label"
        const val SCORE_OUTPUT_TENSOR_NAME = "probabilities"
        const val FEATURE_WIDTH = 40
        const val FEATURE_HEIGHT = 70
        const val FEATURE_COUNT = FEATURE_WIDTH * FEATURE_HEIGHT

        /** Returns the single model specification for [kind]. */
        fun forKind(kind: SvmModelKind): SvmModelSpec =
            entries.single { it.kind == kind }
    }
}
