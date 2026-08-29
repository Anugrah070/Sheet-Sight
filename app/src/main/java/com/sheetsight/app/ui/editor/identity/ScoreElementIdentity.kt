package com.sheetsight.app.ui.editor.identity

import org.w3c.dom.Document
import org.w3c.dom.Element

@JvmInline value class MeasureIdentity(val value: String)
@JvmInline value class NoteIdentity(val value: String)
@JvmInline value class ChordIdentity(val value: String)
@JvmInline value class RestIdentity(val value: String)
@JvmInline value class ClefIdentity(val value: String)
@JvmInline value class BarlineIdentity(val value: String)

enum class BarlineSide { LEFT, RIGHT, MIDDLE }

data class MusicXmlElementRef(
    val partIndex: Int,
    val partId: String,
    val measureIndex: Int,
    val measureNumber: String,
    val noteElementIndex: Int? = null,
    val elementOccurrenceIndex: Int? = null,
    val explicitId: String? = null
)

sealed interface EditableScoreElementRef {
    val source: MusicXmlElementRef
}

data class EditableMeasureRef(
    val identity: MeasureIdentity,
    override val source: MusicXmlElementRef,
    val divisions: Int,
    val events: List<EditableScoreEventRef>,
    val clefs: List<EditableClefRef> = emptyList(),
    val barlines: List<EditableBarlineRef> = emptyList()
) : EditableScoreElementRef

data class EditableClefRef(
    val identity: ClefIdentity,
    override val source: MusicXmlElementRef,
    val staff: Int,
    val occurrenceInMeasure: Int,
    val onsetDivisions: Int,
    val sign: String?,
    val line: Int?
) : EditableScoreElementRef

data class EditableBarlineRef(
    val identity: BarlineIdentity,
    override val source: MusicXmlElementRef,
    val side: BarlineSide,
    val occurrenceInMeasure: Int,
    val explicit: Boolean
) : EditableScoreElementRef

sealed interface EditableScoreEventRef : EditableScoreElementRef {
    val staff: Int
    val voice: Int
    val eventOrdinalInVoice: Int
    val onsetDivisions: Int
    val isGrace: Boolean
}

data class EditableChordRef(
    val identity: ChordIdentity,
    override val source: MusicXmlElementRef,
    override val staff: Int,
    override val voice: Int,
    override val eventOrdinalInVoice: Int,
    override val onsetDivisions: Int,
    override val isGrace: Boolean,
    val notes: List<EditableNoteRef>
) : EditableScoreEventRef

data class EditableNoteRef(
    val identity: NoteIdentity,
    override val source: MusicXmlElementRef,
    val pitchMidi: Int?,
    val pitchStep: String?,
    val pitchOctave: Int?
) : EditableScoreElementRef

data class EditableRestRef(
    val identity: RestIdentity,
    override val source: MusicXmlElementRef,
    override val staff: Int,
    override val voice: Int,
    override val eventOrdinalInVoice: Int,
    override val onsetDivisions: Int,
    override val isGrace: Boolean
) : EditableScoreEventRef

data class EditableScoreIdentityIndex(
    val scoreId: Long,
    val measures: List<EditableMeasureRef>
) {
    val notes: List<EditableNoteRef> = measures.flatMap { measure ->
        measure.events.filterIsInstance<EditableChordRef>().flatMap { it.notes }
    }
    val chords: List<EditableChordRef> = measures.flatMap { it.events.filterIsInstance<EditableChordRef>() }
    val rests: List<EditableRestRef> = measures.flatMap { it.events.filterIsInstance<EditableRestRef>() }
    val clefs: List<EditableClefRef> = measures.flatMap { it.clefs }
    val barlines: List<EditableBarlineRef> = measures.flatMap { it.barlines }
}

class AmbiguousMusicXmlIdentityException(message: String) : IllegalArgumentException(message)

/**
 * Builds stable, score-scoped identities without adding attributes to or
 * serializing the MusicXML document. Structural addresses are authoritative;
 * existing MusicXML `id` values are retained as source metadata when unique.
 */
object MusicXmlIdentityBuilder {
    fun build(scoreId: Long, document: Document): EditableScoreIdentityIndex {
        require(scoreId > 0L) { "A persisted score id is required for stable identities." }
        val root = document.documentElement
            ?: throw AmbiguousMusicXmlIdentityException("MusicXML has no document element.")
        val parts = root.directChildren("part")
        if (parts.isEmpty()) throw AmbiguousMusicXmlIdentityException("MusicXML contains no parts.")
        duplicateValues(parts.map { it.getAttribute("id").trim() }.filter { it.isNotEmpty() })
            .firstOrNull()?.let { duplicate ->
                throw AmbiguousMusicXmlIdentityException("Duplicate MusicXML part id '$duplicate'.")
            }

        val measures = mutableListOf<EditableMeasureRef>()
        parts.forEachIndexed { partIndex, part ->
            val partId = part.getAttribute("id").ifBlank { "part-${partIndex + 1}" }
            val measureElements = part.directChildren("measure")
            var currentDivisions = 1
            detectDuplicateExplicitIds(measureElements, "measure")
            detectDuplicateExplicitIds(measureElements.flatMap { it.directChildren("note") }, "note")
            measureElements.forEachIndexed { measureIndex, measure ->
                val number = measure.getAttribute("number").ifBlank { (measureIndex + 1).toString() }
                val noteElements = measure.directChildren("note")
                detectDuplicateExplicitIds(noteElements, "note")
                val eventCounts = mutableMapOf<Pair<Int, Int>, Int>()
                val lastChordByVoice = mutableMapOf<Pair<Int, Int>, Int>()
                val lastOnsetByVoice = mutableMapOf<Pair<Int, Int>, Int>()
                val events = mutableListOf<EditableScoreEventRef>()
                val clefs = mutableListOf<EditableClefRef>()
                var measureCursor = 0
                var noteIndex = 0
                var clefOccurrence = 0

                measure.directElements().forEach { child ->
                    when (child.tagName) {
                        "attributes" -> {
                            child.directChild("divisions")?.textContent?.trim()?.toIntOrNull()
                                ?.takeIf { it > 0 }?.let { currentDivisions = it }
                            child.directChildren("clef").forEach { clef ->
                                val occurrence = clefOccurrence++
                                val staff = clef.getAttribute("number").trim().toIntOrNull()
                                    ?.takeIf { it > 0 } ?: 1
                                val explicitId = clef.getAttribute("id").ifBlank { null }
                                val source = MusicXmlElementRef(
                                    partIndex = partIndex,
                                    partId = partId,
                                    measureIndex = measureIndex,
                                    measureNumber = number,
                                    elementOccurrenceIndex = occurrence,
                                    explicitId = explicitId
                                )
                                clefs += EditableClefRef(
                                    identity = ClefIdentity(
                                        stableId(
                                            scoreId,
                                            explicitId,
                                            "clef",
                                            "score/$scoreId/part/$partIndex/measure/$measureIndex/staff/$staff/clef/$occurrence"
                                        )
                                    ),
                                    source = source,
                                    staff = staff,
                                    occurrenceInMeasure = occurrence,
                                    onsetDivisions = measureCursor,
                                    sign = clef.directChild("sign")?.textContent?.trim()?.uppercase(),
                                    line = clef.directChild("line")?.textContent?.trim()?.toIntOrNull()
                                )
                            }
                        }
                        "backup" -> measureCursor = (measureCursor - child.durationDivisions()).coerceAtLeast(0)
                        "forward" -> measureCursor += child.durationDivisions()
                        "note" -> {
                            val note = child
                            val sourceNoteIndex = noteIndex++
                    val staff = note.directChild("staff")?.textContent?.trim()?.toIntOrNull()
                        ?.takeIf { it > 0 } ?: 1
                    val voice = note.directChild("voice")?.textContent?.trim()?.toIntOrNull()
                        ?.takeIf { it > 0 } ?: 1
                    val voiceKey = staff to voice
                    val isChordMember = note.directChild("chord") != null
                    val isRest = note.directChild("rest") != null
                    val isGrace = note.directChild("grace") != null
                    val duration = note.durationDivisions()
                    val onset = if (isChordMember) {
                        lastOnsetByVoice[voiceKey]
                            ?: throw AmbiguousMusicXmlIdentityException(
                                "Orphan MusicXML chord member at part $partId measure $number note $sourceNoteIndex."
                            )
                    } else {
                        measureCursor
                    }
                    val source = MusicXmlElementRef(
                        partIndex = partIndex,
                        partId = partId,
                        measureIndex = measureIndex,
                        measureNumber = number,
                        noteElementIndex = sourceNoteIndex,
                        explicitId = note.getAttribute("id").ifBlank { null }
                    )
                    val prefix = prefix(scoreId, partIndex, measureIndex, staff, voice)

                    if (isRest) {
                        if (isChordMember) {
                            throw AmbiguousMusicXmlIdentityException(
                                "A rest cannot be a chord member at part $partId measure $number note $sourceNoteIndex."
                            )
                        }
                        val ordinal = eventCounts.getOrDefault(voiceKey, 0)
                        events += EditableRestRef(
                            identity = RestIdentity(stableId(scoreId, source.explicitId, "rest", "$prefix/event/$ordinal/rest")),
                            source = source,
                            staff = staff,
                            voice = voice,
                            eventOrdinalInVoice = ordinal,
                            onsetDivisions = onset,
                            isGrace = isGrace
                        )
                        eventCounts[voiceKey] = ordinal + 1
                        lastChordByVoice.remove(voiceKey)
                    } else {
                        val pitchMidi = note.pitchMidi()
                        val pitchStep = note.pitchStep()
                        val pitchOctave = note.pitchOctave()
                        if (isChordMember) {
                            val eventIndex = lastChordByVoice[voiceKey]
                                ?: throw AmbiguousMusicXmlIdentityException(
                                    "Orphan MusicXML chord member at part $partId measure $number note $sourceNoteIndex."
                                )
                            val chord = events[eventIndex] as? EditableChordRef
                                ?: throw AmbiguousMusicXmlIdentityException(
                                    "Ambiguous chord membership at part $partId measure $number note $sourceNoteIndex."
                                )
                            events[eventIndex] = chord.copy(
                                notes = chord.notes + EditableNoteRef(
                                    identity = NoteIdentity(
                                        stableId(scoreId, source.explicitId, "note", "$prefix/xml-note/$sourceNoteIndex")
                                    ),
                                    source = source,
                                    pitchMidi = pitchMidi,
                                    pitchStep = pitchStep,
                                    pitchOctave = pitchOctave
                                )
                            )
                        } else {
                            val ordinal = eventCounts.getOrDefault(voiceKey, 0)
                            val chord = EditableChordRef(
                                identity = ChordIdentity(
                                    stableId(scoreId, source.explicitId, "chord", "$prefix/event/$ordinal/chord")
                                ),
                                source = source,
                                staff = staff,
                                voice = voice,
                                eventOrdinalInVoice = ordinal,
                                onsetDivisions = onset,
                                isGrace = isGrace,
                                notes = listOf(
                                    EditableNoteRef(
                                        identity = NoteIdentity(
                                            stableId(scoreId, source.explicitId, "note", "$prefix/xml-note/$sourceNoteIndex")
                                        ),
                                        source = source,
                                        pitchMidi = pitchMidi,
                                        pitchStep = pitchStep,
                                        pitchOctave = pitchOctave
                                    )
                                )
                            )
                            events += chord
                            lastChordByVoice[voiceKey] = events.lastIndex
                            eventCounts[voiceKey] = ordinal + 1
                        }
                    }
                    if (!isChordMember) {
                        lastOnsetByVoice[voiceKey] = onset
                        if (!isGrace) measureCursor += duration
                    }
                        }
                    }
                }

                val measureSource = MusicXmlElementRef(
                    partIndex = partIndex,
                    partId = partId,
                    measureIndex = measureIndex,
                    measureNumber = number,
                    explicitId = measure.getAttribute("id").ifBlank { null }
                )
                val explicitBarlines = measure.directChildren("barline").mapIndexed { occurrence, barline ->
                    val location = when (barline.getAttribute("location").trim().lowercase()) {
                        "left" -> BarlineSide.LEFT
                        "middle" -> BarlineSide.MIDDLE
                        else -> BarlineSide.RIGHT
                    }
                    val explicitId = barline.getAttribute("id").ifBlank { null }
                    EditableBarlineRef(
                        identity = BarlineIdentity(
                            stableId(
                                scoreId,
                                explicitId,
                                "barline",
                                "score/$scoreId/part/$partIndex/measure/$measureIndex/barline/$occurrence/${location.name.lowercase()}"
                            )
                        ),
                        source = MusicXmlElementRef(
                            partIndex = partIndex,
                            partId = partId,
                            measureIndex = measureIndex,
                            measureNumber = number,
                            elementOccurrenceIndex = occurrence,
                            explicitId = explicitId
                        ),
                        side = location,
                        occurrenceInMeasure = occurrence,
                        explicit = true
                    )
                }
                val barlines = buildList {
                    addAll(explicitBarlines)
                    if (explicitBarlines.none { it.side == BarlineSide.LEFT }) {
                        add(syntheticBarline(scoreId, measureSource, BarlineSide.LEFT))
                    }
                    if (explicitBarlines.none { it.side == BarlineSide.RIGHT }) {
                        add(syntheticBarline(scoreId, measureSource, BarlineSide.RIGHT))
                    }
                }
                measures += EditableMeasureRef(
                    identity = MeasureIdentity(
                        stableId(
                            scoreId,
                            measureSource.explicitId,
                            "measure",
                            "score/$scoreId/part/$partIndex/measure/$measureIndex"
                        )
                    ),
                    source = measureSource,
                    divisions = currentDivisions,
                    events = events.toList(),
                    clefs = clefs.toList(),
                    barlines = barlines
                )
            }
        }
        return EditableScoreIdentityIndex(scoreId, measures.toList())
    }

    private fun prefix(scoreId: Long, part: Int, measure: Int, staff: Int, voice: Int): String =
        "score/$scoreId/part/$part/measure/$measure/staff/$staff/voice/$voice"

    private fun stableId(scoreId: Long, explicitId: String?, kind: String, fallback: String): String =
        explicitId?.let { "score/$scoreId/musicxml-id/${it.length}:$it/$kind" } ?: fallback

    private fun syntheticBarline(
        scoreId: Long,
        measureSource: MusicXmlElementRef,
        side: BarlineSide
    ) = EditableBarlineRef(
        identity = BarlineIdentity(
            "score/$scoreId/part/${measureSource.partIndex}/measure/${measureSource.measureIndex}/barline/implicit/${side.name.lowercase()}"
        ),
        source = measureSource.copy(elementOccurrenceIndex = -1),
        side = side,
        occurrenceInMeasure = -1,
        explicit = false
    )

    private fun detectDuplicateExplicitIds(elements: List<Element>, type: String) {
        duplicateValues(elements.map { it.getAttribute("id").trim() }.filter { it.isNotEmpty() })
            .firstOrNull()?.let { duplicate ->
                throw AmbiguousMusicXmlIdentityException("Duplicate MusicXML $type id '$duplicate'.")
            }
    }

    private fun duplicateValues(values: List<String>): Set<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun Element.pitchMidi(): Int? {
        val pitch = directChild("pitch") ?: return null
        val step = pitch.directChild("step")?.textContent?.trim()?.uppercase() ?: return null
        val semitone = when (step) {
            "C" -> 0
            "D" -> 2
            "E" -> 4
            "F" -> 5
            "G" -> 7
            "A" -> 9
            "B" -> 11
            else -> return null
        }
        val alter = pitch.directChild("alter")?.textContent?.trim()?.toIntOrNull() ?: 0
        val octave = pitch.directChild("octave")?.textContent?.trim()?.toIntOrNull() ?: return null
        return (octave + 1) * 12 + semitone + alter
    }

    private fun Element.pitchStep(): String? = directChild("pitch")?.directChild("step")
        ?.textContent?.trim()?.uppercase()?.takeIf { it in NATURAL_STEPS }

    private fun Element.pitchOctave(): Int? = directChild("pitch")?.directChild("octave")
        ?.textContent?.trim()?.toIntOrNull()

    private fun Element.durationDivisions(): Int = directChild("duration")?.textContent?.trim()
        ?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun Element.directElements(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun Element.directChild(name: String): Element? = directChildren(name).firstOrNull()
    private fun Element.directChildren(name: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName == name }

    private val NATURAL_STEPS = setOf("A", "B", "C", "D", "E", "F", "G")
}
