@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.ProgramChangeEvent
import alphaTab.model.Score
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPlaybackMusicXmlTest {
    @Test
    fun playbackFixtureImportsTempoChordRestsAndMultipleDurations() {
        val xml = InstrumentationRegistry.getInstrumentation().context.assets
            .open("editor_playback_fixture.musicxml")
            .bufferedReader()
            .use { it.readText() }

        val score = load(xml)
        val beats = score.tracks[0].staves
            .flatMap { staff -> staff.bars }
            .flatMap { bar -> bar.voices }
            .flatMap { voice -> voice.beats }

        assertEquals(84.0, score.tempo, 0.01)
        assertTrue(beats.any { it.notes.length > 1 })
        assertTrue(beats.any { it.isRest })
        assertTrue(beats.map { it.playbackDuration }.distinct().size >= 3)
    }

    @Test
    fun scoreWithoutTempoUsesAlphaTabDefaultOf120Bpm() {
        val score = load(
            """
                <score-partwise version="4.0">
                  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
                  <part id="P1"><measure number="1">
                    <attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                    <note><rest/><duration>4</duration><type>whole</type></note>
                  </measure></part>
                </score-partwise>
            """.trimIndent()
        )

        assertEquals(120.0, score.tempo, 0.01)
    }

    @Test
    fun editorPlaybackGeneratesGrandPianoProgramForPrimaryAndSecondaryChannels() {
        val xml = InstrumentationRegistry.getInstrumentation().context.assets
            .open("editor_playback_fixture.musicxml")
            .bufferedReader()
            .use { it.readText() }
        val score = load(xml)
        val track = score.tracks[0]
        track.playbackInfo.program = 56.0
        track.playbackInfo.primaryChannel = 2.0
        track.playbackInfo.secondaryChannel = 3.0

        configureEditorPlaybackAsGrandPiano(score)
        val midi = MidiFile()
        MidiFileGenerator(score, Settings(), AlphaSynthMidiFileHandler(midi)).generate()
        val programChanges = midi.events.filterIsInstance<ProgramChangeEvent>()
        val expectedChannels = setOf(
            track.playbackInfo.primaryChannel,
            track.playbackInfo.secondaryChannel
        )

        assertEquals(ACOUSTIC_GRAND_PIANO_PROGRAM, track.playbackInfo.program, 0.0)
        assertEquals(2, expectedChannels.size)
        assertTrue(programChanges.isNotEmpty())
        expectedChannels.forEach { channel ->
            assertTrue(
                "Expected Acoustic Grand Piano on playback channel $channel",
                programChanges.any { it.channel == channel && it.program == ACOUSTIC_GRAND_PIANO_PROGRAM }
            )
        }
        assertTrue(programChanges.all { it.program == ACOUSTIC_GRAND_PIANO_PROGRAM })
    }

    private fun load(xml: String): Score {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        initializeAlphaTabAndroidEnvironment(context.applicationContext)
        return ScoreLoader.loadScoreFromBytes(
            Uint8Array(xml.toByteArray(Charsets.UTF_8).asUByteArray()),
            Settings()
        )
    }
}
