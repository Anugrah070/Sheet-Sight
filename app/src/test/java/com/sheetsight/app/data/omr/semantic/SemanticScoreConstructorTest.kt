package com.sheetsight.app.data.omr.semantic

import com.sheetsight.app.data.omr.rhythm.RhythmExtractionResult
import com.sheetsight.app.data.omr.symbol.AccidentalSymbolLabel
import com.sheetsight.app.data.omr.symbol.ClefSymbolLabel
import com.sheetsight.app.data.omr.symbol.MusicalBarlineCandidate
import com.sheetsight.app.data.omr.symbol.SymbolExtractionResult
import com.sheetsight.app.data.omr.track.BoundingBox
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

