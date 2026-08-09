package com.sheetsight.app.data.practice

import com.sheetsight.app.domain.practice.DurationComparisonReliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeSequenceFactoryTest {
    @Test
    fun `MusicXML conversion preserves note order chords accidentals measures and rests`() {
        val sequence = PracticeMusicXmlLoader().load("lesson.musicxml", SCORE.toByteArray()).sequence

        assertEquals("lesson.musicxml", sequence.source.fileName)
        assertEquals(2, sequence.source.measureCount)
        assertEquals(listOf("C4", "D4", "Rest", "E4 + G4", "F#4", "Gb4"), sequence.steps.map { it.displayText })
        assertEquals(listOf("1", "1", "1", "1", "2", "2"), sequence.steps.map { it.measureNumber })
        assertFalse(sequence.steps[0].requiresPolyphonicRecognition)
        assertTrue(sequence.steps[2].isRest)
        assertTrue(sequence.steps[3].requiresPolyphonicRecognition)
        assertEquals(66, sequence.steps[4].expectedPitches.single().midiNumber)
        assertEquals(66, sequence.steps[5].expectedPitches.single().midiNumber)
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), sequence.steps.map { it.startBeat.toString() })
        assertEquals(sequence.steps.indices.toList(), sequence.steps.map { it.index })
    }

    @Test
    fun `quarter half and eighth durations normalize through MusicXML divisions`() {
        val sequence = PracticeMusicXmlLoader().load("rhythm.musicxml", RHYTHM.toByteArray()).sequence

        assertEquals(listOf("1", "2", "1/2", "1"), sequence.steps.map { it.durationBeats.toString() })
        assertEquals(listOf("0", "1", "3", "7/2"), sequence.steps.map { it.measureBeat.toString() })
        assertEquals(120, sequence.source.detectedTempoBpm)
        assertEquals(3, sequence.source.initialMeter?.beats)
        assertEquals(4, sequence.source.initialMeter?.beatType)
    }

    @Test
    fun `missing numeric duration remains explicitly unresolved`() {
        val sequence = PracticeMusicXmlLoader().load("unresolved.musicxml", UNRESOLVED.toByteArray()).sequence

        assertEquals(1, sequence.totalSteps)
        assertEquals(null, sequence.steps.single().durationBeats)
        assertFalse(sequence.steps.single().isTimingResolved)
        assertTrue(sequence.source.timingWarnings.isNotEmpty())
    }

    @Test
    fun `MusicXML conversion is deterministic`() {
        val loader = PracticeMusicXmlLoader()
        assertEquals(loader.load("same.xml", SCORE.toByteArray()), loader.load("same.xml", SCORE.toByteArray()))
    }

    @Test
    fun `ties and supported articulations become explicit practice semantics`() {
        val sequence = PracticeMusicXmlLoader().load("articulation.musicxml", TIED_STACCATO.toByteArray()).sequence

        assertEquals(DurationComparisonReliability.Reliable, sequence.steps.first().durationComparisonReliability)
        assertTrue(sequence.steps.first().tieStart)
        assertTrue(sequence.steps.last().tieContinuation)
        assertEquals("2", sequence.steps.first().tieSemantics.combinedExpectedDurationBeats.toString())
        assertEquals(com.sheetsight.app.domain.practice.ExpectedArticulation.Staccato, sequence.steps.last().expectedArticulation)
    }

    private companion object {
        val SCORE = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                  <note><rest/><duration>1</duration><type>quarter</type></note>
                  <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                  <note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>1</duration><type>quarter</type><accidental>sharp</accidental></note>
                  <note><pitch><step>G</step><alter>-1</alter><octave>4</octave></pitch><duration>1</duration><type>quarter</type><accidental>flat</accidental></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val RHYTHM = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>2</divisions><time><beats>3</beats><beat-type>4</beat-type></time></attributes>
                <direction><sound tempo="120"/></direction>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><type>quarter</type></note>
                <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration><type>half</type></note>
                <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><type>eighth</type></note>
                <note><rest/><duration>2</duration><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()

        val UNRESOLVED = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>2</divisions></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()

        val TIED_STACCATO = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1"><attributes><divisions>1</divisions></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><tie type="start"/><type>quarter</type></note>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type>
                  <notations><tied type="stop"/><articulations><staccato/></articulations></notations>
                </note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
    }
}
