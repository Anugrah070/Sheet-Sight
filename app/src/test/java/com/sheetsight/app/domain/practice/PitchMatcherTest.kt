package com.sheetsight.app.domain.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchMatcherTest {
    private val matcher = PitchMatcher()
    private val c4Step = step(PracticePitch('C', 0, 4))

    @Test fun `expected C4 and detected C4 is correct`() = assertEquals(MatchState.CorrectPitchOnly, matcher.match(c4Step, detected('C')))
    @Test fun `expected C4 and detected D4 is incorrect`() = assertEquals(MatchState.WrongPitch, matcher.match(c4Step, detected('D')))
    @Test fun `low confidence does not match`() = assertEquals(MatchState.LowConfidence, matcher.match(c4Step, detected('C', confidence = 0.2)))
    @Test fun `silence does not match`() = assertEquals(MatchState.LowConfidence, matcher.match(c4Step, null))
    @Test fun `pitch inside cents tolerance is correct`() = assertEquals(MatchState.CorrectPitchOnly, matcher.match(c4Step, detected('C', cents = 34.9)))
    @Test fun `pitch outside cents tolerance is incorrect`() = assertEquals(MatchState.WrongPitch, matcher.match(c4Step, detected('C', cents = 35.1)))
    @Test fun `enharmonic spelling compares by sounding pitch`() = assertEquals(
        MatchState.CorrectPitchOnly,
        matcher.match(step(PracticePitch('G', -1, 4)), detected('F', alteration = 1))
    )
    @Test fun `monophonic detection never completes chord`() = assertEquals(
        MatchState.Unsupported,
        matcher.match(step(PracticePitch('C', 0, 4), PracticePitch('E', 0, 4)), detected('C'))
    )

    private fun step(vararg pitches: PracticePitch) = PracticeStep(0, "1", listOf(1), pitches.toList(), listOf("id"), 0)
    private fun detected(step: Char, alteration: Int = 0, cents: Double = 0.0, confidence: Double = 0.95) =
        DetectedPitch(261.63, PracticePitch(step, alteration, 4), cents, confidence, 1L, 0.1)
}
