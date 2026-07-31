package com.sheetsight.app.data.omr.debug

import android.graphics.Bitmap

/**
 * The diagnostic stages the OMR smoke test can stop after. Numbering
 * and labels match the required `[OMR_SMOKE] START/END` log format
 * exactly, so grepping logcat for a stage number tells you precisely
 * where a stall or process kill happened.
 *
 * Deliberately more granular than the production pipeline's own
 * grouping: [com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner]
 * runs both ONNX models back-to-back with no seam between them, but a
 * stall could be specific to just one model, so MODEL1_INFERENCE and
 * MODEL2_INFERENCE are split here even though nothing else in the
 * codebase draws that line.
 */
enum class SmokeTestStage(val stageNumber: Int, val label: String) {
    INPUT_DECODE(1, "Input decode"),
    PREPROCESSING(2, "Preprocessing"),
    TILING(3, "Tiling"),
    MODEL1_INFERENCE(4, "Model 1 inference"),
    MODEL2_INFERENCE(5, "Model 2 inference"),
    PREDICTION_MERGING(6, "Prediction-map merging"),
    CLASS_MASK_EXTRACTION(7, "Class-mask extraction"),
    DEWARPING(8, "Dewarping"),
    STAFF_GRID_ASSEMBLY(9, "Staffline/grid assembly"),
    NOTEHEAD_EXTRACTION(10, "Notehead extraction"),
    NOTE_GROUPING(11, "Note grouping"),
    SYMBOL_CLASSIFICATION(12, "Symbol classification"),
    RHYTHM_FRAMEWORK(13, "Rhythm extraction"),
    SEMANTIC_SCORE_CONSTRUCTION(14, "Semantic score construction"),
    MUSICXML_EXPORT(15, "MusicXML export");

    /** e.g. "STAGE 4 (Model 1 inference)" — used verbatim in every [OMR_SMOKE] log line. */
    val logName: String get() = "STAGE $stageNumber ($label)"
}

/** One stage's timing + detailed memory snapshot, taken immediately after the stage completes. */
data class OmrSmokeTestStageTiming(
    val stage: SmokeTestStage,
    val durationMs: Long,
    val memoryAfter: MemorySnapshot
)

/** One small labelled preview bitmap (~320px longest edge) for a completed stage. */
data class OmrSmokeTestPreview(val label: String, val bitmap: Bitmap)

/** Small preview thumbnails for all five [com.sheetsight.app.data.omr.inference.OmrClassMasks] layers. */
data class OmrSmokeTestMaskThumbnails(
    val staff: Bitmap,
    val symbols: Bitmap,
    val stemsRests: Bitmap,
    val noteheads: Bitmap,
    val clefsKeys: Bitmap
)

/**
 * Result of one diagnostic run: the pipeline executes stages 1..N in
 * order and stops right after the requested stop point (or after
 * whichever stage it reached before throwing). Never represents a
 * fabricated success — [lastCompletedStage] plus [errorMessage] together
 * tell the whole story of what actually happened.
 *
 * @property previews Only the stages that actually produced a visual are
 *   present; each entry is already a small thumbnail, not a full-page copy.
 * @property stageDetails Short text summaries (tile counts, tensor shapes,
 *   staff/track counts) for stages that don't have a natural bitmap preview.
 */
data class OmrSmokeTestDiagnosticResult(
    val lastCompletedStage: SmokeTestStage?,
    val stageDurations: List<OmrSmokeTestStageTiming>,
    val previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>,
    val stageDetails: Map<SmokeTestStage, List<String>>,
    val errorMessage: String? = null,
    /** App-private Stage 15 output; the debug UI may copy it to a user-selected document. */
    val musicXmlOutputPath: String? = null
)
