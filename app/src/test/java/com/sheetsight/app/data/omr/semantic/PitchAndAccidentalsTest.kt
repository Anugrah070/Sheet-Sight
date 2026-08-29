package com.sheetsight.app.data.omr.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PitchAndAccidentalsTest {
    @Test
    fun `treble clef positions map from space below staff`() {
        assertPitch(PitchStep.D, 4, PitchAssigner.assign(0, SemanticClef.TREBLE))
        assertPitch(PitchStep.E, 4, PitchAssigner.assign(1, SemanticClef.TREBLE))
        assertPitch(PitchStep.F, 5, PitchAssigner.assign(9, SemanticClef.TREBLE))
    }

    @Test
    fun `bass clef positions map from space below staff`() {
        assertPitch(PitchStep.F, 2, PitchAssigner.assign(0, SemanticClef.BASS))
        assertPitch(PitchStep.G, 2, PitchAssigner.assign(1, SemanticClef.BASS))
        assertPitch(PitchStep.A, 3, PitchAssigner.assign(9, SemanticClef.BASS))
    }

    @Test
    fun `ledger positions extend in both directions`() {
        assertPitch(PitchStep.C, 6, PitchAssigner.assign(13, SemanticClef.TREBLE))
        assertPitch(PitchStep.C, 4, PitchAssigner.assign(-1, SemanticClef.TREBLE))
    }

    @Test
    fun `pitch remains unresolved without clef`() {
        assertNull(PitchAssigner.assign(4, null))
    }

    @Test
    fun `implausible ledger positions remain unresolved without evidence`() {
        assertNull(PitchAssigner.assign(-20, SemanticClef.TREBLE))
        assertNull(PitchAssigner.assign(24, SemanticClef.BASS))
    }

    @Test
    fun `key signature applies by diatonic step`() {
        val state = MeasureAccidentalState(mapOf(PitchStep.F to AccidentalAlteration.SHARP))

        assertEquals(AccidentalAlteration.SHARP, state.alterationFor(PitchStep.F, 4))
        assertEquals(AccidentalAlteration.SHARP, state.alterationFor(PitchStep.F, 5))
        assertEquals(AccidentalAlteration.NATURAL, state.alterationFor(PitchStep.C, 4))
    }

    @Test
    fun `local accidental is octave-specific`() {
        val state = MeasureAccidentalState()
        state.applyLocal(PitchStep.C, 4, AccidentalAlteration.FLAT)

        assertEquals(AccidentalAlteration.FLAT, state.alterationFor(PitchStep.C, 4))
        assertEquals(AccidentalAlteration.NATURAL, state.alterationFor(PitchStep.C, 5))
    }

    @Test
    fun `natural cancels key signature for current measure`() {
        val state = MeasureAccidentalState(mapOf(PitchStep.F to AccidentalAlteration.SHARP))
        state.applyLocal(PitchStep.F, 4, AccidentalAlteration.NATURAL)

        assertEquals(AccidentalAlteration.NATURAL, state.alterationFor(PitchStep.F, 4))
    }

    @Test
    fun `measure reset removes locals and restores key signature`() {
        val state = MeasureAccidentalState(mapOf(PitchStep.F to AccidentalAlteration.SHARP))
        state.applyLocal(PitchStep.F, 4, AccidentalAlteration.NATURAL)
        state.resetMeasure()

        assertEquals(AccidentalAlteration.SHARP, state.alterationFor(PitchStep.F, 4))
    }

    private fun assertPitch(step: PitchStep, octave: Int, pitch: SemanticPitch?) {
        assertEquals(step, pitch?.step)
        assertEquals(octave, pitch?.octave)
    }
}
