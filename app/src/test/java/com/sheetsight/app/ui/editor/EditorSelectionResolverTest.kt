@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapper
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSelectionResolverTest {
    @Test
    fun `rendered chord tones resolve to distinct exact NoteIdentity values and one ChordIdentity`() {
        val fixture = fixture(SCORE_A, XML)
        val beat = fixture.score.tracks[0].staves[0].bars[0].voices[0].beats[0]

        val first = fixture.resolve(AlphaTabSelectionHit.NoteHit(beat.notes[0])).selection
            as EditorSelection.NoteSelection
        val second = fixture.resolve(AlphaTabSelectionHit.NoteHit(beat.notes[1])).selection
            as EditorSelection.NoteSelection

        assertTrue(first.note.identity != second.note.identity)
        assertEquals(first.chordIdentity, second.chordIdentity)
        assertEquals(beat.notes[0].realValue.toInt(), first.note.pitchMidi)
        assertEquals(beat.notes[1].realValue.toInt(), second.note.pitchMidi)
    }

    @Test
    fun `beat hit outside a note head resolves the exact stable chord`() {
        val fixture = fixture(SCORE_A, XML)
        val beat = fixture.score.tracks[0].staves[0].bars[0].voices[0].beats[0]

        val selection = fixture.resolve(AlphaTabSelectionHit.ChordHit(beat)).selection
            as EditorSelection.ChordSelection

        assertEquals(fixture.index.chords.first().identity, selection.chord.identity)
    }

    @Test
    fun `reload and rerender mapping preserve stable selection identity`() {
        val first = fixture(SCORE_A, XML)
        val second = fixture(SCORE_A, XML)
        val firstNote = first.score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[1]
        val secondNote = second.score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[1]

        val firstSelection = first.resolve(AlphaTabSelectionHit.NoteHit(firstNote)).selection
        val secondSelection = second.resolve(AlphaTabSelectionHit.NoteHit(secondNote)).selection

        assertEquals(firstSelection, secondSelection)
    }

    @Test
    fun `missing mapping and objects from another score produce no selection`() {
        val scoreA = fixture(SCORE_A, XML)
        val scoreB = fixture(SCORE_B, XML)
        val foreignNote = scoreA.score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]

        val result = EditorSelectionResolver.resolve(
            sourceKey = scoreB.sourceKey,
            identityIndex = scoreB.index,
            mapping = scoreB.mapping,
            hit = AlphaTabSelectionHit.NoteHit(foreignNote)
        )

        assertNull(result.selection)
        assertTrue(result.diagnostic?.contains("no unique stable NoteIdentity") == true)
    }

    @Test
    fun `unison chord note mapping is rejected without guessing while chord remains resolvable`() {
        val fixture = fixture(SCORE_A, UNISON_XML)
        val beat = fixture.score.tracks[0].staves[0].bars[0].voices[0].beats[0]

        val noteResult = fixture.resolve(AlphaTabSelectionHit.NoteHit(beat.notes[0]))
        val chordResult = fixture.resolve(AlphaTabSelectionHit.ChordHit(beat))

        assertNull(noteResult.selection)
        assertTrue(noteResult.diagnostic != null)
        assertTrue(chordResult.selection is EditorSelection.ChordSelection)
    }

    @Test
    fun `late chord pitch mismatch rejects every tone instead of keeping a partial mapping`() {
        val index = MusicXmlIdentityBuilder.build(SCORE_A, MusicXmlParser.parseBytes(XML.toByteArray()))
        val changedRendererXml = XML.replace("<step>E</step>", "<step>F</step>")
        val score = ScoreLoader.loadScoreFromBytes(
            Uint8Array(changedRendererXml.toByteArray().asUByteArray()),
            Settings()
        )
        val mapping = AlphaTabIdentityMapper.map(index, score)
        val notes = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes

        assertTrue(mapping.issues.isNotEmpty())
        assertNull(mapping.noteIdentity(notes[0]))
        assertNull(mapping.noteIdentity(notes[1]))
    }

    @Test
    fun `rest identity resolves to stable semantic selection`() {
        val fixture = fixture(SCORE_A, XML)
        val restBeat = fixture.score.tracks[0].staves[0].bars[0].voices[0].beats[1]

        assertEquals(fixture.index.rests.single().identity, fixture.mapping.restIdentity(restBeat))
        val selection = fixture.resolve(AlphaTabSelectionHit.RestHit(restBeat)).selection
            as EditorSelection.RestSelection
        assertEquals(fixture.index.rests.single(), selection.rest)
    }

    @Test
    fun `clef measure and both structural barlines resolve without runtime ids`() {
        val fixture = fixture(SCORE_A, XML)
        val bar = fixture.score.tracks[0].staves[0].bars[0]

        val clef = fixture.resolve(AlphaTabSelectionHit.ClefHit(bar)).selection
            as EditorSelection.ClefSelection
        val measure = fixture.resolve(AlphaTabSelectionHit.MeasureHit(bar)).selection
            as EditorSelection.MeasureSelection
        val left = fixture.resolve(
            AlphaTabSelectionHit.BarlineHit(bar, com.sheetsight.app.ui.editor.identity.BarlineSide.LEFT)
        ).selection as EditorSelection.BarlineSelection
        val right = fixture.resolve(
            AlphaTabSelectionHit.BarlineHit(bar, com.sheetsight.app.ui.editor.identity.BarlineSide.RIGHT)
        ).selection as EditorSelection.BarlineSelection

        assertEquals(fixture.index.clefs.single(), clef.clef)
        assertEquals(fixture.index.measures.single(), measure.measure)
        assertTrue(left.barline.identity != right.barline.identity)
        assertTrue(left.barline.identity.value.contains("implicit/left"))
        assertTrue(right.barline.identity.value.contains("implicit/right"))
    }

    @Test
    fun `selection leaves MusicXML bytes unchanged`() {
        val bytes = XML.toByteArray()
        val before = bytes.copyOf()
        val fixture = fixture(SCORE_A, bytes.decodeToString())
        val note = fixture.score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]

        fixture.resolve(AlphaTabSelectionHit.NoteHit(note))
        fixture.resolve(AlphaTabSelectionHit.Empty)

        assertTrue(before.contentEquals(bytes))
    }

    @Test
    fun `surface pixel conversion uses Android density only`() {
        assertEquals(160.0, EditorHitTestCoordinates.toRenderer(320f, 2.0), 0.0)
        assertEquals(80.0, EditorHitTestCoordinates.toRenderer(240f, 3.0), 0.0)
    }

    private fun fixture(scoreId: Long, xml: String): Fixture {
        val index = MusicXmlIdentityBuilder.build(scoreId, MusicXmlParser.parseBytes(xml.toByteArray()))
        val score = ScoreLoader.loadScoreFromBytes(
            Uint8Array(xml.toByteArray().asUByteArray()),
            Settings()
        )
        val mapping = AlphaTabIdentityMapper.map(index, score)
        return Fixture(
            index = index,
            score = score,
            mapping = mapping,
            sourceKey = EditorSourceKey(scoreId, "/files/scores/$scoreId.musicxml", xml.length.toLong(), 1L)
        )
    }

    private data class Fixture(
        val index: com.sheetsight.app.ui.editor.identity.EditableScoreIdentityIndex,
        val score: alphaTab.model.Score,
        val mapping: com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapping,
        val sourceKey: EditorSourceKey
    ) {
        fun resolve(hit: AlphaTabSelectionHit) = EditorSelectionResolver.resolve(
            sourceKey = sourceKey,
            identityIndex = index,
            mapping = mapping,
            hit = hit
        )
    }

    private companion object {
        const val SCORE_A = 41L
        const val SCORE_B = 42L
        val XML = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions><clef><sign>G</sign><line>2</line></clef></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                <note><rest/><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        val UNISON_XML = XML.replace("<step>E</step>", "<step>C</step>")
    }
}
