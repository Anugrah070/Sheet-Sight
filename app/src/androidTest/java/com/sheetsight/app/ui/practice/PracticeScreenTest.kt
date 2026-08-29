package com.sheetsight.app.ui.practice

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sheetsight.app.MainActivity
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeProgress
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.domain.practice.PracticeSource
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.ui.editor.notation.NotationChord
import com.sheetsight.app.ui.editor.notation.NotationClef
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationDurationType
import com.sheetsight.app.ui.editor.notation.NotationMeasure
import com.sheetsight.app.ui.editor.notation.NotationPitch
import com.sheetsight.app.ui.editor.notation.NotationSourceIds
import com.sheetsight.app.ui.editor.notation.NotationStaff
import com.sheetsight.app.ui.editor.notation.NotationStatistics
import com.sheetsight.app.ui.editor.notation.NotationStem
import com.sheetsight.app.ui.editor.notation.NotationSystem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PracticeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun readyScoreShowsExpectedNoteAndStartControl() {
        var startClicked = false
        compose.activity.setContent {
            MaterialTheme { PracticeScreenContent(readyState, onStart = { startClicked = true }) }
        }
        compose.onNodeWithTag("practice_expected_note").assertIsDisplayed()
        compose.onNodeWithTag("practice_score").assertIsDisplayed()
        compose.onNodeWithTag("practice_notation_system_0").assertIsDisplayed()
        compose.onNodeWithTag("practice_highlight_overlay").assertIsDisplayed()
        compose.onNodeWithTag("practice_progress").assertIsDisplayed()
        compose.onNodeWithTag("practice_start").assertIsDisplayed().performClick()
        assertTrue(startClicked)
    }

    @Test
    fun importedMusicXmlUsesFullEngravingRenderer() {
        compose.activity.setContent {
            MaterialTheme {
                PracticeScreenContent(readyState.copy(musicXml = MUSIC_XML))
            }
        }

        compose.onNodeWithTag("practice_score").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("alphatab_score_view").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("alphatab_score_view").assertIsDisplayed()
        compose.onNodeWithTag("practice_notation_system_0").assertDoesNotExist()
    }

    @Test
    fun completedScoreShowsCompletionAndRemovesTargetOverlay() {
        compose.activity.setContent {
            MaterialTheme {
                PracticeScreenContent(
                    readyState.copy(
                        progress = readyState.progress.copy(
                            phase = PracticePhase.Completed,
                            currentStepIndex = 1
                        )
                    )
                )
            }
        }
        compose.onNodeWithTag("practice_completed").assertIsDisplayed()
        compose.onNodeWithTag("practice_highlight_overlay").assertDoesNotExist()
    }

    @Test
    fun permissionDeniedStateIsVisible() {
        compose.activity.setContent {
            MaterialTheme {
                PracticeScreenContent(readyState.copy(microphonePermission = MicrophonePermissionState.Denied))
            }
        }
        compose.onNodeWithTag("practice_permission_denied").assertIsDisplayed()
    }

    @Test
    fun countInIsShownOverTheScore() {
        compose.activity.setContent {
            MaterialTheme {
                PracticeScreenContent(
                    readyState.copy(
                        progress = readyState.progress.copy(
                            phase = PracticePhase.CountIn,
                            countInRemaining = 4
                        )
                    )
                )
            }
        }

        compose.onNodeWithTag("practice_score").assertIsDisplayed()
        compose.onNodeWithTag("practice_count_in").assertIsDisplayed()
        compose.onNodeWithTag("practice_stop").assertIsDisplayed()
    }

    private val readyState by lazy { PracticeUiState(
        progress = PracticeProgress(
            phase = PracticePhase.Ready,
            sequence = PracticeSequence(
                PracticeSource("test.musicxml", 1),
                listOf(
                    PracticeStep(
                        index = 0,
                        measureNumber = "1",
                        staffs = listOf(1),
                        expectedPitches = listOf(PracticePitch('C', 0, 4)),
                        sourceNoteIds = listOf(NotationSourceIds.note(0, "1", 1, 1, 0)),
                        onsetDivisions = 0
                    )
                )
            )
        ),
        notation = notation
    ) }

    private val notation = NotationDocument(
        systems = listOf(
            NotationSystem(
                index = 0,
                staffCount = 1,
                startsNewPage = false,
                measures = listOf(
                    NotationMeasure(
                        number = "1",
                        sourceIndex = 0,
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
                                        stem = NotationStem.UP,
                                        onsetDivisions = 0,
                                        sourceOrder = 1
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
    )

    private companion object {
        val MUSIC_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 3.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
            <score-partwise>
              <part-list><score-part id="P1"><part-name /></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
    }
}
