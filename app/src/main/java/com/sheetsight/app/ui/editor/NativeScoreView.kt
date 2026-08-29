package com.sheetsight.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationSystemCard
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Always-available renderer for the MusicXML subset already validated by the
 * Editor. It draws SMuFL glyphs directly into Compose Canvas and is used if the
 * richer alphaTab engine cannot import or render a particular score.
 */
@Composable
internal fun NativeScoreView(
    document: NotationDocument,
    initialSystemIndex: Int,
    zoom: Float,
    onSystemChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialIndex = initialSystemIndex.coerceIn(0, document.systems.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * zoom.coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM),
        fontScale = baseDensity.fontScale
    )

    LaunchedEffect(listState, document.systems.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect(onSystemChanged)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .testTag("native_score_view")
    ) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                items(
                    count = document.systems.size,
                    key = { document.systems[it].index }
                ) { index ->
                    NotationSystemCard(
                        system = document.systems[index],
                        modifier = Modifier.testTag("native_score_system_$index")
                    )
                }
            }
        }
    }
}
