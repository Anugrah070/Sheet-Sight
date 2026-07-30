package com.sheetsight.app.data.omr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates OMR runs on behalf of future ViewModels and exposes progress
 * via [state], mirroring how [com.sheetsight.app.domain.repository.ScoreRepository]
 * sits between the UI layer and storage. This is the type a Phase 4.2+
 * ViewModel (or a `RunOmrUseCase`) is expected to depend on — it should
 * never need to know [OmrEngine] exists.
 *
 * Holds no reference to [com.sheetsight.app.data.local.ScoreFileStorage] or
 * [com.sheetsight.app.domain.repository.ScoreRepository] yet; wiring a run's
 * result back into a [com.sheetsight.app.domain.model.Score]'s
 * `musicXmlPath` is part of a future phase's pipeline, not this class.
 */
@Singleton
class OmrRepository @Inject constructor(
    private val omrEngine: OmrEngine
) {

    private val _state = MutableStateFlow<OmrState>(OmrState.Idle)

    /** Current lifecycle state of the most recent (or in-progress) OMR run. */
    val state: StateFlow<OmrState> = _state.asStateFlow()

    /**
     * Runs OMR on the image at [imagePath], driving [state] through
     * [OmrState.Recognizing] to [OmrState.Completed]/[OmrState.Failed].
     * [OnnxOmrEngine.recognize] still can't produce a real [OmrResult] —
     * see its KDoc — so today this always ends in [OmrState.Failed] and
     * rethrows; [state] is updated *before* rethrowing so an observing
     * caller sees a clean failure without needing to catch anything.
     *
     * Catches [Throwable], not [Exception]: [NotImplementedError] (what
     * [OnnxOmrEngine.recognize] currently throws) is a [kotlin.Error],
     * which `catch (e: Exception)` would silently miss, leaving [state]
     * stuck on [OmrState.Recognizing] forever.
     */
    suspend fun recognize(imagePath: String): OmrResult {
        _state.value = OmrState.Recognizing
        return try {
            val result = omrEngine.recognize(imagePath)
            _state.value = OmrState.Completed(result)
            result
        } catch (t: Throwable) {
            _state.value = OmrState.Failed(t.message ?: "OMR failed for an unknown reason")
            throw t
        }
    }
}