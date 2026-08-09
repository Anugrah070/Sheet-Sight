package com.sheetsight.app.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sheetsight.app.R
import com.sheetsight.app.domain.practice.MatchState
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.ui.editor.EditorViewModel
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationSystemCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PracticeScoreViewport(
    document: NotationDocument,
    state: PracticeUiState,
    modifier: Modifier = Modifier
) {
    val progress = state.progress
    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var scale by remember(document) { mutableFloatStateOf(1f) }
    var targetVisible by remember { mutableStateOf(true) }
    var previousTarget by remember { mutableStateOf<HighlightTarget?>(null) }
    var successSourceIds by remember { mutableStateOf(emptySet<String>()) }

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("practice_score")
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val systemWidthPx = viewportWidthPx * scale
        val systemWidth = maxWidth * scale
        val scoreIndex = remember(document, systemWidthPx, density.density) {
            PracticeRenderedScoreIndex.create(document, systemWidthPx, density.density)
        }
        val currentTarget = remember(progress.currentStepIndex, progress.phase, scoreIndex) {
            PracticeDisplayState.currentHighlight(progress, scoreIndex)
        }

        fun viewport(): PracticeViewport {
            val layout = listState.layoutInfo
            return PracticeViewport(
                widthPx = viewportWidthPx,
                heightPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat(),
                horizontalOffsetPx = horizontalScroll.value.toFloat(),
                visibleSystems = layout.visibleItemsInfo.map { item ->
                    VisibleSystem(
                        systemIndex = item.index,
                        topPx = (item.offset - layout.viewportStartOffset).toFloat(),
                        bottomPx = (item.offset + item.size - layout.viewportStartOffset).toFloat()
                    )
                }
            )
        }

        suspend fun follow(target: HighlightTarget) {
            val request = PracticeAutoFollow.request(target, viewport(), with(density) { 20.dp.toPx() }) ?: return
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == request.systemIndex }) {
                listState.animateScrollToItem(request.systemIndex)
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == request.systemIndex }
                if (visible != null) {
                    val screenTop = visible.offset + target.bounds.top
                    val screenBottom = visible.offset + target.bounds.bottom
                    if (screenTop < 0f || screenBottom > listState.layoutInfo.viewportEndOffset) {
                        listState.animateScrollToItem(request.systemIndex)
                    }
                }
            }
            request.horizontalOffsetPx?.let { horizontalScroll.animateScrollTo(it.toInt()) }
        }

        val transformable = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
            if (panChange.x != 0f) scope.launch { horizontalScroll.scrollBy(-panChange.x) }
            if (panChange.y != 0f) scope.launch { listState.scrollBy(-panChange.y) }
        }

        LaunchedEffect(currentTarget?.practiceStepIndex, progress.phase) {
            val oldTarget = previousTarget
            if (progress.matchState.advancesPlayableNote && oldTarget != null && oldTarget != currentTarget) {
                successSourceIds = oldTarget.sourceIds
                previousTarget = currentTarget
                delay(360)
                successSourceIds = emptySet()
            } else {
                previousTarget = currentTarget
            }
        }

        // Follow only when progression changes. Manual browsing merely reveals the return action.
        LaunchedEffect(currentTarget?.practiceStepIndex) {
            currentTarget?.let { target ->
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                follow(target)
            }
        }

        LaunchedEffect(currentTarget, viewportWidthPx) {
            snapshotFlow {
                currentTarget?.let { PracticeAutoFollow.isVisible(it, viewport(), with(density) { 20.dp.toPx() }) }
                    ?: true
            }.distinctUntilChanged().collect { targetVisible = it }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().transformable(transformable),
        ) {
            items(
                count = document.systems.size,
                key = { document.systems[it].index }
            ) { index ->
                val system = document.systems[index]
                Box(
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .horizontalScroll(horizontalScroll)
                        .padding(horizontal = 6.dp)
                ) {
                    NotationSystemCard(
                        system = system,
                        modifier = Modifier.width(systemWidth).testTag("practice_notation_system_$index"),
                        highlightedSourceIds = currentTarget
                            ?.takeIf { it.systemIndex == system.index }
                            ?.sourceIds
                            .orEmpty(),
                        successSourceIds = successSourceIds,
                        showPracticePointer = progress.phase != PracticePhase.Completed
                    )
                }
            }
        }

        if (!targetVisible && currentTarget != null) {
            FilledTonalButton(
                onClick = { scope.launch { follow(currentTarget) } },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .testTag("practice_return_to_current")
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Text(
                    stringResource(R.string.practice_return_to_current),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}
