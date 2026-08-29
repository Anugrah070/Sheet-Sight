@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor.identity

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreElementIdentityTest {
    @Test
    fun `same MusicXML loaded twice has equivalent stable note chord rest and measure identities`() {
        val first = identities(scoreId = 7L, XML)
        val second = identities(scoreId = 7L, XML)

        assertEquals(first, second)
        assertEquals(2, first.measures.size)
        assertEquals(3, first.notes.size)
        assertEquals(2, first.chords.size)
        assertEquals(1, first.rests.size)
        assertEquals(1, first.clefs.size)
        assertEquals(4, first.barlines.size)
        assertEquals(2, first.chords.first().notes.size)
        assertEquals(2, first.chords.first().notes.map { it.identity }.distinct().size)
        assertNotEquals(first.chords.first().identity.value, first.notes.first().identity.value)
    }

    @Test
    fun `score namespace prevents identities from crossing scores`() {
        val scoreA = identities(scoreId = 1L, XML)
        val scoreB = identities(scoreId = 2L, XML)

        assertTrue(scoreA.notes.map { it.identity }.toSet().intersect(scoreB.notes.map { it.identity }.toSet()).isEmpty())
        assertTrue(scoreA.measures.map { it.identity }.toSet().intersect(scoreB.measures.map { it.identity }.toSet()).isEmpty())
    }

    @Test(expected = AmbiguousMusicXmlIdentityException::class)
    fun `duplicate explicit note ids are rejected`() {
        identities(
            3L,
            XML.replace("<note>", "<note id=\"duplicate\">")
                .replace("<note><chord/>", "<note id=\"duplicate\"><chord/>")
        )
    }

    @Test
    fun `alphaTab model objects reverse map to stable identities across reloads`() {
        val source = identities(11L, XML)
        val firstScore = alphaScore(XML)
        val secondScore = alphaScore(XML)
        val first = AlphaTabIdentityMapper.map(source, firstScore)
        val second = AlphaTabIdentityMapper.map(source, secondScore)

        assertTrue(first.issues.toString(), first.isComplete)
        assertTrue(second.issues.toString(), second.isComplete)
        assertEquals(first.noteRefs.keys, second.noteRefs.keys)
        assertEquals(first.chordRefs.keys, second.chordRefs.keys)
        assertEquals(first.restRefs.keys, second.restRefs.keys)
        assertEquals(first.measureRefs.keys, second.measureRefs.keys)
        assertEquals(first.clefRefs.keys, second.clefRefs.keys)
        assertEquals(first.barlineRefs.keys, second.barlineRefs.keys)

        val note = firstScore.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        val beat = note.beat
        assertEquals(source.notes.first { it.pitchMidi == note.realValue.toInt() }.identity, first.noteIdentity(note))
        assertEquals(source.chords.first().identity, first.chordIdentity(beat))
        assertEquals(source.measures.first().identity, first.measureIdentity(beat.voice.bar))
    }

    @Test
    fun `missing renderer mapping fails safely`() {
        val source = identities(12L, XML)
        val mapping = AlphaTabIdentityMapper.map(source, alphaScore(EMPTY_XML))

        assertFalse(mapping.isComplete)
        assertTrue(mapping.issues.isNotEmpty())
        val unrelatedNote = alphaScore(XML).tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        assertNull(mapping.noteIdentity(unrelatedNote))
    }

    @Test
    fun `generated beats do not shift mappings across measures voices staffs parts ties and note types`() {
        val source = identities(13L, COMPLEX_XML)
        val score = alphaScore(COMPLEX_XML)
        val mapping = AlphaTabIdentityMapper.map(source, score)

        assertTrue(mapping.issues.joinToString("\n"), mapping.isComplete)
        assertEquals(source.notes.map { it.identity }.toSet(), mapping.noteRefs.keys)
        assertEquals(source.chords.map { it.identity }.toSet(), mapping.chordRefs.keys)
        assertEquals(source.rests.map { it.identity }.toSet(), mapping.restRefs.keys)
        assertEquals(source.clefs.map { it.identity }.toSet(), mapping.clefRefs.keys)
        assertEquals(source.barlines.map { it.identity }.toSet(), mapping.barlineRefs.keys)
        assertEquals(2, score.tracks.length.toInt())
        assertEquals(2, score.tracks[0].staves.length.toInt())
    }

    @Test
    fun `generated renderer rests may appear between source events without shifting note identity`() {
        val sourceXml = XML.replace(
            "<note><rest/><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>",
            ""
        )
        val source = identities(14L, sourceXml)
        val mapping = AlphaTabIdentityMapper.map(source, alphaScore(XML))

        assertTrue(mapping.issues.joinToString("\n"), mapping.isComplete)
        assertEquals(source.notes.map { it.identity }.toSet(), mapping.noteRefs.keys)
    }

    @Test
    fun `runtime ids are diagnostic metadata and never decide stable note identity`() {
        val source = identities(15L, XML)
        val score = alphaScore(XML)
        val first = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        val second = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[1]
        second.id = first.id

        val mapping = AlphaTabIdentityMapper.map(source, score)

        assertTrue(mapping.issues.joinToString("\n"), mapping.isComplete)
        assertEquals(source.notes.map { it.identity }.toSet(), mapping.noteRefs.keys)
    }

    private fun identities(scoreId: Long, xml: String) =
        MusicXmlIdentityBuilder.build(scoreId, MusicXmlParser.parseBytes(xml.toByteArray()))

    private fun alphaScore(xml: String) = ScoreLoader.loadScoreFromBytes(
        Uint8Array(xml.toByteArray().asUByteArray()),
        Settings()
    )

    private companion object {
        val XML = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                  <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                  <note><rest/><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val EMPTY_XML = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
              <part id="P1"><measure number="1"><note><rest/><duration>4</duration><voice>1</voice><type>whole</type><staff>1</staff></note></measure></part>
            </score-partwise>
        """.trimIndent()

        val COMPLEX_XML = """
            <score-partwise version="4.0">
              <part-list>
                <score-part id="P1"><part-name>Piano</part-name></score-part>
                <score-part id="P2"><part-name>Violin</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions><staves>2</staves>
                    <clef number="1"><sign>G</sign><line>2</line></clef>
                    <clef number="2"><sign>F</sign><line>4</line></clef>
                  </attributes>
                  <forward><duration>1</duration><voice>1</voice><staff>1</staff></forward>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>eighth</type><staff>1</staff></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                  <backup><duration>4</duration></backup>
                  <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice><type>half</type><staff>1</staff></note>
                  <backup><duration>4</duration></backup>
                  <note><pitch><step>G</step><octave>3</octave></pitch><duration>4</duration><tie type="start"/><voice>1</voice><type>half</type><staff>2</staff><notations><tied type="start"/></notations></note>
                  <note><chord/><pitch><step>B</step><octave>3</octave></pitch><duration>4</duration><voice>1</voice><type>half</type><staff>2</staff></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>G</step><octave>3</octave></pitch><duration>2</duration><tie type="stop"/><voice>1</voice><type>quarter</type><staff>2</staff><notations><tied type="stop"/></notations></note>
                  <backup><duration>2</duration></backup>
                  <note><rest/><duration>1</duration><voice>1</voice><type>eighth</type><staff>1</staff></note>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>eighth</type><staff>1</staff></note>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                </measure>
              </part>
              <part id="P2">
                <measure number="1">
                  <attributes><divisions>1</divisions><clef><sign>G</sign><line>2</line></clef></attributes>
                  <note><pitch><step>A</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                </measure>
                <measure number="2">
                  <note><grace slash="yes"/><pitch><step>B</step><octave>4</octave></pitch><voice>1</voice><type>eighth</type><staff>1</staff></note>
                  <note><pitch><step>C</step><octave>5</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()
    }
}
