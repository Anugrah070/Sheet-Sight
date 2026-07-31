package com.sheetsight.app.data.omr.musicxml

import com.sheetsight.app.data.omr.semantic.AccidentalAlteration
import com.sheetsight.app.data.omr.semantic.MeasureBoundaryEvidence
import com.sheetsight.app.data.omr.semantic.PitchStep
import com.sheetsight.app.data.omr.semantic.SemanticBarline
import com.sheetsight.app.data.omr.semantic.SemanticBounds
import com.sheetsight.app.data.omr.semantic.SemanticChord
import com.sheetsight.app.data.omr.semantic.SemanticClef
import com.sheetsight.app.data.omr.semantic.SemanticClefChange
import com.sheetsight.app.data.omr.semantic.SemanticDuration
import com.sheetsight.app.data.omr.semantic.SemanticEvent
import com.sheetsight.app.data.omr.semantic.SemanticKeySignature
import com.sheetsight.app.data.omr.semantic.SemanticMeasure
import com.sheetsight.app.data.omr.semantic.SemanticMeasureBoundary
import com.sheetsight.app.data.omr.semantic.SemanticNote
import com.sheetsight.app.data.omr.semantic.SemanticPart
import com.sheetsight.app.data.omr.semantic.SemanticPitch
import com.sheetsight.app.data.omr.semantic.SemanticRest
import com.sheetsight.app.data.omr.semantic.SemanticRhythmState
import com.sheetsight.app.data.omr.semantic.SemanticScore
import com.sheetsight.app.data.omr.semantic.SemanticSourceKind
import com.sheetsight.app.data.omr.semantic.SemanticSourceRef
import com.sheetsight.app.data.omr.semantic.SemanticStaff
import com.sheetsight.app.data.omr.semantic.SemanticStemDirection
import com.sheetsight.app.data.omr.semantic.SemanticSystem
import com.sheetsight.app.data.omr.semantic.SemanticBeamInfo
import com.sheetsight.app.data.omr.semantic.SemanticTimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class MusicXmlWriterTest {
    @Test
    fun `empty semantic score fails validation without fabricating a part or measure`() {
        val result = MusicXmlWriter.serialize(SemanticScore(emptyList()))

        assertEquals(MusicXmlValidationStatus.INVALID, result.validationStatus)
        assertNull(result.xml)
        assertTrue(result.validationErrors.any { it.contains("exactly one semantic part") })
        assertEquals(0, result.exportedMeasureCount)
    }

    @Test
    fun `one resolved note exports pitch duration type staff and counts`() {
        val result = writeScore(
            listOf(
                clef("clef-1", 5, SemanticClef.TREBLE),
                chord("chord-1", 20, listOf(note("note-1", 20, PitchStep.E, 4)))
            )
        )
        val xml = validXml(result)
        val document = parse(xml)
        val note = elements(document, "note").single()

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""))
        assertTrue(xml.contains("-//Recordare//DTD MusicXML 4.0 Partwise//EN"))
        assertEquals("E", note.descendantText("step"))
        assertEquals("0", note.descendantText("alter"))
        assertEquals("4", note.descendantText("octave"))
        assertEquals("1", note.directText("duration"))
        assertEquals("quarter", note.directText("type"))
        assertEquals("1", note.directText("staff"))
        assertEquals(1, result.exportedNoteCount)
        assertEquals(1, result.exportedChordCount)
    }

    @Test
    fun `one resolved rest exports resolved value only`() {
        val result = writeScore(listOf(rest("rest-1", 20, SemanticDuration(1, 8))))
        val note = elements(parse(validXml(result)), "note").single()

        assertNotNull(note.directChild("rest"))
        assertEquals("eighth", note.directText("type"))
        assertEquals(1, result.exportedRestCount)
        assertEquals(0, result.exportedNoteCount)
    }

    @Test
    fun `one chord preserves shared duration and member order`() {
        val result = writeScore(
            listOf(
                clef("clef-1", 5, SemanticClef.TREBLE),
                chord(
                    "chord-1",
                    20,
                    listOf(
                        note("note-c", 20, PitchStep.C, 4),
                        note("note-e", 20, PitchStep.E, 4),
                        note("note-g", 20, PitchStep.G, 4)
                    ),
                    SemanticDuration(1, 2)
                )
            )
        )
        val notes = elements(parse(validXml(result)), "note")

        assertEquals(listOf("C", "E", "G"), notes.map { it.descendantText("step") })
        assertEquals(listOf("2", "2", "2"), notes.map { it.directText("duration") })
        assertNull(notes[0].directChild("chord"))
        assertNotNull(notes[1].directChild("chord"))
        assertNotNull(notes[2].directChild("chord"))
        assertEquals(1, result.exportedChordCount)
    }

    @Test
    fun `treble clef exports G on line two`() {
        val clef = elements(parse(validXml(writeScore(listOf(clef("g", 5, SemanticClef.TREBLE))))), "clef").single()

        assertEquals("G", clef.directText("sign"))
        assertEquals("2", clef.directText("line"))
    }

    @Test
    fun `bass clef exports F on line four`() {
        val clef = elements(parse(validXml(writeScore(listOf(clef("f", 5, SemanticClef.BASS))))), "clef").single()

        assertEquals("F", clef.directText("sign"))
        assertEquals("4", clef.directText("line"))
    }

    @Test
    fun `canonical key signature exports fifths`() {
        val key = SemanticKeySignature(
            id = "key-1",
            measureId = "measure-0",
            staffId = "staff-0",
            horizontalPosition = 10,
            sourceRefs = listOf(source(SemanticSourceKind.ACCIDENTAL, "key-sharps")),
            alterations = mapOf(
                PitchStep.F to AccidentalAlteration.SHARP,
                PitchStep.C to AccidentalAlteration.SHARP
            )
        )
        val result = writeScore(listOf(clef("g", 5, SemanticClef.TREBLE), key))

        assertEquals("2", elements(parse(validXml(result)), "fifths").single().textContent)
    }

    @Test
    fun `unresolved key signature remains absent with warning`() {
        val unresolved = SemanticKeySignature(
            id = "key-unresolved",
            measureId = "measure-0",
            staffId = "staff-0",
            horizontalPosition = 10,
            sourceRefs = listOf(source(SemanticSourceKind.ACCIDENTAL, "unresolved-header")),
            alterations = emptyMap()
        )
        val result = writeScore(listOf(clef("g", 5, SemanticClef.TREBLE), unresolved))

        assertTrue(elements(parse(validXml(result)), "key").isEmpty())
        assertEquals(1, result.omittedUnresolvedEventCount)
        assertTrue(result.warnings.any { it.code == MusicXmlExportWarningCode.UNRESOLVED_KEY_SIGNATURE })
    }

    @Test
    fun `verified time signature exports beats and beat type`() {
        val time = SemanticTimeSignature(
            id = "time-1",
            measureId = "measure-0",
            staffId = "staff-0",
            horizontalPosition = 10,
            sourceRefs = emptyList(),
            beats = 3,
            beatUnit = 4
        )
        val timeElement = elements(parse(validXml(writeScore(listOf(time)))), "time").single()

        assertEquals("3", timeElement.directText("beats"))
        assertEquals("4", timeElement.directText("beat-type"))
    }

    @Test
    fun `barline is emitted only for a semantic barline event`() {
        val withoutBarline = parse(validXml(writeScore(emptyList())))
        val event = SemanticBarline(
            id = "barline-1",
            measureId = "measure-0",
            horizontalPosition = 100,
            sourceRefs = listOf(source(SemanticSourceKind.BARLINE, "1"))
        )
        val withBarline = parse(validXml(writeScore(listOf(event))))

        assertTrue(elements(withoutBarline, "barline").isEmpty())
        assertEquals("right", elements(withBarline, "barline").single().getAttribute("location"))
    }

    @Test
    fun `local sharp and natural export display accidentals and alterations`() {
        val sharp = note(
            "note-sharp",
            20,
            PitchStep.F,
            4,
            AccidentalAlteration.SHARP,
            localAccidental = true
        )
        val natural = note(
            "note-natural",
            40,
            PitchStep.F,
            4,
            AccidentalAlteration.NATURAL,
            localAccidental = true
        )
        val result = writeScore(
            listOf(
                clef("g", 5, SemanticClef.TREBLE),
                chord("chord-sharp", 20, listOf(sharp)),
                chord("chord-natural", 40, listOf(natural))
            )
        )
        val notes = elements(parse(validXml(result)), "note")

        assertEquals(listOf("1", "0"), notes.map { it.descendantText("alter") })
        assertEquals(listOf("sharp", "natural"), notes.map { it.directText("accidental") })
    }

    @Test
    fun `dotted duration exports exact divisions duration type and dot`() {
        val result = writeScore(
            listOf(
                clef("g", 5, SemanticClef.TREBLE),
                chord(
                    "dotted",
                    20,
                    listOf(note("note-1", 20, PitchStep.C, 4)),
                    duration = SemanticDuration(3, 8),
                    dots = 1
                )
            )
        )
        val document = parse(validXml(result))
        val note = elements(document, "note").single()

        assertEquals("2", elements(document, "divisions").single().textContent)
        assertEquals("3", note.directText("duration"))
        assertEquals("quarter", note.directText("type"))
        assertEquals(1, note.getElementsByTagName("dot").length)
    }

    @Test
    fun `multiple measures preserve semantic order with sequential numbers`() {
        val first = measure(0, listOf(rest("rest-1", 20, SemanticDuration(1, 4), "measure-0")))
        val second = measure(1, listOf(rest("rest-2", 120, SemanticDuration(1, 2), "measure-1")))
        val result = MusicXmlWriter.serialize(score(listOf(first, second)))
        val measures = elements(parse(validXml(result)), "measure")

        assertEquals(listOf("1", "2"), measures.map { it.getAttribute("number") })
        assertEquals(2, result.exportedMeasureCount)
    }

    @Test
    fun `pickup and incomplete measures remain implicit`() {
        val pickup = measure(
            index = 0,
            events = emptyList(),
            left = MeasureBoundaryEvidence.STAFF_EXTENT,
            right = MeasureBoundaryEvidence.DETECTED_BARLINE
        )
        val incomplete = measure(
            index = 1,
            events = emptyList(),
            left = MeasureBoundaryEvidence.DETECTED_BARLINE,
            right = MeasureBoundaryEvidence.STAFF_EXTENT
        )
        val measures = elements(parse(validXml(MusicXmlWriter.serialize(score(listOf(pickup, incomplete))))), "measure")

        assertTrue(measures.all { it.getAttribute("implicit") == "yes" })
    }

    @Test
    fun `missing clef leaves note omitted with warning`() {
        val unresolved = note("note-1", 20, PitchStep.C, 4).copy(pitch = null, activeClef = null)
        val result = writeScore(listOf(chord("chord-1", 20, listOf(unresolved))))

        assertEquals(0, elements(parse(validXml(result)), "note").size)
        assertEquals(1, result.omittedUnresolvedEventCount)
        assertTrue(result.warnings.any { it.code == MusicXmlExportWarningCode.MISSING_CLEF })
    }

    @Test
    fun `unresolved chord duration omits the event with warning`() {
        val unresolved = chord("chord-1", 20, listOf(note("note-1", 20, PitchStep.C, 4))).copy(
            duration = null,
            rhythmState = SemanticRhythmState.UNRESOLVED
        )
        val result = writeScore(listOf(clef("g", 5, SemanticClef.TREBLE), unresolved))

        assertEquals(0, elements(parse(validXml(result)), "note").size)
        assertEquals(1, result.omittedUnresolvedEventCount)
        assertTrue(result.warnings.any { it.code == MusicXmlExportWarningCode.UNRESOLVED_DURATION })
    }

    @Test
    fun `whole or half unresolved rest remains omitted with warning`() {
        val unresolved = rest("rest-whole-half", 20, null).copy(
            rhythmState = SemanticRhythmState.UNRESOLVED
        )
        val result = writeScore(listOf(unresolved))

        assertEquals(0, elements(parse(validXml(result)), "note").size)
        assertEquals(0, result.exportedRestCount)
        assertEquals(1, result.omittedUnresolvedEventCount)
        assertTrue(result.warnings.any { it.semanticId == "rest-whole-half" })
    }

    @Test
    fun `repeated serialization is deterministic`() {
        val score = score(
            listOf(
                measure(
                    0,
                    listOf(
                        clef("g", 5, SemanticClef.TREBLE),
                        chord("chord-1", 20, listOf(note("note-1", 20, PitchStep.C, 4)))
                    )
                )
            )
        )

        assertEquals(MusicXmlWriter.serialize(score), MusicXmlWriter.serialize(score))
    }

    @Test
    fun `XML API escapes part name metadata`() {
        val result = MusicXmlWriter.serialize(score(listOf(measure(0, emptyList()))), "Piano & <Lead> \"Solo\"")
        val xml = validXml(result)

        assertTrue(xml.contains("Piano &amp; &lt;Lead&gt; \"Solo\""))
        assertEquals("Piano & <Lead> \"Solo\"", elements(parse(xml), "part-name").single().textContent)
    }

    @Test
    fun `duplicate semantic IDs fail validation instead of producing ambiguous output`() {
        val duplicate = listOf(
            rest("same-id", 20, SemanticDuration(1, 4)),
            rest("same-id", 40, SemanticDuration(1, 4))
        )
        val result = writeScore(duplicate)

        assertEquals(MusicXmlValidationStatus.INVALID, result.validationStatus)
        assertNull(result.xml)
        assertTrue(result.validationErrors.any { it.contains("duplicate semantic id 'same-id'") })
    }

    @Test
    fun `chord elements only follow an emitted pitched base note`() {
        val result = writeScore(
            listOf(
                clef("g", 5, SemanticClef.TREBLE),
                chord(
                    "chord-1",
                    20,
                    listOf(
                        note("note-1", 20, PitchStep.C, 4),
                        note("note-2", 20, PitchStep.E, 4)
                    )
                )
            )
        )
        val notes = elements(parse(validXml(result)), "note")

        assertFalse(notes.first().hasDirectChild("chord"))
        assertTrue(notes.last().hasDirectChild("chord"))
        assertEquals(MusicXmlValidationStatus.VALID, result.validationStatus)
    }

    private fun writeScore(events: List<SemanticEvent>): MusicXmlSerializationResult =
        MusicXmlWriter.serialize(score(listOf(measure(0, events))))

    private fun score(measures: List<SemanticMeasure>): SemanticScore {
        val staff = SemanticStaff("staff-0", 0, "system-0", source(SemanticSourceKind.STAFF_GRID, "staff"))
        val system = SemanticSystem(
            id = "system-0",
            index = 0,
            staffs = listOf(staff),
            measures = measures,
            horizontalBounds = SemanticBounds(0, 0, 200, 100),
            source = source(SemanticSourceKind.STAFF_GRID, "system")
        )
        return SemanticScore(listOf(SemanticPart("part-0", listOf(system))))
    }

    private fun measure(
        index: Int,
        events: List<SemanticEvent>,
        left: MeasureBoundaryEvidence = MeasureBoundaryEvidence.DETECTED_BARLINE,
        right: MeasureBoundaryEvidence = MeasureBoundaryEvidence.DETECTED_BARLINE
    ) = SemanticMeasure(
        id = "measure-$index",
        index = index,
        systemId = "system-0",
        boundary = SemanticMeasureBoundary(index * 100, (index + 1) * 100, left, right),
        events = events
    )

    private fun clef(id: String, x: Int, clef: SemanticClef, measureId: String = "measure-0") =
        SemanticClefChange(id, measureId, "staff-0", x, listOf(source(SemanticSourceKind.CLEF, id)), clef)

    private fun chord(
        id: String,
        x: Int,
        notes: List<SemanticNote>,
        duration: SemanticDuration = SemanticDuration(1, 4),
        dots: Int = 0,
        measureId: String = "measure-0"
    ) = SemanticChord(
        id = id,
        measureId = measureId,
        staffId = "staff-0",
        horizontalPosition = x,
        sourceRefs = listOf(source(SemanticSourceKind.NOTE_GROUP, id)),
        notes = notes,
        duration = duration,
        rhythmState = SemanticRhythmState.RESOLVED,
        stemDirection = SemanticStemDirection.UP,
        beamInfo = SemanticBeamInfo(0, 0),
        augmentationDots = dots
    )

    private fun note(
        id: String,
        x: Int,
        step: PitchStep,
        octave: Int,
        alteration: AccidentalAlteration = AccidentalAlteration.NATURAL,
        localAccidental: Boolean = false,
        measureId: String = "measure-0"
    ) = SemanticNote(
        id = id,
        measureId = measureId,
        staffId = "staff-0",
        horizontalPosition = x,
        sourceRefs = listOf(source(SemanticSourceKind.NOTEHEAD, id)) +
            if (localAccidental) listOf(source(SemanticSourceKind.ACCIDENTAL, "accidental-$id")) else emptyList(),
        pitch = SemanticPitch(step, octave, 1, alteration),
        activeClef = SemanticClef.TREBLE
    )

    private fun rest(
        id: String,
        x: Int,
        duration: SemanticDuration?,
        measureId: String = "measure-0"
    ) = SemanticRest(
        id = id,
        measureId = measureId,
        staffId = "staff-0",
        horizontalPosition = x,
        sourceRefs = listOf(source(SemanticSourceKind.REST, id)),
        duration = duration,
        rhythmState = if (duration == null) SemanticRhythmState.UNRESOLVED else SemanticRhythmState.RESOLVED,
        augmentationDots = 0
    )

    private fun source(kind: SemanticSourceKind, id: String) = SemanticSourceRef(kind, id)

    private fun validXml(result: MusicXmlSerializationResult): String {
        assertEquals(result.validationErrors.joinToString(), MusicXmlValidationStatus.VALID, result.validationStatus)
        return requireNotNull(result.xml)
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml)))
    }

    private fun elements(document: Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.directChild(tag: String): Element? =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.tagName == tag }

    private fun Element.hasDirectChild(tag: String) = directChild(tag) != null
    private fun Element.directText(tag: String) = requireNotNull(directChild(tag)).textContent
    private fun Element.descendantText(tag: String) = getElementsByTagName(tag).item(0).textContent
}
