package com.sheetsight.app.data.omr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates OMR runs on behalf of ViewModels and exposes progress
 * via [state], mirroring how [com.sheetsight.app.domain.repository.ScoreRepository]
 * sits between the UI layer and storage.
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
     * [OmrState.InProgress] to [OmrState.Completed]/[OmrState.Failed].
     */
    suspend fun recognize(imagePath: String): OmrResult {
        _state.value = OmrState.InProgress(
            OmrProgressUpdate(OmrStage.INPUT_DECODE, 0, isIndeterminate = true)
        )
        return try {
            val result = omrEngine.recognize(imagePath, object : OmrProgressListener {
                override fun onProgressUpdate(update: OmrProgressUpdate) {
                    _state.value = OmrState.InProgress(update)
                }
            })
            _state.value = OmrState.Completed(result)
            result
        } catch (t: Throwable) {
            _state.value = OmrState.Failed(t.message ?: "OMR failed for an unknown reason")
            throw t
        }
    }
}
