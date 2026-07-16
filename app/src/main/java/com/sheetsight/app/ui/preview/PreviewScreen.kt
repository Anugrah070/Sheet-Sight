package com.sheetsight.app.ui.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.WidthFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sheetsight.app.R
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.ui.common.PlaceholderContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Professional Sheet Music Preview screen.
 * Optimized for sheet music reading with fit modes, fullscreen toggle,
 * and persistent zoom/page state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    scoreId: Long,
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Load score only once or when ID changes
    LaunchedEffect(scoreId) {
        viewModel.loadScore(scoreId)
    }

    // Scroll to last viewed page once the score is loaded
    LaunchedEffect(uiState.score) {
        val score = uiState.score
        if (score != null && listState.firstVisibleItemIndex == 0 && score.lastViewedPage > 0) {
            listState.scrollToItem(score.lastViewedPage)
        }
    }

    // Save current page as the user scrolls
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.onPageChanged(it) }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = !uiState.isFullscreen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.score?.title ?: "",
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (uiState.score != null) {
                                Text(
                                    text = stringResource(
                                        R.string.preview_page_indicator,
                                        listState.firstVisibleItemIndex + 1,
                                        uiState.score!!.pageCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when {
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
                uiState.error != null -> {
                    PlaceholderContent(
                        message = uiState.error ?: stringResource(R.string.preview_error),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                uiState.score != null -> {
                    SheetViewer(
                        score = uiState.score!!,
                        listState = listState,
                        isFullscreen = uiState.isFullscreen,
                        onToggleFullscreen = viewModel::onToggleFullscreen,
                        onZoomChanged = viewModel::onZoomChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetViewer(
    score: Score,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onZoomChanged: (Float) -> Unit
) {
    val file = remember(score.originalFilePath) { File(score.originalFilePath) }
    val isPdf = remember(file) { file.extension.lowercase() == "pdf" }

    var scale by remember { mutableFloatStateOf(score.lastViewedZoom) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += offsetChange
        onZoomChanged(scale)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleFullscreen() },
                    onDoubleTap = {
                        scale = if (scale > 1.1f) 1f else 2f
                        offset = Offset.Zero
                        onZoomChanged(scale)
                    }
                )
            }
            .transformable(state = transformableState)
    ) {
        val viewerHeight = maxHeight

        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        ) {
            items(score.pageCount, key = { it }) { index ->
                if (isPdf) {
                    PdfPage(
                        file = file,
                        pageIndex = index,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ImagePage(
                        file = file,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Overlay Actions
        AnimatedVisibility(
            visible = !isFullscreen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        onZoomChanged(scale)
                    },
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.medium
                    )
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset Zoom")
                }
                IconButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        onZoomChanged(scale)
                    },
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.medium
                    )
                ) {
                    Icon(Icons.Default.WidthFull, contentDescription = "Fit Width")
                }
                IconButton(
                    onClick = {
                        // Fit Page: set scale so typical A4 fits height
                        scale = (viewerHeight.value / (viewerHeight.value * 1.41f)).coerceAtMost(0.8f)
                        offset = Offset.Zero
                        onZoomChanged(scale)
                    },
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.medium
                    )
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = "Fit Page")
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    file: File,
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(file, pageIndex) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val width = page.width * 2
                            val height = page.height * 2
                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap = bmp
                        }
                    }
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    Box(
        modifier = modifier
            .padding(vertical = 2.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun ImagePage(
    file: File,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = file,
        contentDescription = null,
        modifier = modifier
            .padding(vertical = 2.dp)
            .background(Color.White),
        contentScale = ContentScale.FillWidth
    )
}
