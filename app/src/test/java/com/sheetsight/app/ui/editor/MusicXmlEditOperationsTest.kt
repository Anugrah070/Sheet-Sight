package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MusicXmlEditOperationsTest {
    @Test
    fun `inserting a note splits only the target rest and keeps rhythmic duration`() {
        val bytes = score("<note id=\"r\"><rest/><duration>16</duration><voice>1</voice><type>whole</type><staff>1</staff></note>")
        val rest = index(bytes).rests.single()

        val edit = InsertNote.apply(
            SCORE_ID,
            bytes,
            NoteInsertionAnchor(rest.identity, offsetDivisions = 4),
            EditorNoteDuration.QUARTER,
            "D",
            4
        )

        val notes = notes(edit.musicXmlBytes)
        assertEquals(listOf("4", "4", "8"), notes.map { it.childText("duration") })
        assertTrue(notes[0].directChild("rest") != null)
        assertEquals("D", notes[1].directChild("pitch")?.childText("step"))
        assertTrue(notes[2].directChild("rest") != null)
        assertEquals(16, edit.identityIndex.measures.single().events.sumOf { it.durationDivisions })
        val selected = edit.preferredSelection as PreferredEditSelection.Note
        assertNotNull(edit.identityIndex.notes.singleOrNull { it.identity == selected.identity })
    }

    @Test
    fun `deleting the final chord tone creates an equivalent rest`() {
        val bytes = score(pitched("solo", "C", 4, 8, "half"))
        val note = index(bytes).notes.single()

        val edit = DeleteNote.apply(SCORE_ID, bytes, note.identity)

        val replacement = notes(edit.musicXmlBytes).single()
        assertNotNull(replacement.directChild("rest"))
        assertEquals("8", replacement.childText("duration"))
        assertEquals("half", replacement.childText("type"))
        assertTrue(edit.identityIndex.notes.isEmpty())
        assertEquals(8, edit.identityIndex.rests.single().durationDivisions)
    }

    @Test
    fun `deleting one chord tone preserves the other pitch and duration`() {
        val events = pitched("root", "C", 4, 8, "half") +
            pitched("upper", "E", 4, 8, "half", chord = true)
        val bytes = score(events)
        val before = index(bytes)
        val upper = before.notes.single { it.pitchStep == "E" }

        val edit = DeleteNote.apply(SCORE_ID, bytes, upper.identity)

        assertEquals(1, edit.identityIndex.notes.size)
        assertEquals("C", edit.identityIndex.notes.single().pitchStep)
        assertEquals(8, edit.identityIndex.chords.single().durationDivisions)
        assertFalse(notes(edit.musicXmlBytes).single().directChild("chord") != null)
    }

    @Test
    fun `deleting an unlabelled chord root gives the promoted tone a resolvable stable identity`() {
        val bytes = score(
            """
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>8</duration><voice>1</voice><type>half</type><staff>1</staff></note>
            <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>8</duration><voice>1</voice><type>half</type><staff>1</staff></note>
            """.trimIndent()
        )
        val root = index(bytes).notes.single { it.pitchStep == "C" }

        val edit = DeleteNote.apply(SCORE_ID, bytes, root.identity)

        val selected = edit.preferredSelection as PreferredEditSelection.Note
        assertEquals("E", edit.identityIndex.notes.single { it.identity == selected.identity }.pitchStep)
        assertTrue(notes(edit.musicXmlBytes).single().getAttribute("id").startsWith("sheetsight-note-"))
    }

    @Test
    fun `changing and inserting clefs never changes pitch or duration data`() {
        val bytes = twoMeasureScore()
        val before = index(bytes)
        val initial = before.clefs.single()
        val originalPitches = before.notes.map { it.pitchMidi }
        val originalDurations = before.chords.map { it.durationDivisions }

        val replaced = ReplaceClef.apply(SCORE_ID, bytes, initial.identity, EditorClef.BASS)
        assertEquals(originalPitches, replaced.identityIndex.notes.map { it.pitchMidi })
        assertEquals(originalDurations, replaced.identityIndex.chords.map { it.durationDivisions })
        assertEquals("F", replaced.identityIndex.clefs.single().sign)
        assertEquals(4, replaced.identityIndex.clefs.single().line)

        val measureTwo = replaced.identityIndex.measures.single { it.source.measureIndex == 1 }
        val inserted = InsertClef.apply(
            SCORE_ID,
            replaced.musicXmlBytes,
            measureTwo.identity,
            EditorClef.ALTO
        )
        assertEquals(originalPitches, inserted.identityIndex.notes.map { it.pitchMidi })
        assertEquals(listOf("F", "C"), inserted.identityIndex.clefs.map { it.sign })
        assertTrue(inserted.preferredSelection is PreferredEditSelection.Clef)
    }

    @Test
    fun `common preset and custom time signatures serialize and keep stable identities`() {
        val bytes = score(pitched("n", "C", 4, 16, "whole"))
        val initial = index(bytes).timeSignatures.single()

        val common = ReplaceTimeSignature.apply(SCORE_ID, bytes, initial.identity, EditorTimeSignature.COMMON)
        val commonTime = times(common.musicXmlBytes).single()
        assertEquals("common", commonTime.getAttribute("symbol"))
        assertEquals("4", commonTime.childText("beats"))
        assertEquals(initial.identity, common.identityIndex.timeSignatures.single().identity)

        val customBytes = score(
            pitched("n1", "C", 4, 7, null),
            time = "<time id=\"initial-time\"><beats>7</beats><beat-type>16</beat-type></time>"
        )
        val customInitial = index(customBytes).timeSignatures.single()
        val custom = ReplaceTimeSignature.apply(
            SCORE_ID,
            customBytes,
            customInitial.identity,
            EditorTimeSignature(7, 16)
        )
        assertEquals("7", times(custom.musicXmlBytes).single().childText("beats"))
        assertEquals("16", times(custom.musicXmlBytes).single().childText("beat-type"))
    }

    @Test
    fun `time signature that cannot contain existing measure is rejected without source mutation`() {
        val bytes = score(pitched("n", "C", 4, 16, "whole"))
        val beforeCopy = bytes.copyOf()
        val initial = index(bytes).timeSignatures.single()

        val failure = runCatching {
            ReplaceTimeSignature.apply(SCORE_ID, bytes, initial.identity, EditorTimeSignature(3, 4))
        }

        assertTrue(failure.exceptionOrNull() is MusicXmlEditException)
        assertArrayEquals(beforeCopy, bytes)
    }

    @Test
    fun `initial clef cannot be deleted but later change can`() {
        val inserted = InsertClef.apply(
            SCORE_ID,
            twoMeasureScore(),
            index(twoMeasureScore()).measures.last().identity,
            EditorClef.TENOR
        )
        val clefs = inserted.identityIndex.clefs
        assertTrue(runCatching {
            DeleteClefChange.apply(SCORE_ID, inserted.musicXmlBytes, clefs.first().identity)
        }.isFailure)

        val deleted = DeleteClefChange.apply(SCORE_ID, inserted.musicXmlBytes, clefs.last().identity)
        assertEquals(1, deleted.identityIndex.clefs.size)
        assertEquals("G", deleted.identityIndex.clefs.single().sign)
    }

    private fun index(bytes: ByteArray) = MusicXmlIdentityBuilder.build(SCORE_ID, MusicXmlParser.parseBytes(bytes))

    private fun notes(bytes: ByteArray): List<Element> = MusicXmlParser.parseBytes(bytes)
        .getElementsByTagName("note").elements()

    private fun times(bytes: ByteArray): List<Element> = MusicXmlParser.parseBytes(bytes)
        .getElementsByTagName("time").elements()

    private fun score(
        events: String,
        time: String = "<time id=\"initial-time\"><beats>4</beats><beat-type>4</beat-type></time>"
    ): ByteArray = """
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
          <part id="P1"><measure number="1">
            <attributes><divisions>4</divisions>$time<clef id="initial-clef"><sign>G</sign><line>2</line></clef></attributes>
            $events
          </measure></part>
        </score-partwise>
    """.trimIndent().toByteArray()

    private fun twoMeasureScore(): ByteArray = """
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
          <part id="P1">
            <measure number="1"><attributes><divisions>4</divisions><time id="t"><beats>4</beats><beat-type>4</beat-type></time><clef id="c"><sign>G</sign><line>2</line></clef></attributes>${pitched("n1", "C", 4, 16, "whole")}</measure>
            <measure number="2">${pitched("n2", "D", 4, 16, "whole")}</measure>
          </part>
        </score-partwise>
    """.trimIndent().toByteArray()

    private fun pitched(
        id: String,
        step: String,
        octave: Int,
        duration: Int,
        type: String?,
        chord: Boolean = false
    ) = """
        <note id="$id">${if (chord) "<chord/>" else ""}<pitch><step>$step</step><octave>$octave</octave></pitch><duration>$duration</duration><voice>1</voice>${type?.let { "<type>$it</type>" } ?: ""}<staff>1</staff></note>
    """.trimIndent()

    private fun Element.directChild(name: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == name }

    private fun Element.childText(name: String): String? = directChild(name)?.textContent?.trim()

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private companion object { const val SCORE_ID = 91L }
}
