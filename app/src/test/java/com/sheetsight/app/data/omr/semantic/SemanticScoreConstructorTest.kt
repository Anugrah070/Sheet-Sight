package com.sheetsight.app.data.omr.semantic

import com.sheetsight.app.data.omr.musicxml.MusicXmlValidationStatus
import com.sheetsight.app.data.omr.musicxml.MusicXmlNotationParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlWriter
import com.sheetsight.app.data.omr.rhythm.RhythmExtractionResult
import com.sheetsight.app.data.omr.symbol.AccidentalSymbolLabel
import com.sheetsight.app.data.omr.symbol.ClefSymbolLabel
import com.sheetsight.app.data.omr.symbol.MusicalBarlineCandidate
import com.sheetsight.app.data.omr.symbol.SymbolExtractionResult
import com.sheetsight.app.data.omr.track.BoundingBox
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticScoreConstructorTest {
    @Test
    fun `constructs chord rest clef change and barline events`() {
        val first = SemanticTestFixtures.note(1, 50, 1)
        val second = SemanticTestFixtures.note(2, 150, 1)
        val score = construct(
            notes = listOf(
                SemanticTestFixtures.rhythmChord(1, 50, listOf(first)),
                SemanticTestFixtures.rhythmChord(2, 150, listOf(second))
            ),
            rests = listOf(SemanticTestFixtures.rest(1, 75)),
            clefs = listOf(
                SemanticTestFixtures.clef(10, ClefSymbolLabel.G_CLEF),
                SemanticTestFixtures.clef(120, ClefSymbolLabel.F_CLEF)
            ),
            barlines = listOf(MusicalBarlineCandidate(BoundingBox(99, 30, 101, 90), 0))
        )

        assertEquals(1, score.systems.size)
        assertEquals(1, score.staffs.size)
        assertEquals(2, score.measures.size)
        assertEquals(2, score.measures.flatMap { it.events }.filterIsInstance<SemanticChord>().size)
        assertEquals(1, score.measures.flatMap { it.events }.filterIsInstance<SemanticRest>().size)
        assertEquals(2, score.measures.flatMap { it.events }.filterIsInstance<SemanticClefChange>().size)
        assertEquals(PitchStep.E, score.chord(1).notes.single().pitch?.step)
        assertEquals(4, score.chord(1).notes.single().pitch?.octave)
        assertEquals(PitchStep.G, score.chord(2).notes.single().pitch?.step)
        assertEquals(2, score.chord(2).notes.single().pitch?.octave)
    }

    @Test
    fun `detected barlines survive semantic construction into MusicXML measures`() {
        val score = construct(
            barlines = listOf(
                MusicalBarlineCandidate(BoundingBox(79, 30, 81, 90), 0),
                MusicalBarlineCandidate(BoundingBox(139, 30, 141, 90), 0)
            )
        )

        val result = MusicXmlWriter.serialize(score)
        val parsed = MusicXmlNotationParser.parse(MusicXmlParser.parseString(requireNotNull(result.xml)))
        val notation = NotationLayoutEngine.layout(parsed)

        assertEquals(MusicXmlValidationStatus.VALID, result.validationStatus)
        assertEquals(3, result.exportedMeasureCount)
        assertEquals(score.measures.size, result.exportedMeasureCount)
        assertEquals(2, result.exportedBarlineCount)
        assertEquals(2, Regex("<barline\\b").findAll(requireNotNull(result.xml)).count())
        assertEquals(result.exportedMeasureCount, parsed.statistics.measureCount)
        assertEquals(result.exportedBarlineCount, parsed.statistics.explicitBarlineCount)
        assertEquals(result.exportedMeasureCount, notation.renderedMeasureCount)
    }

    @Test
    fun `barlines are assigned only to their own system`() {
        val score = SemanticScoreConstructor.construct(
            SemanticTestFixtures.twoSystemStaffGrid(),
            symbols(
                barlines = listOf(
                    MusicalBarlineCandidate(BoundingBox(79, 30, 81, 90), group = 0),
                    MusicalBarlineCandidate(BoundingBox(139, 130, 141, 190), group = 1)
                )
            ),
            RhythmExtractionResult(emptyList(), emptyList())
        )

        assertEquals(2, score.systems.size)
        assertEquals(listOf(0 to 80, 80 to 200), score.systems[0].measures.map { it.boundary.left to it.boundary.right })
        assertEquals(listOf(0 to 140, 140 to 200), score.systems[1].measures.map { it.boundary.left to it.boundary.right })
        assertEquals(1, score.systems[0].measures.flatMap { it.events }.filterIsInstance<SemanticBarline>().size)
        assertEquals(1, score.systems[1].measures.flatMap { it.events }.filterIsInstance<SemanticBarline>().size)
    }

    @Test
    fun `missing barline evidence preserves one unresolved interval without guessing`() {
        val score = construct()
        val result = MusicXmlWriter.serialize(score)

        assertEquals(1, score.measures.size)
        assertEquals(
            SemanticMeasureBoundary(
                left = 0,
                right = 200,
                leftEvidence = MeasureBoundaryEvidence.STAFF_EXTENT,
                rightEvidence = MeasureBoundaryEvidence.STAFF_EXTENT
            ),
            score.measures.single().boundary
        )
        assertTrue(
            score.validationWarnings.any {
                it.code == SemanticValidationCode.UNRESOLVED_MEASURE_BOUNDARY &&
                    it.semanticId == "system-0"
            }
        )
        assertEquals(1, result.exportedMeasureCount)
        assertTrue(result.warnings.any { it.message.contains("UNRESOLVED_MEASURE_BOUNDARY") })
    }

    @Test
    fun `assigned edge barline beyond staff ink remains right boundary evidence`() {
        val score = construct(
            barlines = listOf(MusicalBarlineCandidate(BoundingBox(205, 30, 207, 90), 0))
        )
        val result = MusicXmlWriter.serialize(score)

        assertEquals(1, score.measures.size)
        assertEquals(206, score.measures.single().boundary.right)
        assertEquals(MeasureBoundaryEvidence.DETECTED_BARLINE, score.measures.single().boundary.rightEvidence)
        assertEquals(1, result.exportedBarlineCount)
        assertEquals(listOf("right"), result.exportedBarlineLocations)
    }

    @Test
    fun `constructs all chord pitches`() {
        val notes = listOf(
            SemanticTestFixtures.note(1, 60, 1),
            SemanticTestFixtures.note(2, 60, 3),
            SemanticTestFixtures.note(3, 60, 5)
        )
        val score = construct(
            notes = listOf(SemanticTestFixtures.rhythmChord(8, 60, notes)),
            clefs = listOf(SemanticTestFixtures.clef(10, ClefSymbolLabel.G_CLEF))
        )

        assertEquals(listOf(PitchStep.E, PitchStep.G, PitchStep.B), score.chord(8).notes.map { it.pitch?.step })
    }

    @Test
    fun `key local natural and measure reset alter pitches without changing diatonic pitch`() {
        val note1 = SemanticTestFixtures.note(1, 50, 2)
        val note2 = SemanticTestFixtures.note(2, 90, 2)
        val note3 = SemanticTestFixtures.note(3, 110, 2)
        val note4 = SemanticTestFixtures.note(4, 135, 2)
        val note5 = SemanticTestFixtures.note(5, 160, 2)
        val score = construct(
            notes = listOf(
                SemanticTestFixtures.rhythmChord(1, 50, listOf(note1)),
                SemanticTestFixtures.rhythmChord(2, 90, listOf(note2)),
                SemanticTestFixtures.rhythmChord(3, 110, listOf(note3)),
                SemanticTestFixtures.rhythmChord(4, 135, listOf(note4)),
                SemanticTestFixtures.rhythmChord(5, 160, listOf(note5))
            ),
            clefs = listOf(SemanticTestFixtures.clef(5, ClefSymbolLabel.G_CLEF)),
            accidentals = listOf(
                SemanticTestFixtures.accidental(20, 75, AccidentalSymbolLabel.SHARP, null),
                SemanticTestFixtures.accidental(82, 75, AccidentalSymbolLabel.FLAT, 2),
                SemanticTestFixtures.accidental(128, 75, AccidentalSymbolLabel.NATURAL, 4)
            ),
            barlines = listOf(MusicalBarlineCandidate(BoundingBox(149, 30, 151, 90), 0))
        )

        val alterations = (1..5).map { score.chord(it).notes.single().pitch?.alteration }
        assertEquals(
            listOf(
                AccidentalAlteration.SHARP,
                AccidentalAlteration.FLAT,
                AccidentalAlteration.FLAT,
                AccidentalAlteration.NATURAL,
                AccidentalAlteration.SHARP
            ),
            alterations
        )
        assertTrue(score.measures.flatMap { it.events }.any { it is SemanticKeySignature })
        assertTrue((1..5).all { score.chord(it).notes.single().pitch?.step == PitchStep.F })
    }

    @Test
    fun `recognition geometry is unchanged and repeated output is deterministic`() {
        val note = SemanticTestFixtures.note(7, 60, 4)
        val originalPixels = note.sourcePixelIndices.copyOf()
        val rhythm = SemanticTestFixtures.rhythmChord(7, 60, listOf(note))
        val symbols = symbols(clefs = listOf(SemanticTestFixtures.clef(10, ClefSymbolLabel.G_CLEF)))
        val input = RhythmExtractionResult(listOf(rhythm), emptyList())

        val first = SemanticScoreConstructor.construct(SemanticTestFixtures.staffGrid(), symbols, input)
        val second = SemanticScoreConstructor.construct(SemanticTestFixtures.staffGrid(), symbols, input)

        assertEquals(first, second)
        assertEquals(BoundingBox(55, 30, 66, 65), rhythm.chord.boundingBox)
        assertArrayEquals(originalPixels, note.sourcePixelIndices)
    }

    @Test
    fun `split inner voices keep one source group but receive unique semantic and MusicXML voices`() {
        val upperNote = SemanticTestFixtures.note(1, 60, 5)
        val lowerNote = SemanticTestFixtures.note(2, 60, 1)
        val upper = SemanticTestFixtures.rhythmChord(10, 60, listOf(upperNote)).copy(noteGroupId = 7)
        val lowerBase = SemanticTestFixtures.rhythmChord(11, 60, listOf(lowerNote))
        val lower = lowerBase.copy(
            noteGroupId = 7,
            chord = lowerBase.chord.copy(stemDirection = com.sheetsight.app.data.omr.grouping.StemDirection.DOWN),
            stemDirection = com.sheetsight.app.data.omr.grouping.StemDirection.DOWN
        )
        val score = construct(
            notes = listOf(upper, lower),
            clefs = listOf(SemanticTestFixtures.clef(10, ClefSymbolLabel.G_CLEF))
        )
        val chords = score.measures.flatMap { it.events }.filterIsInstance<SemanticChord>()
        val result = MusicXmlWriter.serialize(score)
        val xml = requireNotNull(result.xml)

        assertEquals(listOf("chord-10", "chord-11"), chords.map { it.id })
        assertEquals(MusicXmlValidationStatus.VALID, result.validationStatus)
        assertEquals(1, Regex("<voice>1</voice>").findAll(xml).count())
        assertEquals(1, Regex("<voice>2</voice>").findAll(xml).count())
        assertTrue(xml.contains("<backup>"))
    }

    private fun construct(
        notes: List<com.sheetsight.app.data.omr.rhythm.RhythmCandidate> = emptyList(),
        rests: List<com.sheetsight.app.data.omr.rhythm.RestRhythmResult> = emptyList(),
        clefs: List<com.sheetsight.app.data.omr.symbol.ClefCandidate> = emptyList(),
        accidentals: List<com.sheetsight.app.data.omr.symbol.AccidentalCandidate> = emptyList(),
        barlines: List<MusicalBarlineCandidate> = emptyList()
    ): SemanticScore = SemanticScoreConstructor.construct(
        SemanticTestFixtures.staffGrid(),
        symbols(clefs, accidentals, barlines),
        RhythmExtractionResult(notes, rests)
    )

    private fun symbols(
        clefs: List<com.sheetsight.app.data.omr.symbol.ClefCandidate> = emptyList(),
        accidentals: List<com.sheetsight.app.data.omr.symbol.AccidentalCandidate> = emptyList(),
        barlines: List<MusicalBarlineCandidate> = emptyList()
    ) = SymbolExtractionResult(barlines, clefs, accidentals, emptyList())

    private fun SemanticScore.chord(id: Int): SemanticChord = measures
        .flatMap { it.events }
        .filterIsInstance<SemanticChord>()
        .single { it.id == "chord-$id" }
}
