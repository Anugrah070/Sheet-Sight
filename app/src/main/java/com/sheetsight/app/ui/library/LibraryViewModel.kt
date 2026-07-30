package com.sheetsight.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheetsight.app.R
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import com.sheetsight.app.domain.usecase.DeleteScoreUseCase
import com.sheetsight.app.domain.usecase.ImportOutcome
import com.sheetsight.app.domain.usecase.ImportScoreUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val REFRESH_MIN_DURATION_MS = 400L

/**
 * UI state for the Library tab's main content (list/grid + search/sort/import).
 * Transient dialog and gesture state (rename/delete dialogs, pull-to-refresh)
 * are exposed as separate [StateFlow]s on the ViewModel instead of folded in
 * here, since they're independent of the list itself.
 *
 * @property allScoresEmpty True once loaded if the library has no scores at all —
 *   distinct from [scores] being empty due to an active search filter.
 * @property scores The scores to display: all stored scores, filtered by
 *   [searchQuery] and ordered by [sortOption].
 * @property isLoading True only until the first emission arrives from Room.
 * @property isImporting True while a file selected via the FAB is being
 *   copied into local storage and its metadata written to Room.
 */
data class LibraryUiState(
    val scores: List<Score> = emptyList(),
    val allScoresEmpty: Boolean = false,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val sortOption: LibrarySortOption = LibrarySortOption.DATE_IMPORTED,
    val isImporting: Boolean = false,
    val viewMode: LibraryViewMode = LibraryViewMode.LIST
)

/**
 * Observes [ScoreRepository] and exposes [LibraryUiState] to [LibraryScreen]
 * via [StateFlow], combined with local search/sort/import/view-mode UI state.
 *
 * Import/delete success/failure messages are delivered via [events] rather
 * than [uiState], since they're one-shot notifications (snackbars) and
 * should not be re-shown on configuration change the way persistent state
 * would be.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scoreRepository: ScoreRepository,
    private val importScoreUseCase: ImportScoreUseCase,
    private val deleteScoreUseCase: DeleteScoreUseCase
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val sortOption = MutableStateFlow(LibrarySortOption.DATE_IMPORTED)
    private val isImporting = MutableStateFlow(false)
    private val viewMode = MutableStateFlow(LibraryViewMode.LIST)

    private val _isRefreshing = MutableStateFlow(false)
    /** Drives the pull-to-refresh spinner. See [onRefresh] for what "refresh" means here. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _renameTarget = MutableStateFlow<Score?>(null)
    /** The score currently being renamed via dialog, or null if no rename is in progress. */
    val renameTarget: StateFlow<Score?> = _renameTarget.asStateFlow()

    private val _deleteTarget = MutableStateFlow<Score?>(null)
    /** The score pending delete confirmation, or null if no delete is in progress. */
    val deleteTarget: StateFlow<Score?> = _deleteTarget.asStateFlow()

    private val eventChannel = Channel<String>(Channel.BUFFERED)
    /** One-shot UI events (import/delete success/error messages) for the screen to show as snackbars. */
    val events: Flow<String> = eventChannel.receiveAsFlow()

    private val allScores = scoreRepository.getAllScores()

    private val filteredScores = combine(
        allScores,
        searchQuery,
        sortOption
    ) { scores, query, sort ->
        scores.filterByQuery(query).sortedWith(sort)
    }.distinctUntilChanged()

    val uiState: StateFlow<LibraryUiState> = combine(
        filteredScores,
        allScores.map { it.isEmpty() }.distinctUntilChanged(),
        searchQuery,
        sortOption,
        isImporting,
        viewMode
    ) { args ->
        LibraryUiState(
            scores = args[0] as List<Score>,
            allScoresEmpty = args[1] as Boolean,
            isLoading = false,
            searchQuery = args[2] as String,
            sortOption = args[3] as LibrarySortOption,
            isImporting = args[4] as Boolean,
            viewMode = args[5] as LibraryViewMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = LibraryUiState()
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onSortOptionSelected(option: LibrarySortOption) {
        sortOption.value = option
    }

    fun onViewModeToggled() {
        viewMode.value = if (viewMode.value == LibraryViewMode.LIST) {
            LibraryViewMode.GRID
        } else {
            LibraryViewMode.LIST
        }
    }

    fun onToggleFavorite(score: Score) {
        viewModelScope.launch {
            scoreRepository.updateScore(score.copy(isFavorite = !score.isFavorite))
        }
    }

    /**
     * Records that [score] was opened (updates [Score.lastOpenedDate], which
     * feeds the "Recently Practiced" sort). There's no
     * Editor/Practice destination to navigate to yet (Phase 5/6), so this is
     * currently the entire "open" action.
     */
    fun onScoreOpened(score: Score) {
        viewModelScope.launch {
            scoreRepository.markOpened(score.id, System.currentTimeMillis())
        }
    }

    /**
     * Manually re-syncs the visible list with Room. Note: [uiState] is
     * already backed by a live [Flow], so the list is never actually stale —
     * this exists to satisfy the expected pull-to-refresh gesture and as a
     * hook for future work (e.g. re-validating that imported files still
     * exist on disk). The minimum duration is purely so the spinner doesn't
     * flash instantaneously and read as broken.
     */
    fun onRefresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(REFRESH_MIN_DURATION_MS)
            _isRefreshing.value = false
        }
    }

    fun onRenameRequested(score: Score) {
        _renameTarget.value = score
    }

    fun onRenameCancelled() {
        _renameTarget.value = null
    }

    fun onRenameConfirmed(newTitle: String) {
        val target = _renameTarget.value ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return // Screen validates and blocks confirm on blank input.
        viewModelScope.launch {
            scoreRepository.updateScore(target.copy(title = trimmed))
            _renameTarget.value = null
        }
    }

    fun onDeleteRequested(score: Score) {
        _deleteTarget.value = score
    }

    fun onDeleteCancelled() {
        _deleteTarget.value = null
    }

    fun onDeleteConfirmed() {
        val target = _deleteTarget.value ?: return
        viewModelScope.launch {
            deleteScoreUseCase(target)
            _deleteTarget.value = null
            eventChannel.send(context.getString(R.string.library_delete_success, target.title))
        }
    }

    /** Kicks off import of the file the user picked via the SAF document picker. */
    fun onFileSelectedForImport(uri: Uri) {
        if (isImporting.value) return // Ignore taps while an import is already in flight.
        viewModelScope.launch {
            isImporting.value = true
            val outcome = importScoreUseCase(uri)
            isImporting.value = false
            val message = when (outcome) {
                is ImportOutcome.Success -> context.getString(R.string.import_success, outcome.score.title)
                is ImportOutcome.Failure -> outcome.message
            }
            eventChannel.send(message)
        }
    }

    private fun List<Score>.filterByQuery(query: String): List<Score> =
        if (query.isBlank()) this else filter { it.title.contains(query, ignoreCase = true) }

    private fun List<Score>.sortedWith(sort: LibrarySortOption): List<Score> {
        val comparator = compareByDescending<Score> { it.isFavorite }
            .then(when (sort) {
                LibrarySortOption.NAME -> compareBy { it.title.lowercase() }
                LibrarySortOption.DATE_IMPORTED -> compareByDescending { it.importDate }
                LibrarySortOption.RECENTLY_PRACTICED -> compareByDescending { it.lastOpenedDate ?: 0L }
            })
        return sortedWith(comparator)
    }
}
