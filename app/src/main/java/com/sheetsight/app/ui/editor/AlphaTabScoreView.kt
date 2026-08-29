package com.sheetsight.app.ui.editor

import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.Settings
import alphaTab.ScrollMode
import alphaTab.StaveProfile
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.contracts.ExperimentalContracts
import kotlin.math.abs

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
internal class ImportedAlphaTabScore internal constructor(internal val score: Score)

/**
 * Native Android sheet-music host. alphaTab parses MusicXML on a worker thread
 * and renders through Android Canvas/Skia; no WebView, JavaScript, or network is
 * involved. The built-in Compose renderer takes over on any import/render error.
 */
@OptIn(ExperimentalUnsignedTypes::class, ExperimentalContracts::class)
@Composable
internal fun AlphaTabScoreView(
    musicXml: String,
    sourceKey: String = musicXml.hashCode().toString(),
    document: NotationDocument,
    initialSystemIndex: Int,
    zoom: Float,
    onSystemChanged: (Int) -> Unit,
    allowNativeFallback: Boolean = true,
    onRenderError: (String) -> Unit = {},
    onAlphaTabScoreLoaded: (ImportedAlphaTabScore) -> Unit = {},
    identityMapping: AlphaTabIdentityMapping? = null,
    selection: AlphaTabRenderSelection? = null,
    pitchVisualUpdate: AlphaTabPitchVisualUpdate? = null,
    onSelectionHit: (AlphaTabSelectionHit) -> Unit = {},
    onZoomGestureFinished: (Float) -> Unit = {},
    cursorStepIndex: Int? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var rendered by remember(sourceKey) { mutableStateOf(false) }
    var alphaTabFailure by remember(sourceKey) { mutableStateOf<String?>(null) }
    var score by remember(sourceKey) { mutableStateOf<Score?>(null) }
    var isImporting by remember(sourceKey) { mutableStateOf(true) }

    LaunchedEffect(sourceKey) {
        withContext(Dispatchers.Default) {
            runCatching {
                isImporting = true
                // Diagnostic logging (Part 7): verify OMR output has musical content
                // before blaming the renderer for a blank screen.
                val charCount = musicXml.length
                val partCount = "<part ".toRegex().findAll(musicXml).count()
                val measureCount = "<measure ".toRegex().findAll(musicXml).count()
                val noteCount = "<note".toRegex().findAll(musicXml).count()
                Log.d(
                    ALPHATAB_LOG_TAG,
                    "MUSICXML_DIAGNOSTICS sourceKey=$sourceKey chars=$charCount " +
                        "parts=$partCount measures=$measureCount notes=$noteCount"
                )

                // Ensure the alphaTab environment is initialized before loading any score.
                // This is critical for the native Android renderer to function.
                initializeAlphaTabAndroidEnvironment(context.applicationContext)

                val bytes = musicXml.toByteArray(Charsets.UTF_8)
                ScoreLoader.loadScoreFromBytes(
                    Uint8Array(bytes.asUByteArray()),
                    Settings()
                )
            }.onSuccess { loadedScore ->
                score = loadedScore
                isImporting = false
            }.onFailure { failure ->
                Log.w(ALPHATAB_LOG_TAG, "alphaTab could not import MusicXML", failure)
                alphaTabFailure = failure.message ?: "MusicXML import failed."
                isImporting = false
            }
        }
    }

    LaunchedEffect(alphaTabFailure, allowNativeFallback) {
        val failure = alphaTabFailure
        if (failure != null && !allowNativeFallback) onRenderError(failure)
    }

    LaunchedEffect(score) {
        score?.let { onAlphaTabScoreLoaded(ImportedAlphaTabScore(it)) }
    }

    LaunchedEffect(sourceKey, rendered, alphaTabFailure) {
        if (!rendered && alphaTabFailure == null) {
            delay(ALPHATAB_RENDER_TIMEOUT_MILLIS)
            if (!rendered && alphaTabFailure == null) {
                alphaTabFailure = "Rendering timed out."
                Log.w(ALPHATAB_LOG_TAG, "alphaTab timed out; switching to the native fallback")
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("sheet_music_score_view")
    ) {
        val currentScore = score
        if (alphaTabFailure != null && allowNativeFallback) {
            NativeScoreView(
                document = document,
                initialSystemIndex = initialSystemIndex,
                zoom = zoom,
                onSystemChanged = onSystemChanged,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).testTag("native_renderer_fallback")
            ) {
                Text(
                    text = "Built-in renderer",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        } else if (currentScore != null) {
            if (cursorStepIndex == null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("alphatab_score_view"),
                    factory = { context -> StableAlphaTabView(context) },
                    update = { view ->
                        view.showScore(
                            score = currentScore,
                            zoom = zoom,
                            initialSystemIndex = initialSystemIndex,
                            systemCount = document.systems.size,
                            onRendered = {
                                rendered = true
                                alphaTabFailure = null
                            },
                            onError = { failure ->
                                Log.w(ALPHATAB_LOG_TAG, "alphaTab render failed", failure)
                                alphaTabFailure = failure.message ?: "The score could not be engraved."
                            },
                            onSystemChanged = onSystemChanged,
                            identityMapping = identityMapping,
                            selection = selection,
                            pitchVisualUpdate = pitchVisualUpdate,
                            onSelectionHit = onSelectionHit,
                            onZoomGestureFinished = onZoomGestureFinished
                        )
                    },
                    onRelease = StableAlphaTabView::release
                )
            } else AndroidView(
                modifier = Modifier.fillMaxSize().testTag("alphatab_score_view"),
                factory = { context ->
                    AlphaTabView(context, null).apply {
                        setBackgroundColor(Color.rgb(255, 254, 250))
                        val state = AlphaTabRendererState()
                        tag = state
                        settings.display.apply {
                            layoutMode = LayoutMode.Page
                            staveProfile = StaveProfile.Score
                            barsPerRow = PRACTICE_FRIENDLY_BARS_PER_ROW
                            stretchForce = SCORE_STRETCH_FORCE
                            justifyLastSystem = true
                            padding = DoubleList(
                                SCORE_HORIZONTAL_PADDING,
                                SCORE_VERTICAL_PADDING,
                                SCORE_HORIZONTAL_PADDING,
                                SCORE_BOTTOM_PADDING
                            )
                            firstSystemPaddingTop = FIRST_SYSTEM_PADDING
                            systemPaddingTop = SYSTEM_PADDING
                            systemPaddingBottom = SYSTEM_PADDING
                            lastSystemPaddingBottom = LAST_SYSTEM_PADDING
                            resources.barNumberColor = alphaTab.model.Color(
                                SCORE_INK_LEVEL,
                                SCORE_INK_LEVEL,
                                SCORE_INK_LEVEL,
                                255.0
                            )
                        }
                        settings.player.apply {
                            enablePlayer = cursorStepIndex != null
                            enableCursor = cursorStepIndex != null
                            enableAnimatedBeatCursor = false
                            enableElementHighlighting = cursorStepIndex != null
                            enableUserInteraction = false
                            // AlphaTab otherwise scrolls the containing Activity window,
                            // which can push Compose navigation/header chrome off-screen.
                            scrollMode = ScrollMode.Off
                        }
                        setPracticeCursorColors(cursorStepIndex)
                        api.postRenderFinished.on {
                            post {
                                if (!state.released) {
                                    rendered = true
                                    alphaTabFailure = null
                                    restoreSystemPosition(this, initialSystemIndex, document.systems.size)
                                    updatePracticeCursor(this, currentScore, cursorStepIndex, state)
                                }
                            }
                        }
                        api.error.on { failure ->
                            post {
                                if (!state.released) {
                                    Log.w(ALPHATAB_LOG_TAG, "alphaTab render failed", failure)
                                    alphaTabFailure = failure.message ?: "The score could not be engraved."
                                }
                            }
                        }
                    }
                },
                update = { view ->
                    val state = view.tag as AlphaTabRendererState
                    val renderKey = sourceKey.hashCode()
                    val safeZoom = zoom.coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
                    if (state.renderKey != renderKey) {
                        state.renderKey = renderKey
                        state.appliedZoom = safeZoom
                        state.appliedCursorStepIndex = null
                        rendered = false
                        view.settings.display.scale = safeZoom.toDouble()
                        view.tracks = arrayListOf(currentScore.tracks[0])
                    } else if (abs(state.appliedZoom - safeZoom) >= 0.01f) {
                        state.appliedZoom = safeZoom
                        view.settings.display.scale = safeZoom.toDouble()
                        view.api.updateSettings()
                    }
                    if (state.renderKey == renderKey && rendered &&
                        state.appliedCursorStepIndex != cursorStepIndex
                    ) {
                        updatePracticeCursor(view, currentScore, cursorStepIndex, state)
                    }
                },
                onRelease = { view ->
                    (view.tag as? AlphaTabRendererState)?.released = true
                    view.api.destroy()
                }
            )
        }

        if (!rendered && alphaTabFailure == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.testTag("sheet_music_render_loading")
                )
                Text(
                    text = if (isImporting) "Loading score\u2026" else "Rendering score\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun restoreSystemPosition(view: AlphaTabView, initialSystemIndex: Int, systemCount: Int) {
    if (initialSystemIndex <= 0 || systemCount <= 1) return
    view.post {
        val progress = initialSystemIndex.toFloat() / (systemCount - 1).toFloat()
        val estimatedRange = (view.height * systemCount).coerceAtLeast(view.height)
        view.scrollTo(0, (estimatedRange * progress).toInt())
    }
}

private data class AlphaTabRendererState(
    var renderKey: Int = 0,
    var appliedZoom: Float = Float.NaN,
    var appliedCursorStepIndex: Int? = null,
    var released: Boolean = false
)

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun AlphaTabView.setPracticeCursorColors(cursorStepIndex: Int?) {
    val cursorVisible = cursorStepIndex != null && cursorStepIndex >= 0
    barCursorFillColor = if (cursorVisible) PRACTICE_BAR_CURSOR_COLOR else Color.TRANSPARENT
    beatCursorFillColor = if (cursorVisible) PRACTICE_BEAT_CURSOR_COLOR else Color.TRANSPARENT
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun updatePracticeCursor(
    view: AlphaTabView,
    score: Score,
    cursorStepIndex: Int?,
    state: AlphaTabRendererState
) {
    state.appliedCursorStepIndex = cursorStepIndex
    view.setPracticeCursorColors(cursorStepIndex)
    if (cursorStepIndex == null || cursorStepIndex < 0) return

    val tick = practiceTickPositions(score).getOrNull(cursorStepIndex) ?: return
    view.api.tickPosition = tick
}

/**
 * Practice steps are grouped by score onset. alphaTab represents simultaneous
 * notes on different voices/staves as separate beats, so collapse them to one
 * absolute playback tick before indexing the current step.
 */
@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun practiceTickPositions(score: Score): List<Double> = buildSet {
    score.tracks.firstOrNull()?.staves?.forEach { staff ->
        staff.bars.forEach { bar ->
            bar.voices.forEach { voice ->
                voice.beats.forEach { beat ->
                    if (!beat.isEmpty || beat.isRest) add(beat.absolutePlaybackStart)
                }
            }
        }
    }
}.sorted()

private const val ALPHATAB_RENDER_TIMEOUT_MILLIS = 20_000L
private const val ALPHATAB_LOG_TAG = "SheetSightAlphaTab"
private const val PRACTICE_FRIENDLY_BARS_PER_ROW = 4.0
private const val SCORE_STRETCH_FORCE = 0.9
private const val SCORE_HORIZONTAL_PADDING = 18.0
private const val SCORE_VERTICAL_PADDING = 14.0
private const val SCORE_BOTTOM_PADDING = 28.0
private const val FIRST_SYSTEM_PADDING = 6.0
private const val SYSTEM_PADDING = 12.0
private const val LAST_SYSTEM_PADDING = 18.0
private const val SCORE_INK_LEVEL = 42.0
private val PRACTICE_BAR_CURSOR_COLOR = Color.argb(38, 230, 149, 26)
private val PRACTICE_BEAT_CURSOR_COLOR = Color.argb(184, 230, 149, 26)
