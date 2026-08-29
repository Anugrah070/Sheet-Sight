package com.sheetsight.app.data.audio.recognition

import com.sheetsight.app.domain.practice.PracticePitch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedPitchMatcherTest {
    private val matcher = ExpectedPitchMatcher()

    @Test
    fun `expected pitch confidence dominates a neighboring semitone`() {
        val c4 = PracticePitch.fromMidi(60)
        val evidence = matcher.analyze(harmonicStack(listOf(60)), listOf(c4), 100L).expected.single()
        assertTrue(evidence.confidence >= 0.52)
        assertTrue(evidence.competingScore < evidence.harmonicScore + 0.2)
        assertTrue(evidence.harmonicCoverage >= 2)
    }

    @Test
    fun `neighboring wrong note is rejected for a monophonic expectation`() {
        val expectedC4 = PracticePitch.fromMidi(60)
        val evidence = matcher.analyze(
            harmonicStack(listOf(61)),
            listOf(expectedC4),
            100L
        ).expected.single()
        assertTrue(evidence.toString(), evidence.confidence < 0.52)
    }

    @Test
    fun `major chord returns independent evidence for every expected pitch`() {
        val expected = listOf(60, 64, 67).map(PracticePitch::fromMidi)
        val evidence = matcher.analyze(harmonicStack(listOf(60, 64, 67)), expected, 100L).expected
        assertEquals(setOf(60, 64, 67), evidence.map { it.expectedPitch.midiNumber }.toSet())
        assertTrue(evidence.joinToString(), evidence.all { it.confidence >= 0.52 })
    }

    @Test
    fun `inharmonic bass stack is accepted without integer harmonic assumption`() {
        val expected = PracticePitch.fromMidi(33)
        val evidence = matcher.analyze(
            harmonicStack(listOf(33), coefficient = 3e-4),
            listOf(expected),
            100L
        ).expected.single()
        assertTrue(evidence.toString(), evidence.confidence >= 0.52)
        assertTrue(evidence.toString(), evidence.inharmonicityCoefficient > 0.0)
    }

    private fun harmonicStack(midis: List<Int>, coefficient: Double = 0.0): FloatArray {
        return FloatArray(SIZE) { index ->
            var sample = 0.0
            for (midi in midis) {
                val first = 440.0 * 2.0.pow((midi - 69) / 12.0)
                for (partial in 1..7) {
                    val frequency = PianoPartialTemplate.partialFrequency(first, partial, coefficient)
                    sample += 0.12 / (midis.size * partial) * sin(2.0 * PI * frequency * index / SAMPLE_RATE)
                }
            }
            sample.toFloat()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val SIZE = 8_192
    }
}
