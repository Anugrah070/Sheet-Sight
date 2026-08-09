package com.sheetsight.app.data.practice

import com.sheetsight.app.domain.practice.MAX_PRACTICE_BPM
import com.sheetsight.app.domain.practice.MIN_PRACTICE_BPM
import com.sheetsight.app.domain.practice.DurationComparisonReliability
import com.sheetsight.app.domain.practice.ExpectedArticulation
import com.sheetsight.app.domain.practice.MusicalBeat
import com.sheetsight.app.domain.practice.PracticeMeter
import com.sheetsight.app.domain.practice.PracticePitch
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.domain.practice.PracticeSource
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.domain.practice.PracticeTimingResolution
import com.sheetsight.app.domain.practice.PracticeTieSemantics
import com.sheetsight.app.ui.editor.notation.NotationArticulation
import com.sheetsight.app.ui.editor.notation.NotationChord
import com.sheetsight.app.ui.editor.notation.NotationEvent
import com.sheetsight.app.ui.editor.notation.NotationMeasure
import com.sheetsight.app.ui.editor.notation.NotationRest
import com.sheetsight.app.ui.editor.notation.NotationSourceIds
import kotlin.math.roundToInt

/** Converts the existing MusicXML notation model into an immutable onset-ordered practice timeline. */
object PracticeSequenceFactory {
    fun create(
        fileName: String,
        measures: List<NotationMeasure>,
        detectedTempoBpm: Double? = null,
        durationSemanticsReliable: Boolean = true
    ): PracticeSequence {
        val warnings = linkedSetOf<String>()
        if (!durationSemanticsReliable) {
            warnings += "Some MusicXML note semantics are unsupported; affected duration feedback remains Unknown."
        }
        val detectedTempo = detectedTempoBpm
            ?.takeIf { it.isFinite() }
            ?.roundToInt()
            ?.takeIf { it in MIN_PRACTICE_BPM..MAX_PRACTICE_BPM }
        if (detectedTempoBpm != null && detectedTempo == null) {
            warnings += "MusicXML tempo is outside the supported $MIN_PRACTICE_BPM-$MAX_PRACTICE_BPM BPM range."
        }

        val unindexedSteps = mutableListOf<UnindexedStep>()
        var measureStart: MusicalBeat? = MusicalBeat.ZERO
        measures.forEach { measure ->
            val events = measure.staffs.flatMap { staff ->
                staff.events.map { event -> SourceEvent(staff.number, event) }
            }
            val grouped = events
                .groupBy { source ->
                    source.measureBeat?.let(EventOnsetKey::Resolved)
                        ?: EventOnsetKey.Unresolved(source.event.sourceOrder, source.staff)
                }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<EventOnsetKey, List<SourceEvent>>> {
                        if (it.key is EventOnsetKey.Resolved) 0 else 1
                    }.thenBy { (it.key as? EventOnsetKey.Resolved)?.beat ?: MusicalBeat.ZERO }
                        .thenBy { it.value.minOf { source -> source.event.sourceOrder } }
                        .thenBy { it.value.minOf { source -> source.staff } }
                )

            grouped.forEach { (_, simultaneous) ->
                val ordered = simultaneous.sortedWith(
                    compareBy<SourceEvent> { it.event.sourceOrder }.thenBy { it.staff }
                )
                val chords = ordered.filter { it.event is NotationChord }
                val rests = ordered.filter { it.event is NotationRest }
                val pitchSources = chords.flatMap { source ->
                    val chord = source.event as NotationChord
                    chord.pitches.mapIndexed { pitchIndex, pitch ->
                        PitchSource(
                            pitch = PracticePitch(pitch.step, pitch.alteration, pitch.octave),
                            sourceId = NotationSourceIds.note(
                                measure.sourceIndex,
                                measure.number,
                                source.staff,
                                chord.sourceOrder,
                                pitchIndex
                            ),
                            staff = source.staff,
                            voice = chord.voice,
                            semantics = chord.noteSemantics.getOrNull(pitchIndex)
                        )
                    }
                }
                val pitches = pitchSources.map { it.pitch }
                val isRest = pitches.isEmpty() && rests.isNotEmpty()
                if (pitches.isEmpty() && !isRest) return@forEach

                val measureBeat = ordered.mapNotNull { it.measureBeat }.distinct().singleOrNull()
                val startBeat = if (measureStart != null && measureBeat != null) measureStart!! + measureBeat else null
                val durationSources = if (isRest) rests else chords
                val durationBeats = durationSources
                    .takeIf { sources -> sources.isNotEmpty() && sources.all { it.durationBeats != null } }
                    ?.mapNotNull { it.durationBeats }
                    ?.minOrNull()
                val resolution = when {
                    startBeat == null || measureBeat == null -> PracticeTimingResolution.UnresolvedPosition
                    durationBeats == null -> PracticeTimingResolution.UnresolvedDuration
                    else -> PracticeTimingResolution.Resolved
                }
                val reason = when (resolution) {
                    PracticeTimingResolution.Resolved -> null
                    PracticeTimingResolution.UnresolvedDuration ->
                        "Measure ${measure.number} contains a note/rest without usable MusicXML duration or divisions."
                    PracticeTimingResolution.UnresolvedPosition ->
                        "Measure ${measure.number} has no deterministic absolute beat position."
                }
                reason?.let(warnings::add)

                unindexedSteps += UnindexedStep(
                    measureNumber = measure.number,
                    staffs = ordered.map { it.staff }.distinct(),
                    pitches = pitches,
                    sourceIds = pitchSources.map { it.sourceId },
                    pitchSources = pitchSources,
                    onsetDivisions = ordered.minOf { it.event.onsetDivisions },
                    startBeat = startBeat,
                    durationBeats = durationBeats,
                    measureBeat = measureBeat,
                    isRest = isRest,
                    timingResolution = resolution,
                    unresolvedTimingReason = reason,
                    durationComparisonReliability = when {
                        durationBeats == null -> DurationComparisonReliability.UnresolvedDuration
                        !durationSemanticsReliable && !isRest -> DurationComparisonReliability.UnknownArticulation
                        pitchSources.any { it.semantics?.hasUnknownNotation == true } ->
                            DurationComparisonReliability.UnknownArticulation
                        else -> DurationComparisonReliability.Reliable
                    },
                    expectedArticulation = resolveArticulation(pitchSources),
                    hasSlur = pitchSources.any { it.semantics?.slurStart == true || it.semantics?.slurStop == true }
                )
            }

            val measureDuration = events
                .takeIf { sources -> sources.isNotEmpty() && sources.all { it.measureBeat != null && it.durationBeats != null } }
                ?.maxOfOrNull { source -> source.measureBeat!! + source.durationBeats!! }
            measureStart = if (measureStart != null && measureDuration != null) measureStart!! + measureDuration else null
            if (measureDuration == null && events.isNotEmpty()) {
                warnings += "Timing after measure ${measure.number} is unresolved."
            }
        }

        val initialMeter = measures.asSequence()
            .flatMap { it.staffs.asSequence() }
            .mapNotNull { it.timeSignature }
            .firstOrNull()
            ?.let { PracticeMeter(it.beats, it.beatType) }
        val tiedSteps = resolveTies(unindexedSteps, warnings)
        return PracticeSequence(
            source = PracticeSource(
                fileName = fileName,
                measureCount = measures.size,
                detectedTempoBpm = detectedTempo,
                initialMeter = initialMeter,
                timingWarnings = warnings.toList()
            ),
            steps = tiedSteps.mapIndexed { index, step ->
                PracticeStep(
                    index = index,
                    measureNumber = step.measureNumber,
                    staffs = step.staffs,
                    expectedPitches = step.pitches,
                    sourceNoteIds = step.sourceIds,
                    onsetDivisions = step.onsetDivisions,
                    startBeat = step.startBeat,
                    durationBeats = step.durationBeats,
                    measureBeat = step.measureBeat,
                    isRest = step.isRest,
                    timingResolution = step.timingResolution,
                    unresolvedTimingReason = step.unresolvedTimingReason,
                    durationComparisonReliability = step.durationComparisonReliability,
                    tieSemantics = step.tieSemantics,
                    expectedArticulation = step.expectedArticulation,
                    hasSlur = step.hasSlur
                )
            }
        )
    }

    private fun resolveTies(
        steps: List<UnindexedStep>,
        warnings: MutableSet<String>
    ): List<UnindexedStep> {
        val resolved = steps.toMutableList()
        val active = mutableMapOf<TieKey, ActiveTie>()
        steps.forEachIndexed { index, step ->
            val source = step.pitchSources.singleOrNull() ?: return@forEachIndexed
            val semantics = source.semantics ?: return@forEachIndexed
            if (!semantics.tieStart && !semantics.tieStop) return@forEachIndexed
            val key = TieKey(source.staff, source.voice, source.pitch.midiNumber)
            val current = active[key]
            when {
                semantics.tieStop && current == null -> markTieUnresolved(resolved, index)
                semantics.tieStop && current != null -> {
                    current.indices += index
                    if (semantics.tieStart) {
                        // A stop+start note is a continuation in a longer chain.
                    } else {
                        applyResolvedTie(resolved, current)
                        active.remove(key)
                    }
                }
                semantics.tieStart && current == null -> active[key] = ActiveTie(
                    groupId = "tie:${source.sourceId}",
                    indices = mutableListOf(index)
                )
                semantics.tieStart -> markTieUnresolved(resolved, index)
            }
        }
        active.values.forEach { dangling -> dangling.indices.forEach { markTieUnresolved(resolved, it) } }
        if (resolved.any { !it.tieSemantics.resolved }) {
            warnings += "Malformed or pitch-mismatched MusicXML tie; affected duration feedback remains Unknown."
        }
        return resolved
    }

    private fun applyResolvedTie(steps: MutableList<UnindexedStep>, tie: ActiveTie) {
        val durations = tie.indices.map { steps[it].durationBeats }
        val combined = durations.takeIf { values -> values.all { it != null } }
            ?.filterNotNull()
            ?.fold(MusicalBeat.ZERO, MusicalBeat::plus)
        tie.indices.forEachIndexed { chainIndex, stepIndex ->
            val original = steps[stepIndex]
            steps[stepIndex] = original.copy(
                durationComparisonReliability = if (combined == null) {
                    DurationComparisonReliability.UnresolvedDuration
                } else original.durationComparisonReliability,
                tieSemantics = PracticeTieSemantics(
                    groupId = tie.groupId,
                    tieStart = chainIndex == 0,
                    tieContinuation = chainIndex > 0,
                    tieEnd = chainIndex == tie.indices.lastIndex,
                    combinedExpectedDurationBeats = if (chainIndex == 0) combined else null,
                    resolved = combined != null
                )
            )
        }
    }

    private fun markTieUnresolved(steps: MutableList<UnindexedStep>, index: Int) {
        val original = steps[index]
        steps[index] = original.copy(
            durationComparisonReliability = DurationComparisonReliability.UnresolvedTie,
            tieSemantics = PracticeTieSemantics(resolved = false)
        )
    }

    private fun resolveArticulation(pitches: List<PitchSource>): ExpectedArticulation {
        if (pitches.isEmpty()) return ExpectedArticulation.Normal
        val markings = pitches.flatMap { it.semantics?.articulations.orEmpty() }.toSet()
        if (pitches.any { it.semantics?.hasUnknownNotation == true }) return ExpectedArticulation.Unknown
        return when {
            NotationArticulation.FERMATA in markings -> ExpectedArticulation.Fermata
            markings == setOf(NotationArticulation.STACCATO) -> ExpectedArticulation.Staccato
            markings == setOf(NotationArticulation.TENUTO) -> ExpectedArticulation.Tenuto
            markings == setOf(NotationArticulation.ACCENT) -> ExpectedArticulation.Accent
            markings == setOf(NotationArticulation.STRONG_ACCENT) -> ExpectedArticulation.StrongAccent
            markings == setOf(NotationArticulation.STACCATISSIMO) -> ExpectedArticulation.Staccatissimo
            markings.isEmpty() -> ExpectedArticulation.Normal
            else -> ExpectedArticulation.Unknown
        }
    }

    private data class SourceEvent(val staff: Int, val event: NotationEvent) {
        val measureBeat: MusicalBeat? = event.divisionsPerQuarter
            ?.takeIf { it > 0 }
            ?.let { MusicalBeat.of(event.onsetDivisions.toLong(), it.toLong()) }
        val durationBeats: MusicalBeat? = event.durationDivisions
            ?.takeIf { it > 0 }
            ?.let { duration ->
                event.divisionsPerQuarter?.takeIf { it > 0 }
                    ?.let { divisions -> MusicalBeat.of(duration.toLong(), divisions.toLong()) }
            }
    }

    private data class PitchSource(
        val pitch: PracticePitch,
        val sourceId: String,
        val staff: Int,
        val voice: Int,
        val semantics: com.sheetsight.app.ui.editor.notation.NotationNoteSemantics?
    )

    private data class TieKey(val staff: Int, val voice: Int, val midi: Int)
    private data class ActiveTie(val groupId: String, val indices: MutableList<Int>)

    private sealed interface EventOnsetKey {
        data class Resolved(val beat: MusicalBeat) : EventOnsetKey
        data class Unresolved(val sourceOrder: Int, val staff: Int) : EventOnsetKey
    }

    private data class UnindexedStep(
        val measureNumber: String,
        val staffs: List<Int>,
        val pitches: List<PracticePitch>,
        val sourceIds: List<String>,
        val pitchSources: List<PitchSource>,
        val onsetDivisions: Int,
        val startBeat: MusicalBeat?,
        val durationBeats: MusicalBeat?,
        val measureBeat: MusicalBeat?,
        val isRest: Boolean,
        val timingResolution: PracticeTimingResolution,
        val unresolvedTimingReason: String?,
        val durationComparisonReliability: DurationComparisonReliability,
        val tieSemantics: PracticeTieSemantics = PracticeTieSemantics(),
        val expectedArticulation: ExpectedArticulation,
        val hasSlur: Boolean
    )
}
