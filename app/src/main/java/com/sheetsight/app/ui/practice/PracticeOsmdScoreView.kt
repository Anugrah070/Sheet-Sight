package com.sheetsight.app.ui.practice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sheetsight.app.R
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.ui.editor.EditorViewModel
import com.sheetsight.app.ui.editor.AlphaTabScoreView

/** Full MusicXML engraving for Practice, with cursor following and compact zoom controls. */
@Composable
internal fun PracticeEngravedScoreView(
    musicXml: String,
    document: com.sheetsight.app.ui.editor.notation.NotationDocument,
    state: PracticeUiState,
    modifier: Modifier = Modifier
) {
    val progress = state.progress
    var zoom by remember(musicXml) { mutableFloatStateOf(DEFAULT_PRACTICE_ZOOM) }
    val cursorStep = if (progress.phase == PracticePhase.Completed) -1 else progress.currentStepIndex

    Box(modifier = modifier.testTag("practice_score")) {
        AlphaTabScoreView(
            musicXml = musicXml,
            document = document,
            initialSystemIndex = 0,
            zoom = zoom,
            onSystemChanged = {},
            cursorStepIndex = cursorStep,
            modifier = Modifier.fillMaxSize()
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 5.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { zoom = (zoom - ZOOM_STEP).coerceAtLeast(EditorViewModel.MIN_ZOOM) },
                    modifier = Modifier.size(40.dp).testTag("practice_zoom_out")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.editor_zoom_out))
                }
                IconButton(
                    onClick = { zoom = DEFAULT_PRACTICE_ZOOM },
                    modifier = Modifier.size(54.dp).testTag("practice_reset_zoom")
                ) {
                    Text("${(zoom * 100).toInt()}%")
                }
                IconButton(
                    onClick = { zoom = (zoom + ZOOM_STEP).coerceAtMost(EditorViewModel.MAX_ZOOM) },
                    modifier = Modifier.size(40.dp).testTag("practice_zoom_in")
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.editor_zoom_in))
                }
            }
        }
    }
}

private const val ZOOM_STEP = 0.1f
private const val DEFAULT_PRACTICE_ZOOM = 0.9f
