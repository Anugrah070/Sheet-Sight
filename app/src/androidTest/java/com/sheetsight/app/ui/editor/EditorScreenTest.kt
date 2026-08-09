package com.sheetsight.app.ui.editor

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sheetsight.app.MainActivity
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
import org.junit.Rule
import org.junit.Test

class EditorScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loadingStateIsVisible() = verifyTag(EditorUiState.Loading, "editor_loading")
    @Test fun noMusicXmlStateIsVisible() = verifyTag(EditorUiState.NoMusicXml("Title"), "editor_no_musicxml")
    @Test fun parseErrorStateIsVisible() = verifyTag(EditorUiState.ParseError("Title", "debug"), "editor_parse_error")

    @Test
    fun readyStateDisplaysNotationAndZoomControlRemainsUsable() {
        compose.activity.setContent { MaterialTheme { EditorScreenContent(readyState) } }
        compose.onNodeWithTag("editor_ready").assertIsDisplayed()
        compose.onNodeWithTag("notation_system_0").assertIsDisplayed()
        compose.onNodeWithTag("editor_reset_zoom").assertIsDisplayed().performClick()
    }

    private fun verifyTag(state: EditorUiState, tag: String) {
        compose.activity.setContent { MaterialTheme { EditorScreenContent(state) } }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private val readyState = EditorUiState.Ready(
        scoreId = 1L,
        title = "Ready",
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
        warningSummary = null
    )
}
