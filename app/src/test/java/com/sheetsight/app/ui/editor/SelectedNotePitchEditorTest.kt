package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SelectedNotePitchEditorTest {
    @Test
    fun editsOnlySelectedChordToneAndPreservesItsNonPitchSemantics() {
        val source = fixture.toByteArray()
        val before = MusicXmlIdentityBuilder.build(SCORE_ID, MusicXmlParser.parseBytes(source))
        val selected = before.chords.single().notes[1]

        val result = SelectedNotePitchEditor.edit(SCORE_ID, source, selected.identity, NaturalNoteDirection.UP)

        val document = MusicXmlParser.parseBytes(result.musicXmlBytes)
        val notes = document.documentElement.directChildren("part").single()
            .directChildren("measure").single().directChildren("note")
        assertEquals("C", notes[0].pitchChild("step"))
        assertEquals("F", notes[1].pitchChild("step"))
        assertEquals("4", notes[1].pitchChild("octave"))
        assertEquals("2", notes[1].directChildren("duration").single().textContent)
        assertEquals("2", notes[1].directChildren("voice").single().textContent)
        assertEquals("1", notes[1].directChildren("staff").single().textContent)
        assertNotNull(notes[1].directChildren("chord").singleOrNull())
        assertNotNull(notes[1].directChildren("tie").singleOrNull())
        assertNotNull(notes[1].directChildren("notations").singleOrNull()
            ?.directChildren("articulations")?.singleOrNull()?.directChildren("staccato")?.singleOrNull())
        assertEquals("keep me", document.documentElement.directChildren("credit").single().textContent.trim())
        assertEquals(selected.identity, result.noteIdentity)
        assertEquals(65, result.pitchMidi)
        val after = MusicXmlIdentityBuilder.build(SCORE_ID, document)
        assertEquals(selected.identity, after.notes.single { it.pitchMidi == 65 }.identity)
    }

    @Test
    fun adjacentNaturalMovementCoversWholeAndHalfStepsAndOctaveBoundaries() {
        assertNatural("C", 4, NaturalNoteDirection.UP, "D", 4)
        assertNatural("D", 4, NaturalNoteDirection.DOWN, "C", 4)
        assertNatural("E", 4, NaturalNoteDirection.UP, "F", 4)
        assertNatural("F", 4, NaturalNoteDirection.DOWN, "E", 4)
        assertNatural("B", 4, NaturalNoteDirection.UP, "C", 5)
        assertNatural("C", 5, NaturalNoteDirection.DOWN, "B", 4)
    }

    @Test
    fun oneDragAppliesItsFullDiatonicOffsetAcrossOctaves() {
        val bytes = singleNote("B", 4).toByteArray()
        val identity = MusicXmlIdentityBuilder.build(SCORE_ID, MusicXmlParser.parseBytes(bytes)).notes.single().identity

        val up = SelectedNotePitchEditor.edit(SCORE_ID, bytes, identity, diatonicOffset = 9)
        val note = MusicXmlParser.parseBytes(up.musicXmlBytes).getElementsByTagName("note").item(0) as Element

        assertEquals("D", note.pitchChild("step"))
        assertEquals("6", note.pitchChild("octave"))
        assertEquals(86, up.pitchMidi)
    }

    @Test
    fun alteredAndExplicitNaturalSourcesBecomeAdjacentNaturalNotesWithoutAccidentalElements() {
        val sharp = singleNote("C", 4, "<alter>1</alter>", "<accidental>sharp</accidental>")
        val flat = singleNote("E", 4, "<alter>-1</alter>", "<accidental>flat</accidental>")
        val doubleSharp = singleNote("C", 4, "<alter>2</alter>", "<accidental>double-sharp</accidental>")
        val doubleFlat = singleNote("E", 4, "<alter>-2</alter>", "<accidental>flat-flat</accidental>")
        val explicitNatural = singleNote("F", 4, "<alter>0</alter>", "<accidental>natural</accidental>")
        assertNaturalXml(sharp, NaturalNoteDirection.UP, "D", 4)
        assertNaturalXml(flat, NaturalNoteDirection.DOWN, "D", 4)
        assertNaturalXml(doubleSharp, NaturalNoteDirection.UP, "D", 4)
        assertNaturalXml(doubleFlat, NaturalNoteDirection.DOWN, "D", 4)
        assertNaturalXml(explicitNatural, NaturalNoteDirection.DOWN, "E", 4)
    }

    @Test
    fun missingIdentityFailsWithoutOutput() {
        val source = singleNote("C", 4).toByteArray()
        val failure = runCatching {
            SelectedNotePitchEditor.edit(
                SCORE_ID,
                source,
                com.sheetsight.app.ui.editor.identity.NoteIdentity("missing"),
                NaturalNoteDirection.UP
            )
        }
        assertTrue(failure.isFailure)
    }

    private fun assertNatural(
        sourceStep: String,
        sourceOctave: Int,
        direction: NaturalNoteDirection,
        expectedStep: String,
        expectedOctave: Int
    ) = assertNaturalXml(singleNote(sourceStep, sourceOctave), direction, expectedStep, expectedOctave)

    private fun assertNaturalXml(
        xml: String,
        direction: NaturalNoteDirection,
        expectedStep: String,
        expectedOctave: Int
    ) {
        val bytes = xml.toByteArray()
        val identity = MusicXmlIdentityBuilder.build(SCORE_ID, MusicXmlParser.parseBytes(bytes)).notes.single().identity
        val edit = SelectedNotePitchEditor.edit(SCORE_ID, bytes, identity, direction)
        val note = MusicXmlParser.parseBytes(edit.musicXmlBytes).getElementsByTagName("note").item(0) as Element
        val pitch = note.directChildren("pitch").single()
        assertEquals(expectedStep, pitch.directChildren("step").single().textContent)
        assertEquals(expectedOctave.toString(), pitch.directChildren("octave").single().textContent)
        assertTrue(pitch.directChildren("alter").isEmpty())
        assertTrue(note.directChildren("accidental").isEmpty())
    }

    private fun singleNote(
        step: String,
        octave: Int,
        alter: String = "",
        accidental: String = ""
    ) = """
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
          <part id="P1"><measure number="1"><note><pitch><step>$step</step>$alter<octave>$octave</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff>$accidental</note></measure></part>
        </score-partwise>
    """.trimIndent()

    private fun Element.pitchChild(tag: String): String =
        directChildren("pitch").single().directChildren(tag).single().textContent

    private fun Element.directChildren(tag: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName == tag }

    private companion object {
        const val SCORE_ID = 7L
        val fixture = """
            <score-partwise version="4.0">
              <credit><credit-words>keep me</credit-words></credit>
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <note id="lower"><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>2</voice><type>half</type><staff>1</staff></note>
                <note id="upper"><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><tie type="start"/><voice>2</voice><type>half</type><staff>1</staff><notations><articulations><staccato/></articulations><tied type="start"/></notations></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
    }
}
