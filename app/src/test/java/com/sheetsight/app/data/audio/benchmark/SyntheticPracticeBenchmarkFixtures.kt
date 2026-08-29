package com.sheetsight.app.data.audio.benchmark

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Human-authored deterministic labels over synthesized piano-like signals. These fixtures validate
 * benchmark mechanics and safety regressions only; they are not physical-piano accuracy evidence.
 */
object SyntheticPracticeBenchmarkFixtures {
    private const val SAMPLE_RATE = 22_050
    private val pianoHarmonics = doubleArrayOf(0.82, 1.0, 0.64, 0.42, 0.28, 0.19)
    private val weakFundamentalHarmonics = doubleArrayOf(0.10, 1.0, 0.72, 0.48, 0.31, 0.20)

    val clips: List<BenchmarkClip> by lazy {
        listOf(
            clip("correct-low", BenchmarkScenario.CORRECT_ISOLATED, BenchmarkRegister.LOW, BenchmarkAttack.NORMAL,
                listOf(36), listOf(500L), listOf(note(36, 500, 900, 0.050, weakFundamentalHarmonics))),
            clip("correct-mid", BenchmarkScenario.CORRECT_ISOLATED, BenchmarkRegister.MID, BenchmarkAttack.SOFT,
                listOf(60), listOf(500L), listOf(note(60, 500, 850, 0.016))),
            clip("correct-high", BenchmarkScenario.CORRECT_ISOLATED, BenchmarkRegister.HIGH, BenchmarkAttack.NORMAL,
                listOf(84), listOf(500L), listOf(note(84, 500, 750, 0.045))),

            clip("very-soft-low", BenchmarkScenario.VERY_SOFT_EXPECTED, BenchmarkRegister.LOW, BenchmarkAttack.VERY_SOFT,
                listOf(36), listOf(500L), listOf(note(36, 500, 1_000, 0.0044, weakFundamentalHarmonics)), noise = 0.00035),
            clip("very-soft-mid", BenchmarkScenario.VERY_SOFT_EXPECTED, BenchmarkRegister.MID, BenchmarkAttack.VERY_SOFT,
                listOf(60), listOf(500L), listOf(note(60, 500, 900, 0.0044)), noise = 0.00035),
            clip("very-soft-high", BenchmarkScenario.VERY_SOFT_EXPECTED, BenchmarkRegister.HIGH, BenchmarkAttack.VERY_SOFT,
                listOf(84), listOf(500L), listOf(note(84, 500, 800, 0.0044)), noise = 0.00035),
            clip("strong-mid", BenchmarkScenario.STRONG_EXPECTED, BenchmarkRegister.MID, BenchmarkAttack.STRONG,
                listOf(64), listOf(500L), listOf(note(64, 500, 700, 0.180))),

            clip("wrong-d4", BenchmarkScenario.WRONG_ISOLATED, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60), listOf(null), listOf(note(62, 500, 800, 0.070))),
            clip("wrong-g4", BenchmarkScenario.WRONG_ISOLATED, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60), listOf(null), listOf(note(67, 500, 800, 0.070))),
            clip("neighbor-c-sharp", BenchmarkScenario.NEIGHBOR_SEMITONE, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60), listOf(null), listOf(note(61, 500, 800, 0.070))),
            clip("octave-below", BenchmarkScenario.OCTAVE_ERROR, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60), listOf(null), listOf(note(48, 500, 1_000, 0.070, weakFundamentalHarmonics))),
            clip("octave-above", BenchmarkScenario.OCTAVE_ERROR, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60), listOf(null), listOf(note(72, 500, 800, 0.070))),

            clip("repeated-restrikes", BenchmarkScenario.REPEATED_RESTRIKES, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60, 60, 60), listOf(500L, 1_100L, 1_700L), listOf(
                    note(60, 500, 330, 0.090), note(60, 1_100, 330, 0.085), note(60, 1_700, 350, 0.080)
                ), durationMillis = 2_600),
            clip("repeated-sustain", BenchmarkScenario.REPEATED_SUSTAIN, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60, 60, 60), listOf(500L, null, null), listOf(note(60, 500, 1_900, 0.085)), durationMillis = 2_700),
            clip("legato-mid", BenchmarkScenario.LEGATO_TRANSITION, BenchmarkRegister.MID, BenchmarkAttack.SOFT,
                listOf(60, 64, 67), listOf(500L, 1_000L, 1_500L), listOf(
                    note(60, 500, 900, 0.030), note(64, 1_000, 900, 0.030), note(67, 1_500, 800, 0.030)
                ), durationMillis = 2_700),

            clip("sustain-residual-only", BenchmarkScenario.SUSTAIN_RESIDUAL, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60, 72), listOf(500L, null), listOf(note(60, 500, 1_900, 0.080, weakFundamentalHarmonics)), durationMillis = 2_700),
            clip("sustain-then-new-octave", BenchmarkScenario.SUSTAIN_RESIDUAL, BenchmarkRegister.MID, BenchmarkAttack.NORMAL,
                listOf(60, 72), listOf(500L, 1_300L), listOf(
                    note(60, 500, 1_900, 0.070, weakFundamentalHarmonics), note(72, 1_300, 850, 0.075)
                ), durationMillis = 2_700),
            clip("after-long-silence", BenchmarkScenario.NOTE_AFTER_SILENCE, BenchmarkRegister.MID, BenchmarkAttack.SOFT,
                listOf(64), listOf(1_500L), listOf(note(64, 1_500, 700, 0.014)), durationMillis = 2_600),
            clip("room-noise", BenchmarkScenario.BACKGROUND_NOISE, BenchmarkRegister.MID, BenchmarkAttack.NONE,
                listOf(60), listOf(null), emptyList(), noise = 0.0030, humAmplitude = 0.0025),
            clip("silence", BenchmarkScenario.SILENCE, BenchmarkRegister.MID, BenchmarkAttack.NONE,
                listOf(60), listOf(null), emptyList())
        )
    }

    private fun note(
        midi: Int,
        onsetMillis: Long,
        durationMillis: Long,
        amplitude: Double,
        harmonics: DoubleArray = pianoHarmonics
    ) = SynthNote(midi, onsetMillis, durationMillis, amplitude, harmonics)

    private fun clip(
        id: String,
        scenario: BenchmarkScenario,
        register: BenchmarkRegister,
        attack: BenchmarkAttack,
        expected: List<Int>,
        onsets: List<Long?>,
        notes: List<SynthNote>,
        durationMillis: Long = 2_300,
        noise: Double = 0.0002,
        humAmplitude: Double = 0.0
    ): BenchmarkClip {
        val samples = FloatArray((durationMillis * SAMPLE_RATE / 1_000L).toInt())
        notes.forEach { synth -> add(samples, synth) }
        var randomState = id.hashCode().toLong() and 0x7fff_ffffL
        for (index in samples.indices) {
            randomState = (1_103_515_245L * randomState + 12_345L) and 0x7fff_ffffL
            val white = (randomState.toDouble() / 0x7fff_ffffL * 2.0 - 1.0) * noise
            val hum = humAmplitude * sin(2.0 * PI * 60.0 * index / SAMPLE_RATE)
            samples[index] = (samples[index] + white + hum).coerceIn(-0.95, 0.95).toFloat()
        }
        return BenchmarkClip(
            label = BenchmarkLabel(
                id = id,
                scenario = scenario,
                register = register,
                attack = attack,
                expectedMidiSequence = expected,
                expectedOnsetsMillis = onsets,
                performedMidi = notes.map { it.midi },
                provenance = BenchmarkProvenance.AUTHOR_DEFINED_SYNTHETIC,
                notes = "Deterministic synthesized regression fixture"
            ),
            sampleRateHz = SAMPLE_RATE,
            samples = samples
        )
    }

    private fun add(target: FloatArray, note: SynthNote) {
        val start = (note.onsetMillis * SAMPLE_RATE / 1_000L).toInt()
        val end = ((note.onsetMillis + note.durationMillis + 120L) * SAMPLE_RATE / 1_000L).toInt()
            .coerceAtMost(target.size)
        val harmonicRms = sqrt(note.harmonics.sumOf { it * it } / 2.0)
        val frequency = 440.0 * 2.0.pow((note.midi - 69) / 12.0)
        for (index in start until end) {
            val elapsedMillis = (index - start) * 1_000.0 / SAMPLE_RATE
            val envelope = when {
                elapsedMillis < 12.0 -> elapsedMillis / 12.0
                elapsedMillis <= note.durationMillis -> exp(-1.25 * elapsedMillis / note.durationMillis)
                else -> exp(-1.25) * (1.0 - (elapsedMillis - note.durationMillis) / 120.0).coerceIn(0.0, 1.0)
            }
            var value = 0.0
            note.harmonics.forEachIndexed { harmonicIndex, weight ->
                val harmonic = harmonicIndex + 1
                value += weight * sin(2.0 * PI * frequency * harmonic * index / SAMPLE_RATE + harmonic * 0.17)
            }
            target[index] += (note.amplitude * envelope * value / harmonicRms).toFloat()
        }
    }

    private data class SynthNote(
        val midi: Int,
        val onsetMillis: Long,
        val durationMillis: Long,
        val amplitude: Double,
        val harmonics: DoubleArray
    )
}
