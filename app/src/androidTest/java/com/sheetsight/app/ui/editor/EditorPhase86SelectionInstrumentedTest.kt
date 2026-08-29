package com.sheetsight.app.ui.editor

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import alphaTab.model.NoteAccidentalMode
import alphaTab.model.AccidentalType
import alphaTab.model.KeySignature
import com.sheetsight.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorPhase86SelectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tapsEverySafeExactElementAndKeepsPinchSelectionStable() {
        assertEquals(AccidentalType.None, forceNoneAccidentalInPackagedRuntime())
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("editor_phase86_complex.musicxml").use { it.readBytes() }
        val loaded = EditorMusicXmlLoader().loadBytes(bytes, SCORE_ID)
        val sourceKey = EditorSourceKey(SCORE_ID, "/fixtures/phase86.musicxml", bytes.size.toLong(), 1L)
        val ready = EditorUiState.Ready(
            scoreId = SCORE_ID,
            title = "Phase 8.6 complex fixture",
            currentMusicXmlPath = sourceKey.currentMusicXmlPath,
            sourceKey = sourceKey,
            renderSessionKey = "phase86-complex-session",
            document = loaded.document,
            musicXml = loaded.musicXml,
            initialSystemIndex = 0,
            initialZoom = 1f,
            warningSummary = null,
            identityIndex = requireNotNull(loaded.identityIndex)
        )
        val selection = mutableStateOf<EditorSelection?>(null)
        val visual = mutableStateOf<EditorPitchVisualUpdate?>(null)
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = ready,
                    selection = selection.value,
                    pitchVisualUpdate = visual.value,
                    onSelectionChanged = { selection.value = it }
                )
            }
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                val view = compose.activity.window.decorView.findPhase86StableView()
                view.retainedChunkCount > 1 && view.mappedVisibleNoteIdentitiesForTest.isNotEmpty()
            }.getOrDefault(false)
        }
        val view = compose.activity.window.decorView.findPhase86StableView()
        val expectedSafe = ready.identityIndex.notes.map { it.identity.value }
            .filterNot { it.contains("ambiguous-low") || it.contains("ambiguous-high") }
            .toSet()

        assertEquals(expectedSafe, view.mappedVisibleNoteIdentitiesForTest)
        assertEquals(expectedSafe, view.safelyTappableNoteIdentitiesForTest)
        tapAndVerifyEveryIdentity(view, expectedSafe, selection)
        assertEquals(1, view.selectedColoredNoteCount)
        assertFalse(view.selectionBorderRendered)

        val safeRests = view.safelyTappableRestIdentitiesForTest
        val safeClefs = view.safelyTappableClefIdentitiesForTest
        val safeBarlines = view.safelyTappableBarlineIdentitiesForTest
        val safeMeasures = view.safelyTappableMeasureIdentitiesForTest
        assertTrue("Complex fixture must expose exact rest glyphs", safeRests.isNotEmpty())
        assertTrue("Complex fixture must expose exact clef glyphs", safeClefs.isNotEmpty())
        assertTrue("Complex fixture must expose exact barline glyphs", safeBarlines.isNotEmpty())
        assertTrue("Complex fixture must expose exact measure regions", safeMeasures.isNotEmpty())
        assertTrue(safeRests.all { id -> ready.identityIndex.rests.any { it.identity.value == id } })
        assertTrue(safeClefs.all { id -> ready.identityIndex.clefs.any { it.identity.value == id } })
        assertTrue(safeBarlines.all { id -> ready.identityIndex.barlines.any { it.identity.value == id } })
        assertTrue(safeMeasures.all { id -> ready.identityIndex.measures.any { it.identity.value == id } })
        tapAndVerifyEveryRest(view, safeRests, selection)
        tapAndVerifyEveryClef(view, safeClefs, selection)
        tapAndVerifyEveryBarline(view, safeBarlines, selection)
        tapAndVerifyEveryMeasure(view, safeMeasures, selection)
        assertEquals(0, view.selectedColoredNoteCount)

        compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeUp() }
        compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeDown() }
        val fullRendersBeforeZoom = view.renderCount
        compose.onNodeWithTag("editor_more").performClick()
        compose.onNodeWithTag("editor_zoom_in").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { view.renderCount > fullRendersBeforeZoom }
        val afterZoomIdentity = expectedSafe.sorted().last()
        compose.runOnUiThread { assertTrue(view.performSafeVisibleNoteTapForTest(afterZoomIdentity)) }
        compose.waitUntil {
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity?.value == afterZoomIdentity &&
                view.selectedColoredNoteIdentitiesForTest == setOf(afterZoomIdentity)
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        val fullRendersBeforePinch = view.renderCount
        val zoomBeforePinch = view.currentZoomForTest
        val centerBeforePinch = view.viewportCenterScorePointForTest
        compose.runOnUiThread { view.performPinchForTest(scaleFactor = 1.75f) }
        compose.waitUntil(timeoutMillis = 30_000) {
            view.renderCount > fullRendersBeforePinch && view.currentZoomForTest > zoomBeforePinch &&
                view.lastPinchLatencyMillis != null
        }
        assertEquals(1, view.lastPinchAuthoritativeRenderCount)
        assertTrue(requireNotNull(view.lastPinchLatencyMillis) < 1_000L)
        val centerAfterPinch = view.viewportCenterScorePointForTest
        assertEquals(centerBeforePinch.x, centerAfterPinch.x, 5.0)
        assertEquals(centerBeforePinch.y, centerAfterPinch.y, 5.0)
        compose.runOnUiThread { assertTrue(view.performSafeVisibleNoteTapForTest(afterZoomIdentity)) }
        compose.waitUntil {
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity?.value == afterZoomIdentity
        }

        val zoomAfterPinchIn = view.currentZoomForTest
        val rendersBeforePinchOut = view.renderCount
        compose.runOnUiThread { view.performPinchForTest(scaleFactor = 0.55f) }
        compose.waitUntil(timeoutMillis = 30_000) {
            view.renderCount > rendersBeforePinchOut && view.currentZoomForTest < zoomAfterPinchIn &&
                view.lastPinchLatencyMillis != null
        }
        assertEquals(1, view.lastPinchAuthoritativeRenderCount)
        assertEquals(expectedSafe, view.safelyTappableNoteIdentitiesForTest)
        assertEquals(safeRests, view.safelyTappableRestIdentitiesForTest)
        assertEquals(safeClefs, view.safelyTappableClefIdentitiesForTest)
        assertEquals(safeBarlines, view.safelyTappableBarlineIdentitiesForTest)
        assertEquals(safeMeasures, view.safelyTappableMeasureIdentitiesForTest)
        assertEquals(
            afterZoomIdentity,
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity?.value
        )

        val rendersBeforeMaximum = view.renderCount
        compose.runOnUiThread { view.performPinchForTest(scaleFactor = 100f) }
        compose.waitUntil(timeoutMillis = 30_000) {
            view.renderCount > rendersBeforeMaximum &&
                view.currentZoomForTest == EditorViewModel.MAX_ZOOM &&
                view.lastPinchLatencyMillis != null
        }
        assertEquals(1, view.lastPinchAuthoritativeRenderCount)
        val rendersAtMaximum = view.renderCount
        compose.runOnUiThread { view.performPinchForTest(scaleFactor = 2f) }
        compose.waitForIdle()
        assertEquals(EditorViewModel.MAX_ZOOM, view.currentZoomForTest)
        assertEquals("A clamped no-op pinch must not engrave again", rendersAtMaximum, view.renderCount)

        val rendersBeforeMinimum = view.renderCount
        compose.runOnUiThread { view.performPinchForTest(scaleFactor = 0.01f) }
        compose.waitUntil(timeoutMillis = 30_000) {
            view.renderCount > rendersBeforeMinimum &&
                view.currentZoomForTest == EditorViewModel.MIN_ZOOM &&
                view.lastPinchLatencyMillis != null
        }
        assertEquals(1, view.lastPinchAuthoritativeRenderCount)
        assertEquals(
            afterZoomIdentity,
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity?.value
        )

        val editTarget = ready.identityIndex.notes.single { it.source.explicitId == "edit-target" }
        compose.runOnUiThread { assertTrue(view.performSafeVisibleNoteTapForTest(editTarget.identity.value)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity == editTarget.identity &&
                view.selectedColoredNoteIdentitiesForTest == setOf(editTarget.identity.value)
        }
        val localizedRendersBeforePitch = view.localizedRenderCount
        val bitmapsBeforePitch = view.retainedBitmapIdentities
        compose.runOnIdle {
            visual.value = EditorPitchVisualUpdate.Apply(
                revision = 86L,
                noteIdentity = editTarget.identity,
                pitchMidi = 65,
                pitchStep = "F",
                pitchOctave = 4
            )
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            view.lastPitchVisualRevisionForTest == 86L &&
                view.runtimeAccidentalModeForTest(editTarget.identity.value) == NoteAccidentalMode.ForceNone &&
                view.localizedRenderCount > localizedRendersBeforePitch
        }
        compose.waitUntil(timeoutMillis = 10_000) { view.lastPitchFeedbackMillis != null }
        val pitchFeedbackMillis = requireNotNull(view.lastPitchFeedbackMillis)
        val pitchReplacedChunks = view.lastLocalizedChunkIds.size
        assertEquals(1, pitchReplacedChunks)
        assertTrue(pitchFeedbackMillis < 1_000L)
        val bitmapsAfterPitch = view.retainedBitmapIdentities
        val replacedChunk = view.lastLocalizedChunkIds.single()
        assertNotEquals(bitmapsBeforePitch[replacedChunk], bitmapsAfterPitch[replacedChunk])
        bitmapsBeforePitch.filterKeys { it != replacedChunk }.forEach { (chunk, bitmapIdentity) ->
            assertEquals("Unaffected retained bitmap $chunk changed", bitmapIdentity, bitmapsAfterPitch[chunk])
        }
        assertEquals(expectedSafe, view.safelyTappableNoteIdentitiesForTest)
        tapAndVerifyEveryIdentity(view, expectedSafe, selection)

        println(
            "EDITOR_PHASE86_ACCEPTANCE safeNotes=${expectedSafe.size} " +
                "safeRests=${safeRests.size} safeClefs=${safeClefs.size} " +
                "safeBarlines=${safeBarlines.size} safeMeasures=${safeMeasures.size} " +
                "feedbackMs=$pitchFeedbackMillis replacedChunks=$pitchReplacedChunks " +
                "pinchMs=${view.lastPinchLatencyMillis} pinchRenders=${view.lastPinchAuthoritativeRenderCount} " +
                "retainedChunks=${view.retainedChunkCount}"
        )
    }

    private fun tapAndVerifyEveryRest(
        view: StableAlphaTabView,
        identities: Set<String>,
        selection: androidx.compose.runtime.MutableState<EditorSelection?>
    ) = identities.sorted().forEach { identity ->
        compose.runOnUiThread { assertTrue(identity, view.performSafeRestTapForTest(identity)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (selection.value as? EditorSelection.RestSelection)?.rest?.identity?.value == identity &&
                view.selectedColoredNoteCount == 0
        }
    }

    private fun tapAndVerifyEveryClef(
        view: StableAlphaTabView,
        identities: Set<String>,
        selection: androidx.compose.runtime.MutableState<EditorSelection?>
    ) = identities.sorted().forEach { identity ->
        compose.runOnUiThread { assertTrue(identity, view.performSafeClefTapForTest(identity)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (selection.value as? EditorSelection.ClefSelection)?.clef?.identity?.value == identity
        }
    }

    private fun tapAndVerifyEveryBarline(
        view: StableAlphaTabView,
        identities: Set<String>,
        selection: androidx.compose.runtime.MutableState<EditorSelection?>
    ) = identities.sorted().forEach { identity ->
        compose.runOnUiThread { assertTrue(identity, view.performSafeBarlineTapForTest(identity)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (selection.value as? EditorSelection.BarlineSelection)?.barline?.identity?.value == identity
        }
    }

    private fun tapAndVerifyEveryMeasure(
        view: StableAlphaTabView,
        identities: Set<String>,
        selection: androidx.compose.runtime.MutableState<EditorSelection?>
    ) = identities.sorted().forEach { identity ->
        compose.runOnUiThread { assertTrue(identity, view.performSafeMeasureTapForTest(identity)) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (selection.value as? EditorSelection.MeasureSelection)?.measure?.identity?.value == identity
        }
    }

    private fun tapAndVerifyEveryIdentity(
        view: StableAlphaTabView,
        identities: Set<String>,
        selection: androidx.compose.runtime.MutableState<EditorSelection?>
    ) {
        identities.sorted().forEach { identity ->
            compose.runOnUiThread { assertTrue(identity, view.performSafeVisibleNoteTapForTest(identity)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                (selection.value as? EditorSelection.NoteSelection)?.note?.identity?.value == identity &&
                    view.selectedColoredNoteIdentitiesForTest == setOf(identity)
            }
            assertEquals("Exactly one note must be colored after selecting $identity", 1, view.selectedColoredNoteCount)
        }
    }

    private fun forceNoneAccidentalInPackagedRuntime(): Any? {
        val helperClass = Class.forName("alphaTab.rendering.utils.AccidentalHelper")
        val companion = helperClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        return companion.javaClass.getDeclaredMethod(
            "computeAccidental",
            KeySignature::class.java,
            NoteAccidentalMode::class.java,
            Double::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            AccidentalType::class.java
        ).apply { isAccessible = true }.invoke(
            companion,
            KeySignature.G,
            NoteAccidentalMode.ForceNone,
            65.0,
            false,
            null
        )
    }

    private companion object {
        const val SCORE_ID = 86L
    }
}

private fun View.findPhase86StableView(): StableAlphaTabView {
    if (this is StableAlphaTabView) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            runCatching { return getChildAt(index).findPhase86StableView() }
        }
    }
    error("StableAlphaTabView was not found")
}
