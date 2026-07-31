package com.sheetsight.app.ui.debug

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.data.omr.OmrProgressListener
import com.sheetsight.app.data.omr.OmrProgressUpdate
import com.sheetsight.app.data.omr.debug.OmrSmokeTestDiagnosticResult
import com.sheetsight.app.data.omr.debug.OmrSmokeTestRunner
import com.sheetsight.app.data.omr.debug.SmokeTestStage
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject

/**
 * UI state for [OmrSmokeTestScreen] — a developer-only diagnostic tool,
 * not part of the production Library/Preview/Editor flow.
 *
 * @property stopAfter The stage the *next* run will stop after. Defaults
 *   to [SmokeTestStage.INPUT_DECODE] (the conservative starting point)
 *   and is reset back to that whenever a new score is picked.
 * @property diagnostic The most recent run's [OmrSmokeTestDiagnosticResult] —
 *   always the real pipeline's actual progress, never a fabricated success.
 */
data class OmrSmokeTestUiState(
    val scores: List<Score> = emptyList(),
    val selectedScore: Score? = null,
    val isRunning: Boolean = false,
    val stopAfter: SmokeTestStage = SmokeTestStage.INPUT_DECODE,
    val diagnostic: OmrSmokeTestDiagnosticResult? = null,
    val progress: OmrProgressUpdate? = null,
    val error: String? = null,
    val isSavingMusicXml: Boolean = false,
    val musicXmlSaveMessage: String? = null,
    val musicXmlSaveError: String? = null
)

@HiltViewModel
class OmrSmokeTestViewModel @Inject constructor(
    private val scoreRepository: ScoreRepository,
    private val smokeTestRunner: OmrSmokeTestRunner,
    private val scoreFileStorage: ScoreFileStorage,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(OmrSmokeTestUiState())
    val uiState: StateFlow<OmrSmokeTestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            scoreRepository.getAllScores().collect { scores ->
                _uiState.update { it.copy(scores = scores) }
            }
        }
    }

    fun onScoreSelected(score: Score) {
        _uiState.update {
            it.copy(
                selectedScore = score,
                stopAfter = SmokeTestStage.INPUT_DECODE, // conservative reset per score
                diagnostic = null,
                progress = null,
                error = null,
                isSavingMusicXml = false,
                musicXmlSaveMessage = null,
                musicXmlSaveError = null
            )
        }
    }

    /** Developer explicitly picks a stop point from the dropdown. */
    fun onStopAfterSelected(stage: SmokeTestStage) {
        _uiState.update { it.copy(stopAfter = stage) }
    }

    /** Re-runs from the beginning, stopping after the currently selected [OmrSmokeTestUiState.stopAfter]. */
    fun onRunRequested() {
        val score = _uiState.value.selectedScore ?: return
        if (_uiState.value.isRunning) return
        runSmokeTest(score.originalFilePath, _uiState.value.stopAfter)
    }

    /**
     * Moves the stop point one stage further and re-runs from scratch up
     * to it — the "Run through stage" control the diagnostic workflow
     * calls for. Deliberately re-runs everything rather than resuming
     * from the previous run's in-memory objects, since none of that
     * would have survived a real process kill anyway.
     */
    fun onAdvanceToNextStageRequested() {
        val score = _uiState.value.selectedScore ?: return
        if (_uiState.value.isRunning) return
        val stages = SmokeTestStage.entries
        val nextStage = stages.getOrNull(stages.indexOf(_uiState.value.stopAfter) + 1) ?: return
        _uiState.update { it.copy(stopAfter = nextStage) }
        runSmokeTest(score.originalFilePath, nextStage)
    }

    /** Copies Stage 15's app-private file to the document selected by the developer. */
    fun onPersistentMusicXmlTargetSelected(destinationUri: Uri) {
        val sourcePath = _uiState.value.diagnostic?.musicXmlOutputPath ?: return
        if (_uiState.value.isSavingMusicXml) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSavingMusicXml = true,
                    musicXmlSaveMessage = null,
                    musicXmlSaveError = null
                )
            }
            try {
                val bytes = withContext(ioDispatcher) {
                    scoreFileStorage.copyMusicXmlToDocument(sourcePath, destinationUri)
                }
                _uiState.update {
                    it.copy(
                        musicXmlSaveMessage = "Saved persistent MusicXML copy ($bytes bytes)."
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(
                        musicXmlSaveError = "Could not save MusicXML: ${t.message ?: t::class.java.simpleName}"
                    )
                }
            } finally {
                _uiState.update { it.copy(isSavingMusicXml = false) }
            }
        }
    }

    private fun runSmokeTest(imagePath: String, stopAfter: SmokeTestStage) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    error = null,
                    progress = null,
                    musicXmlSaveMessage = null,
                    musicXmlSaveError = null
                )
            }
            try {
                val result = smokeTestRunner.run(imagePath, stopAfter, object : OmrProgressListener {
                    override fun onProgressUpdate(update: OmrProgressUpdate) {
                        _uiState.update { it.copy(progress = update) }
                    }
                })
                _uiState.update { it.copy(diagnostic = result, error = result.errorMessage) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update { it.copy(error = "Smoke test crashed: ${t::class.java.name}: ${t.message}") }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }
}
