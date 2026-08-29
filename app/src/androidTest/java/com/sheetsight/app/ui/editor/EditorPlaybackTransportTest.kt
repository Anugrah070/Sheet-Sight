package com.sheetsight.app.ui.editor

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sheetsight.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorPlaybackTransportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun controlsAreDisabledWhileLoadingAndEnableWhenReady() {
        var state by mutableStateOf<EditorPlaybackState>(EditorPlaybackState.Initializing)
        compose.activity.setContent {
            MaterialTheme { transport(state = state) }
        }

        compose.onNodeWithTag("editor_playback_loading").assertIsDisplayed()
        compose.onNodeWithTag("editor_play_pause").assertIsNotEnabled()
        compose.onNodeWithTag("editor_stop").assertIsNotEnabled()

        compose.runOnUiThread { state = EditorPlaybackState.Ready }
        compose.onNodeWithTag("editor_play_pause").assertIsEnabled()
            .assertContentDescriptionEquals("Play score")
        compose.onNodeWithTag("editor_stop").assertIsNotEnabled()
    }

    @Test
    fun primaryControlPlaysPausesAndResumes() {
        var state by mutableStateOf<EditorPlaybackState>(EditorPlaybackState.Ready)
        compose.activity.setContent {
            MaterialTheme {
                transport(
                    state = state,
                    onPlayPause = {
                        state = when (state) {
                            EditorPlaybackState.Playing -> EditorPlaybackState.Paused
                            else -> EditorPlaybackState.Playing
                        }
                    }
                )
            }
        }

        compose.onNodeWithTag("editor_play_pause").performClick()
            .assertContentDescriptionEquals("Pause playback")
        compose.onNodeWithTag("editor_play_pause").performClick()
            .assertContentDescriptionEquals("Resume playback")
        compose.onNodeWithTag("editor_play_pause").performClick()
            .assertContentDescriptionEquals("Pause playback")
    }

    @Test
    fun stopAndCompletionResetTheTransport() {
        var state by mutableStateOf<EditorPlaybackState>(EditorPlaybackState.Playing)
        compose.activity.setContent {
            MaterialTheme {
                transport(
                    state = state,
                    onStop = { state = EditorPlaybackState.Ready }
                )
            }
        }

        compose.onNodeWithTag("editor_stop").assertIsEnabled().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("editor_play_pause").assertContentDescriptionEquals("Play score")

        compose.runOnUiThread { state = EditorPlaybackState.Completed }
        compose.onNodeWithTag("editor_play_pause").assertIsEnabled()
            .assertContentDescriptionEquals("Play score")
        compose.onNodeWithTag("editor_stop").assertIsNotEnabled()
    }

    @Test
    fun playbackFailureLeavesScoreVisibleAndOffersRetry() {
        var retried = false
        compose.activity.setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.size(40.dp).testTag("score_still_visible"))
                    transport(
                        state = EditorPlaybackState.Error("Playback unavailable. Try again."),
                        onRetry = { retried = true }
                    )
                }
            }
        }

        compose.onNodeWithTag("score_still_visible").assertIsDisplayed()
        compose.onNodeWithTag("editor_playback_error").assertIsDisplayed()
        compose.onNodeWithTag("editor_playback_retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, retried) }
    }

    @Test
    fun scoreReplacementAndLeavingCompositionReleaseThePreviousPlayer() {
        val first = FakePlaybackEngine()
        val second = FakePlaybackEngine()
        var session by mutableStateOf(PlaybackSessionInput("first", first))
        var visible by mutableStateOf(true)

        compose.activity.setContent {
            MaterialTheme {
                if (visible) PlaybackLifecycleHarness(session.sourceKey, session.engine)
            }
        }
        compose.waitForIdle()

        compose.runOnUiThread {
            session = PlaybackSessionInput("second", second)
        }
        compose.waitForIdle()
        assertEquals(1, first.stopCount)
        assertEquals(1, first.releaseCount)

        compose.runOnUiThread { visible = false }
        compose.waitForIdle()
        assertEquals(1, second.stopCount)
        assertEquals(1, second.releaseCount)
    }

    @Composable
    private fun transport(
        state: EditorPlaybackState,
        onPlayPause: () -> Unit = {},
        onStop: () -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        EditorPlaybackTransport(state, onPlayPause, onStop, onRetry)
    }

    @Composable
    private fun PlaybackLifecycleHarness(sourceKey: String, engine: EditorPlaybackEngine) {
        val coordinator = remember(sourceKey) { EditorPlaybackCoordinator(engine) {} }
        DisposableEffect(coordinator) {
            onDispose(coordinator::release)
        }
    }

    private class FakePlaybackEngine : EditorPlaybackEngine {
        var stopCount = 0
        var releaseCount = 0

        override fun playPause() = Unit
        override fun stop() { stopCount++ }
        override fun release() { releaseCount++ }
    }

    private data class PlaybackSessionInput(
        val sourceKey: String,
        val engine: EditorPlaybackEngine
    )
}
