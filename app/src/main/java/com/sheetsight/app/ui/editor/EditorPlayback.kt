@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.AlphaTabView
import alphaTab.PlayerMode
import alphaTab.ScrollMode
import alphaTab.model.AutomationType
import alphaTab.model.Score
import alphaTab.synth.PlayerState
import android.content.Context
import android.util.Log

/** Explicit Editor transport states. Rendering remains independent from these states. */
internal sealed interface EditorPlaybackState {
    data object Initializing : EditorPlaybackState
    data object Ready : EditorPlaybackState
    data object Playing : EditorPlaybackState
    data object Paused : EditorPlaybackState
    data object Completed : EditorPlaybackState
    data class Error(val message: String) : EditorPlaybackState
}

internal val EditorPlaybackState.canPlayPause: Boolean
    get() = this is EditorPlaybackState.Ready ||
        this is EditorPlaybackState.Playing ||
        this is EditorPlaybackState.Paused ||
        this is EditorPlaybackState.Completed

internal val EditorPlaybackState.canStop: Boolean
    get() = this is EditorPlaybackState.Playing || this is EditorPlaybackState.Paused

internal val EditorPlaybackState.isPlaying: Boolean
    get() = this is EditorPlaybackState.Playing

/** Small boundary that keeps the transport state machine unit-testable without Android views. */
internal interface EditorPlaybackEngine {
    fun playPause()
    fun stop()
    fun release()
}

internal class EditorPlaybackCoordinator(
    private val engine: EditorPlaybackEngine,
    private val onStateChanged: (EditorPlaybackState) -> Unit
) {
    var state: EditorPlaybackState = EditorPlaybackState.Initializing
        private set

    private var released = false
    private var completionResetInProgress = false

    fun onReady() = transition(EditorPlaybackState.Ready)

    fun togglePlayPause() {
        if (!released && state.canPlayPause) {
            completionResetInProgress = false
            engine.playPause()
        }
    }

    fun onPlayerState(state: PlayerState, stopped: Boolean) {
        if (released) return
        when (state) {
            PlayerState.Playing -> {
                completionResetInProgress = false
                transition(EditorPlaybackState.Playing)
            }
            PlayerState.Paused -> {
                if (stopped) {
                    if (!completionResetInProgress) transition(EditorPlaybackState.Ready)
                } else {
                    transition(EditorPlaybackState.Paused)
                }
            }
        }
    }

    fun stop() {
        if (released) return
        completionResetInProgress = false
        runCatching(engine::stop).onFailure(::onFailure)
        if (state !is EditorPlaybackState.Error) transition(EditorPlaybackState.Ready)
    }

    fun onFinished() {
        if (released) return
        // alphaTab's finished event does not guarantee that the public position has
        // already returned to zero. Stop explicitly, but retain Completed as useful UI state.
        completionResetInProgress = true
        transition(EditorPlaybackState.Completed)
        runCatching(engine::stop).onFailure(::onFailure)
    }

    fun onFailure(failure: Throwable) {
        if (released) return
        completionResetInProgress = false
        runCatching(engine::stop)
        transition(EditorPlaybackState.Error(PLAYBACK_ERROR_MESSAGE))
    }

    /** Stops the old MIDI before the same host is pointed at another score. */
    fun onSourceReplacing() {
        if (released) return
        completionResetInProgress = false
        runCatching(engine::stop).onFailure(::onFailure)
        if (state !is EditorPlaybackState.Error) transition(EditorPlaybackState.Initializing)
    }

    fun release() {
        if (released) return
        released = true
        // Suppress state callbacks caused by stop during teardown.
        runCatching(engine::stop)
        runCatching(engine::release)
    }

    private fun transition(next: EditorPlaybackState) {
        if (released || state == next) return
        state = next
        onStateChanged(next)
    }
}

/**
 * One lifecycle-bound AlphaSynth host. Its AlphaTabView is never the visible Editor renderer;
 * StableAlphaTabView remains responsible for reliable score display and scrolling.
 */
internal class EditorAlphaTabPlaybackPlayer(
    context: Context,
    onStateChanged: (EditorPlaybackState) -> Unit
) {
    val view = AlphaTabView(context, null).apply {
        settings.core.apply {
            engine = "android"
            useWorkers = false
            enableLazyLoading = true
        }
        settings.player.apply {
            enablePlayer = true
            playerMode = PlayerMode.EnabledSynthesizer
            enableCursor = false
            enableAnimatedBeatCursor = false
            enableElementHighlighting = false
            enableUserInteraction = false
            scrollMode = ScrollMode.Off
        }
    }

    private val engine = object : EditorPlaybackEngine {
        override fun playPause() = view.api.playPause()
        override fun stop() = view.api.stop()

        override fun release() {
            unsubscribeAll()
            // AlphaTabView owns API destruction in onDetachedFromWindow. Calling destroy here
            // as well would double-destroy the synth when AndroidView removes the view.
        }
    }
    private val coordinator = EditorPlaybackCoordinator(engine, onStateChanged)
    private val unsubscribers = mutableListOf<() -> Unit>()
    private var loadedSourceKey: String? = null
    private var released = false

    init {
        unsubscribers += view.api.playerReady.on { view.post { coordinator.onReady() } }
        unsubscribers += view.api.playerStateChanged.on { event ->
            view.post { coordinator.onPlayerState(event.state, event.stopped) }
        }
        unsubscribers += view.api.playerFinished.on { view.post { coordinator.onFinished() } }
        unsubscribers += view.api.error.on { failure ->
            Log.w(EDITOR_PLAYBACK_LOG_TAG, "Offline alphaTab playback failed", failure)
            view.post { coordinator.onFailure(failure) }
        }
    }

    fun loadScore(sourceKey: String, score: Score) {
        if (released || loadedSourceKey == sourceKey) return
        if (loadedSourceKey != null) coordinator.onSourceReplacing()
        loadedSourceKey = sourceKey
        runCatching {
            configureEditorPlaybackAsGrandPiano(score)
            view.tracks = arrayListOf(score.tracks[0])
        }
            .onFailure { failure ->
                Log.w(EDITOR_PLAYBACK_LOG_TAG, "Could not load score into offline player", failure)
                coordinator.onFailure(failure)
            }
    }

    fun togglePlayPause() = coordinator.togglePlayPause()

    fun stop() = coordinator.stop()

    fun release() {
        if (released) return
        released = true
        coordinator.release()
    }

    private fun unsubscribeAll() {
        unsubscribers.toList().forEach { unsubscribe -> runCatching(unsubscribe) }
        unsubscribers.clear()
    }
}

/**
 * Configures alphaTab's playback model without rewriting the source MusicXML.
 * alphaTab 1.6.1 uses zero-based General MIDI program numbers, so program 0 is
 * GM program 1 (Acoustic Grand Piano). MidiFileGenerator applies a track's one
 * PlaybackInformation program to both its primary and secondary channels.
 */
internal fun configureEditorPlaybackAsGrandPiano(score: Score): Score = score.apply {
    tracks.forEach { track ->
        track.playbackInfo.program = ACOUSTIC_GRAND_PIANO_PROGRAM
        track.staves.forEach { staff ->
            staff.bars.forEach { bar ->
                bar.voices.forEach { voice ->
                    voice.beats.forEach { beat ->
                        beat.automations = beat.automations.filter { automation ->
                            automation.type != AutomationType.Instrument
                        }
                    }
                }
            }
        }
    }
}

internal class EditorPlaybackCommandHolder {
    var player: EditorAlphaTabPlaybackPlayer? = null

    fun togglePlayPause() = player?.togglePlayPause() ?: Unit
    fun stop() = player?.stop() ?: Unit
}

private const val PLAYBACK_ERROR_MESSAGE = "Playback unavailable. Try again."
private const val EDITOR_PLAYBACK_LOG_TAG = "SheetSightPlayback"
internal const val ACOUSTIC_GRAND_PIANO_PROGRAM = 0.0
