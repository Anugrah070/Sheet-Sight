package com.sheetsight.app.data.omr.symbol

/**
 * One classifier result.
 *
 * [decisionScores] are ONNX SVM decision outputs, not calibrated
 * probabilities. Phase 4 verifies their numeric parity separately.
 */
data class SymbolClassification(
    val model: SvmModelKind,
    val classId: Int,
    val label: OemerSymbolLabel,
    val decisionScores: List<Float>
)
