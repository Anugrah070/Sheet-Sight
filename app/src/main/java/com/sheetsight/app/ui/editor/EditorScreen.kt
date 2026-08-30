package com.sheetsight.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheetsight.app.R
import com.sheetsight.app.domain.model.Score
import android.content.res.Configuration
import android.util.Log
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapper
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapping

@Composable
fun EditorScreen(
    scoreId: Long?,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recognizedScores by viewModel.recognizedScores.collectAsStateWithLifecycle()
    val deletingScoreIds by viewModel.deletingScoreIds.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val noteEditInProgress by viewModel.noteEditInProgress.collectAsStateWithLifecycle()
    val pitchVisualUpdate by viewModel.pitchVisualUpdate.collectAsStateWithLifecycle()
    val visibleState = editorVisibleStateForRoute(scoreId, uiState)
    LaunchedEffect(scoreId) { viewModel.loadScore(scoreId ?: -1L) }
    EditorScreenContent(
        state = visibleState,
        recognizedScores = recognizedScores,
        deletingScoreIds = deletingScoreIds,
        feedback = feedback,
        selection = selection,
        noteEditInProgress = noteEditInProgress,
        pitchVisualUpdate = pitchVisualUpdate,
        modifier = modifier,
        onSelectScore = { selected -> viewModel.loadScore(selected.id) },
        onShowScorePicker = viewModel::showScorePicker,
        onDeleteGeneratedScore = viewModel::deleteGeneratedScore,
        onFeedbackShown = viewModel::onFeedbackShown,
        onRetry = viewModel::retry,
        onRenderError = viewModel::onRenderError,
        onSelectionChanged = viewModel::onSelectionChanged,
        onNoteDragBy = viewModel::moveSelectedNoteBy,
        onSystemChanged = viewModel::onSystemChanged,
        onZoomChanged = viewModel::onZoomChanged,
        onBack = onBack
    )
}

/** A null tab route intentionally permits a locally picked score to remain visible. */
internal fun editorVisibleStateForRoute(routeScoreId: Long?, uiState: EditorUiState): EditorUiState =
    if (routeScoreId != null && (uiState as? EditorUiState.Ready)?.scoreId?.let { it != routeScoreId } == true) {
        EditorUiState.Loading
    } else {
        uiState
    }

@Composable
fun EditorScreenContent(
    state: EditorUiState,
    recognizedScores: List<Score> = emptyList(),
    deletingScoreIds: Set<Long> = emptySet(),
    feedback: EditorFeedback? = null,
    selection: EditorSelection? = null,
    noteEditInProgress: Boolean = false,
    pitchVisualUpdate: EditorPitchVisualUpdate? = null,
    modifier: Modifier = Modifier,
    onSelectScore: (Score) -> Unit = {},
    onShowScorePicker: () -> Unit = {},
    onDeleteGeneratedScore: (Long) -> Unit = {},
    onFeedbackShown: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRenderError: (EditorSourceKey, String) -> Unit = { _, _ -> },
    onSelectionChanged: (EditorSelection?) -> Unit = {},
    onNoteDragBy: (Int) -> Unit = {},
    onSystemChanged: (Int) -> Unit = {},
    onZoomChanged: (Float) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val ready = state as? EditorUiState.Ready
    var scale by remember(ready?.scoreId) { mutableFloatStateOf(ready?.initialZoom ?: 1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedback) {
        val current = feedback ?: return@LaunchedEffect
        val message = when (current) {
            EditorFeedback.GeneratedScoreDeleted -> "Generated score deleted."
            is EditorFeedback.DeleteFailed -> current.message
            is EditorFeedback.PitchEditFailed -> current.message
        }
        snackbarHostState.showSnackbar(message)
        onFeedbackShown()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Show back-to-picker button when a score is loaded (not when navigated via deep link with scoreId)
    val showPickerBack = state !is EditorUiState.NoScoreSelected && state !is EditorUiState.Loading

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isFullscreen) {
                EditorViewerBar(
                    title = ready?.title ?: state.editorTitle(),
                    scale = scale,
                    isLandscape = isLandscape,
                    showScoreControls = ready != null,
                    showPickerBack = showPickerBack,
                    warning = ready?.warningSummary,
                    onBack = onBack,
                    onShowScorePicker = onShowScorePicker,
                    onToggleFullscreen = { isFullscreen = true },
                    onZoomOut = {
                        scale = (scale - ZOOM_STEP).coerceAtLeast(EditorViewModel.MIN_ZOOM)
                        onZoomChanged(scale)
                    },
                    onResetZoom = {
                        scale = 1f
                        onZoomChanged(scale)
                    },
                    onZoomIn = {
                        scale = (scale + ZOOM_STEP).coerceAtMost(EditorViewModel.MAX_ZOOM)
                        onZoomChanged(scale)
                    }
                )
            }
        }
    ) { padding ->
        val contentModifier = if (isFullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().padding(padding)
        }

        Box(modifier = contentModifier) {
            when (state) {
                EditorUiState.Loading -> EditorLoading(Modifier.fillMaxSize())
                EditorUiState.NoScoreSelected -> ScorePickerList(
                    scores = recognizedScores,
                    onSelectScore = onSelectScore,
                    deletingScoreIds = deletingScoreIds,
                    onDeleteGeneratedScore = onDeleteGeneratedScore,
                    modifier = Modifier.fillMaxSize()
                )
                is EditorUiState.NoCurrentMusicXml -> EditorMessage(
                    message = stringResource(R.string.editor_no_musicxml),
                    modifier = Modifier.fillMaxSize().testTag("editor_no_musicxml")
                )
                is EditorUiState.FileMissing -> EditorMessage(
                    message = stringResource(R.string.editor_file_missing),
                    modifier = Modifier.fillMaxSize().testTag("editor_file_missing"),
                    action = {
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                )
                is EditorUiState.UnreadableFile -> EditorMessage(
                    message = stringResource(R.string.editor_file_unreadable),
                    modifier = Modifier.fillMaxSize().testTag("editor_file_unreadable"),
                    action = { TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } }
                )
                is EditorUiState.EmptyFile -> EditorMessage(
                    message = stringResource(R.string.editor_file_empty),
                    modifier = Modifier.fillMaxSize().testTag("editor_file_empty")
                )
                is EditorUiState.ParseError -> EditorMessage(
                    message = stringResource(R.string.editor_parse_error),
                    modifier = Modifier.fillMaxSize().testTag("editor_parse_error"),
                    action = { TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } }
                )
                is EditorUiState.UnsupportedScore -> EditorMessage(
                    message = state.reason,
                    modifier = Modifier.fillMaxSize().testTag("editor_unsupported")
                )
                is EditorUiState.RenderError -> EditorMessage(
                    message = stringResource(R.string.editor_render_error),
                    modifier = Modifier.fillMaxSize().testTag("editor_render_error"),
                    action = { TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } }
                )
                is EditorUiState.ScoreNotFound -> EditorMessage(
                    message = stringResource(R.string.editor_score_not_found),
                    modifier = Modifier.fillMaxSize().testTag("editor_score_not_found")
                )
                is EditorUiState.Ready -> ReadyScore(
                    ready = state,
                    scale = scale,
                    modifier = Modifier.fillMaxSize(),
                    onSystemChanged = onSystemChanged,
                    onRenderError = onRenderError,
                    selection = selection,
                    onSelectionChanged = onSelectionChanged,
                    noteEditInProgress = noteEditInProgress,
                    pitchVisualUpdate = pitchVisualUpdate,
                    onNoteDragBy = onNoteDragBy,
                    onZoomGestureFinished = { gestureScale ->
                        scale = gestureScale.coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
                        onZoomChanged(scale)
                    }
                )
            }

            if (isFullscreen) {
                IconButton(
                    onClick = { isFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .padding(WindowInsets.statusBars.asPaddingValues())
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            MaterialTheme.shapes.small
                        )
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen")
                }
            }
        }
    }
}

@Composable
private fun EditorViewerBar(
    title: String,
    scale: Float,
    isLandscape: Boolean,
    showScoreControls: Boolean,
    showPickerBack: Boolean,
    warning: String?,
    onBack: () -> Unit,
    onShowScorePicker: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(statusBarPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(if (isLandscape) 40.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (showPickerBack) {
                            onShowScorePicker()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp).testTag("editor_back")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                }
                Text(
                    text = title,
                    style = if (isLandscape) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                if (showScoreControls) {
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
                    }
                    Box {
                        IconButton(
                            onClick = { expanded = true },
                            modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp).testTag("editor_more")
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.editor_more_options))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onZoomOut, modifier = Modifier.testTag("editor_zoom_out")) {
                                    Icon(Icons.Default.Remove, stringResource(R.string.editor_zoom_out))
                                }
                                Text("${(scale * 100).toInt()}%", modifier = Modifier.width(52.dp))
                                IconButton(onClick = onResetZoom, modifier = Modifier.testTag("editor_reset_zoom")) {
                                    Icon(Icons.Default.RestartAlt, stringResource(R.string.editor_reset_zoom))
                                }
                                IconButton(onClick = onZoomIn, modifier = Modifier.testTag("editor_zoom_in")) {
                                    Icon(Icons.Default.Add, stringResource(R.string.editor_zoom_in))
                                }
                            }
                            warning?.let {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
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
private fun ScorePickerList(
    scores: List<Score>,
    onSelectScore: (Score) -> Unit,
    deletingScoreIds: Set<Long>,
    onDeleteGeneratedScore: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeletion by remember { mutableStateOf<Score?>(null) }
    pendingDeletion?.let { score ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete generated score?") },
            text = {
                Text("This removes the generated MusicXML from the Editor. Your original imported PDF/image will remain in the Library.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        onDeleteGeneratedScore(score.id)
                    },
                    modifier = Modifier.testTag("editor_confirm_delete_${score.id}")
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            }
        )
    }
    if (scores.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No recognized scores yet.",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Run OMR on a score from your Library to make it available here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Generated scores",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(scores, key = { it.id }) { score ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = score.id !in deletingScoreIds) { onSelectScore(score) }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = score.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "MusicXML · Generated score",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            var expanded by remember(score.id) { mutableStateOf(false) }
                            IconButton(
                                onClick = { expanded = true },
                                enabled = score.id !in deletingScoreIds,
                                modifier = Modifier.testTag("editor_score_more_${score.id}")
                            ) {
                                if (score.id in deletingScoreIds) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Generated score options")
                                }
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Delete generated score") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        expanded = false
                                        pendingDeletion = score
                                    },
                                    modifier = Modifier.testTag("editor_delete_generated_${score.id}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun ReadyScore(
    ready: EditorUiState.Ready,
    scale: Float,
    modifier: Modifier,
    onSystemChanged: (Int) -> Unit,
    onRenderError: (EditorSourceKey, String) -> Unit,
    selection: EditorSelection?,
    onSelectionChanged: (EditorSelection?) -> Unit,
    noteEditInProgress: Boolean,
    pitchVisualUpdate: EditorPitchVisualUpdate?,
    onNoteDragBy: (Int) -> Unit,
    onZoomGestureFinished: (Float) -> Unit
) {
    var importedScore by remember(ready.renderSessionKey) { mutableStateOf<ImportedAlphaTabScore?>(null) }
    val identityMapping = remember(importedScore, ready.identityIndex) {
        importedScore?.let { loaded ->
            AlphaTabIdentityMapper.map(ready.identityIndex, loaded.score).also { mapping ->
                mapping.issues.forEach { issue ->
                    Log.w("SheetSightIdentity", "${issue.code} ${issue.stableIdentity}: ${issue.detail}")
                }
            }
        }
    }
    var playbackState by remember(ready.renderSessionKey) {
        mutableStateOf<EditorPlaybackState>(EditorPlaybackState.Initializing)
    }
    var playbackGeneration by remember(ready.renderSessionKey) { mutableStateOf(0) }
    val playbackCommands = remember(ready.renderSessionKey) { EditorPlaybackCommandHolder() }
    val renderSelection = identityMapping?.let { mapping ->
        EditorSelectionResolver.renderSelection(selection, ready.sourceKey, mapping)
    }
    val runtimePitchUpdate = identityMapping?.let { mapping ->
        pitchVisualUpdate?.let { update ->
            mapping.note(update.noteIdentity)?.let { note ->
                when (update) {
                    is EditorPitchVisualUpdate.Apply -> AlphaTabPitchVisualUpdate.Apply(
                        update.revision, note, update.pitchMidi
                    )
                    is EditorPitchVisualUpdate.Rollback -> AlphaTabPitchVisualUpdate.Rollback(update.revision, note)
                    is EditorPitchVisualUpdate.Commit -> AlphaTabPitchVisualUpdate.Commit(update.revision, note)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .testTag("editor_ready")
    ) {
        EditorPlaybackTransport(
            state = playbackState,
            onPlayPause = playbackCommands::togglePlayPause,
            onStop = playbackCommands::stop,
            onRetry = {
                playbackState = EditorPlaybackState.Initializing
                playbackGeneration++
            }
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AlphaTabScoreView(
                sourceKey = ready.renderSessionKey,
                musicXml = ready.musicXml,
                document = ready.document,
                initialSystemIndex = ready.initialSystemIndex,
                zoom = scale,
                onSystemChanged = onSystemChanged,
                allowNativeFallback = false,
                onRenderError = { message -> onRenderError(ready.sourceKey, message) },
                identityMapping = identityMapping,
                selection = renderSelection,
                pitchVisualUpdate = runtimePitchUpdate,
                onSelectionHit = { hit ->
                    val mapping = identityMapping
                    if (mapping == null) {
                        Log.w("SheetSightIdentity", "Selection ignored before identity mapping completed")
                        onSelectionChanged(null)
                    } else {
                        val resolution = EditorSelectionResolver.resolve(
                            sourceKey = ready.sourceKey,
                            identityIndex = ready.identityIndex,
                            mapping = mapping,
                            hit = hit
                        )
                        resolution.diagnostic?.let { diagnostic ->
                            Log.w("SheetSightIdentity", "Selection rejected: $diagnostic")
                        }
                        onSelectionChanged(resolution.selection)
                    }
                },
                onZoomGestureFinished = onZoomGestureFinished,
                onNoteDragBy = onNoteDragBy,
                onAlphaTabScoreLoaded = { loaded ->
                    importedScore = loaded
                },
                modifier = Modifier.fillMaxSize()
            )

            if (noteEditInProgress) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 2.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).testTag("editor_pitch_saving")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Saving",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            importedScore?.let { score ->
                key(ready.renderSessionKey, playbackGeneration) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .alpha(0f)
                            .testTag("editor_playback_host"),
                        factory = { context ->
                            val player = EditorAlphaTabPlaybackPlayer(context) { nextState ->
                                playbackState = nextState
                            }
                            player.view.apply {
                                tag = player
                                playbackCommands.player = player
                            }
                        },
                        update = { view ->
                            val player = view.tag as EditorAlphaTabPlaybackPlayer
                            playbackCommands.player = player
                            player.loadScore(ready.renderSessionKey, score.score)
                        },
                        onRelease = { view ->
                            val player = view.tag as? EditorAlphaTabPlaybackPlayer
                            player?.release()
                            if (playbackCommands.player === player) playbackCommands.player = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditorPlaybackTransport(
    state: EditorPlaybackState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryDescription = when (state) {
        EditorPlaybackState.Playing -> stringResource(R.string.editor_playback_pause)
        EditorPlaybackState.Paused -> stringResource(R.string.editor_playback_resume)
        else -> stringResource(R.string.editor_playback_play)
    }

    Surface(tonalElevation = 1.dp, modifier = modifier.fillMaxWidth().testTag("editor_playback_transport")) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                EditorPlaybackState.Initializing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).testTag("editor_playback_loading"),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.editor_playback_initializing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                }
                is EditorPlaybackState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("editor_playback_error")
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("editor_playback_retry")) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                else -> Spacer(modifier = Modifier.weight(1f))
            }

            IconButton(
                onClick = onPlayPause,
                enabled = state.canPlayPause,
                modifier = Modifier.testTag("editor_play_pause")
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = primaryDescription
                )
            }
            IconButton(
                onClick = onStop,
                enabled = state.canStop,
                modifier = Modifier.testTag("editor_stop")
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.editor_playback_stop))
            }
        }
    }
}

private const val ZOOM_STEP = 0.1f

private fun EditorUiState.editorTitle(): String = when (this) {
    is EditorUiState.Ready -> title
    is EditorUiState.NoCurrentMusicXml -> title
    is EditorUiState.FileMissing -> title
    is EditorUiState.UnreadableFile -> title
    is EditorUiState.EmptyFile -> title
    is EditorUiState.ParseError -> title
    is EditorUiState.UnsupportedScore -> title
    is EditorUiState.RenderError -> title
    else -> "Editor"
}
