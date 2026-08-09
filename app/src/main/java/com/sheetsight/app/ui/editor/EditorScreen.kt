package com.sheetsight.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.ui.editor.notation.NotationSystemCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    scoreId: Long?,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(scoreId) { viewModel.loadScore(scoreId ?: -1L) }
    EditorScreenContent(
        state = uiState,
        modifier = modifier,
        onRetry = viewModel::retry,
        onSystemChanged = viewModel::onSystemChanged,
        onZoomChanged = viewModel::onZoomChanged
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreenContent(
    state: EditorUiState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onSystemChanged: (Int) -> Unit = {},
    onZoomChanged: (Float) -> Unit = {}
) {
    val title = when (state) {
        is EditorUiState.Ready -> state.title
        is EditorUiState.NoMusicXml -> state.title
        is EditorUiState.FileMissing -> state.title
        is EditorUiState.ParseError -> state.title
        is EditorUiState.UnsupportedContent -> state.title
        else -> stringResource(R.string.nav_editor)
    }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(title, maxLines = 1) }) }
    ) { padding ->
        when (state) {
            EditorUiState.Loading -> EditorLoading(Modifier.fillMaxSize().padding(padding))
            EditorUiState.NoScoreSelected -> EditorMessage(
                message = stringResource(R.string.editor_no_score_selected),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_unresolved")
            )
            is EditorUiState.NoMusicXml -> EditorMessage(
                message = stringResource(R.string.editor_no_musicxml),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_no_musicxml")
            )
            is EditorUiState.FileMissing -> EditorMessage(
                message = stringResource(R.string.editor_file_missing),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_file_missing"),
                action = {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.action_retry))
                    }
                }
            )
            is EditorUiState.ParseError -> EditorMessage(
                message = stringResource(R.string.editor_parse_error),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_parse_error"),
                action = { TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } }
            )
            is EditorUiState.UnsupportedContent -> EditorMessage(
                message = state.reason,
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_unsupported")
            )
            is EditorUiState.ScoreNotFound -> EditorMessage(
                message = stringResource(R.string.editor_score_not_found),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("editor_score_not_found")
            )
            is EditorUiState.Ready -> ReadyScore(
                ready = state,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSystemChanged = onSystemChanged,
                onZoomChanged = onZoomChanged
            )
        }
    }
}

@Composable
private fun EditorLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("editor_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.editor_loading), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun EditorMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        action?.invoke()
    }
}

@Composable
private fun ReadyScore(
    ready: EditorUiState.Ready,
    modifier: Modifier,
    onSystemChanged: (Int) -> Unit,
    onZoomChanged: (Float) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ready.initialSystemIndex)
    val horizontalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scale by remember(ready.scoreId) { mutableFloatStateOf(ready.initialZoom) }
    val transformable = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
        if (nextScale != scale) {
            scale = nextScale
            onZoomChanged(nextScale)
        }
        if (scale > 1f && panChange.x != 0f) {
            scope.launch { horizontalScroll.scrollBy(-panChange.x) }
        }
        if (panChange.y != 0f) {
            scope.launch { listState.scrollBy(-panChange.y) }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect(onSystemChanged)
    }

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .transformable(transformable)
            .testTag("editor_ready")
    ) {
        val notationWidth = maxWidth * scale
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            ready.warningSummary?.let { warning ->
                item(key = "warning") {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            warning,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            items(
                count = ready.document.systems.size,
                key = { ready.document.systems[it].index }
            ) { index ->
                Box(
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .horizontalScroll(horizontalScroll)
                        .padding(horizontal = 8.dp)
                ) {
                    NotationSystemCard(
                        system = ready.document.systems[index],
                        modifier = Modifier.width(notationWidth).testTag("notation_system_$index")
                    )
                }
            }
            item(key = "summary") {
                val stats = ready.document.statistics
                Text(
                    stringResource(
                        R.string.editor_score_summary,
                        stats.measureCount,
                        ready.document.systems.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(scale * 100).toInt()}%", modifier = Modifier.padding(start = 12.dp))
                    IconButton(
                        onClick = {
                            scale = 1f
                            scope.launch { horizontalScroll.animateScrollTo(0) }
                            onZoomChanged(scale)
                        },
                        modifier = Modifier.testTag("editor_reset_zoom")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.editor_reset_zoom))
                    }
                }
            }
        }
    }
}
