package com.sheetsight.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorMusicXmlLoaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `UTF-16 MusicXML is decoded for the alphaTab renderer`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-16"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Klavier</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><rest/><duration>1</duration><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        val file = temporaryFolder.newFile("utf16.musicxml")
        file.writeBytes(xml.toByteArray(Charsets.UTF_16))

        val result = EditorMusicXmlLoader().load(file)

        assertEquals(1, result.document.statistics.measureCount)
        assertTrue(result.musicXml.contains("Klavier"))
        assertFalse(result.musicXml.startsWith('\uFEFF'))
    }

    @Test
    fun `compressed MusicXML extension is rejected before parsing`() {
        val file = temporaryFolder.newFile("score.mxl").apply { writeText("not a zip") }

        assertThrows(UnsupportedEditorScoreException::class.java) {
            EditorMusicXmlLoader().load(file)
        }
    }

    @Test
    fun `empty MusicXML is reported explicitly`() {
        val file = temporaryFolder.newFile("empty.musicxml")

        assertThrows(EmptyEditorScoreException::class.java) {
            EditorMusicXmlLoader().load(file)
        }
    }
}
