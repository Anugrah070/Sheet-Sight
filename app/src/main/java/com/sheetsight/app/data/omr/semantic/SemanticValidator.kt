package com.sheetsight.app.data.omr.semantic

enum class SemanticValidationCode {
    EVENT_MEASURE_MISMATCH,
    UNKNOWN_STAFF,
    DUPLICATE_ASSIGNMENT,
    UNRESOLVED_PITCH,
    UNRESOLVED_DURATION,
    NON_DETERMINISTIC_ORDER,
    OVERLAPPING_MEASURES,
    UNASSIGNED_ACCIDENTAL
}

data class SemanticValidationWarning(
    val code: SemanticValidationCode,
    val message: String,
    val semanticId: String? = null,
    val sourceRefs: List<SemanticSourceRef> = emptyList()
)

object SemanticValidator {
    fun validate(score: SemanticScore): List<SemanticValidationWarning> {
        val warnings = mutableListOf<SemanticValidationWarning>()
        val staffIds = score.staffs.map { it.id }.toSet()
        val assignedSources = mutableMapOf<Pair<SemanticSourceKind, String>, String>()

        score.systems.sortedBy { it.index }.forEach { system ->
            val orderedMeasures = system.measures.sortedBy { it.index }
            orderedMeasures.zipWithNext().forEach { (left, right) ->
                if (left.boundary.right > right.boundary.left) {
                    warnings += warning(
                        SemanticValidationCode.OVERLAPPING_MEASURES,
                        "${left.id} overlaps ${right.id}",
                        right.id
                    )
                }
            }
            if (system.measures != orderedMeasures) {
                warnings += warning(
                    SemanticValidationCode.NON_DETERMINISTIC_ORDER,
                    "measures in ${system.id} are not ordered by index",
                    system.id
                )
            }

            system.measures.forEach { measure ->
                val orderedEvents = measure.events.sortedWith(semanticEventComparator)
                if (measure.events != orderedEvents) {
                    warnings += warning(
                        SemanticValidationCode.NON_DETERMINISTIC_ORDER,
                        "events in ${measure.id} are not in deterministic order",
                        measure.id
                    )
                }
                measure.events.forEach { event ->
                    validateEvent(event, measure, staffIds, warnings, assignedSources)
                    if (event is SemanticChord) {
                        if (event.duration == null) unresolvedDuration(event, warnings)
                        event.notes.forEach { note ->
                            validateEvent(note, measure, staffIds, warnings, assignedSources)
                            if (note.pitch == null) {
                                warnings += warning(
                                    SemanticValidationCode.UNRESOLVED_PITCH,
                                    "${note.id} has no resolved pitch",
                                    note.id,
                                    note.sourceRefs
                                )
                            }
                        }
                    }
                    if (event is SemanticRest && event.duration == null) {
                        unresolvedDuration(event, warnings)
                    }
                }
            }
        }
        return warnings.sortedWith(
            compareBy<SemanticValidationWarning> { it.code.ordinal }
                .thenBy { it.semanticId ?: "" }
                .thenBy { it.message }
        )
    }

    private fun validateEvent(
        event: SemanticEvent,
        measure: SemanticMeasure,
        staffIds: Set<String>,
        warnings: MutableList<SemanticValidationWarning>,
        assignedSources: MutableMap<Pair<SemanticSourceKind, String>, String>
    ) {
        if (event.measureId != measure.id) {
            warnings += warning(
                SemanticValidationCode.EVENT_MEASURE_MISMATCH,
                "${event.id} declares ${event.measureId} but is stored in ${measure.id}",
                event.id,
                event.sourceRefs
            )
        }
        if (event is SemanticNote && event.staffId !in staffIds) {
            warnings += warning(
                SemanticValidationCode.UNKNOWN_STAFF,
                "${event.id} references unknown staff ${event.staffId}",
                event.id,
                event.sourceRefs
            )
        }
        event.sourceRefs.forEach { source ->
            val prior = assignedSources.putIfAbsent(source.kind to source.id, event.id)
            if (prior != null && prior != event.id) {
                warnings += warning(
                    SemanticValidationCode.DUPLICATE_ASSIGNMENT,
                    "${source.kind}:${source.id} is assigned to both $prior and ${event.id}",
                    event.id,
                    listOf(source)
                )
            }
        }
    }

    private fun unresolvedDuration(
        event: SemanticEvent,
        warnings: MutableList<SemanticValidationWarning>
    ) {
        warnings += warning(
            SemanticValidationCode.UNRESOLVED_DURATION,
            "${event.id} has no resolved duration",
            event.id,
            event.sourceRefs
        )
    }

    private fun warning(
        code: SemanticValidationCode,
        message: String,
        id: String?,
        sources: List<SemanticSourceRef> = emptyList()
    ) = SemanticValidationWarning(code, message, id, sources)
}

internal val semanticEventComparator =
    compareBy<SemanticEvent> { it.horizontalPosition }
        .thenBy { eventTypeOrder(it) }
        .thenBy { it.id }

private fun eventTypeOrder(event: SemanticEvent): Int = when (event) {
    is SemanticClefChange -> 0
    is SemanticKeySignature -> 1
    is SemanticTimeSignature -> 2
    is SemanticChord -> 3
    is SemanticNote -> 4
    is SemanticRest -> 5
    is SemanticBarline -> 6
}
