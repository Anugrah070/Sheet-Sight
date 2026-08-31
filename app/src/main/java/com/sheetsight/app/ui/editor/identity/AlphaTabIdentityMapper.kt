@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor.identity

import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Note
import alphaTab.model.Score
import java.util.IdentityHashMap

data class AlphaTabElementRef(
    val trackIndex: Int,
    val staffIndex: Int,
    val barIndex: Int,
    val voiceIndex: Int? = null,
    val beatIndex: Int? = null,
    val noteIndex: Int? = null,
    /** Runtime-only alphaTab id; never used as the persistent identity. */
    val alphaTabRuntimeId: Double
)

enum class AlphaTabMappingIssueCode {
    MISSING_TRACK,
    MISSING_STAFF,
    MISSING_MEASURE,
    MISSING_VOICE,
    MISSING_BEAT,
    EVENT_KIND_MISMATCH,
    NOTE_COUNT_MISMATCH,
    NOTE_PITCH_MISMATCH,
    AMBIGUOUS_NOTE_PITCH,
    DUPLICATE_RUNTIME_ID,
    AMBIGUOUS_VOICE,
    EVENT_SEQUENCE_MISMATCH,
    RENDERED_NOTE_UNMAPPED,
    AMBIGUOUS_CLEF,
    UNSUPPORTED_MID_MEASURE_CLEF,
    AMBIGUOUS_BARLINE,
    UNSUPPORTED_MIDDLE_BARLINE
}

data class AlphaTabMappingIssue(
    val code: AlphaTabMappingIssueCode,
    val stableIdentity: String,
    val detail: String
)

class AlphaTabIdentityMapping internal constructor(
    val scoreId: Long,
    val noteRefs: Map<NoteIdentity, AlphaTabElementRef>,
    val chordRefs: Map<ChordIdentity, AlphaTabElementRef>,
    val restRefs: Map<RestIdentity, AlphaTabElementRef>,
    val measureRefs: Map<MeasureIdentity, List<AlphaTabElementRef>>,
    val clefRefs: Map<ClefIdentity, List<AlphaTabElementRef>>,
    val timeSignatureRefs: Map<TimeSignatureIdentity, List<AlphaTabElementRef>>,
    val barlineRefs: Map<BarlineIdentity, List<AlphaTabElementRef>>,
    val issues: List<AlphaTabMappingIssue>,
    private val notesByObject: IdentityHashMap<Note, NoteIdentity>,
    private val chordsByObject: IdentityHashMap<Beat, ChordIdentity>,
    private val restsByObject: IdentityHashMap<Beat, RestIdentity>,
    private val measuresByObject: IdentityHashMap<Bar, MeasureIdentity>,
    private val clefsByObject: IdentityHashMap<Bar, ClefIdentity>,
    private val timeSignaturesByObject: IdentityHashMap<Bar, TimeSignatureIdentity>,
    private val barlinesByObject: IdentityHashMap<Bar, Map<BarlineSide, BarlineIdentity>>,
    private val notesByIdentity: Map<NoteIdentity, Note>,
    private val chordsByIdentity: Map<ChordIdentity, Beat>,
    private val restsByIdentity: Map<RestIdentity, Beat>,
    private val measuresByIdentity: Map<MeasureIdentity, List<Bar>>,
    private val clefsByIdentity: Map<ClefIdentity, List<Bar>>,
    private val timeSignaturesByIdentity: Map<TimeSignatureIdentity, List<Bar>>,
    private val barlinesByIdentity: Map<BarlineIdentity, List<Bar>>
) {
    fun noteIdentity(note: Note): NoteIdentity? = notesByObject[note]
    fun chordIdentity(beat: Beat): ChordIdentity? = chordsByObject[beat]
    fun restIdentity(beat: Beat): RestIdentity? = restsByObject[beat]
    fun measureIdentity(bar: Bar): MeasureIdentity? = measuresByObject[bar]
    fun clefIdentity(bar: Bar): ClefIdentity? = clefsByObject[bar]
    fun timeSignatureIdentity(bar: Bar): TimeSignatureIdentity? = timeSignaturesByObject[bar]
    fun barlineIdentity(bar: Bar, side: BarlineSide): BarlineIdentity? = barlinesByObject[bar]?.get(side)
    fun note(identity: NoteIdentity): Note? = notesByIdentity[identity]
    fun chord(identity: ChordIdentity): Beat? = chordsByIdentity[identity]
    fun rest(identity: RestIdentity): Beat? = restsByIdentity[identity]
    fun measure(identity: MeasureIdentity): List<Bar> = measuresByIdentity[identity].orEmpty()
    fun clef(identity: ClefIdentity): List<Bar> = clefsByIdentity[identity].orEmpty()
    fun timeSignature(identity: TimeSignatureIdentity): List<Bar> = timeSignaturesByIdentity[identity].orEmpty()
    fun barline(identity: BarlineIdentity): List<Bar> = barlinesByIdentity[identity].orEmpty()
    val isComplete: Boolean get() = issues.isEmpty()
}

/**
 * Maps structural MusicXML identities onto alphaTab 1.6.1 model objects.
 * alphaTab's `noteMouseDown`/`beatMouseDown` events return these exact Note and
 * Beat objects; future interaction can therefore reverse-map an event without
 * screen-coordinate identity or renderer DOM inspection.
 */
object AlphaTabIdentityMapper {
    fun map(source: EditableScoreIdentityIndex, score: Score): AlphaTabIdentityMapping {
        val noteRefs = linkedMapOf<NoteIdentity, AlphaTabElementRef>()
        val chordRefs = linkedMapOf<ChordIdentity, AlphaTabElementRef>()
        val restRefs = linkedMapOf<RestIdentity, AlphaTabElementRef>()
        val measureRefs = linkedMapOf<MeasureIdentity, MutableList<AlphaTabElementRef>>()
        val clefRefs = linkedMapOf<ClefIdentity, MutableList<AlphaTabElementRef>>()
        val timeSignatureRefs = linkedMapOf<TimeSignatureIdentity, MutableList<AlphaTabElementRef>>()
        val barlineRefs = linkedMapOf<BarlineIdentity, MutableList<AlphaTabElementRef>>()
        val notesByObject = IdentityHashMap<Note, NoteIdentity>()
        val chordsByObject = IdentityHashMap<Beat, ChordIdentity>()
        val restsByObject = IdentityHashMap<Beat, RestIdentity>()
        val measuresByObject = IdentityHashMap<Bar, MeasureIdentity>()
        val clefsByObject = IdentityHashMap<Bar, ClefIdentity>()
        val timeSignaturesByObject = IdentityHashMap<Bar, TimeSignatureIdentity>()
        val barlinesByObject = IdentityHashMap<Bar, Map<BarlineSide, BarlineIdentity>>()
        val notesByIdentity = linkedMapOf<NoteIdentity, Note>()
        val chordsByIdentity = linkedMapOf<ChordIdentity, Beat>()
        val restsByIdentity = linkedMapOf<RestIdentity, Beat>()
        val measuresByIdentity = linkedMapOf<MeasureIdentity, MutableList<Bar>>()
        val clefsByIdentity = linkedMapOf<ClefIdentity, MutableList<Bar>>()
        val timeSignaturesByIdentity = linkedMapOf<TimeSignatureIdentity, MutableList<Bar>>()
        val barlinesByIdentity = linkedMapOf<BarlineIdentity, MutableList<Bar>>()
        val activeClef = mutableMapOf<Pair<Int, Int>, ClefIdentity>()
        val activeTimeSignature = mutableMapOf<Pair<Int, Int>, TimeSignatureIdentity>()
        val issues = mutableListOf<AlphaTabMappingIssue>()

        source.measures.forEach { measure ->
            val track = score.tracks.getOrNull(measure.source.partIndex)
            if (track == null) {
                issues += issue(AlphaTabMappingIssueCode.MISSING_TRACK, measure.identity.value, "No alphaTab track exists.")
                return@forEach
            }
            val staffsUsed = buildSet {
                addAll(measure.events.map { it.staff })
                addAll(measure.clefs.map { it.staff })
                addAll(1..track.staves.length.toInt().coerceAtLeast(1))
            }
            staffsUsed.forEach staffLoop@{ staffNumber ->
                val staffIndex = staffNumber - 1
                val staff = track.staves.getOrNull(staffIndex)
                if (staff == null) {
                    issues += issue(AlphaTabMappingIssueCode.MISSING_STAFF, measure.identity.value, "Staff $staffNumber is missing.")
                    return@staffLoop
                }
                val bar = staff.bars.getOrNull(measure.source.measureIndex)
                if (bar == null) {
                    issues += issue(AlphaTabMappingIssueCode.MISSING_MEASURE, measure.identity.value, "Measure ${measure.source.measureIndex} is missing.")
                    return@staffLoop
                }
                val barRef = AlphaTabElementRef(
                    trackIndex = measure.source.partIndex,
                    staffIndex = staffIndex,
                    barIndex = bar.index.toInt(),
                    alphaTabRuntimeId = bar.id
                )
                measureRefs.getOrPut(measure.identity) { mutableListOf() } += barRef
                measuresByObject[bar] = measure.identity
                measuresByIdentity.getOrPut(measure.identity) { mutableListOf() } += bar

                val clefOccurrences = measure.clefs.filter { it.staff == staffNumber }
                clefOccurrences.filter { it.onsetDivisions > 0 }.forEach { clef ->
                    issues += issue(
                        AlphaTabMappingIssueCode.UNSUPPORTED_MID_MEASURE_CLEF,
                        clef.identity.value,
                        "alphaTab 1.6.1 exposes only the bar-level clef; occurrence onset=${clef.onsetDivisions} cannot be mapped exactly."
                    )
                }
                val startClefs = clefOccurrences.filter { it.onsetDivisions == 0 }
                val currentClef = when {
                    startClefs.size == 1 -> startClefs.single().identity.also {
                        activeClef[measure.source.partIndex to staffNumber] = it
                    }
                    startClefs.size > 1 -> {
                        startClefs.forEach { clef ->
                            issues += issue(
                                AlphaTabMappingIssueCode.AMBIGUOUS_CLEF,
                                clef.identity.value,
                                "Multiple clef occurrences exist at the start of one staff/measure."
                            )
                        }
                        null
                    }
                    else -> activeClef[measure.source.partIndex to staffNumber]
                }
                if (currentClef != null) {
                    clefRefs.getOrPut(currentClef) { mutableListOf() } += barRef
                    clefsByObject[bar] = currentClef
                    clefsByIdentity.getOrPut(currentClef) { mutableListOf() } += bar
                }

                val startTimes = measure.timeSignatures.filter {
                    it.staff == staffNumber && it.onsetDivisions == 0
                }
                val currentTime = when {
                    startTimes.size == 1 -> startTimes.single().identity.also {
                        activeTimeSignature[measure.source.partIndex to staffNumber] = it
                    }
                    startTimes.size > 1 -> null
                    else -> activeTimeSignature[measure.source.partIndex to staffNumber]
                }
                if (currentTime != null) {
                    timeSignatureRefs.getOrPut(currentTime) { mutableListOf() } += barRef
                    timeSignaturesByObject[bar] = currentTime
                    timeSignaturesByIdentity.getOrPut(currentTime) { mutableListOf() } += bar
                }

                val barlineIdentities = linkedMapOf<BarlineSide, BarlineIdentity>()
                BarlineSide.entries.forEach { side ->
                    val candidates = measure.barlines.filter { it.side == side }
                    when {
                        side == BarlineSide.MIDDLE && candidates.isNotEmpty() -> candidates.forEach { candidate ->
                            issues += issue(
                                AlphaTabMappingIssueCode.UNSUPPORTED_MIDDLE_BARLINE,
                                candidate.identity.value,
                                "alphaTab exposes no exact mid-measure barline glyph mapping."
                            )
                        }
                        candidates.size == 1 -> {
                            val identity = candidates.single().identity
                            barlineIdentities[side] = identity
                            barlineRefs.getOrPut(identity) { mutableListOf() } += barRef
                            barlinesByIdentity.getOrPut(identity) { mutableListOf() } += bar
                        }
                        candidates.size > 1 -> candidates.forEach { candidate ->
                            issues += issue(
                                AlphaTabMappingIssueCode.AMBIGUOUS_BARLINE,
                                candidate.identity.value,
                                "Multiple ${side.name.lowercase()} barlines cannot share one rendered glyph safely."
                            )
                        }
                    }
                }
                barlinesByObject[bar] = barlineIdentities

                val eventsByVoice = measure.events.filter { it.staff == staffNumber }.groupBy { it.voice }
                eventsByVoice.forEach { (sourceVoice, voiceEvents) ->
                    val candidates = bar.voices.toList().mapNotNull { voice ->
                        val beats = voice.meaningfulBeats()
                        uniqueSubsequenceMatch(voiceEvents, beats, measure.divisions)?.let { matched ->
                            VoiceMatch(voice, matched)
                        }
                    }
                    val preferred = candidates.singleOrNull { it.voice.index.toInt() == sourceVoice - 1 }
                    val resolved = preferred ?: candidates.singleOrNull()
                    if (resolved == null) {
                        val code = if (candidates.size > 1) {
                            AlphaTabMappingIssueCode.AMBIGUOUS_VOICE
                        } else {
                            AlphaTabMappingIssueCode.EVENT_SEQUENCE_MISMATCH
                        }
                        voiceEvents.forEach { event ->
                            issues += issue(
                                code,
                                event.stableValue(),
                                if (candidates.size > 1) {
                                    "Voice $sourceVoice matches multiple alphaTab voices (${candidates.map { it.voice.index.toInt() }})."
                                } else {
                                    "Voice $sourceVoice has no alphaTab voice containing the same ordered onsets, event kinds, and pitches. " +
                                        "source=${voiceEvents.map(::eventDiagnostic)} " +
                                        "alpha=${bar.voices.toList().map { candidate -> candidate.index.toInt() to candidate.beats.toList().map(::beatDiagnostic) }}"
                                }
                            )
                        }
                        return@forEach
                    }
                    val voice = resolved.voice
                    voiceEvents.zip(resolved.beats).forEach eventLoop@{ (event, beat) ->
                    val beatRef = AlphaTabElementRef(
                        trackIndex = measure.source.partIndex,
                        staffIndex = staffIndex,
                        barIndex = bar.index.toInt(),
                        voiceIndex = voice.index.toInt(),
                        beatIndex = beat.index.toInt(),
                        alphaTabRuntimeId = beat.id
                    )
                    when (event) {
                        is EditableRestRef -> {
                            if (!beat.isRest) {
                                issues += issue(AlphaTabMappingIssueCode.EVENT_KIND_MISMATCH, event.identity.value, "Expected an alphaTab rest.")
                            } else {
                                restRefs[event.identity] = beatRef
                                restsByObject[beat] = event.identity
                                restsByIdentity[event.identity] = beat
                            }
                        }
                        is EditableChordRef -> {
                            if (beat.isRest) {
                                issues += issue(AlphaTabMappingIssueCode.EVENT_KIND_MISMATCH, event.identity.value, "Expected an alphaTab chord.")
                                return@eventLoop
                            }
                            chordRefs[event.identity] = beatRef
                            chordsByObject[beat] = event.identity
                            chordsByIdentity[event.identity] = beat
                            mapNotes(
                                event = event,
                                beat = beat,
                                beatRef = beatRef,
                                noteRefs = noteRefs,
                                notesByObject = notesByObject,
                                notesByIdentity = notesByIdentity,
                                issues = issues
                            )
                        }
                    }
                    }
                }
            }
        }

        score.tracks.toList().forEachIndexed { trackIndex, track ->
            track.staves.toList().forEachIndexed { staffIndex, staff ->
                staff.bars.toList().forEachIndexed { barIndex, bar ->
                    bar.voices.toList().forEachIndexed { voiceIndex, voice ->
                        voice.meaningfulBeats().forEachIndexed { beatIndex, beat ->
                            beat.notes.toList().filter { it.isVisible }.forEach { note ->
                                if (!notesByObject.containsKey(note)) {
                                    issues += issue(
                                        AlphaTabMappingIssueCode.RENDERED_NOTE_UNMAPPED,
                                        "rendered/track/$trackIndex/staff/$staffIndex/bar/$barIndex/voice/$voiceIndex/beat/$beatIndex/note/${note.index.toInt()}",
                                        "Visible alphaTab note pitch=${note.realValue.toInt()} runtimeId=${note.id} has no safe MusicXML NoteIdentity mapping."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return AlphaTabIdentityMapping(
            scoreId = source.scoreId,
            noteRefs = noteRefs.toMap(),
            chordRefs = chordRefs.toMap(),
            restRefs = restRefs.toMap(),
            measureRefs = measureRefs.mapValues { it.value.toList() },
            clefRefs = clefRefs.mapValues { it.value.toList() },
            timeSignatureRefs = timeSignatureRefs.mapValues { it.value.toList() },
            barlineRefs = barlineRefs.mapValues { it.value.toList() },
            issues = issues.toList(),
            notesByObject = notesByObject,
            chordsByObject = chordsByObject,
            restsByObject = restsByObject,
            measuresByObject = measuresByObject,
            clefsByObject = clefsByObject,
            timeSignaturesByObject = timeSignaturesByObject,
            barlinesByObject = barlinesByObject,
            notesByIdentity = notesByIdentity.toMap(),
            chordsByIdentity = chordsByIdentity.toMap(),
            restsByIdentity = restsByIdentity.toMap(),
            measuresByIdentity = measuresByIdentity.mapValues { it.value.toList() },
            clefsByIdentity = clefsByIdentity.mapValues { it.value.toList() },
            timeSignaturesByIdentity = timeSignaturesByIdentity.mapValues { it.value.toList() },
            barlinesByIdentity = barlinesByIdentity.mapValues { it.value.toList() }
        )
    }

    private fun mapNotes(
        event: EditableChordRef,
        beat: Beat,
        beatRef: AlphaTabElementRef,
        noteRefs: MutableMap<NoteIdentity, AlphaTabElementRef>,
        notesByObject: IdentityHashMap<Note, NoteIdentity>,
        notesByIdentity: MutableMap<NoteIdentity, Note>,
        issues: MutableList<AlphaTabMappingIssue>
    ) {
        val alphaNotes = beat.notes.toList()
        if (alphaNotes.size != event.notes.size) {
            issues += issue(
                AlphaTabMappingIssueCode.NOTE_COUNT_MISMATCH,
                event.identity.value,
                "MusicXML has ${event.notes.size} note(s), alphaTab has ${alphaNotes.size}."
            )
            return
        }
        val sourcePitches = event.notes.map { it.pitchMidi }
        if (sourcePitches.any { pitch -> pitch != null && sourcePitches.count { it == pitch } > 1 }) {
            issues += issue(
                AlphaTabMappingIssueCode.AMBIGUOUS_NOTE_PITCH,
                event.identity.value,
                "A unison chord cannot be matched to individual rendered note heads safely."
            )
            return
        }
        val unused = alphaNotes.toMutableList()
        val resolved = mutableListOf<Pair<EditableNoteRef, Note>>()
        for (sourceNote in event.notes) {
            val matched = if (sourceNote.pitchMidi == null) {
                if (unused.size == 1) unused.single() else null
            } else {
                unused.singleOrNull { it.realValue.toInt() == sourceNote.pitchMidi }
            }
            if (matched == null) {
                issues += issue(
                    AlphaTabMappingIssueCode.NOTE_PITCH_MISMATCH,
                    sourceNote.identity.value,
                    "No unique alphaTab note has MusicXML MIDI pitch ${sourceNote.pitchMidi}."
                )
                return
            }
            unused.remove(matched)
            resolved += sourceNote to matched
        }

        resolved.forEach { (sourceNote, matched) ->
            val ref = beatRef.copy(noteIndex = matched.index.toInt(), alphaTabRuntimeId = matched.id)
            noteRefs[sourceNote.identity] = ref
            notesByObject[matched] = sourceNote.identity
            notesByIdentity[sourceNote.identity] = matched
        }
    }

    private fun EditableScoreEventRef.stableValue(): String = when (this) {
        is EditableChordRef -> identity.value
        is EditableRestRef -> identity.value
    }

    private fun issue(code: AlphaTabMappingIssueCode, identity: String, detail: String) =
        AlphaTabMappingIssue(code, identity, detail)

    /**
     * Finds one ordered source-to-renderer alignment while allowing alphaTab's
     * generated timing/rest beats between source events. More than one valid
     * alignment is rejected because choosing either would invent identity.
     */
    private fun uniqueSubsequenceMatch(
        events: List<EditableScoreEventRef>,
        beats: List<Beat>,
        divisions: Int
    ): List<Beat>? {
        if (events.isEmpty()) return emptyList()
        var solution: List<Beat>? = null
        var solutionCount = 0

        fun search(eventIndex: Int, beatIndex: Int, matched: MutableList<Beat>) {
            if (solutionCount > 1) return
            if (eventIndex == events.size) {
                solutionCount++
                if (solutionCount == 1) solution = matched.toList()
                return
            }
            val remainingEvents = events.size - eventIndex
            val lastStart = beats.size - remainingEvents
            if (beatIndex > lastStart) return
            for (candidateIndex in beatIndex..lastStart) {
                val candidate = beats[candidateIndex]
                if (!eventMatches(events[eventIndex], candidate, divisions)) continue
                matched += candidate
                search(eventIndex + 1, candidateIndex + 1, matched)
                matched.removeAt(matched.lastIndex)
                if (solutionCount > 1) return
            }
        }

        search(0, 0, mutableListOf())
        return solution.takeIf { solutionCount == 1 }
    }

    private fun eventMatches(event: EditableScoreEventRef, beat: Beat, divisions: Int): Boolean {
        val rendererIsGrace = beat.graceType != alphaTab.model.GraceType.None
        if (event.isGrace != rendererIsGrace || !onsetMatches(event, beat, divisions)) return false
        return when (event) {
            is EditableRestRef -> beat.isRest
            is EditableChordRef -> !beat.isRest && pitchMultiset(event) == beat.notes.toList()
                .map { it.realValue.toInt() }.sorted()
        }
    }

    private fun onsetMatches(event: EditableScoreEventRef, beat: Beat, divisions: Int): Boolean {
        if (event.isGrace) return true
        // alphaTab 1.6.1's verified MidiUtils.QuarterTime constant is 960 ticks.
        val expected = event.onsetDivisions.toDouble() * ALPHATAB_QUARTER_TICKS /
            divisions.coerceAtLeast(1).toDouble()
        return kotlin.math.abs(beat.displayStart - expected) < 0.5
    }

    private fun pitchMultiset(event: EditableChordRef): List<Int> =
        event.notes.mapNotNull { it.pitchMidi }.sorted()

    private fun eventDiagnostic(event: EditableScoreEventRef): String = when (event) {
        is EditableRestRef -> "onset=${event.onsetDivisions}:rest"
        is EditableChordRef -> "onset=${event.onsetDivisions}:notes=${pitchMultiset(event)}"
    }

    private fun beatDiagnostic(beat: Beat): String =
        "start=${beat.displayStart}:empty=${beat.isEmpty}:rest=${beat.isRest}:notes=${beat.notes.toList().map { it.realValue.toInt() }.sorted()}"

    private const val ALPHATAB_QUARTER_TICKS = 960.0
}

private data class VoiceMatch(
    val voice: alphaTab.model.Voice,
    val beats: List<Beat>
)

private fun <T> alphaTab.collections.List<T>.getOrNull(index: Int): T? =
    if (index >= 0 && index < length.toInt()) this[index] else null

private fun <T> alphaTab.collections.List<T>.toList(): List<T> =
    (0 until length.toInt()).map { this[it] }

private fun alphaTab.model.Voice.meaningfulBeats(): List<Beat> =
    // MusicXML <forward> placeholders can report isEmpty=false in alphaTab 1.6.1
    // despite having neither a rest nor a rendered note head. They are timing
    // carriers, not source events, and must not shift structural event ordinals.
    beats.toList().filter { beat -> beat.notes.length > 0.0 || (beat.isRest && !beat.isEmpty) }
