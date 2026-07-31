package com.sheetsight.app.data.omr.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticValidatorTest {
    @Test
    fun `duplicate source assignment returns structured warning`() {
        val shared = SemanticSourceRef(SemanticSourceKind.REST, "shared")
        val measure = measure(
            listOf(
                rest("rest-1", shared, SemanticDuration(1, 4)),
                rest("rest-2", shared, SemanticDuration(1, 4))
            )
        )

        val warnings = SemanticValidator.validate(score(measure))

        assertTrue(warnings.any { it.code == SemanticValidationCode.DUPLICATE_ASSIGNMENT })
    }

    @Test
    fun `unresolved pitch returns structured warning`() {
        val note = SemanticNote(
            "note-1",
            "measure-0",
            "staff-0",
            20,
            listOf(SemanticSourceRef(SemanticSourceKind.NOTEHEAD, "1")),
            null,
            null
        )
        val chord = chord(notes = listOf(note), duration = SemanticDuration(1, 4))

        val warnings = SemanticValidator.validate(score(measure(listOf(chord))))

        assertTrue(warnings.any { it.code == SemanticValidationCode.UNRESOLVED_PITCH })
    }

    @Test
    fun `unresolved duration returns structured warning`() {
        val warnings = SemanticValidator.validate(score(measure(listOf(rest("rest-1", duration = null)))))

        assertEquals(
            listOf(SemanticValidationCode.UNRESOLVED_DURATION),
            warnings.map { it.code }
        )
    }

    private fun score(measure: SemanticMeasure): SemanticScore {
        val staff = SemanticStaff(
            "staff-0",
            0,
            "system-0",
            SemanticSourceRef(SemanticSourceKind.STAFF_GRID, "staff")
        )
        val system = SemanticSystem(
            "system-0",
            0,
            listOf(staff),
            listOf(measure),
            SemanticBounds(0, 0, 100, 100),
            SemanticSourceRef(SemanticSourceKind.STAFF_GRID, "system")
        )
        return SemanticScore(listOf(SemanticPart("part-0", listOf(system))))
    }

    private fun measure(events: List<SemanticEvent>) = SemanticMeasure(
        "measure-0",
        0,
        "system-0",
        SemanticMeasureBoundary(
            0,
            100,
            MeasureBoundaryEvidence.STAFF_EXTENT,
            MeasureBoundaryEvidence.STAFF_EXTENT
        ),
        events
    )

    private fun rest(
        id: String,
        source: SemanticSourceRef = SemanticSourceRef(SemanticSourceKind.REST, id),
        duration: SemanticDuration?
    ) = SemanticRest(
        id,
        "measure-0",
        "staff-0",
        20,
        listOf(source),
        duration,
        if (duration == null) SemanticRhythmState.UNRESOLVED else SemanticRhythmState.RESOLVED,
        0
    )

    private fun chord(
        notes: List<SemanticNote>,
        duration: SemanticDuration?
    ) = SemanticChord(
        "chord-0",
        "measure-0",
        "staff-0",
        20,
        listOf(SemanticSourceRef(SemanticSourceKind.NOTE_GROUP, "0")),
        notes,
        duration,
        SemanticRhythmState.RESOLVED,
        SemanticStemDirection.UP,
        SemanticBeamInfo(0, 0),
        0
    )
}
