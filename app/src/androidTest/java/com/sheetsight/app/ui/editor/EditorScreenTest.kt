package com.sheetsight.app.ui.editor

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import android.view.View
import android.view.ViewGroup
import com.sheetsight.app.MainActivity
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.ui.editor.notation.NotationChord
import com.sheetsight.app.ui.editor.notation.NotationClef
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationDurationType
import com.sheetsight.app.ui.editor.notation.NotationMeasure
import com.sheetsight.app.ui.editor.notation.NotationPitch
import com.sheetsight.app.ui.editor.notation.NotationStaff
import com.sheetsight.app.ui.editor.notation.NotationStatistics
import com.sheetsight.app.ui.editor.notation.NotationStem
import com.sheetsight.app.ui.editor.notation.NotationSystem
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class EditorScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loadingStateIsVisible() = verifyTag(EditorUiState.Loading, "editor_loading")
    @Test fun noMusicXmlStateIsVisible() = verifyTag(EditorUiState.NoCurrentMusicXml("Title"), "editor_no_musicxml")
    @Test fun parseErrorStateIsVisible() = verifyTag(EditorUiState.ParseError("Title", "debug"), "editor_parse_error")
    @Test fun renderErrorStateIsVisible() = verifyTag(EditorUiState.RenderError("Title", "debug"), "editor_render_error")

    @Test
    fun scorePickerDisplaysSourceTitleAndNeverInternalMusicXmlPath() {
        val score = Score(
            id = 9L,
            title = "Clair de Lune.pdf",
            originalFilePath = "/library/Clair de Lune.pdf",
            originalMusicXmlPath = "/internal/omr/9/original.musicxml",
            currentMusicXmlPath = "/internal/omr/9/current.musicxml",
            importDate = 1L,
            pageCount = 2
        )
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = EditorUiState.NoScoreSelected,
                    recognizedScores = listOf(score)
                )
            }
        }

        compose.onNodeWithText("Clair de Lune.pdf").assertIsDisplayed()
        compose.onNodeWithText("/internal/omr/9/current.musicxml").assertDoesNotExist()
        compose.onNodeWithText("MusicXML · Generated score").assertIsDisplayed()
    }

    @Test
    fun nullableRoutePickerReadyContentReplacesLoadingContent() {
        val score = Score(
            id = 1L,
            title = "Generated score",
            originalFilePath = "/files/scores/source.pdf",
            originalMusicXmlPath = readyState.currentMusicXmlPath,
            currentMusicXmlPath = readyState.currentMusicXmlPath,
            importDate = 1L,
            pageCount = 1
        )
        compose.activity.setContent {
            var state by androidx.compose.runtime.remember {
                mutableStateOf<EditorUiState>(EditorUiState.NoScoreSelected)
            }
            MaterialTheme {
                EditorScreenContent(
                    state = editorVisibleStateForRoute(routeScoreId = null, uiState = state),
                    recognizedScores = listOf(score),
                    onSelectScore = {
                        state = EditorUiState.Loading
                        state = readyState
                    }
                )
            }
        }

        compose.onNodeWithText("Generated score").performClick()
        compose.onNodeWithTag("editor_ready").assertIsDisplayed()
        compose.onNodeWithTag("editor_loading").assertDoesNotExist()
    }

    @Test
    fun deletingGeneratedScoreRequiresExplicitConfirmation() {
        val score = Score(
            id = 9L,
            title = "Clair de Lune.pdf",
            originalFilePath = "/library/Clair de Lune.pdf",
            originalMusicXmlPath = "/internal/omr/9/current.musicxml",
            currentMusicXmlPath = "/internal/omr/9/current.musicxml",
            importDate = 1L,
            pageCount = 2
        )
        val deletedId = mutableStateOf<Long?>(null)
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = EditorUiState.NoScoreSelected,
                    recognizedScores = listOf(score),
                    onDeleteGeneratedScore = { deletedId.value = it }
                )
            }
        }

        compose.onNodeWithTag("editor_score_more_9").performClick()
        compose.onNodeWithTag("editor_delete_generated_9").performClick()
        compose.onNodeWithText("Delete generated score?").assertIsDisplayed()
        assertEquals(null, deletedId.value)
        compose.onNodeWithTag("editor_confirm_delete_9").performClick()
        assertEquals(9L, deletedId.value)
    }

    @Test
    fun readyStateDisplaysNotationAndZoomControlRemainsUsable() {
        compose.activity.setContent { MaterialTheme { EditorScreenContent(readyState) } }
        compose.onNodeWithTag("editor_ready").assertIsDisplayed()
        compose.onNodeWithTag("sheet_music_score_view").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("alphatab_score_view").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("alphatab_score_view").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("sheet_music_render_loading").assertDoesNotExist()
        compose.onNodeWithTag("alphatab_score_view").assertIsDisplayed()
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()
        assertEquals(1, stableView.alphaTabInitCount)
        assertEquals(1, stableView.scoreLoadCount)
        assertEquals(1, stableView.renderCount)
        compose.onNodeWithTag("editor_more").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, stableView.alphaTabInitCount)
        assertEquals(1, stableView.scoreLoadCount)
        assertEquals(1, stableView.renderCount)
        compose.onNodeWithTag("editor_reset_zoom").assertIsDisplayed().performClick()
    }

    @Test
    fun readyScoreRendersBeforeFullscreenAndSurvivesRecomposition() {
        val recompositionMarker = mutableIntStateOf(0)
        compose.activity.setContent {
            MaterialTheme {
                @Suppress("UNUSED_EXPRESSION")
                recompositionMarker.intValue
                EditorScreenContent(readyState)
            }
        }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()
        assertTrue("Score chunks must exist before fullscreen", stableView.retainedChunkCount > 0)
        assertTrue(
            "Rendered chunks must be measured and visible before fullscreen",
            stableView.hasVisibleRenderedChunks
        )
        assertEquals(1, stableView.renderCount)

        compose.runOnUiThread { recompositionMarker.intValue++ }
        compose.waitForIdle()

        compose.onNodeWithTag("alphatab_score_view").assertIsDisplayed()
        assertTrue("Score must remain visible after recomposition", stableView.retainedChunkCount > 0)
        assertTrue("Rendered chunks must remain visible after recomposition", stableView.hasVisibleRenderedChunks)
        assertEquals("Unchanged score and width must not re-render", 1, stableView.renderCount)
    }

    @Test
    fun noteSelectionSurvivesRecompositionScrollAndZoomAndEmptyTapClearsIt() {
        val selection = mutableStateOf<EditorSelection?>(null)
        val recompositionMarker = mutableIntStateOf(0)
        val draggedSteps = mutableIntStateOf(0)
        compose.activity.setContent {
            @Suppress("UNUSED_EXPRESSION")
            recompositionMarker.intValue
            MaterialTheme {
                EditorScreenContent(
                    state = selectionReadyState,
                    selection = selection.value,
                    onSelectionChanged = { selection.value = it },
                    onNoteDragBy = { draggedSteps.intValue += it }
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()

        compose.runOnUiThread { assertTrue(stableView.performFirstNoteTapForTest()) }
        compose.waitUntil { selection.value is EditorSelection.NoteSelection }
        val selectedIdentity = (selection.value as EditorSelection.NoteSelection).note.identity
        compose.waitUntil { stableView.selectionPointerRendered }
        assertEquals("Selection must not trigger a score-system repaint", 0, stableView.localizedRenderCount)
        assertFalse("Selection border must not be rendered", stableView.selectionBorderRendered)
        compose.onNodeWithTag("editor_pitch_controls").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(stableView.performNoteDragForTest(selectedIdentity.value, 2)) }
        assertEquals(2, draggedSteps.intValue)

        compose.runOnUiThread { assertTrue(stableView.performChordToneTapForTest(1)) }
        compose.waitUntil {
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity != selectedIdentity
        }
        compose.runOnUiThread { assertTrue(stableView.performChordToneTapForTest(0)) }
        compose.waitUntil {
            (selection.value as? EditorSelection.NoteSelection)?.note?.identity == selectedIdentity
        }

        compose.runOnUiThread { assertTrue(stableView.performAdjacentNonNoteTapForTest()) }
        compose.waitUntil { selection.value == null }
        compose.runOnUiThread { assertTrue(stableView.performFirstNoteTapForTest()) }
        compose.waitUntil { selection.value is EditorSelection.NoteSelection }

        compose.runOnUiThread { recompositionMarker.intValue++ }
        compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeUp() }
        compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeDown() }
        compose.onNodeWithTag("editor_more").performClick()
        compose.onNodeWithTag("editor_zoom_in").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { stableView.renderCount >= 2 }

        assertEquals(selectedIdentity, (selection.value as EditorSelection.NoteSelection).note.identity)
        compose.waitUntil { stableView.selectionPointerRendered }

        compose.runOnUiThread { stableView.performEmptyTapForTest() }
        compose.waitUntil { selection.value == null }
        compose.waitUntil { stableView.selectedColoredNoteCount == 0 }
    }

    @Test
    fun insertionCursorDurationToolbarAndAccessibleInsertStayAlignedAfterZoom() {
        val selection = mutableStateOf<EditorSelection?>(null)
        val inserted = mutableStateOf<NoteInsertionAnchor?>(null)
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = readyState,
                    selection = selection.value,
                    onSelectionChanged = { selection.value = it },
                    onInsertNote = { anchor, _, _, _ -> inserted.value = anchor }
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()
        val restIdentity = stableView.safelyTappableRestIdentitiesForTest.single()

        compose.runOnUiThread { assertTrue(stableView.performSafeRestTapForTest(restIdentity)) }
        compose.waitUntil { stableView.insertionCursorRendered }
        compose.onNodeWithTag("editor_duration_quarter").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("editor_insert_note").assertIsDisplayed().assertHeightIsAtLeast(48.dp)

        compose.runOnUiThread { stableView.performPinchForTest(1.25f) }
        compose.waitUntil(timeoutMillis = 20_000) { stableView.currentZoomForTest > 1f }
        compose.waitUntil { stableView.insertionCursorRendered }
        compose.onNodeWithTag("editor_insert_note").performClick()
        compose.waitUntil { inserted.value?.restIdentity?.value == restIdentity }
    }

    @Test
    fun contextualDeleteClefAndTimeActionsExposeAccessibleTouchTargets() {
        val state = selectionReadyState
        val note = state.identityIndex.notes.first()
        val chord = state.identityIndex.chords.single()
        val selection = mutableStateOf<EditorSelection?>(
            EditorSelection.NoteSelection(state.sourceKey, chord.identity, note)
        )
        var deleted by mutableStateOf(false)
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = state,
                    selection = selection.value,
                    onSelectionChanged = { selection.value = it },
                    onDeleteSelection = { deleted = true }
                )
            }
        }

        compose.onNodeWithTag("editor_delete_selection").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil { deleted }

        compose.runOnUiThread {
            val clef = state.identityIndex.clefs.single()
            selection.value = EditorSelection.ClefSelection(state.sourceKey, clef)
        }
        compose.onNodeWithTag("editor_clef_action").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("editor_clef_bass").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("editor_clef_bass").performClick()
        compose.runOnUiThread {
            selection.value = EditorSelection.MeasureSelection(state.sourceKey, state.identityIndex.measures.single())
        }
        compose.onNodeWithTag("editor_time_signature_action")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("editor_time_4_4").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun noteUpKeepsReadyScoreAndScrollWhileReplacingOnlyTheAffectedRetainedChunk() {
        val measures = (1..48).joinToString("\n") { number ->
            """
            <measure number="$number">
              ${if (number == 1) "<attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>" else ""}
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>whole</type><staff>1</staff></note>
            </measure>
            """.trimIndent()
        }
        val xml = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">$measures</part>
            </score-partwise>
        """.trimIndent()
        val sourceKey = EditorSourceKey(1L, "/scores/instant-natural.musicxml", xml.length.toLong(), 3L)
        val state = readyState.copy(
            sourceKey = sourceKey,
            renderSessionKey = "instant-natural-render-session",
            musicXml = xml,
            document = readyState.document.copy(
                systems = List(12) { index -> readyState.document.systems[0].copy(index = index) }
            ),
            identityIndex = MusicXmlIdentityBuilder.build(1L, MusicXmlParser.parseBytes(xml.toByteArray()))
        )
        val selection = mutableStateOf<EditorSelection?>(null)
        val visual = mutableStateOf<EditorPitchVisualUpdate?>(null)
        compose.activity.setContent {
            MaterialTheme {
                EditorScreenContent(
                    state = state,
                    selection = selection.value,
                    pitchVisualUpdate = visual.value,
                    noteEditInProgress = visual.value is EditorPitchVisualUpdate.Apply,
                    onSelectionChanged = { selection.value = it }
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()
        assertTrue(stableView.retainedChunkCount > 1)
        compose.runOnUiThread { assertTrue(stableView.performFirstNoteTapForTest()) }
        compose.waitUntil { selection.value is EditorSelection.NoteSelection }
        compose.waitUntil(timeoutMillis = 20_000) { stableView.selectionPointerRendered }
        repeat(2) { compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeUp() } }
        compose.waitForIdle()

        val selected = selection.value as EditorSelection.NoteSelection
        val scrollBefore = stableView.verticalScrollYForTest
        val bitmapsBefore = stableView.retainedBitmapIdentities
        val fullRenderCount = stableView.renderCount
        val localizedBefore = stableView.localizedRenderCount
        compose.runOnUiThread {
            visual.value = EditorPitchVisualUpdate.Apply(1L, selected.note.identity, 62, "D", 4)
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            stableView.localizedRenderCount == localizedBefore + 1 && stableView.lastPitchFeedbackMillis != null
        }

        val bitmapsAfter = stableView.retainedBitmapIdentities
        val changed = bitmapsBefore.keys.filter { bitmapsBefore[it] != bitmapsAfter[it] }.toSet()
        assertEquals(stableView.lastLocalizedChunkIds, changed)
        assertEquals(fullRenderCount, stableView.renderCount)
        assertEquals(scrollBefore, stableView.verticalScrollYForTest)
        assertTrue(stableView.hasVisibleRenderedChunks)
        val feedbackMs = requireNotNull(stableView.lastPitchFeedbackMillis)
        println(
            "EDITOR_ACCEPTANCE feedbackMs=$feedbackMs replacedChunks=${changed.size} " +
                "retainedChunks=${stableView.retainedChunkCount} scrollY=$scrollBefore"
        )
        assertTrue(feedbackMs < 1_000L)
        compose.onNodeWithTag("editor_loading").assertDoesNotExist()
        compose.waitUntil { stableView.selectionPointerRendered }
        compose.runOnUiThread { assertTrue(stableView.performFirstNoteTapForTest()) }
        assertEquals(selected.note.identity, (selection.value as EditorSelection.NoteSelection).note.identity)
    }

    @Test
    fun longScoreRetainsChunksAfterRoundTripScrollAndOmitsRendererAnnotation() {
        val measures = (1..80).joinToString("\n") { number ->
            """
            <measure number="$number">
              ${if (number == 1) "<attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>" else ""}
              <note><rest/><duration>4</duration><type>whole</type></note>
            </measure>
            """.trimIndent()
        }
        val state = readyState.copy(
            musicXml = """
                <score-partwise version="4.0">
                  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
                  <part id="P1">$measures</part>
                </score-partwise>
            """.trimIndent(),
            document = readyState.document.copy(
                systems = List(20) { index -> readyState.document.systems[0].copy(index = index) }
            )
        )

        compose.activity.setContent { MaterialTheme { EditorScreenContent(state) } }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("sheet_music_render_loading").fetchSemanticsNodes().isEmpty()
        }
        val stableView = compose.activity.window.decorView.findStableAlphaTabView()
        assertTrue("Expected multiple retained score chunks", stableView.retainedChunkCount > 1)
        assertFalse("alphaTab renderer annotation must be removed", stableView.rendererAnnotationPresent)
        val retainedBeforeScroll = stableView.retainedChunkCount

        repeat(4) { compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeUp() } }
        repeat(4) { compose.onNodeWithTag("alphatab_score_view").performTouchInput { swipeDown() } }
        compose.waitForIdle()

        assertEquals(retainedBeforeScroll, stableView.retainedChunkCount)
        assertFalse(stableView.rendererAnnotationPresent)
    }

    @Test
    fun offlinePlayerBecomesReadyAndSupportsPlayPauseResumeStopAndCompletion() {
        val musicXml = InstrumentationRegistry.getInstrumentation().context.assets
            .open("editor_playback_fixture.musicxml")
            .bufferedReader()
            .use { it.readText() }
        val state = readyState.copy(
            sourceKey = EditorSourceKey(1L, "/scores/playback.musicxml", musicXml.length.toLong(), 2L),
            musicXml = musicXml
        )

        compose.activity.setContent { MaterialTheme { EditorScreenContent(state) } }
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                compose.onNodeWithTag("editor_play_pause").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        compose.onNodeWithTag("editor_play_pause").performClick()
        waitForPrimaryDescription("Pause playback")
        compose.onNodeWithTag("editor_play_pause").performClick()
        waitForPrimaryDescription("Resume playback")
        compose.onNodeWithTag("editor_play_pause").performClick()
        waitForPrimaryDescription("Pause playback")

        compose.onNodeWithTag("editor_stop").assertIsEnabled().performClick()
        waitForPrimaryDescription("Play score")
        compose.onNodeWithTag("editor_stop").assertIsNotEnabled()

        compose.onNodeWithTag("editor_play_pause").performClick()
        waitForPrimaryDescription("Pause playback")
        waitForPrimaryDescription("Play score", timeoutMillis = 30_000)
        compose.onNodeWithTag("editor_stop").assertIsNotEnabled()
        compose.onNodeWithTag("alphatab_score_view").assertIsDisplayed()
    }

    private fun waitForPrimaryDescription(description: String, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                compose.onNodeWithTag("editor_play_pause")
                    .assertContentDescriptionEquals(description)
                true
            }.getOrDefault(false)
        }
    }

    private fun verifyTag(state: EditorUiState, tag: String) {
        compose.activity.setContent { MaterialTheme { EditorScreenContent(state) } }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private val readyState = EditorUiState.Ready(
        scoreId = 1L,
        title = "Ready",
        currentMusicXmlPath = "/scores/current.musicxml",
        sourceKey = EditorSourceKey(1L, "/scores/current.musicxml", 256L, 1L),
        renderSessionKey = "editor-screen-test",
        musicXml = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1"><note><rest/><duration>1</duration><type>quarter</type></note></measure></part>
            </score-partwise>
        """.trimIndent(),
        document = NotationDocument(
            systems = listOf(
                NotationSystem(
                    index = 0,
                    staffCount = 1,
                    startsNewPage = false,
                    measures = listOf(
                        NotationMeasure(
                            number = "1",
                            startsNewSystem = false,
                            startsNewPage = false,
                            staffs = listOf(
                                NotationStaff(
                                    number = 1,
                                    clef = NotationClef.TREBLE,
                                    keyFifths = 0,
                                    timeSignature = null,
                                    events = listOf(
                                        NotationChord(
                                            pitches = listOf(NotationPitch('C', 0, 4, null)),
                                            durationType = NotationDurationType.QUARTER,
                                            dots = 0,
                                            voice = 1,
                                            stem = NotationStem.UP
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            statistics = NotationStatistics(1, 1, 1, 1, 0),
            unsupportedElements = emptyMap()
        ),
        initialSystemIndex = 0,
        initialZoom = 1f,
        warningSummary = null,
        identityIndex = MusicXmlIdentityBuilder.build(
            1L,
            MusicXmlParser.parseBytes(
                """
                <score-partwise version="4.0">
                  <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
                  <part id="P1"><measure number="1"><note><rest/><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note></measure></part>
                </score-partwise>
                """.trimIndent().toByteArray()
            )
        )
    )

    private val selectionMusicXml = """
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
          <part id="P1"><measure number="1">
            <attributes><divisions>1</divisions><clef><sign>G</sign><line>2</line></clef></attributes>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
            <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
          </measure></part>
        </score-partwise>
    """.trimIndent()

    private val selectionReadyState = readyState.copy(
        sourceKey = EditorSourceKey(1L, "/scores/selection.musicxml", selectionMusicXml.length.toLong(), 2L),
        currentMusicXmlPath = "/scores/selection.musicxml",
        musicXml = selectionMusicXml,
        identityIndex = MusicXmlIdentityBuilder.build(
            1L,
            MusicXmlParser.parseBytes(selectionMusicXml.toByteArray())
        )
    )
}

private fun View.findStableAlphaTabView(): StableAlphaTabView {
    if (this is StableAlphaTabView) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            runCatching { return getChildAt(index).findStableAlphaTabView() }
        }
    }
    error("StableAlphaTabView was not found")
}
