package com.sheetsight.app.data.omr.musicxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlRhythmPlannerTest {
    @Test
    fun `oemer 0_1_8 checkpoint golden inserts sixteen units on lagging lower staff`() {
        val plan = MusicXmlRhythmPlanner.plan(
            inputs = listOf(
                input("upper-quarter", staff = 0, x = 10, duration = 16, order = 0),
                input("lower-half", staff = 1, x = 10, duration = 32, order = 1),
                input("upper-half", staff = 0, x = 30, duration = 32, order = 2)
            ),
            staffCount = 2,
            horizontalTolerance = 10.0
        )

        // Captured by executing the SHA-256-verified oemer 0.1.8 wheel's
        // build_system.py::Measure.align_symbols on the equivalent Rest input.
        assertEquals(listOf(listOf(16L, 32L), listOf(32L, 0L)), plan.slotDurationsBefore)
        assertEquals(listOf(listOf(16L, 32L), listOf(32L, 16L)), plan.slotDurationsAfter)
        val inserted = plan.entries.single { it.generatedRest }
        assertNull(inserted.eventId)
        assertEquals(1, inserted.staffIndex)
        assertEquals(30, inserted.horizontalPosition)
        assertEquals(32L, inserted.onsetUnits)
        assertEquals(16L, inserted.durationUnits)
    }

    @Test
    fun `same slot events start together while each staff keeps its own cursor`() {
        val plan = MusicXmlRhythmPlanner.plan(
            inputs = listOf(
                input("upper-1", staff = 0, x = 10, duration = 16, order = 0),
                input("lower-1", staff = 1, x = 10, duration = 16, order = 1),
                input("upper-2", staff = 0, x = 30, duration = 8, order = 2),
                input("lower-2", staff = 1, x = 30, duration = 8, order = 3)
            ),
            staffCount = 2,
            horizontalTolerance = 10.0
        )
        val byId = plan.entries.associateBy { it.eventId }

        assertEquals(0L, byId.getValue("upper-1").onsetUnits)
        assertEquals(0L, byId.getValue("lower-1").onsetUnits)
        assertEquals(16L, byId.getValue("upper-2").onsetUnits)
        assertEquals(16L, byId.getValue("lower-2").onsetUnits)
        assertTrue(plan.entries.none { it.generatedRest })
    }

    private fun input(id: String, staff: Int, x: Int, duration: Long, order: Int) =
        MusicXmlRhythmInput(id, staff, x, duration, order)
}
