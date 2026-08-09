package com.sheetsight.app.domain.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectedDurationResolverTest {
    @Test
    fun `quarter duration converts at 60 and 120 BPM`() {
        val quarter = note(MusicalBeat.of(1))

        assertEquals(1_000L, ExpectedDurationResolver.resolve(quarter, 60)?.milliseconds)
        assertEquals(500L, ExpectedDurationResolver.resolve(quarter, 120)?.milliseconds)
    }

    @Test
    fun `half eighth and dotted durations use normalized beats`() {
        assertEquals(2_000L, ExpectedDurationResolver.resolve(note(MusicalBeat.of(2)), 60)?.milliseconds)
        assertEquals(500L, ExpectedDurationResolver.resolve(note(MusicalBeat.of(1, 2)), 60)?.milliseconds)
        assertEquals(1_500L, ExpectedDurationResolver.resolve(note(MusicalBeat.of(3, 2)), 60)?.milliseconds)
    }

    @Test
    fun `unresolved or semantically ambiguous duration remains unknown`() {
        assertNull(ExpectedDurationResolver.resolve(note(null), 60))
        assertNull(
            ExpectedDurationResolver.resolve(
                note(MusicalBeat.of(1)).copy(
                    durationComparisonReliability = DurationComparisonReliability.UnknownArticulation
                ),
                60
            )
        )
    }

    private fun note(duration: MusicalBeat?) = PracticeStep(
        index = 0,
        measureNumber = "1",
        staffs = listOf(1),
        expectedPitches = listOf(PracticePitch('C', 0, 4)),
        sourceNoteIds = listOf("0"),
        onsetDivisions = 0,
        startBeat = MusicalBeat.ZERO,
        durationBeats = duration,
        measureBeat = MusicalBeat.ZERO
    )
}
