package com.sheetsight.app.data.omr

/**
 * Lifecycle state of an OMR run, exposed by [OmrRepository] so a future
 * ViewModel (Phase 4.2+, likely feeding the Phase 5 Editor) can render
 * progress/error UI via [kotlinx.coroutines.flow.StateFlow] without polling.
 */
sealed interface OmrState {

    /** No OMR run has started, or the previous run's result was consumed. */
    data object Idle : OmrState

    /** OMR inference or post-processing is in progress with detailed metrics. */
    data class InProgress(val progress: OmrProgressUpdate) : OmrState

    /** The run finished successfully with [result]. */
    val result: OmrResult? get() = null
    data class Completed(override val result: OmrResult) : OmrState

    /** The run failed; [message] is user-facing, not a raw exception dump. */
    data class Failed(val message: String) : OmrState
}
