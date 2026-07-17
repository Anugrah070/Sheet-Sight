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
 * `musicXmlPath` is part of the Phase 4.2 pipeline, not this scaffolding.
 */
@Singleton
class OmrRepository @Inject constructor(
    private val omrEngine: OmrEngine
) {

    private val _state = MutableStateFlow<OmrState>(OmrState.Idle)

    /** Current lifecycle state of the most recent (or in-progress) OMR run. */
    val state: StateFlow<OmrState> = _state.asStateFlow()

    /**
     * Runs OMR on the image at [imagePath]. Not implemented until Phase
     * 4.2 — no preprocessing or inference happens as of Phase 4.1.
     */
    suspend fun recognize(imagePath: String): OmrResult {
        throw NotImplementedError(
            "OmrRepository.recognize() is not implemented until Phase 4.2."
        )
    }
}