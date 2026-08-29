package com.sheetsight.app.data.omr.semantic

object PitchAssigner {
    fun assign(staffPosition: Int, clef: SemanticClef?): SemanticPitch? {
        clef ?: return null
        if (staffPosition !in MIN_POSITION_WITHOUT_LEDGER_EVIDENCE..MAX_POSITION_WITHOUT_LEDGER_EVIDENCE) {
            return null
        }
        val (baseStep, baseOctave) = when (clef) {
            SemanticClef.TREBLE -> PitchStep.D to 4
            SemanticClef.BASS -> PitchStep.F to 2
        }
        val baseIndex = baseOctave * PitchStep.entries.size + baseStep.ordinal
        val diatonicIndex = baseIndex + staffPosition
        val octave = Math.floorDiv(diatonicIndex, PitchStep.entries.size)
        val step = PitchStep.entries[Math.floorMod(diatonicIndex, PitchStep.entries.size)]
        return SemanticPitch(step, octave, staffPosition, AccidentalAlteration.NATURAL)
    }

    // The current semantic seam has no retained ledger-line evidence. Keep
    // ordinary piano ledger notes, but leave more extreme assignments
    // unresolved instead of exporting impossible octaves. A future evidence
    // object may widen this range for a specific note.
    private const val MIN_POSITION_WITHOUT_LEDGER_EVIDENCE = -5
    private const val MAX_POSITION_WITHOUT_LEDGER_EVIDENCE = 15
}

/** Mutable construction helper whose state is copied into immutable semantic notes. */
class MeasureAccidentalState(
    keySignature: Map<PitchStep, AccidentalAlteration> = emptyMap()
) {
    private var key = keySignature.toMap()
    private val local = mutableMapOf<Pair<PitchStep, Int>, AccidentalAlteration>()

    fun updateKeySignature(alterations: Map<PitchStep, AccidentalAlteration>) {
        key = alterations.toMap()
        local.clear()
    }

    fun applyLocal(step: PitchStep, octave: Int, alteration: AccidentalAlteration) {
        local[step to octave] = alteration
    }

    fun alterationFor(step: PitchStep, octave: Int): AccidentalAlteration =
        local[step to octave] ?: key[step] ?: AccidentalAlteration.NATURAL

    fun resetMeasure() {
        local.clear()
    }
}
