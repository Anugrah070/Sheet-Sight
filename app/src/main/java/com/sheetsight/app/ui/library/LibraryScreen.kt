package com.sheetsight.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.ui.common.PlaceholderContent

private val FavoriteGold = Color(0xFFFFD700)

/**
 * Library tab: search, sort, grid/list browsing, favorite, rename, delete,
 * pull-to-refresh, and import. OMR/MusicXML generation is still Phase 4 —
 * import only stores the raw file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
    onOpenScore: (Long) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val renameTarget by viewModel.renameTarget.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onFileSelectedForImport) }

    renameTarget?.let { score ->
        RenameScoreDialog(
            score = score,
            onConfirm = viewModel::onRenameConfirmed,
            onDismiss = viewModel::onRenameCancelled
        )
    }

    deleteTarget?.let { score ->
        DeleteScoreDialog(
            score = score,
            onConfirm = viewModel::onDeleteConfirmed,
            onDismiss = viewModel::onDeleteCancelled
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!uiState.isImporting) {
                        importLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                    }
                }
            ) {
                if (uiState.isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_import_fab_cd))
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                LibraryToolbar(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChange,
                    viewMode = uiState.viewMode,
                    onViewModeToggled = viewModel::onViewModeToggled
                )

                SortOptionRow(
                    selected = uiState.sortOption,
                    onSelected = viewModel::onSortOptionSelected
                )

                when {
                    uiState.isLoading -> Unit
                    uiState.allScoresEmpty -> PlaceholderContent(
                        message = stringResource(R.string.library_placeholder),
                        modifier = Modifier.fillMaxSize()
                    )
                    uiState.scores.isEmpty() -> PlaceholderContent(
                        message = stringResource(R.string.library_no_results),
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> LibraryContent(
                        scores = uiState.scores,
                        viewMode = uiState.viewMode,
                        onOpen = { score ->
                            viewModel.onScoreOpened(score)
                            onOpenScore(score.id)
                        },
                        onToggleFavorite = viewModel::onToggleFavorite,
                        onRename = viewModel::onRenameRequested,
                        onDelete = viewModel::onDeleteRequested
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    viewMode: LibraryViewMode,
    onViewModeToggled: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear_cd))
                    }
                }
            },
            singleLine = true
        )
        IconButton(onClick = onViewModeToggled) {
            Icon(
                imageVector = if (viewMode == LibraryViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList,
                contentDescription = stringResource(
                    if (viewMode == LibraryViewMode.LIST) R.string.library_view_grid_cd else R.string.library_view_list_cd
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOptionRow(
    selected: LibrarySortOption,
    onSelected: (LibrarySortOption) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibrarySortOption.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(stringResource(option.labelRes)) }
            )
        }
    }
}

@Composable
private fun LibraryContent(
    scores: List<Score>,
    viewMode: LibraryViewMode,
    onOpen: (Score) -> Unit,
    onToggleFavorite: (Score) -> Unit,
    onRename: (Score) -> Unit,
    onDelete: (Score) -> Unit
) {
    if (viewMode == LibraryViewMode.LIST) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(scores, key = { it.id }) { score ->
                ScoreListItem(
                    score = score,
                    onOpen = { onOpen(score) },
                    onToggleFavorite = { onToggleFavorite(score) },
                    onRename = { onRename(score) },
                    onDelete = { onDelete(score) }
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(scores, key = { it.id }) { score ->
                ScoreGridItem(
                    score = score,
                    onOpen = { onOpen(score) },
                    onToggleFavorite = { onToggleFavorite(score) },
                    onRename = { onRename(score) },
                    onDelete = { onDelete(score) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScoreItemCard(
    score: Score,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable (Boolean, () -> Unit, () -> Unit) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = { menuExpanded = true }
        )
    ) {
        content(menuExpanded, { menuExpanded = true }, { menuExpanded = false })
    }
}

@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
            contentDescription = stringResource(R.string.library_favorite_cd),
            tint = if (isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScoreActionsButton(
    scoreTitle: String,
    menuExpanded: Boolean,
    isFavorite: Boolean,
    onShowMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        IconButton(onClick = onShowMenu) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.library_item_actions_cd, scoreTitle)
            )
        }
        ScoreActionsMenu(
            expanded = menuExpanded,
            isFavorite = isFavorite,
            onDismiss = onDismissMenu,
            onOpen = onOpen,
            onToggleFavorite = onToggleFavorite,
            onRename = onRename,
            onDelete = onDelete
        )
    }
}

@Composable
private fun ScoreListItem(
    score: Score,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ScoreItemCard(
        score = score,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        onOpen = onOpen,
        onToggleFavorite = onToggleFavorite,
        onRename = onRename,
        onDelete = onDelete
    ) { menuExpanded, onShowMenu, onDismissMenu ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = score.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${score.pageCount} pages",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            FavoriteButton(isFavorite = score.isFavorite, onClick = onToggleFavorite)
            ScoreActionsButton(
                scoreTitle = score.title,
                menuExpanded = menuExpanded,
                isFavorite = score.isFavorite,
                onShowMenu = onShowMenu,
                onDismissMenu = onDismissMenu,
                onOpen = onOpen,
                onToggleFavorite = onToggleFavorite,
                onRename = onRename,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun ScoreGridItem(
    score: Score,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ScoreItemCard(
        score = score,
        modifier = Modifier.fillMaxWidth(),
        onOpen = onOpen,
        onToggleFavorite = onToggleFavorite,
        onRename = onRename,
        onDelete = onDelete
    ) { menuExpanded, onShowMenu, onDismissMenu ->
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = score.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2
            )
            Text(
                text = "${score.pageCount} pages",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FavoriteButton(isFavorite = score.isFavorite, onClick = onToggleFavorite)
                ScoreActionsButton(
                    scoreTitle = score.title,
                    menuExpanded = menuExpanded,
                    isFavorite = score.isFavorite,
                    onShowMenu = onShowMenu,
                    onDismissMenu = onDismissMenu,
                    onOpen = onOpen,
                    onToggleFavorite = onToggleFavorite,
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        }
    }
}

/** Shared long-press / overflow menu contents for both list and grid items. */
@Composable
private fun ScoreActionsMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_action_open)) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); onOpen() }
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isFavorite) R.string.library_action_unfavorite else R.string.library_action_favorite
                    )
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            onClick = { onDismiss(); onToggleFavorite() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_action_rename)) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = { onDismiss(); onRename() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_action_delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@Composable
private fun RenameScoreDialog(
    score: Score,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(score.id) { mutableStateOf(score.title) }
    val isBlank = text.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.library_rename_label)) },
                    isError = isBlank,
                    singleLine = true
                )
                if (isBlank) {
                    Text(
                        text = stringResource(R.string.library_rename_blank_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = !isBlank) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun DeleteScoreDialog(
    score: Score,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_delete_dialog_title)) },
        text = { Text(stringResource(R.string.library_delete_dialog_message, score.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
