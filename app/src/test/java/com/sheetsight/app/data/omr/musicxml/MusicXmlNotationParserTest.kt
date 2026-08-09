package com.sheetsight.app.data.omr.musicxml

import com.sheetsight.app.ui.editor.notation.NotationAccidental
import com.sheetsight.app.ui.editor.notation.NotationChord
import com.sheetsight.app.ui.editor.notation.NotationClef
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import com.sheetsight.app.ui.editor.notation.NotationRest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlNotationParserTest {
    @Test
    fun `single measure parses notes chord rest accidentals and two staffs`() {
        val parsed = parse(twoStaffScore)
        val measure = parsed.measures.single()

        assertEquals(1, parsed.statistics.measureCount)
        assertEquals(2, parsed.statistics.staffCount)
        assertEquals(2, parsed.statistics.noteCount)
        assertEquals(1, parsed.statistics.chordCount)
        assertEquals(1, parsed.statistics.restCount)
        assertEquals(NotationClef.TREBLE, measure.staffs[0].clef)
        assertEquals(NotationClef.BASS, measure.staffs[1].clef)
        val chord = measure.staffs[0].events.single() as NotationChord
        assertEquals(2, chord.pitches.size)
        assertEquals(NotationAccidental.SHARP, chord.pitches.first().displayedAccidental)
        assertTrue(measure.staffs[1].events.single() is NotationRest)
    }

    @Test
    fun `multiple measures preserve explicit system breaks`() {
        val xml = scoreXml(
            measure("1", note("C", 4) + "<barline location=\"right\"/>") +
                measure(
                    "2",
                    note("D", 4) + "<barline location=\"middle\"/>",
                    print = "<print new-system=\"yes\"/>"
                )
        )
        val document = NotationLayoutEngine.layout(parse(xml))

        assertEquals(2, document.statistics.measureCount)
        assertEquals(2, document.statistics.explicitBarlineCount)
        assertEquals(listOf("right", "middle"), document.statistics.explicitBarlineLocations)
        assertEquals(2, document.renderedMeasureCount)
        assertEquals(2, document.systems.size)
        assertEquals("1", document.systems[0].measures.single().number)
        assertEquals("2", document.systems[1].measures.single().number)
    }

    @Test
    fun `unsupported and omitted exporter content does not prevent valid rendering`() {
        val xml = scoreXml(
            measure(
                "1",
                "<!-- unresolved exporter events are omitted from MusicXML -->" +
                    "<direction><direction-type><words>unsupported</words></direction-type></direction>" +
                    note("E", 4)
            )
        )
        val document = NotationLayoutEngine.layout(parse(xml))

        assertTrue(document.hasRenderableEvents)
        assertEquals(1, document.statistics.noteCount)
        assertEquals(1, document.unsupportedElements["direction"])
    }

    @Test(expected = UnsupportedMusicXmlException::class)
    fun `timewise MusicXML is reported as unsupported`() {
        parse("<score-timewise version=\"4.0\"><measure number=\"1\"/></score-timewise>")
    }

    private fun parse(xml: String) = MusicXmlNotationParser.parse(MusicXmlParser.parseString(xml))

    private fun scoreXml(measures: String) = """
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Music</part-name></score-part></part-list>
          <part id="P1">$measures</part>
        </score-partwise>
    """.trimIndent()

    private fun measure(number: String, content: String, print: String = "") =
        "<measure number=\"$number\">$print$content</measure>"

    private fun note(step: String, octave: Int) = """
        <note><pitch><step>$step</step><alter>0</alter><octave>$octave</octave></pitch>
        <duration>1</duration><voice>1</voice><type>quarter</type><stem>up</stem><staff>1</staff></note>
    """.trimIndent()

    private val twoStaffScore = scoreXml(
        measure(
            "1",
            """
            <attributes>
              <divisions>1</divisions><staves>2</staves><part-symbol>brace</part-symbol>
              <key number="1"><fifths>1</fifths></key>
              <time number="1"><beats>4</beats><beat-type>4</beat-type></time>
              <clef number="1"><sign>G</sign><line>2</line></clef>
              <clef number="2"><sign>F</sign><line>4</line></clef>
            </attributes>
            <note><pitch><step>C</step><alter>1</alter><octave>4</octave></pitch><duration>1</duration>
              <voice>1</voice><type>quarter</type><accidental>sharp</accidental><stem>up</stem><staff>1</staff></note>
            <note><chord/><pitch><step>E</step><alter>0</alter><octave>4</octave></pitch><duration>1</duration>
              <voice>1</voice><type>quarter</type><stem>up</stem><staff>1</staff></note>
            <backup><duration>1</duration></backup>
            <note><rest/><duration>2</duration><voice>1</voice><type>half</type><staff>2</staff></note>
            """.trimIndent()
        )
    )
}
