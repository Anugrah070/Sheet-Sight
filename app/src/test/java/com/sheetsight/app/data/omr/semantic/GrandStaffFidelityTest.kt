package com.sheetsight.app.data.omr.semantic

import com.sheetsight.app.data.omr.grouping.ChordCandidate
import com.sheetsight.app.data.omr.grouping.StemDirection
import com.sheetsight.app.data.omr.musicxml.MusicXmlValidationStatus
import com.sheetsight.app.data.omr.musicxml.MusicXmlWriter
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.notehead.NoteheadStaffAssignment
import com.sheetsight.app.data.omr.notehead.NoteheadType
import com.sheetsight.app.data.omr.rhythm.RhythmCandidate
import com.sheetsight.app.data.omr.rhythm.RhythmDuration
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceStatus
import com.sheetsight.app.data.omr.rhythm.RhythmExtractionResult
import com.sheetsight.app.data.omr.rhythm.RhythmResolutionState
import com.sheetsight.app.data.omr.rhythm.RhythmValue
import com.sheetsight.app.data.omr.rhythm.StemAssociation
import com.sheetsight.app.data.omr.rhythm.StemAssociationStatus
import com.sheetsight.app.data.omr.symbol.ClefCandidate
import com.sheetsight.app.data.omr.symbol.ClefSymbolLabel
import com.sheetsight.app.data.omr.symbol.SvmModelKind
import com.sheetsight.app.data.omr.symbol.SvmModelSpec
import com.sheetsight.app.data.omr.symbol.SymbolClassification
import com.sheetsight.app.data.omr.symbol.SymbolExtractionResult
import com.sheetsight.app.data.omr.symbol.SymbolStaffAssignment
import com.sheetsight.app.data.omr.track.AssignedStaff
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.data.omr.track.StaffGridValidator
import com.sheetsight.app.data.omr.track.StaffTrackGroupAssigner
import com.sheetsight.app.data.omr.track.StaffZoneGridExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class GrandStaffFidelityTest {
    @Test
    fun `surviving grand staff keeps classified clefs in separate pitch spaces`() {
        val score = grandStaffScore()
        val chords = score.measures.flatMap { it.events }.filterIsInstance<SemanticChord>()

        assertEquals(2, score.systems.single().staffs.size)
        assertEquals(
            listOf(SemanticClef.TREBLE, SemanticClef.BASS),
            chords.map { it.notes.single().activeClef }
        )
        assertEquals(
            listOf(PitchStep.B to 4, PitchStep.D to 3),
            chords.map { it.notes.single().pitch!!.let { pitch -> pitch.step to pitch.octave } }
        )
    }

    @Test
    fun `surviving grand staff exports two staves and keeps right hand note on staff one`() {
        val result = MusicXmlWriter.serialize(grandStaffScore(), partName = "Piano")

        assertEquals(MusicXmlValidationStatus.VALID, result.validationStatus)
        val document = parse(assertNotNull(result.xml).let { result.xml!! })
        assertEquals("2", document.getElementsByTagName("staves").item(0).textContent)

        val clefs = document.getElementsByTagName("clef").asElements()
        assertEquals(listOf("1", "2"), clefs.map { it.getAttribute("number") })
        assertEquals(listOf("G", "F"), clefs.map { it.getElementsByTagName("sign").item(0).textContent })

        val notes = document.getElementsByTagName("note").asElements()
        assertEquals(listOf("1", "2"), notes.map { it.getElementsByTagName("staff").item(0).textContent })
        assertEquals("B", notes.first().getElementsByTagName("step").item(0).textContent)
        assertEquals("4", notes.first().getElementsByTagName("octave").item(0).textContent)
    }

    private fun grandStaffScore(): SemanticScore {
        val staffGrid = grandStaffGrid()
        val upper = note(id = 1, x = 50, track = 0, staffPosition = 5)
        val lower = note(id = 2, x = 60, track = 1, staffPosition = 5)
        val symbols = SymbolExtractionResult(
            barlines = emptyList(),
            clefs = listOf(
                clef(id = 0, x = 10, track = 0, label = ClefSymbolLabel.G_CLEF),
                clef(id = 1, x = 10, track = 1, label = ClefSymbolLabel.F_CLEF)
            ),
            accidentals = emptyList(),
            rests = emptyList()
        )
        return SemanticScoreConstructor.construct(
            staffGrid,
            symbols,
            RhythmExtractionResult(
                noteGroups = listOf(
                    rhythmChord(id = 1, x = 50, track = 0, note = upper),
                    rhythmChord(id = 2, x = 60, track = 1, note = lower)
                ),
                rests = emptyList()
            )
        )
    }

    private fun grandStaffGrid(): List<List<AssignedStaff>> {
        val width = 80
        val height = 190
        val rows = setOf(
            20, 28, 41, 54, 67, 80,
            112, 120, 130, 140, 150, 160
        )
        val mask = BooleanArray(width * height) { index -> index / width in rows }
        val extracted = StaffZoneGridExtractor.extract(mask, width, height)
        assertEquals(List(8) { 2 }, extracted.map { it.size })
        return StaffGridValidator.validate(StaffTrackGroupAssigner.assign(extracted, numTrack = 2))
    }

    private fun note(id: Int, x: Int, track: Int, staffPosition: Int) = NoteheadCandidate(
        id = id,
        boundingBox = BoundingBox(x - 4, 50 + track * 80, x + 5, 58 + track * 80),
        type = NoteheadType.SOLID,
        staffAssignment = NoteheadStaffAssignment(track, group = 0, staffPosition),
        sourcePixelIndices = intArrayOf(id),
        stemOnRight = true
    )

    private fun rhythmChord(id: Int, x: Int, track: Int, note: NoteheadCandidate): RhythmCandidate {
        val box = BoundingBox(x - 5, 30 + track * 80, x + 6, 70 + track * 80)
        val chord = ChordCandidate(id, listOf(note), box, StemDirection.UP, true, track, group = 0)
        return RhythmCandidate(
            id = id,
            noteGroupId = id,
            chord = chord,
            noteheads = listOf(note),
            evidenceStatus = RhythmEvidenceStatus.COMPLETE,
            stemDirection = StemDirection.UP,
            stemAssociation = StemAssociation(StemAssociationStatus.ASSIGNED, StemDirection.UP, box),
            beamCount = 0,
            flagCount = 0,
            dotCount = 0,
            dotEvidence = emptyList(),
            baseDuration = RhythmDuration.QUARTER,
            dottedDuration = RhythmValue.of(1, 4),
            resolutionState = RhythmResolutionState.RESOLVED,
            unresolvedReasons = emptyList()
        )
    }

    private fun clef(id: Int, x: Int, track: Int, label: ClefSymbolLabel): ClefCandidate {
        val spec = SvmModelSpec.CLEF
        return ClefCandidate(
            boundingBox = BoundingBox(x - 3, 30 + track * 90, x + 4, 85 + track * 75),
            label = label,
            assignment = SymbolStaffAssignment(track, group = 0),
            classification = SymbolClassification(
                model = SvmModelKind.CLEF,
                classId = spec.labels.indexOf(label),
                label = label,
                decisionScores = emptyList()
            )
        )
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        List(length) { index -> item(index) as Element }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // Features wrapped in try-catch because some are not supported on Android
        listOf(
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities"
        ).forEach { feature ->
            try {
                setFeature(feature, false)
            } catch (e: Exception) {
                // Safe to ignore if the feature is not supported by the platform's parser
            }
        }
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(InputSource(StringReader(xml)))
}
