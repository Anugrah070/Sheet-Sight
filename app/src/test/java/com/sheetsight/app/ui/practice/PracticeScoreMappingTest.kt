package com.sheetsight.app.ui.practice

import com.sheetsight.app.domain.practice.StablePitchEvent
import com.sheetsight.app.domain.practice.NoteOnsetEvidence
import com.sheetsight.app.data.practice.PracticeMusicXmlLoader
import com.sheetsight.app.domain.practice.DetectedPitch
import com.sheetsight.app.domain.practice.PracticeEngine
import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.domain.practice.PracticeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PracticeScoreMappingTest {
    private val loaded = PracticeMusicXmlLoader().load("mapping.musicxml", SCORE.toByteArray())
    private val index = PracticeRenderedScoreIndex.create(loaded.notation, systemWidthPx = 1000f, density = 1f)

    @Test
    fun `PracticeStep maps to the correct rendered note and associations`() {
        val target = requireNotNull(index.resolve(loaded.sequence.steps[0]))

        assertEquals(0, target.practiceStepIndex)
        assertEquals("1", target.measureNumber)
        assertEquals(listOf(1), target.staffNumbers)
        assertEquals(0, target.systemIndex)
        assertEquals(0, target.pageIndex)
        assertEquals(loaded.sequence.steps[0].sourceNoteIds, target.noteheads.map { it.sourceId })
    }

    @Test
    fun `chord step maps every notehead into one target`() {
        val chord = requireNotNull(index.resolve(loaded.sequence.steps[2]))

        assertEquals(listOf("E4", "G4"), loaded.sequence.steps[2].expectedPitches.map { it.displayName })
        assertEquals(2, chord.noteheads.size)
        assertEquals(2, chord.sourceIds.size)
        assertEquals(setOf(1), chord.noteheads.map { it.staffNumber }.toSet())
        assertEquals(1, chord.systemIndex)
        assertEquals(1, chord.pageIndex)
    }

    @Test
    fun `missing rendered source ID is safely unresolved`() {
        val missing = loaded.sequence.steps[0].copy(sourceNoteIds = listOf("missing"))
        assertNull(index.resolve(missing))
    }

    @Test
    fun `repeated lookup is deterministic`() {
        val step = loaded.sequence.steps[1]
        assertEquals(index.resolve(step), index.resolve(step))
    }

    @Test
    fun `incorrect note keeps highlight and correct note changes it exactly once`() {
        val engine = PracticeEngine().apply { load(loaded.sequence); start() }
        val initial = PracticeDisplayState.currentHighlight(engine.progress, index)

        engine.onPitchEvent(stable('B'))
        assertEquals(initial, PracticeDisplayState.currentHighlight(engine.progress, index))

        engine.onPitchEvent(stable('C'))
        val afterCorrect = PracticeDisplayState.currentHighlight(engine.progress, index)
        assertNotNull(afterCorrect)
        assertNotEquals(initial, afterCorrect)

        engine.onPitchEvent(stable('C'))
        assertEquals(afterCorrect, PracticeDisplayState.currentHighlight(engine.progress, index))
    }

    @Test
    fun `sustained repeated note cannot jump multiple highlights`() {
        val first = loaded.sequence.steps[0]
        val second = loaded.sequence.steps[1].copy(
            index = 1,
            expectedPitches = first.expectedPitches
        )
        val repeated = PracticeSequence(PracticeSource("repeat.xml", 1), listOf(first, second))
        val engine = PracticeEngine().apply { load(repeated); start() }

        engine.onPitchEvent(stable('C'))
        val secondHighlight = PracticeDisplayState.currentHighlight(engine.progress, index)
        engine.onPitchEvent(stable('C'))

        assertEquals(1, engine.progress.currentStepIndex)
        assertEquals(secondHighlight, PracticeDisplayState.currentHighlight(engine.progress, index))
    }

    @Test
    fun `completed state removes active target`() {
        val single = PracticeSequence(PracticeSource("one.xml", 1), listOf(loaded.sequence.steps.first()))
        val engine = PracticeEngine().apply { load(single); start(); onPitchEvent(stable('C')) }

        assertEquals(PracticePhase.Completed, engine.progress.phase)
        assertNull(PracticeDisplayState.currentHighlight(engine.progress, index))
    }

    @Test
    fun `visible target produces no scroll request`() {
        val target = requireNotNull(index.resolve(loaded.sequence.steps[0]))
        val viewport = PracticeViewport(
            widthPx = 1000f,
            heightPx = 300f,
            horizontalOffsetPx = 0f,
            visibleSystems = listOf(VisibleSystem(0, 0f, 150f))
        )

        assertNull(PracticeAutoFollow.request(target, viewport, edgePaddingPx = 4f))
    }

    @Test
    fun `target outside the horizontal viewport produces a scroll request`() {
        val target = requireNotNull(index.resolve(loaded.sequence.steps[0]))
        val viewport = PracticeViewport(
            widthPx = 180f,
            heightPx = 300f,
            horizontalOffsetPx = 700f,
            visibleSystems = listOf(VisibleSystem(0, 0f, 150f))
        )

        val request = PracticeAutoFollow.request(target, viewport, edgePaddingPx = 4f)
        assertEquals(0, request?.systemIndex)
        assertNotNull(request?.horizontalOffsetPx)
    }

    @Test
    fun `outside target and next system request the correct system`() {
        val nextSystem = requireNotNull(index.resolve(loaded.sequence.steps[2]))
        val viewport = PracticeViewport(
            widthPx = 1000f,
            heightPx = 300f,
            horizontalOffsetPx = 0f,
            visibleSystems = listOf(VisibleSystem(0, 0f, 150f))
        )

        val request = PracticeAutoFollow.request(nextSystem, viewport, edgePaddingPx = 4f)
        assertEquals(1, request?.systemIndex)
    }

    @Test
    fun `manual navigation decisions do not mutate PracticeStep`() {
        val engine = PracticeEngine().apply { load(loaded.sequence); start() }
        val before = engine.progress
        val target = requireNotNull(PracticeDisplayState.currentHighlight(before, index))
        PracticeAutoFollow.request(
            target,
            PracticeViewport(400f, 200f, 400f, emptyList())
        )

        assertEquals(before, engine.progress)
        assertEquals(target, PracticeDisplayState.currentHighlight(engine.progress, index))
    }

    private fun stable(step: Char) = StablePitchEvent.Stable(
        pitch = DetectedPitch(
            frequencyHz = 261.63,
            nearestPitch = PracticePitch(step, 0, 4),
            centsOffset = 0.0,
            confidence = 0.95,
            timestampMillis = 1L,
            signalLevel = 0.1
        ),
        onsetEvidence = NoteOnsetEvidence.InitialAttack
    )

    private companion object {
        val SCORE = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>1</divisions>
                    <clef><sign>G</sign><line>2</line></clef>
                  </attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                </measure>
                <measure number="2">
                  <print new-system="yes" new-page="yes"/>
                  <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                  <note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()
    }
}
