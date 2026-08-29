@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.synth.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPlaybackCoordinatorTest {
    @Test
    fun initializingIgnoresPlaybackUntilPlayerReady() {
        val fixture = fixture()

        fixture.coordinator.togglePlayPause()
        assertEquals(0, fixture.engine.playPauseCount)

        fixture.coordinator.onReady()
        assertEquals(EditorPlaybackState.Ready, fixture.coordinator.state)
        fixture.coordinator.togglePlayPause()
        assertEquals(1, fixture.engine.playPauseCount)
    }

    @Test
    fun playPauseResumeTracksAlphaSynthStateEvents() {
        val fixture = fixture()
        fixture.coordinator.onReady()

        fixture.coordinator.togglePlayPause()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)
        assertEquals(EditorPlaybackState.Playing, fixture.coordinator.state)

        fixture.coordinator.togglePlayPause()
        fixture.coordinator.onPlayerState(PlayerState.Paused, stopped = false)
        assertEquals(EditorPlaybackState.Paused, fixture.coordinator.state)

        fixture.coordinator.togglePlayPause()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)
        assertEquals(EditorPlaybackState.Playing, fixture.coordinator.state)
        assertEquals(3, fixture.engine.playPauseCount)
    }

    @Test
    fun stopReturnsToBeginningReadyState() {
        val fixture = fixture()
        fixture.coordinator.onReady()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)

        fixture.coordinator.stop()

        assertEquals(1, fixture.engine.stopCount)
        assertEquals(EditorPlaybackState.Ready, fixture.coordinator.state)
    }

    @Test
    fun completionStopsAndKeepsCompletedUiAtBeginning() {
        val fixture = fixture()
        fixture.coordinator.onReady()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)

        fixture.coordinator.onFinished()
        fixture.coordinator.onPlayerState(PlayerState.Paused, stopped = true)

        assertEquals(1, fixture.engine.stopCount)
        assertEquals(EditorPlaybackState.Completed, fixture.coordinator.state)
        fixture.coordinator.togglePlayPause()
        assertEquals(1, fixture.engine.playPauseCount)
    }

    @Test
    fun replacingScoreStopsPreviousPlaybackAndInitializesNext() {
        val fixture = fixture()
        fixture.coordinator.onReady()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)

        fixture.coordinator.onSourceReplacing()

        assertEquals(1, fixture.engine.stopCount)
        assertEquals(EditorPlaybackState.Initializing, fixture.coordinator.state)
    }

    @Test
    fun releaseStopsOnceReleasesEngineAndIgnoresLateCallbacks() {
        val fixture = fixture()
        fixture.coordinator.onReady()

        fixture.coordinator.release()
        fixture.coordinator.release()
        fixture.coordinator.onPlayerState(PlayerState.Playing, stopped = false)

        assertEquals(1, fixture.engine.stopCount)
        assertEquals(1, fixture.engine.releaseCount)
        assertEquals(EditorPlaybackState.Ready, fixture.coordinator.state)
    }

    @Test
    fun initializationFailureIsRecoverableTransportState() {
        val fixture = fixture()

        fixture.coordinator.onFailure(IllegalStateException("audio unavailable"))

        assertTrue(fixture.coordinator.state is EditorPlaybackState.Error)
        assertEquals(1, fixture.engine.stopCount)
    }

    private fun fixture(): Fixture {
        val engine = FakePlaybackEngine()
        return Fixture(engine, EditorPlaybackCoordinator(engine) {})
    }

    private data class Fixture(
        val engine: FakePlaybackEngine,
        val coordinator: EditorPlaybackCoordinator
    )

    private class FakePlaybackEngine : EditorPlaybackEngine {
        var playPauseCount = 0
        var stopCount = 0
        var releaseCount = 0

        override fun playPause() { playPauseCount++ }
        override fun stop() { stopCount++ }
        override fun release() { releaseCount++ }
    }
}
