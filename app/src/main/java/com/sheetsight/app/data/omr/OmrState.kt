package com.sheetsight.app.data.omr

/**
 * Lifecycle state of an OMR run, exposed by [OmrRepository] so a future
 * ViewModel (Phase 4.2+, likely feeding the Phase 5 Editor) can render
 * progress/error UI via [kotlinx.coroutines.flow.StateFlow] without polling.
 *
 * Naming a "Preprocessing" and "Recognizing" state here does not imply
 * either is implemented yet — no preprocessing or inference code exists
 * as of Phase 4.1. This only defines the shape the pipeline will report
 * through once it does.
 */
sealed interface OmrState {

    /** No OMR run has started, or the previous run's result was consumed. */
    data object Idle : OmrState

    /** Image is being prepared (deskew/normalize) ahead of model inference. */
    data object Preprocessing : OmrState

    /** ONNX Runtime Mobile inference is in progress. */
    data object Recognizing : OmrState

    /** The run finished successfully with [result]. */
    data class Completed(val result: OmrResult) : OmrState

    /** The run failed; [message] is user-facing, not a raw exception dump. */
    data class Failed(val message: String) : OmrState
}