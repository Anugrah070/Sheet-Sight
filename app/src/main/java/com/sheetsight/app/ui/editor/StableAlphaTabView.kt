@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.LayoutMode
import alphaTab.Settings
import alphaTab.StaveProfile
import alphaTab.collections.DoubleList
import alphaTab.collections.Map
import alphaTab.model.Bar
import alphaTab.model.BarStyle
import alphaTab.model.BarSubElement
import alphaTab.model.Beat
import alphaTab.model.BeatStyle
import alphaTab.model.BeatSubElement
import alphaTab.model.Note
import alphaTab.model.NoteAccidentalMode
import alphaTab.model.NoteStyle
import alphaTab.model.NoteSubElement
import alphaTab.model.Score
import alphaTab.rendering.RenderFinishedEventArgs
import alphaTab.rendering.ScoreRenderer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapping
import com.sheetsight.app.ui.editor.identity.BarlineSide
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A read-only alphaTab host that keeps every rendered score chunk resident.
 *
 * alphaTab 1.6.1's bundled [alphaTab.platform.android.AlphaTabRenderSurface]
 * recycles bitmaps as soon as they leave the viewport and requests them again
 * when the user scrolls back. That optimization is useful for very large
 * tablatures, but it can leave a revisited system blank when a re-render request
 * is delayed or lost. The Editor values reliable two-way navigation more than
 * that memory optimization, so this host uses alphaTab's public low-level
 * renderer with lazy loading disabled and paints all returned chunks itself.
 *
 * Practice deliberately continues to use [alphaTab.AlphaTabView], because its
 * cursor and player integration are unrelated to the read-only Editor bug.
 */
internal class StableAlphaTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density.toDouble()
    private val verticalScroll = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private val surface = StableScoreSurface(context)
    private val horizontalScroll = HorizontalScrollView(context).apply {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private var renderer: ScoreRenderer? = null
    private var score: Score? = null
    private var settings: Settings? = null
    private var renderKey: Int = 0
    private var renderedWidth: Int = 0
    private var initialSystemIndex: Int = 0
    private var systemCount: Int = 1
    private var pendingScrollProgress: Float? = null
    private var pendingRender = false
    private var renderScheduled = false
    private var released = false
    private var onRendered: () -> Unit = {}
    private var onError: (Throwable) -> Unit = {}
    private var onSystemChanged: (Int) -> Unit = {}
    private var identityMapping: AlphaTabIdentityMapping? = null
    private var selection: AlphaTabRenderSelection? = null
    private var selectedElementStyle: SelectedElementStyle? = null
    private var onSelectionHit: (AlphaTabSelectionHit) -> Unit = {}
    private var onZoomGestureFinished: (Float) -> Unit = {}
    private var authoritativeZoom = 1f
    private var gestureStartZoom = 1f
    private var previewZoom = 1f
    private var pinchInProgress = false
    private var pendingZoomAnchor: ZoomAnchor? = null
    private var pinchStartedNanos = 0L
    private var pinchStartRenderCount = 0
    private var lastPitchVisualRevision = Long.MIN_VALUE
    private var optimisticPitchSnapshot: RuntimePitchSnapshot? = null
    private var localizedRefreshStartedNanos = 0L
    private var localizedRenderGeneration = 0L
    private var localizedExpectedChunks = 0
    private var localizedCompletedChunks = 0
    private val localizedNoteBounds = IdentityHashMap<Note, List<alphaTab.rendering.utils.Bounds>>()
    private val activeLocalizedRenderers = mutableSetOf<ScoreRenderer>()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return beginPinch(detector.focusX, detector.focusY)
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return updatePinch(detector.scaleFactor, detector.focusX, detector.focusY)
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            finishPinchGesture(cancelled = false)
        }
    })

    private fun beginPinch(focusX: Float, focusY: Float): Boolean {
        if (released || surface.chunkCount == 0) return false
        pinchInProgress = true
        surface.cancelPendingTap()
        gestureStartZoom = authoritativeZoom
        previewZoom = authoritativeZoom
        val contentX = horizontalScroll.scrollX + focusX
        val contentY = verticalScroll.scrollY + focusY
        surface.pivotX = contentX
        surface.pivotY = contentY
        pendingZoomAnchor = ZoomAnchor(
            contentX = contentX,
            contentY = contentY,
            viewportX = focusX,
            viewportY = focusY,
            fromZoom = authoritativeZoom,
            toZoom = authoritativeZoom
        )
        lastPinchLatencyMillis = null
        lastPinchAuthoritativeRenderCount = 0
        pinchStartedNanos = System.nanoTime()
        pinchStartRenderCount = renderCount
        return true
    }

    private fun updatePinch(scaleFactor: Float, focusX: Float, focusY: Float): Boolean {
        if (!pinchInProgress) return false
        previewZoom = (previewZoom * scaleFactor)
            .coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
        val previewFactor = previewZoom / gestureStartZoom
        surface.scaleX = previewFactor
        surface.scaleY = previewFactor
        pendingZoomAnchor = pendingZoomAnchor?.copy(
            viewportX = focusX,
            viewportY = focusY,
            toZoom = previewZoom
        )
        return true
    }

    internal val retainedChunkCount: Int get() = surface.chunkCount
    internal val hasVisibleRenderedChunks: Boolean get() = surface.hasVisibleRenderedChunks
    internal val rendererAnnotationPresent: Boolean get() = surface.rendererAnnotationPresent
    internal val retainedBitmapIdentities: kotlin.collections.Map<String, Int> get() = surface.bitmapIdentities
    internal val verticalScrollYForTest: Int get() = verticalScroll.scrollY
    internal val selectedColoredNoteCount: Int
        get() = allScoreNotes().count { note ->
            note.style?.colors?.get(NoteSubElement.StandardNotationNoteHead)?.raw == SELECTED_ELEMENT_COLOR.raw
        }
    internal val selectedColoredNoteIdentitiesForTest: Set<String>
        get() {
            val mapping = identityMapping ?: return emptySet()
            return allScoreNotes().mapNotNull { note ->
                if (note.style?.colors?.get(NoteSubElement.StandardNotationNoteHead)?.raw == SELECTED_ELEMENT_COLOR.raw) {
                    mapping.noteIdentity(note)?.value
                } else {
                    null
                }
            }.toSet()
        }
    internal val selectionBorderRendered: Boolean get() = false
    internal var alphaTabInitCount: Int = 0
        private set
    internal var scoreLoadCount: Int = 0
        private set
    internal var renderCount: Int = 0
        private set
    internal var localizedRenderCount: Int = 0
        private set
    internal var lastPitchFeedbackMillis: Long? = null
        private set
    internal val lastPitchVisualRevisionForTest: Long get() = lastPitchVisualRevision
    internal var lastLocalizedChunkIds: Set<String> = emptySet()
        private set
    internal val safelyTappableNoteIdentitiesForTest: Set<String>
        get() = safeNoteTapTargets().mapTo(linkedSetOf()) { it.identity }
    internal val mappedVisibleNoteIdentitiesForTest: Set<String>
        get() {
            val mapping = identityMapping ?: return emptySet()
            return allScoreNotes().filter { it.isVisible }.mapNotNull { mapping.noteIdentity(it)?.value }.toSet()
        }
    internal val currentZoomForTest: Float get() = authoritativeZoom
    internal val viewportCenterScorePointForTest: ExactHitPoint
        get() = ExactHitPoint(
            horizontalScroll.scrollX + width / 2.0,
            (verticalScroll.scrollY + height / 2.0) / authoritativeZoom
        )
    internal var lastPinchLatencyMillis: Long? = null
        private set
    internal var lastPinchAuthoritativeRenderCount: Int = 0
        private set
    internal fun performPinchForTest(scaleFactor: Float) {
        val focusX = width / 2f
        val focusY = height / 2f
        check(beginPinch(focusX, focusY))
        check(updatePinch(scaleFactor, focusX, focusY))
        finishPinchGesture(cancelled = false)
    }

    init {
        setBackgroundColor(SCORE_PAGE_COLOR)
        surface.setBackgroundColor(SCORE_PAGE_COLOR)
        verticalScroll.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        horizontalScroll.addView(
            verticalScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(
            horizontalScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        surface.onTap = { x, y ->
            hitTest(
                EditorHitTestCoordinates.toRenderer(x, density),
                EditorHitTestCoordinates.toRenderer(y, density)
            )
        }

        verticalScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val range = (surface.measuredHeight - verticalScroll.height).coerceAtLeast(0)
            if (range > 0 && systemCount > 1) {
                val progress = scrollY.toFloat() / range.toFloat()
                onSystemChanged((progress * (systemCount - 1)).roundToInt().coerceIn(0, systemCount - 1))
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1 || pinchInProgress) {
            surface.cancelPendingTap()
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) finishPinchGesture(cancelled = true)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL && pinchInProgress) {
            finishPinchGesture(cancelled = true)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun finishPinchGesture(cancelled: Boolean) {
        if (!pinchInProgress) return
        pinchInProgress = false
        surface.cancelPendingTap()
        parent?.requestDisallowInterceptTouchEvent(false)
        val finalZoom = if (cancelled) gestureStartZoom else previewZoom
        pendingZoomAnchor = pendingZoomAnchor?.copy(toZoom = finalZoom)
        if (abs(finalZoom - authoritativeZoom) < MIN_ZOOM_CHANGE) {
            pendingZoomAnchor = null
            surface.scaleX = 1f
            surface.scaleY = 1f
            return
        }
        Log.d(
            STABLE_ALPHATAB_LOG_TAG,
            "EDITOR_PINCH end from=$authoritativeZoom to=$finalZoom previewEventsCoalesced=true"
        )
        onZoomGestureFinished(finalZoom)
    }

    fun showScore(
        score: Score,
        zoom: Float,
        initialSystemIndex: Int,
        systemCount: Int,
        onRendered: () -> Unit,
        onError: (Throwable) -> Unit,
        onSystemChanged: (Int) -> Unit,
        identityMapping: AlphaTabIdentityMapping?,
        selection: AlphaTabRenderSelection?,
        pitchVisualUpdate: AlphaTabPitchVisualUpdate?,
        onSelectionHit: (AlphaTabSelectionHit) -> Unit,
        onZoomGestureFinished: (Float) -> Unit = {}
    ) {
        if (released) return
        this.onRendered = onRendered
        this.onError = onError
        this.onSystemChanged = onSystemChanged
        this.onZoomGestureFinished = onZoomGestureFinished
        val mappingChanged = this.identityMapping !== identityMapping
        this.identityMapping = identityMapping
        val selectionChanged = selection != this.selection
        val selectionAffected = if (selectionChanged) applySelectionStyle(selection) else emptySet()
        this.selection = selection
        this.onSelectionHit = onSelectionHit
        this.initialSystemIndex = initialSystemIndex.coerceAtLeast(0)
        this.systemCount = systemCount.coerceAtLeast(1)
        val pitchAffected = applyPitchVisualUpdate(pitchVisualUpdate)

        val safeZoom = zoom.coerceIn(EditorViewModel.MIN_ZOOM, EditorViewModel.MAX_ZOOM)
        authoritativeZoom = safeZoom
        val nextKey = 31 * System.identityHashCode(score) + safeZoom.toBits()
        if (renderKey == nextKey && this.score === score) {
            refreshSystems(selectionAffected + pitchAffected.map { it.beat.voice.bar.masterBar.index })
            if (mappingChanged) renderer?.boundsLookup?.takeIf { it.isFinished }?.let(::diagnoseVisibleElements)
            return
        }

        val scoreChanged = this.score !== score
        pendingScrollProgress = if (scoreChanged) {
            if (this.systemCount > 1) {
                this.initialSystemIndex.toFloat() / (this.systemCount - 1).toFloat()
            } else {
                0f
            }
        } else {
            currentScrollProgress()
        }
        renderKey = nextKey
        this.score = score
        if (scoreChanged) scoreLoadCount++
        settings = createStableEditorSettings(safeZoom)
        scheduleRenderAfterLayout(force = true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && width != oldWidth) {
            val scoreWasWaitingForMeasurement = pendingRender
            if (!scoreWasWaitingForMeasurement && surface.chunkCount > 0) {
                pendingScrollProgress = currentScrollProgress()
            }
            scheduleRenderAfterLayout(force = scoreWasWaitingForMeasurement)
        }
    }

    /**
     * Rendering from onSizeChanged() is too early: alphaTab can synchronously return
     * chunks there, and StableScoreSurface.requestLayout() is then made from inside
     * the parent's layout traversal. Android may defer/drop that nested request,
     * leaving the surface at its initial 1x1 measurement until fullscreen causes a
     * second layout. Queueing on the next animation frame is tied to the real view
     * lifecycle (not a timed delay) and lets chunk sizes trigger a clean layout pass.
     */
    private fun scheduleRenderAfterLayout(force: Boolean) {
        if (released) return
        pendingRender = pendingRender || force || width <= 0
        if (width <= 0) {
            requestLayout()
            return
        }
        if (renderScheduled) return
        renderScheduled = true
        postOnAnimation {
            renderScheduled = false
            if (released) return@postOnAnimation
            if (width <= 0 || isInLayout) {
                requestLayout()
                scheduleRenderAfterLayout(force = pendingRender)
                return@postOnAnimation
            }
            val forceNow = pendingRender
            pendingRender = false
            renderWhenLaidOut(forceNow)
        }
    }

    private fun renderWhenLaidOut(force: Boolean) {
        val currentScore = score ?: return
        val currentSettings = settings ?: return
        if (width <= 0) {
            pendingRender = true
            Log.d(STABLE_ALPHATAB_LOG_TAG, "Render deferred: view not yet measured (width=0)")
            return
        }
        if (!force && renderedWidth == width) return
        renderedWidth = width

        runCatching {
            renderer?.destroy()
            activeLocalizedRenderers.forEach(ScoreRenderer::destroy)
            activeLocalizedRenderers.clear()
            localizedNoteBounds.clear()
            surface.beginReplacement()
            initializeAlphaTabAndroidEnvironment(context.applicationContext)
            if (alphaTabInitCount == 0) alphaTabInitCount = 1

            val nextRenderer = ScoreRenderer(currentSettings).also { scoreRenderer ->
                scoreRenderer.width = width / density
                scoreRenderer.partialRenderFinished.on { result -> acceptChunk(result) }
                scoreRenderer.postRenderFinished.on {
                    post {
                        if (!released && renderer === scoreRenderer) {
                            surface.commitReplacement(density)
                            surface.removeRendererAnnotation()
                            restoreReadingPosition()
                            scoreRenderer.boundsLookup?.let(::diagnoseVisibleElements)
                            Log.d(
                                STABLE_ALPHATAB_LOG_TAG,
                                "EDITOR_RENDER alphaTabInitCount=$alphaTabInitCount " +
                                    "scoreLoadCount=$scoreLoadCount renderCount=$renderCount"
                            )
                            onRendered()
                        }
                    }
                }
                scoreRenderer.error.on { failure ->
                    post {
                        if (!released && renderer === scoreRenderer) {
                            surface.discardReplacement()
                            surface.scaleX = 1f
                            surface.scaleY = 1f
                            onError(failure)
                        }
                    }
                }
            }
            renderer = nextRenderer
            renderCount++
            val trackIndexes = DoubleList().apply {
                currentScore.tracks.asSequence().forEach { track -> push(track.index) }
            }
            nextRenderer.renderScore(currentScore, trackIndexes)
        }.onFailure { failure ->
            surface.discardReplacement()
            surface.scaleX = 1f
            surface.scaleY = 1f
            Log.w(STABLE_ALPHATAB_LOG_TAG, "Stable alphaTab render failed", failure)
            onError(failure)
        }
    }

    private fun acceptChunk(result: RenderFinishedEventArgs) {
        val bitmap = result.renderResult as? Bitmap ?: return
        surface.put(
            ScoreChunk(
                id = result.id,
                x = result.x,
                y = result.y,
                width = result.width,
                height = result.height,
                totalWidth = result.totalWidth,
                totalHeight = result.totalHeight,
                firstMasterBarIndex = result.firstMasterBarIndex,
                lastMasterBarIndex = result.lastMasterBarIndex,
                bitmap = bitmap
            ),
            density
        )
    }

    private fun currentScrollProgress(): Float {
        val range = (surface.measuredHeight - verticalScroll.height).coerceAtLeast(0)
        return if (range > 0) {
            (verticalScroll.scrollY.toFloat() / range.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    private fun restoreReadingPosition() {
        val zoomAnchor = pendingZoomAnchor
        if (zoomAnchor != null) {
            pendingZoomAnchor = null
            surface.post {
                val factor = zoomAnchor.toZoom / zoomAnchor.fromZoom
                val targetX = (zoomAnchor.contentX * factor - zoomAnchor.viewportX).roundToInt()
                    .coerceIn(0, (surface.measuredWidth - horizontalScroll.width).coerceAtLeast(0))
                val targetY = (zoomAnchor.contentY * factor - zoomAnchor.viewportY).roundToInt()
                    .coerceIn(0, (surface.measuredHeight - verticalScroll.height).coerceAtLeast(0))
                horizontalScroll.scrollTo(targetX, 0)
                verticalScroll.scrollTo(0, targetY)
                surface.scaleX = 1f
                surface.scaleY = 1f
                Log.d(
                    STABLE_ALPHATAB_LOG_TAG,
                    "EDITOR_PINCH reconcile zoom=${zoomAnchor.toZoom} anchor=(${zoomAnchor.contentX},${zoomAnchor.contentY}) " +
                        "viewport=(${zoomAnchor.viewportX},${zoomAnchor.viewportY}) scroll=($targetX,$targetY) renderCount=$renderCount"
                )
                lastPinchLatencyMillis = (System.nanoTime() - pinchStartedNanos) / 1_000_000L
                lastPinchAuthoritativeRenderCount = renderCount - pinchStartRenderCount
            }
            pendingScrollProgress = null
            return
        }
        val progress = pendingScrollProgress ?: return
        pendingScrollProgress = null
        verticalScroll.post {
            val range = (surface.measuredHeight - verticalScroll.height).coerceAtLeast(0)
            verticalScroll.scrollTo(0, (range * progress).roundToInt())
        }
    }

    private fun hitTest(rendererX: Double, rendererY: Double) {
        val lookup = renderer?.boundsLookup
        if (lookup == null || !lookup.isFinished) {
            Log.d(STABLE_ALPHATAB_LOG_TAG, "Selection cleared: bounds lookup is not ready")
            onSelectionHit(AlphaTabSelectionHit.Empty)
            return
        }
        val noteBounds = allNoteHeadBounds(lookup)
        val note = ExactNoteHeadHitTester.findUnique(
            rendererX,
            rendererY,
            noteBounds
        )
        val restBounds = exactRestBounds(lookup)
        val clefBounds = exactClefBounds(lookup)
        val barlineBounds = exactBarlineBounds(lookup)
        val measureBounds = exactMeasureBounds(lookup)
        val hit = when {
            note != null -> AlphaTabSelectionHit.NoteHit(note)
            else -> ExactElementHitTester.findUnique(rendererX, rendererY, restBounds)
                ?.let(AlphaTabSelectionHit::RestHit)
                ?: ExactElementHitTester.findUnique(rendererX, rendererY, clefBounds)
                    ?.let(AlphaTabSelectionHit::ClefHit)
                ?: ExactElementHitTester.findUnique(rendererX, rendererY, barlineBounds)
                    ?.let { AlphaTabSelectionHit.BarlineHit(it.bar, it.side) }
                ?: ExactElementHitTester.findUnique(rendererX, rendererY, measureBounds)
                    ?.let(AlphaTabSelectionHit::MeasureHit)
                ?: AlphaTabSelectionHit.Empty
        }
        logHitDiagnostic(rendererX, rendererY, hit, noteBounds, restBounds, clefBounds, barlineBounds, measureBounds)
        onSelectionHit(hit)
    }

    private fun allNoteHeadBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup
    ): List<ExactNoteHeadBounds<Note>> {
        val currentScore = score ?: return emptyList()
        val results = mutableListOf<ExactNoteHeadBounds<Note>>()
        currentScore.tracks.asSequence().forEach { track ->
            track.staves.asSequence().forEach { staff ->
                staff.bars.asSequence().forEach { bar ->
                    bar.voices.asSequence().forEach { voice ->
                        voice.beats.asSequence().forEach { beat ->
                            beat.notes.asSequence().forEach { note ->
                                if (note.isVisible) {
                                    noteHeadBounds(lookup, note).forEach { bounds ->
                                        results += ExactNoteHeadBounds(
                                            note = note,
                                            x = bounds.x,
                                            y = bounds.y,
                                            width = bounds.w,
                                            height = bounds.h
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    private fun noteHeadBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup,
        note: alphaTab.model.Note
    ): List<alphaTab.rendering.utils.Bounds> {
        localizedNoteBounds[note]?.let { return it }
        val beats = lookup.findBeats(note.beat) ?: return emptyList()
        val matches = mutableListOf<alphaTab.rendering.utils.Bounds>()
        for (beatBounds in beats) {
            val notes = beatBounds.notes ?: continue
            for (noteBounds in notes) {
                if (noteBounds.note === note) matches += noteBounds.noteHeadBounds
            }
        }
        return matches
    }

    private fun exactRestBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup
    ): List<ExactElementBounds<Beat>> = buildList {
        val currentScore = score ?: return@buildList
        currentScore.tracks.asSequence().forEach { track ->
            track.staves.asSequence().forEach { staff ->
                staff.bars.asSequence().forEach { bar ->
                    val barRenderer = rendererForBar(bar) ?: return@forEach
                    bar.voices.asSequence().flatMap { it.beats.asSequence() }
                        .filter { it.isRest && !it.isEmpty }
                        .forEach { beat ->
                            val container = invokeMethod(barRenderer, "getBeatContainer", beat) ?: return@forEach
                            val onNotes = invokeMethod(container, "getOnNotes") ?: return@forEach
                            if (onNotes.javaClass.simpleName != "ScoreBeatGlyph") return@forEach
                            val glyph = invokeMethod(onNotes, "getRestGlyph") ?: return@forEach
                            val beatBounds = lookup.findBeats(beat)?.asSequence()
                                ?.firstOrNull { it.barBounds.bar === bar } ?: return@forEach
                            val voiceContainer = invokeMethod(container, "getVoiceContainer") ?: return@forEach
                            val x = beatBounds.onNotesX - doubleValue(onNotes, "getCenterX") +
                                doubleValue(glyph, "getX")
                            val y = beatBounds.barBounds.realBounds.y + doubleValue(voiceContainer, "getY") +
                                doubleValue(container, "getY") + doubleValue(onNotes, "getY") +
                                doubleValue(glyph, "getY")
                            val rect = musicFontRect(glyph, x, y) ?: return@forEach
                            add(
                                ExactElementBounds(
                                    beat,
                                    rect.x,
                                    rect.y,
                                    rect.width,
                                    rect.height
                                )
                            )
                        }
                }
            }
        }
    }

    private fun exactClefBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup
    ): List<ExactElementBounds<Bar>> = buildList {
        allScoreBars().forEach { bar ->
            if (identityMapping?.clefIdentity(bar) == null) return@forEach
            glyphCandidates(lookup, bar, "ClefGlyph").flatMap { it.rects }.forEach { rect ->
                add(ExactElementBounds(bar, rect.x, rect.y, rect.width, rect.height))
            }
        }
    }

    private fun exactBarlineBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup
    ): List<ExactElementBounds<BarlineRenderTarget>> = buildList {
        allScoreBars().forEach { bar ->
            val barBounds = barBounds(lookup, bar) ?: return@forEach
            val mid = barBounds.realBounds.x + barBounds.realBounds.w / 2.0
            val rendererCenters = glyphCenterXs(lookup, bar, "BarLineGlyph")
            glyphCandidates(lookup, bar, "BarLineGlyph").forEachIndexed { index, candidate ->
                // Repeat dots extend inward and can move the painted envelope across the measure
                // midpoint. The BarLineGlyph group's renderer position, not its ink centroid,
                // authoritatively identifies whether this is the left or right occurrence.
                val centerX = rendererCenters.getOrNull(index) ?: candidate.centerX
                val side = if (centerX <= mid) BarlineSide.LEFT else BarlineSide.RIGHT
                if (identityMapping?.barlineIdentity(bar, side) == null) return@forEachIndexed
                candidate.rects.forEach { rect ->
                    add(ExactElementBounds(BarlineRenderTarget(bar, side), rect.x, rect.y, rect.width, rect.height))
                }
            }
        }
    }

    private fun exactMeasureBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup
    ): List<ExactElementBounds<Bar>> = buildList {
        allScoreBars().forEach { bar ->
            if (identityMapping?.measureIdentity(bar) == null) return@forEach
            val bounds = barBounds(lookup, bar)?.visualBounds ?: return@forEach
            add(ExactElementBounds(bar, bounds.x, bounds.y, bounds.w, bounds.h))
        }
    }

    private fun rendererForBar(bar: Bar): Any? = runCatching {
        val currentRenderer = renderer ?: return@runCatching null
        val getter = currentRenderer.javaClass.getMethod("getLayout\$alphaTab_android_release")
        val layout = getter.invoke(currentRenderer) ?: return@runCatching null
        layout.javaClass.methods.single { method ->
            method.name == "getRendererForBar" && method.parameterTypes.size == 2
        }.invoke(layout, "score", bar)
    }.onFailure { failure ->
        Log.w(STABLE_ALPHATAB_LOG_TAG, "Exact glyph lookup could not access alphaTab's renderer tree", failure)
    }.getOrNull()

    private fun barBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup,
        bar: Bar
    ): alphaTab.rendering.utils.BarBounds? = lookup.findMasterBar(bar.masterBar)?.bars?.asSequence()
        ?.firstOrNull { it.bar === bar }

    private fun glyphCandidates(
        lookup: alphaTab.rendering.utils.BoundsLookup,
        bar: Bar,
        typeName: String
    ): List<GlyphCandidate> {
        val barRenderer = rendererForBar(bar) ?: return emptyList()
        val bounds = barBounds(lookup, bar)?.realBounds ?: return emptyList()
        val roots = listOfNotNull(
            reflectedField(barRenderer, "_preBeatGlyphs"),
            reflectedField(barRenderer, "_postBeatGlyphs")
        )
        return buildList {
            roots.forEach { root ->
                collectGlyphCandidates(root, bounds.x, bounds.y, typeName, this)
            }
        }
    }

    /** Returns renderer occurrences even when their computed rectangle is unusable for tapping. */
    private fun glyphCenterXs(
        lookup: alphaTab.rendering.utils.BoundsLookup,
        bar: Bar,
        typeName: String
    ): List<Double> {
        val barRenderer = rendererForBar(bar) ?: return emptyList()
        val bounds = barBounds(lookup, bar)?.realBounds ?: return emptyList()
        val roots = listOfNotNull(
            reflectedField(barRenderer, "_preBeatGlyphs"),
            reflectedField(barRenderer, "_postBeatGlyphs")
        )
        return buildList {
            roots.forEach { root -> collectGlyphCenterXs(root, bounds.x, typeName, this) }
        }
    }

    private fun collectGlyphCenterXs(
        glyph: Any,
        parentX: Double,
        typeName: String,
        output: MutableList<Double>
    ) {
        val absoluteX = parentX + doubleValue(glyph, "getX")
        if (glyph.javaClass.simpleName == typeName) {
            val width = doubleValue(glyph, "getWidth")
            if (absoluteX.isFinite() && width.isFinite() && width > 0.0) output += absoluteX + width / 2.0
            return
        }
        glyphChildren(glyph).forEach { child -> collectGlyphCenterXs(child, absoluteX, typeName, output) }
    }

    private fun collectGlyphCandidates(
        glyph: Any,
        parentX: Double,
        parentY: Double,
        typeName: String,
        output: MutableList<GlyphCandidate>
    ) {
        val absoluteX = parentX + doubleValue(glyph, "getX")
        val absoluteY = parentY + doubleValue(glyph, "getY")
        if (glyph.javaClass.simpleName == typeName) {
            val rects = leafGlyphRects(glyph, parentX, parentY).filter(RendererRect::isUsable)
            if (rects.isNotEmpty()) output += GlyphCandidate(rects)
            return
        }
        glyphChildren(glyph).forEach { child ->
            collectGlyphCandidates(child, absoluteX, absoluteY, typeName, output)
        }
    }

    private fun leafGlyphRects(glyph: Any, parentX: Double, parentY: Double): List<RendererRect> {
        val absoluteX = parentX + doubleValue(glyph, "getX")
        val absoluteY = parentY + doubleValue(glyph, "getY")
        val children = glyphChildren(glyph)
        if (children.isEmpty()) return listOfNotNull(exactLeafGlyphRect(glyph, absoluteX, absoluteY))
        return children.flatMap { child -> leafGlyphRects(child, absoluteX, absoluteY) }
    }

    /**
     * alphaTab deliberately reports zero layout height for most Bravura glyphs and for barline
     * leaves. Recreate the rectangles from the same font and paint formulas used by AndroidCanvas
     * and BarLineGlyphBase instead of widening a neighboring BeatBounds/BarBounds rectangle.
     */
    private fun exactLeafGlyphRect(glyph: Any, x: Double, y: Double): RendererRect? {
        if (declaredMethod(glyph.javaClass, "getSymbol") != null) {
            return musicFontRect(glyph, x, y)
        }
        if (glyph.javaClass.simpleName.startsWith("BarLine")) {
            return barlineLeafRect(glyph, x, y)
        }
        return RendererRect(
            x,
            y,
            doubleValue(glyph, "getWidth"),
            doubleValue(glyph, "getHeight")
        ).takeIf(RendererRect::isUsable)
    }

    private fun musicFontRect(glyph: Any, baselineX: Double, baselineY: Double): RendererRect? {
        val symbol = invokeMethod(glyph, "getSymbol") ?: return null
        val codePoint = (invokeMethod(symbol, "getValue") as? Number)?.toInt() ?: return null
        if (codePoint == 0) return null
        val glyphScale = doubleValue(glyph, "getGlyphScale").takeIf { it.isFinite() } ?: return null
        val text = codePoint.toChar().toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            typeface = alphaTabMusicTypeface()
            textSize = (34.0 * glyphScale).toFloat()
            isSubpixelText = true
            hinting = Paint.HINTING_ON
        }
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) return null
        val centered = invokeMethod(glyph, "getCenter") as? Boolean ?: false
        val originX = if (centered) baselineX - paint.measureText(text) / 2.0 else baselineX
        return RendererRect(
            originX + ink.left,
            baselineY + ink.top,
            ink.width().toDouble(),
            ink.height().toDouble()
        ).takeIf(RendererRect::isUsable)
    }

    private fun alphaTabMusicTypeface(): Typeface = runCatching {
        Class.forName("alphaTab.platform.android.AndroidCanvasKt")
            .getMethod("getMusicFont")
            .invoke(null) as Typeface
    }.getOrElse {
        Typeface.createFromAsset(context.assets, "Bravura.otf")
    }

    private fun barlineLeafRect(glyph: Any, x: Double, y: Double): RendererRect? {
        val barRenderer = invokeMethod(glyph, "getRenderer") ?: return null
        val topPadding = doubleValue(barRenderer, "getTopPadding")
        val bottomPadding = doubleValue(barRenderer, "getBottomPadding")
        val rendererHeight = doubleValue(barRenderer, "getHeight")
        if (!topPadding.isFinite() || !bottomPadding.isFinite() || !rendererHeight.isFinite()) return null
        val paintedTop = y + topPadding
        val paintedHeight = rendererHeight - topPadding - bottomPadding
        if (paintedHeight <= 0.0) return null
        val width = doubleValue(glyph, "getWidth").takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return when (glyph.javaClass.simpleName) {
            "BarLineRepeatDotsGlyph" -> RendererRect(
                x - 1.5,
                paintedTop + paintedHeight / 2.0 - 6.0,
                3.0,
                12.0
            )
            "BarLineDottedGlyph" -> RendererRect(x - 1.0, paintedTop, 2.0, paintedHeight)
            "BarLineDashedGlyph" -> RendererRect(x, paintedTop, 1.0, paintedHeight)
            "BarLineShortGlyph" -> {
                val lineHeight = (invokeMethod(barRenderer, "getLineHeight", 1.0) as? Number)?.toDouble()
                    ?: return null
                val drawnLineCount = doubleValue(barRenderer, "getDrawnLineCount")
                if (!drawnLineCount.isFinite() || drawnLineCount <= 3.0) return null
                val shortHeight = lineHeight * 2.0
                val shortY = paintedTop + ((drawnLineCount - 1.0) / 2.0 * lineHeight) - shortHeight / 2.0
                RendererRect(x, shortY, 1.0, shortHeight)
            }
            "BarLineTickGlyph" -> {
                val lineHeight = (invokeMethod(barRenderer, "getLineHeight", 1.0) as? Number)?.toDouble()
                    ?: return null
                RendererRect(x, paintedTop - lineHeight / 2.0 + 1.0, 1.0, lineHeight)
            }
            else -> RendererRect(x, paintedTop, width, paintedHeight)
        }.takeIf(RendererRect::isUsable)
    }

    private fun glyphChildren(glyph: Any): List<Any> {
        val getter = declaredMethod(glyph.javaClass, "getGlyphs") ?: return emptyList()
        val collection = runCatching { getter.apply { isAccessible = true }.invoke(glyph) }.getOrNull()
            ?: return emptyList()
        if (collection is Iterable<*>) return collection.filterNotNull()
        val length = doubleValue(collection, "getLength").toInt()
        return (0 until length).mapNotNull { invokeMethod(collection, "get", it) }
    }

    private fun declaredMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun invokeMethod(instance: Any, name: String, vararg arguments: Any): Any? = runCatching {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == arguments.size
            }?.let { method ->
                method.isAccessible = true
                return@runCatching method.invoke(instance, *arguments)
            }
            current = current.superclass
        }
        null
    }.getOrNull()

    private fun doubleValue(instance: Any, getter: String): Double =
        (invokeMethod(instance, getter) as? Number)?.toDouble() ?: Double.NaN

    private fun reflectedField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun applySelectionStyle(nextSelection: AlphaTabRenderSelection?): Set<Double> {
        val affected = linkedSetOf<Double>()
        selectedElementStyle?.let { previous ->
            previous.restore()
            affected += previous.masterBarIndexes
        }
        selectedElementStyle = null
        if (nextSelection == null) return affected

        val restorer = when (nextSelection) {
            is AlphaTabRenderSelection.NoteSelection -> styleNotes(listOf(nextSelection.note))
            is AlphaTabRenderSelection.ChordSelection -> styleNotes(nextSelection.beat.notes.asSequence().toList())
            is AlphaTabRenderSelection.RestSelection -> styleRest(nextSelection.beat)
            is AlphaTabRenderSelection.ClefSelection -> styleBars(
                nextSelection.bars,
                BarSubElement.StandardNotationClef
            )
            is AlphaTabRenderSelection.BarlineSelection -> styleBars(
                nextSelection.bars,
                BarSubElement.StandardNotationBarLines
            )
            is AlphaTabRenderSelection.MeasureSelection -> styleBars(
                nextSelection.bars,
                BarSubElement.StandardNotationStaffLine
            )
        }
        selectedElementStyle = restorer
        affected += restorer.masterBarIndexes
        return affected
    }

    private fun styleNotes(notes: List<Note>): SelectedElementStyle {
        val originals = notes.map { it to it.style }
        originals.forEach { (note, original) ->
            note.style = NoteStyle().apply {
                if (original != null) {
                    noteHead = original.noteHead
                    noteHeadCenterOnStem = original.noteHeadCenterOnStem
                    colors = Map(original.colors)
                }
                colors.set(NoteSubElement.StandardNotationNoteHead, SELECTED_ELEMENT_COLOR)
            }
        }
        return SelectedElementStyle(
            masterBarIndexes = notes.mapTo(linkedSetOf()) { it.beat.voice.bar.masterBar.index },
            restore = { originals.forEach { (note, style) -> note.style = style } }
        )
    }

    private fun styleRest(beat: Beat): SelectedElementStyle {
        val original = beat.style
        beat.style = BeatStyle().apply {
            if (original != null) colors = Map(original.colors)
            colors.set(BeatSubElement.StandardNotationRests, SELECTED_ELEMENT_COLOR)
        }
        return SelectedElementStyle(setOf(beat.voice.bar.masterBar.index)) { beat.style = original }
    }

    private fun styleBars(bars: List<Bar>, subElement: BarSubElement): SelectedElementStyle {
        val originals = bars.map { it to it.style }
        originals.forEach { (bar, original) ->
            bar.style = BarStyle().apply {
                if (original != null) colors = Map(original.colors)
                colors.set(subElement, SELECTED_ELEMENT_COLOR)
            }
        }
        return SelectedElementStyle(
            bars.mapTo(linkedSetOf()) { it.masterBar.index },
            restore = { originals.forEach { (bar, style) -> bar.style = style } }
        )
    }

    private fun applyPitchVisualUpdate(update: AlphaTabPitchVisualUpdate?): Set<Note> {
        if (update == null || update.revision == lastPitchVisualRevision) return emptySet()
        lastPitchVisualRevision = update.revision
        return when (update) {
            is AlphaTabPitchVisualUpdate.Apply -> {
                Log.d(
                    STABLE_ALPHATAB_LOG_TAG,
                    "EDITOR_PITCH_VISUAL apply revision=${update.revision} runtimeId=${update.note.id} midi=${update.pitchMidi}"
                )
                optimisticPitchSnapshot = RuntimePitchSnapshot(
                    note = update.note,
                    octave = update.note.octave,
                    tone = update.note.tone,
                    accidentalMode = update.note.accidentalMode
                )
                update.note.octave = (update.pitchMidi / 12).toDouble()
                update.note.tone = (update.pitchMidi % 12).toDouble()
                update.note.accidentalMode = NoteAccidentalMode.ForceNone
                setOf(update.note)
            }
            is AlphaTabPitchVisualUpdate.Rollback -> {
                val snapshot = optimisticPitchSnapshot
                optimisticPitchSnapshot = null
                if (snapshot != null && snapshot.note === update.note) {
                    update.note.octave = snapshot.octave
                    update.note.tone = snapshot.tone
                    update.note.accidentalMode = snapshot.accidentalMode
                    setOf(update.note)
                } else {
                    emptySet()
                }
            }
            is AlphaTabPitchVisualUpdate.Commit -> {
                optimisticPitchSnapshot = null
                emptySet()
            }
        }
    }

    /**
     * alphaTab 1.6.1's retained lazy-partial repaint is unavailable when lazy
     * loading is disabled, and it cannot relayout changed pitch geometry. Render
     * only the owning system's bar range with a short-lived renderer, then
     * transplant that bitmap and its exact bounds for both selection and pitch.
     */
    private fun refreshSystems(masterBarIndexes: Set<Double>) {
        val currentScore = score ?: return
        if (masterBarIndexes.isEmpty() || width <= 0 || surface.chunkCount == 0) return
        val targets = masterBarIndexes.mapNotNull { masterBarIndex ->
            surface.chunkForMasterBar(masterBarIndex)
        }.distinctBy { it.id }
        if (targets.isEmpty()) {
            Log.w(STABLE_ALPHATAB_LOG_TAG, "Localized selection render skipped: no retained system owns the element")
            return
        }
        activeLocalizedRenderers.forEach(ScoreRenderer::destroy)
        activeLocalizedRenderers.clear()
        val generation = ++localizedRenderGeneration
        localizedRenderCount++
        lastPitchFeedbackMillis = null
        lastLocalizedChunkIds = targets.mapTo(linkedSetOf()) { it.id }
        localizedExpectedChunks = targets.size
        localizedCompletedChunks = 0
        localizedRefreshStartedNanos = System.nanoTime()
        targets.forEach { target -> renderPitchSystem(currentScore, target, generation) }
    }

    private fun renderPitchSystem(currentScore: Score, target: ScoreChunk, generation: Long) {
        // Render the complete layout offscreen so alphaTab uses exactly the same system breaks as
        // the authoritative score. startBar/barCount slices can reflow one retained system into
        // multiple bitmaps at clamp zooms, which cannot be promoted without stitching geometry.
        val localSettings = createStableEditorSettings(settings?.display?.scale?.toFloat() ?: 1f)
        val candidates = mutableListOf<LocalizedRenderResult>()
        val localRenderer = ScoreRenderer(localSettings).also { candidate ->
            candidate.width = width / density
            candidate.partialRenderFinished.on { result ->
                val bitmap = result.renderResult as? Bitmap ?: return@on
                if (result.firstMasterBarIndex >= 0.0) {
                    candidates += LocalizedRenderResult(result, bitmap)
                } else if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            candidate.postRenderFinished.on {
                post {
                    val globallyIndexed = candidates.singleOrNull { rendered ->
                        rendered.event.firstMasterBarIndex <= target.firstMasterBarIndex &&
                            rendered.event.lastMasterBarIndex >= target.lastMasterBarIndex
                    }
                    // alphaTab 1.6.1 reports startBar slices from zero on some
                    // Android renders. Accept that relative result only when its
                    // lookup proves it actually contains the requested notes.
                    val relativelyIndexed = candidates.singleOrNull()?.takeIf { globallyIndexed == null }
                    val result = globallyIndexed ?: relativelyIndexed
                    candidates.filter { it !== result }.forEach { unused ->
                        if (!unused.bitmap.isRecycled) unused.bitmap.recycle()
                    }
                    if (!released && generation == localizedRenderGeneration && result != null) {
                        updateLocalizedBounds(candidate, target, result.event)
                        surface.replaceBitmap(target.id, result.bitmap)
                        renderer?.boundsLookup?.takeIf { it.isFinished }?.let(::diagnoseVisibleElements)
                        val elapsed = (System.nanoTime() - localizedRefreshStartedNanos) / 1_000_000L
                        localizedCompletedChunks++
                        if (localizedCompletedChunks >= localizedExpectedChunks) {
                            lastPitchFeedbackMillis = elapsed
                        }
                        Log.d(
                            STABLE_ALPHATAB_LOG_TAG,
                            "EDITOR_LOCAL_PITCH_RENDER chunk=${target.id} " +
                                "bars=${target.firstMasterBarIndex.toInt()}..${target.lastMasterBarIndex.toInt()} " +
                                "replacedChunks=$localizedCompletedChunks/$localizedExpectedChunks feedbackMs=$elapsed"
                        )
                    } else if (result != null && !result.bitmap.isRecycled) {
                        result.bitmap.recycle()
                    } else if (!released) {
                        Log.w(
                            STABLE_ALPHATAB_LOG_TAG,
                            "Localized pitch render returned no validated system bitmap for ${target.id} " +
                                "targetBars=${target.firstMasterBarIndex.toInt()}..${target.lastMasterBarIndex.toInt()} " +
                                "candidateBars=${candidates.map { "${it.event.firstMasterBarIndex.toInt()}..${it.event.lastMasterBarIndex.toInt()}" }}"
                        )
                    }
                    activeLocalizedRenderers.remove(candidate)
                    candidate.destroy()
                }
            }
            candidate.error.on { failure ->
                post {
                    if (!released) Log.w(STABLE_ALPHATAB_LOG_TAG, "Localized pitch render failed", failure)
                    activeLocalizedRenderers.remove(candidate)
                    candidate.destroy()
                }
            }
        }
        activeLocalizedRenderers += localRenderer
        val trackIndexes = DoubleList().apply {
            currentScore.tracks.asSequence().forEach { track -> push(track.index) }
        }
        localRenderer.renderScore(currentScore, trackIndexes)
    }

    private fun updateLocalizedBounds(
        localRenderer: ScoreRenderer,
        target: ScoreChunk,
        result: RenderFinishedEventArgs
    ) {
        val lookup = localRenderer.boundsLookup ?: return
        val sourceRegion = RendererRect(result.x, result.y, result.width, result.height)
        val targetRegion = RendererRect(target.x, target.y, target.width, target.height)
        allScoreNotes().filter { note ->
            val index = note.beat.voice.bar.masterBar.index
            index >= target.firstMasterBarIndex && index <= target.lastMasterBarIndex
        }.forEach { note ->
            val transformed = rawNoteHeadBounds(lookup, note).mapNotNull { bounds ->
                LocalizedBoundsTransform.transform(
                    RendererRect(bounds.x, bounds.y, bounds.w, bounds.h),
                    sourceRegion,
                    targetRegion
                )?.let { transformed ->
                    alphaTab.rendering.utils.Bounds().apply {
                        x = transformed.x
                        y = transformed.y
                        w = transformed.width
                        h = transformed.height
                    }
                }
            }
            if (transformed.isEmpty()) localizedNoteBounds.remove(note) else localizedNoteBounds[note] = transformed
        }
    }

    private fun rawNoteHeadBounds(
        lookup: alphaTab.rendering.utils.BoundsLookup,
        note: Note
    ): List<alphaTab.rendering.utils.Bounds> {
        val beats = lookup.findBeats(note.beat) ?: return emptyList()
        return buildList {
            for (beatBounds in beats) {
                val notes = beatBounds.notes ?: continue
                for (noteBounds in notes) {
                    if (noteBounds.note === note) add(noteBounds.noteHeadBounds)
                }
            }
        }
    }

    private fun diagnoseVisibleElements(lookup: alphaTab.rendering.utils.BoundsLookup) {
        val mapping = identityMapping ?: return
        val allBounds = allNoteHeadBounds(lookup)
        allScoreNotes().filter { it.isVisible }.forEach { note ->
            val stableIdentity = mapping.noteIdentity(note)
            val bounds = noteHeadBounds(lookup, note)
            val invalidBounds = bounds.any { bounds ->
                !bounds.x.isFinite() || !bounds.y.isFinite() ||
                    !bounds.w.isFinite() || !bounds.h.isFinite() || bounds.w <= 0.0 || bounds.h <= 0.0
            }
            val hasUniqueExactPoint = bounds.isNotEmpty() && !invalidBounds &&
                ExactNoteHeadHitTester.findUniquePoint(note, allBounds) != null
            if (stableIdentity == null || !hasUniqueExactPoint) {
                val reasons = buildList {
                    if (stableIdentity == null) add("NO_STABLE_NOTE_IDENTITY")
                    if (bounds.isEmpty()) add("NO_NOTE_HEAD_BOUNDS")
                    if (invalidBounds) add("INVALID_NOTE_HEAD_BOUNDS")
                    if (bounds.isNotEmpty() && !invalidBounds && !hasUniqueExactPoint) {
                        add("NO_UNIQUE_EXACT_NOTE_HEAD_POINT")
                    }
                }
                Log.w(
                    STABLE_ALPHATAB_LOG_TAG,
                    "UNSELECTABLE_VISIBLE_ELEMENT type=note stableIdentity=${stableIdentity?.value ?: "none"} " +
                        "runtimeId=${note.id} pitch=${note.realValue.toInt()} " +
                        "track=${note.beat.voice.bar.staff.track.index.toInt()} " +
                        "staff=${note.beat.voice.bar.staff.index.toInt()} bar=${note.beat.voice.bar.index.toInt()} " +
                        "voice=${note.beat.voice.index.toInt()} beat=${note.beat.index.toInt()} " +
                        "bounds=${bounds.joinToString { "${it.x},${it.y},${it.w},${it.h}" }} " +
                        "reason=${reasons.joinToString("+")}"
                )
            }
        }

        val restBounds = exactRestBounds(lookup)
        allScoreBars().flatMap { it.voices.asSequence() }.flatMap { it.beats.asSequence() }
            .filter { it.isRest && !it.isEmpty }.forEach { beat ->
                diagnoseExactElement(
                    type = "rest",
                    identity = mapping.restIdentity(beat)?.value,
                    element = beat,
                    bounds = restBounds,
                    indexes = elementIndexes(beat)
                )
            }
        val clefBounds = exactClefBounds(lookup)
        allScoreBars().filter {
            mapping.clefIdentity(it) != null && glyphCenterXs(lookup, it, "ClefGlyph").isNotEmpty()
        }.forEach { bar ->
            diagnoseExactElement(
                type = "clef",
                identity = mapping.clefIdentity(bar)?.value,
                element = bar,
                bounds = clefBounds,
                indexes = elementIndexes(bar)
            )
        }
        val barlineBounds = exactBarlineBounds(lookup)
        allScoreBars().forEach { bar ->
            val realBounds = barBounds(lookup, bar)?.realBounds ?: return@forEach
            val middleX = realBounds.x + realBounds.w / 2.0
            val renderedSides = glyphCenterXs(lookup, bar, "BarLineGlyph").mapTo(linkedSetOf()) { centerX ->
                if (centerX <= middleX) BarlineSide.LEFT else BarlineSide.RIGHT
            }
            listOf(BarlineSide.LEFT, BarlineSide.RIGHT).forEach { side ->
                if (side !in renderedSides) return@forEach
                val identity = mapping.barlineIdentity(bar, side)?.value ?: return@forEach
                val target = BarlineRenderTarget(bar, side)
                diagnoseExactElement(
                    type = "barline-${side.name.lowercase()}",
                    identity = identity,
                    element = target,
                    bounds = barlineBounds,
                    indexes = elementIndexes(bar)
                )
            }
        }
        val measureBounds = exactMeasureBounds(lookup)
        allScoreBars().forEach { bar ->
            diagnoseExactElement(
                type = "measure",
                identity = mapping.measureIdentity(bar)?.value,
                element = bar,
                bounds = measureBounds,
                indexes = elementIndexes(bar)
            )
        }
    }

    private fun <T> diagnoseExactElement(
        type: String,
        identity: String?,
        element: T,
        bounds: List<ExactElementBounds<T>>,
        indexes: String
    ) {
        val own = bounds.filter { it.element == element }
        val point = ExactElementHitTester.findUniquePoint(element, bounds)
        if (identity != null && own.isNotEmpty() && point != null) return
        val reasons = buildList {
            if (identity == null) add("NO_STABLE_IDENTITY")
            if (own.isEmpty()) add("NO_EXACT_BOUNDS")
            if (own.isNotEmpty() && point == null) add("AMBIGUOUS_EXACT_HIT_REGION")
        }
        Log.w(
            STABLE_ALPHATAB_LOG_TAG,
            "UNSELECTABLE_VISIBLE_ELEMENT type=$type stableIdentity=${identity ?: "none"} $indexes " +
                "zoom=$authoritativeZoom scroll=(${horizontalScroll.scrollX},${verticalScroll.scrollY}) " +
                "bounds=${own.joinToString { "${it.x},${it.y},${it.width},${it.height}" }} reason=${reasons.joinToString("+")}"
        )
    }

    private fun logHitDiagnostic(
        rendererX: Double,
        rendererY: Double,
        hit: AlphaTabSelectionHit,
        notes: List<ExactNoteHeadBounds<Note>>,
        rests: List<ExactElementBounds<Beat>>,
        clefs: List<ExactElementBounds<Bar>>,
        barlines: List<ExactElementBounds<BarlineRenderTarget>>,
        measures: List<ExactElementBounds<Bar>>
    ) {
        fun contains(x: Double, y: Double, bx: Double, by: Double, w: Double, h: Double) =
            w > 0.0 && h > 0.0 && x >= bx && x <= bx + w && y >= by && y <= by + h
        val overlaps = notes.count { contains(rendererX, rendererY, it.x, it.y, it.width, it.height) } +
            rests.count { contains(rendererX, rendererY, it.x, it.y, it.width, it.height) } +
            clefs.count { contains(rendererX, rendererY, it.x, it.y, it.width, it.height) } +
            barlines.count { contains(rendererX, rendererY, it.x, it.y, it.width, it.height) } +
            measures.count { contains(rendererX, rendererY, it.x, it.y, it.width, it.height) }
        val mapping = identityMapping
        val description = when (hit) {
            AlphaTabSelectionHit.Empty -> "type=empty identity=none indexes=none"
            is AlphaTabSelectionHit.NoteHit -> "type=note identity=${mapping?.noteIdentity(hit.note)?.value} ${elementIndexes(hit.note.beat)} note=${hit.note.index.toInt()}"
            is AlphaTabSelectionHit.ChordHit -> "type=chord identity=${mapping?.chordIdentity(hit.beat)?.value} ${elementIndexes(hit.beat)}"
            is AlphaTabSelectionHit.RestHit -> "type=rest identity=${mapping?.restIdentity(hit.beat)?.value} ${elementIndexes(hit.beat)}"
            is AlphaTabSelectionHit.ClefHit -> "type=clef identity=${mapping?.clefIdentity(hit.bar)?.value} ${elementIndexes(hit.bar)}"
            is AlphaTabSelectionHit.BarlineHit -> "type=barline-${hit.side.name.lowercase()} identity=${mapping?.barlineIdentity(hit.bar, hit.side)?.value} ${elementIndexes(hit.bar)}"
            is AlphaTabSelectionHit.MeasureHit -> "type=measure identity=${mapping?.measureIdentity(hit.bar)?.value} ${elementIndexes(hit.bar)}"
        }
        Log.d(
            STABLE_ALPHATAB_LOG_TAG,
            "EDITOR_HIT view=(${rendererX * density},${rendererY * density}) renderer=($rendererX,$rendererY) " +
                "$description zoom=$authoritativeZoom scroll=(${horizontalScroll.scrollX},${verticalScroll.scrollY}) overlaps=$overlaps boundsReady=true"
        )
    }

    private fun elementIndexes(bar: Bar): String =
        "part=${bar.staff.track.index.toInt()} staff=${bar.staff.index.toInt()} measure=${bar.index.toInt()}"

    private fun elementIndexes(beat: Beat): String =
        "${elementIndexes(beat.voice.bar)} voice=${beat.voice.index.toInt()} beat=${beat.index.toInt()}"

    private fun allScoreNotes(): Sequence<Note> = score?.tracks?.asSequence().orEmpty()
        .flatMap { it.staves.asSequence() }
        .flatMap { it.bars.asSequence() }
        .flatMap { it.voices.asSequence() }
        .flatMap { it.beats.asSequence() }
        .flatMap { it.notes.asSequence() }

    private fun allScoreBars(): Sequence<Bar> = score?.tracks?.asSequence().orEmpty()
        .flatMap { it.staves.asSequence() }
        .flatMap { it.bars.asSequence() }

    internal fun performFirstNoteTapForTest(): Boolean {
        return performChordToneTapForTest(0)
    }

    internal fun performChordToneTapForTest(noteIndex: Int): Boolean {
        val currentScore = score ?: return false
        if (currentScore.tracks.length <= 0.0) return false
        val track = currentScore.tracks[0]
        if (track.staves.length <= 0.0 || track.staves[0].bars.length <= 0.0) return false
        val bar = track.staves[0].bars[0]
        val note = bar.voices.asSequence()
            .flatMap { it.beats.asSequence() }
            .flatMap { it.notes.asSequence() }
            .drop(noteIndex)
            .firstOrNull() ?: return false
        val bounds = noteHeadBounds(renderer?.boundsLookup ?: return false, note).singleOrNull()
            ?: return false
        hitTest(bounds.x + bounds.w / 2.0, bounds.y + bounds.h / 2.0)
        return true
    }

    internal fun performAdjacentNonNoteTapForTest(): Boolean {
        val note = allScoreNotes().firstOrNull() ?: return false
        val bounds = noteHeadBounds(renderer?.boundsLookup ?: return false, note).singleOrNull() ?: return false
        hitTest(bounds.x + bounds.w + 0.01, bounds.y + bounds.h / 2.0)
        return true
    }

    internal fun performSafeVisibleNoteTapForTest(identity: String): Boolean {
        val target = safeNoteTapTargets().singleOrNull { it.identity == identity } ?: return false
        hitTest(target.point.x, target.point.y)
        return true
    }

    internal val safelyTappableRestIdentitiesForTest: Set<String>
        get() = safeRestTapTargets().mapTo(linkedSetOf()) { it.identity }
    internal val safelyTappableClefIdentitiesForTest: Set<String>
        get() = safeClefTapTargets().mapTo(linkedSetOf()) { it.identity }
    internal val safelyTappableBarlineIdentitiesForTest: Set<String>
        get() = safeBarlineTapTargets().mapTo(linkedSetOf()) { it.identity }
    internal val safelyTappableMeasureIdentitiesForTest: Set<String>
        get() = safeMeasureTapTargets().mapTo(linkedSetOf()) { it.identity }

    internal fun performSafeRestTapForTest(identity: String): Boolean =
        performSafeElementTap(identity, safeRestTapTargets())

    internal fun performSafeClefTapForTest(identity: String): Boolean =
        performSafeElementTap(identity, safeClefTapTargets())

    internal fun performSafeBarlineTapForTest(identity: String): Boolean =
        performSafeElementTap(identity, safeBarlineTapTargets())

    internal fun performSafeMeasureTapForTest(identity: String): Boolean =
        performSafeElementTap(identity, safeMeasureTapTargets())

    internal fun runtimeAccidentalModeForTest(identity: String): NoteAccidentalMode? =
        identityMapping?.noteRefs?.keys?.singleOrNull { it.value == identity }
            ?.let { identityMapping?.note(it) }
            ?.accidentalMode

    private fun safeNoteTapTargets(): List<SafeNoteTapTarget> {
        val lookup = renderer?.boundsLookup?.takeIf { it.isFinished } ?: return emptyList()
        val mapping = identityMapping ?: return emptyList()
        val allBounds = allNoteHeadBounds(lookup)
        return allScoreNotes().filter { it.isVisible }.mapNotNull { note ->
            val identity = mapping.noteIdentity(note)?.value ?: return@mapNotNull null
            val point = ExactNoteHeadHitTester.findUniquePoint(note, allBounds) ?: return@mapNotNull null
            SafeNoteTapTarget(identity, point)
        }.toList()
    }

    private fun safeRestTapTargets(): List<SafeElementTapTarget> {
        val lookup = renderer?.boundsLookup?.takeIf { it.isFinished } ?: return emptyList()
        val mapping = identityMapping ?: return emptyList()
        val bounds = exactRestBounds(lookup)
        return bounds.map { it.element }.distinctBy(System::identityHashCode).mapNotNull { beat ->
            val identity = mapping.restIdentity(beat)?.value ?: return@mapNotNull null
            ExactElementHitTester.findUniquePoint(beat, bounds)?.let { SafeElementTapTarget(identity, it) }
        }.distinctBy { it.identity }
    }

    private fun safeClefTapTargets(): List<SafeElementTapTarget> {
        val lookup = renderer?.boundsLookup?.takeIf { it.isFinished } ?: return emptyList()
        val mapping = identityMapping ?: return emptyList()
        val bounds = exactClefBounds(lookup)
        return bounds.map { it.element }.distinctBy(System::identityHashCode).mapNotNull { bar ->
            val identity = mapping.clefIdentity(bar)?.value ?: return@mapNotNull null
            ExactElementHitTester.findUniquePoint(bar, bounds)?.let { SafeElementTapTarget(identity, it) }
        }.distinctBy { it.identity }
    }

    private fun safeBarlineTapTargets(): List<SafeElementTapTarget> {
        val lookup = renderer?.boundsLookup?.takeIf { it.isFinished } ?: return emptyList()
        val mapping = identityMapping ?: return emptyList()
        val bounds = exactBarlineBounds(lookup)
        return bounds.map { it.element }.distinct().mapNotNull { target ->
            val identity = mapping.barlineIdentity(target.bar, target.side)?.value ?: return@mapNotNull null
            ExactElementHitTester.findUniquePoint(target, bounds)?.let { SafeElementTapTarget(identity, it) }
        }.distinctBy { it.identity }
    }

    private fun safeMeasureTapTargets(): List<SafeElementTapTarget> {
        val lookup = renderer?.boundsLookup?.takeIf { it.isFinished } ?: return emptyList()
        val mapping = identityMapping ?: return emptyList()
        val bounds = exactMeasureBounds(lookup)
        val blockers = buildList<ExactElementBounds<Any>> {
            allNoteHeadBounds(lookup).forEach { add(ExactElementBounds(it.note, it.x, it.y, it.width, it.height)) }
            exactRestBounds(lookup).forEach { add(ExactElementBounds(it.element, it.x, it.y, it.width, it.height)) }
            exactClefBounds(lookup).forEach { add(ExactElementBounds(it.element, it.x, it.y, it.width, it.height)) }
            exactBarlineBounds(lookup).forEach { add(ExactElementBounds(it.element, it.x, it.y, it.width, it.height)) }
        }
        return bounds.map { it.element }.distinctBy(System::identityHashCode).mapNotNull { bar ->
            val identity = mapping.measureIdentity(bar)?.value ?: return@mapNotNull null
            findUnblockedMeasurePoint(bar, bounds, blockers)?.let { SafeElementTapTarget(identity, it) }
        }.distinctBy { it.identity }
    }

    private fun findUnblockedMeasurePoint(
        bar: Bar,
        measures: List<ExactElementBounds<Bar>>,
        blockers: List<ExactElementBounds<Any>>
    ): ExactHitPoint? {
        val usableBlockers = blockers.filter { it.width > 0.0 && it.height > 0.0 }
        measures.filter { it.element === bar && it.width > 0.0 && it.height > 0.0 }.forEach { target ->
            val right = target.x + target.width
            val bottom = target.y + target.height
            val overlaps = usableBlockers.filter {
                it.x < right && it.x + it.width > target.x && it.y < bottom && it.y + it.height > target.y
            }
            val xEdges = (listOf(target.x, right) + overlaps.flatMap {
                listOf(it.x.coerceIn(target.x, right), (it.x + it.width).coerceIn(target.x, right))
            }).distinct().sorted()
            val yEdges = (listOf(target.y, bottom) + overlaps.flatMap {
                listOf(it.y.coerceIn(target.y, bottom), (it.y + it.height).coerceIn(target.y, bottom))
            }).distinct().sorted()
            for (xIndex in 0 until xEdges.lastIndex) for (yIndex in 0 until yEdges.lastIndex) {
                val point = ExactHitPoint(
                    (xEdges[xIndex] + xEdges[xIndex + 1]) / 2.0,
                    (yEdges[yIndex] + yEdges[yIndex + 1]) / 2.0
                )
                val blocked = overlaps.any {
                    point.x >= it.x && point.x <= it.x + it.width &&
                        point.y >= it.y && point.y <= it.y + it.height
                }
                if (!blocked && ExactElementHitTester.findUnique(point.x, point.y, measures) === bar) return point
            }
        }
        return null
    }

    private fun performSafeElementTap(identity: String, targets: List<SafeElementTapTarget>): Boolean {
        val target = targets.singleOrNull { it.identity == identity } ?: return false
        hitTest(target.point.x, target.point.y)
        return true
    }

    internal fun performEmptyTapForTest() {
        onSelectionHit(AlphaTabSelectionHit.Empty)
    }

    fun release() {
        if (released) return
        released = true
        renderScheduled = false
        applySelectionStyle(null)
        activeLocalizedRenderers.forEach(ScoreRenderer::destroy)
        activeLocalizedRenderers.clear()
        renderer?.destroy()
        renderer = null
        surface.clear()
    }
}

private class StableScoreSurface(context: Context) : View(context) {
    private val chunks = linkedMapOf<String, ScoreChunk>()
    private var replacementChunks: LinkedHashMap<String, ScoreChunk>? = null
    private var contentWidth = 1
    private var contentHeight = 1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    var onTap: (Float, Float) -> Unit = { _, _ -> }

    val chunkCount: Int get() = chunks.size
    val hasVisibleRenderedChunks: Boolean
        get() = chunks.values.any { !it.bitmap.isRecycled } &&
            measuredWidth > 1 && measuredHeight > 1 && isShown
    val rendererAnnotationPresent: Boolean
        get() = chunks.values.any(::isRendererAnnotation)
    val bitmapIdentities: kotlin.collections.Map<String, Int>
        get() = chunks.mapValues { (_, chunk) -> System.identityHashCode(chunk.bitmap) }
    fun chunkIdForMasterBar(masterBarIndex: Double): String? = chunks.values.singleOrNull { chunk ->
        chunk.firstMasterBarIndex >= 0 && masterBarIndex >= chunk.firstMasterBarIndex &&
            masterBarIndex <= chunk.lastMasterBarIndex
    }?.id
    fun chunkForMasterBar(masterBarIndex: Double): ScoreChunk? = chunks.values.singleOrNull { chunk ->
        chunk.firstMasterBarIndex >= 0 && masterBarIndex >= chunk.firstMasterBarIndex &&
            masterBarIndex <= chunk.lastMasterBarIndex
    }
    fun replaceBitmap(id: String, bitmap: Bitmap): Boolean {
        val previous = chunks[id] ?: return false
        chunks[id] = previous.copy(bitmap = bitmap)
        if (!previous.bitmap.isRecycled) previous.bitmap.recycle()
        postInvalidate()
        return true
    }
    fun beginReplacement() {
        discardReplacement()
        replacementChunks = linkedMapOf()
    }

    fun commitReplacement(density: Double) {
        val replacement = replacementChunks ?: return
        replacementChunks = null
        chunks.values.forEach { previous ->
            if (replacement.values.none { it.bitmap === previous.bitmap } && !previous.bitmap.isRecycled) {
                previous.bitmap.recycle()
            }
        }
        chunks.clear()
        chunks.putAll(replacement)
        val last = chunks.values.lastOrNull()
        contentWidth = max(1, ((last?.totalWidth ?: 1.0) * density).roundToInt())
        contentHeight = max(1, ((last?.totalHeight ?: 1.0) * density).roundToInt())
        requestLayout()
        postInvalidate()
    }

    fun discardReplacement() {
        replacementChunks?.values?.forEach { chunk ->
            if (!chunk.bitmap.isRecycled) chunk.bitmap.recycle()
        }
        replacementChunks = null
    }

    fun put(chunk: ScoreChunk, density: Double) {
        val target = replacementChunks
        if (target != null) {
            target.remove(chunk.id)?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            target[chunk.id] = chunk
            return
        }
        chunks.remove(chunk.id)?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        chunks[chunk.id] = chunk
        contentWidth = max(1, (chunk.totalWidth * density).roundToInt())
        contentHeight = max(1, (chunk.totalHeight * density).roundToInt())
        requestLayout()
        postInvalidate()
    }

    /**
     * alphaTab 1.6.1 appends an unconditional, separately rendered 12-unit
     * "rendered by alphaTab" chunk after all score/footer content. MPL-2.0 does
     * not require visible in-score attribution, so remove only that final
     * engine annotation; score copyright and other MusicXML content remain.
     */
    fun removeRendererAnnotation() {
        val annotation = chunks.values.maxByOrNull { it.y }?.takeIf(::isRendererAnnotation)
            ?: return

        chunks.remove(annotation.id)
        if (!annotation.bitmap.isRecycled) annotation.bitmap.recycle()
        contentHeight = max(1, ((annotation.totalHeight - annotation.height) * resources.displayMetrics.density).roundToInt())
        requestLayout()
        postInvalidate()
    }

    private fun isRendererAnnotation(chunk: ScoreChunk): Boolean =
        chunk.firstMasterBarIndex < 0 &&
            chunk.lastMasterBarIndex < 0 &&
            abs(chunk.height - ALPHATAB_ANNOTATION_HEIGHT) < 0.01 &&
            abs(chunk.y + chunk.height - chunk.totalHeight) < 0.01

    fun clear() {
        discardReplacement()
        chunks.values.forEach { chunk ->
            if (!chunk.bitmap.isRecycled) chunk.bitmap.recycle()
        }
        chunks.clear()
        contentWidth = 1
        contentHeight = 1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(contentWidth, widthMeasureSpec),
            resolveSize(contentHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        chunks.values.forEach { chunk ->
            if (chunk.bitmap.isRecycled) return@forEach
            runCatching {
                canvas.drawBitmap(
                    chunk.bitmap,
                    null as Rect?,
                    RectF(
                        (chunk.x * density).toFloat(),
                        (chunk.y * density).toFloat(),
                        ((chunk.x + chunk.width) * density).toFloat(),
                        ((chunk.y + chunk.height) * density).toFloat()
                    ),
                    null
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    onTap(event.x, event.y)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = true
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun cancelPendingTap() {
        moved = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

internal object EditorHitTestCoordinates {
    fun toRenderer(surfacePixels: Float, density: Double): Double =
        if (density > 0.0) surfacePixels / density else Double.NaN
}

internal data class RendererRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

private fun RendererRect.isUsable(): Boolean =
    x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0

/** Maps short-lived localized-render coordinates into the retained bitmap rectangle. */
internal object LocalizedBoundsTransform {
    fun transform(bounds: RendererRect, source: RendererRect, target: RendererRect): RendererRect? {
        if (!source.isUsable() || !target.isUsable() || !bounds.isUsable()) return null
        val scaleX = target.width / source.width
        val scaleY = target.height / source.height
        val transformed = RendererRect(
            x = target.x + (bounds.x - source.x) * scaleX,
            y = target.y + (bounds.y - source.y) * scaleY,
            width = bounds.width * scaleX,
            height = bounds.height * scaleY
        )
        return transformed.takeIf { it.isUsable() }
    }

    private fun RendererRect.isUsable(): Boolean =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            width > 0.0 && height > 0.0
}

private data class ScoreChunk(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val totalWidth: Double,
    val totalHeight: Double,
    val firstMasterBarIndex: Double,
    val lastMasterBarIndex: Double,
    val bitmap: Bitmap
)

private data class SelectedElementStyle(
    val masterBarIndexes: Set<Double>,
    val restore: () -> Unit
)

private data class BarlineRenderTarget(val bar: Bar, val side: BarlineSide)

private data class GlyphCandidate(val rects: List<RendererRect>) {
    val centerX: Double = rects.minOf { it.x } +
        (rects.maxOf { it.x + it.width } - rects.minOf { it.x }) / 2.0
}

private data class ZoomAnchor(
    val contentX: Float,
    val contentY: Float,
    val viewportX: Float,
    val viewportY: Float,
    val fromZoom: Float,
    val toZoom: Float
)

private data class LocalizedRenderResult(
    val event: RenderFinishedEventArgs,
    val bitmap: Bitmap
)

private data class SafeNoteTapTarget(
    val identity: String,
    val point: ExactHitPoint
)

private data class SafeElementTapTarget(
    val identity: String,
    val point: ExactHitPoint
)

internal sealed interface AlphaTabPitchVisualUpdate {
    val revision: Long
    val note: Note

    data class Apply(
        override val revision: Long,
        override val note: Note,
        val pitchMidi: Int
    ) : AlphaTabPitchVisualUpdate

    data class Rollback(
        override val revision: Long,
        override val note: Note
    ) : AlphaTabPitchVisualUpdate

    data class Commit(
        override val revision: Long,
        override val note: Note
    ) : AlphaTabPitchVisualUpdate
}

private data class RuntimePitchSnapshot(
    val note: Note,
    val octave: Double,
    val tone: Double,
    val accidentalMode: NoteAccidentalMode
)

private fun createStableEditorSettings(zoom: Float) = Settings().apply {
    core.apply {
        engine = "android"
        enableLazyLoading = false
        useWorkers = false
        // alphaTab 1.6.1 populates NoteBounds only when this is enabled.
        // Phase 8.2 does not consume taps yet, but preserves the renderer-side
        // objects needed for safe Note/Beat hit mapping in Phase 8.3.
        includeNoteBounds = true
    }
    display.apply {
        scale = zoom.toDouble()
        layoutMode = LayoutMode.Page
        staveProfile = StaveProfile.Score
        barsPerRow = -1.0
        stretchForce = 0.9
        justifyLastSystem = true
        padding = DoubleList(8.0, 4.0, 8.0, 0.0)
        firstSystemPaddingTop = 4.0
        systemPaddingTop = 8.0
        systemPaddingBottom = 8.0
        lastSystemPaddingBottom = 6.0
        resources.barNumberColor = alphaTab.model.Color(42.0, 42.0, 42.0, 255.0)
    }
    player.apply {
        enablePlayer = false
        enableCursor = false
        enableUserInteraction = false
    }
}

/** alphaTab marks this required Android bootstrap internal in 1.6.1. */
internal fun initializeAlphaTabAndroidEnvironment(context: Context) {
    AlphaTabAndroidEnvironment.ensureInitialized(context)
}

private object AlphaTabAndroidEnvironment {
    @Volatile private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val environmentClass = Class.forName("alphaTab.platform.android.AndroidEnvironment")
            val companion = environmentClass.getField("Companion").get(null)
            companion.javaClass.getMethod("initializeAndroid", Context::class.java)
                .invoke(companion, context.applicationContext)
            initialized = true
        }
    }
}

private const val ALPHATAB_ANNOTATION_HEIGHT = 12.0
private const val MIN_ZOOM_CHANGE = 0.001f
private const val STABLE_ALPHATAB_LOG_TAG = "SheetSightAlphaTab"
private val SCORE_PAGE_COLOR = Color.rgb(255, 254, 250)
private val SELECTED_ELEMENT_COLOR = alphaTab.model.Color(0.0, 121.0, 107.0, 255.0)
