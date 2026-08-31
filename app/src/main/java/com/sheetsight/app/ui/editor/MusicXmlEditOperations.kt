package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.ClefIdentity
import com.sheetsight.app.ui.editor.identity.EditableMeasureRef
import com.sheetsight.app.ui.editor.identity.EditableScoreIdentityIndex
import com.sheetsight.app.ui.editor.identity.MeasureIdentity
import com.sheetsight.app.ui.editor.identity.MusicXmlElementRef
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import com.sheetsight.app.ui.editor.identity.NoteIdentity
import com.sheetsight.app.ui.editor.identity.RestIdentity
import com.sheetsight.app.ui.editor.identity.TimeSignatureIdentity
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

enum class EditorNoteDuration(val musicXmlType: String, val quartersNumerator: Int, val quartersDenominator: Int) {
    WHOLE("whole", 4, 1),
    HALF("half", 2, 1),
    QUARTER("quarter", 1, 1),
    EIGHTH("eighth", 1, 2),
    SIXTEENTH("16th", 1, 4);

    fun divisions(divisionsPerQuarter: Int): Int? {
        val scaled = divisionsPerQuarter * quartersNumerator
        return (scaled / quartersDenominator).takeIf { scaled % quartersDenominator == 0 && it > 0 }
    }
}

enum class EditorClef(val sign: String, val line: Int) {
    TREBLE("G", 2),
    BASS("F", 4),
    ALTO("C", 3),
    TENOR("C", 4);

    companion object {
        fun from(sign: String?, line: Int?): EditorClef? = entries.singleOrNull {
            it.sign == sign?.uppercase() && it.line == line
        }
    }
}

data class EditorTimeSignature(
    val beats: Int,
    val beatType: Int,
    val symbol: String? = null
) {
    init {
        require(beats in 1..64) { "The numerator must be between 1 and 64." }
        require(beatType in SUPPORTED_DENOMINATORS) { "The denominator must be a power of two from 1 through 64." }
        require(symbol == null || symbol == "common" || symbol == "cut") { "Unsupported time-signature symbol." }
        require(symbol != "common" || (beats == 4 && beatType == 4)) { "Common time must be 4/4." }
        require(symbol != "cut" || (beats == 2 && beatType == 2)) { "Cut time must be 2/2." }
    }

    companion object {
        private val SUPPORTED_DENOMINATORS = setOf(1, 2, 4, 8, 16, 32, 64)
        val COMMON = EditorTimeSignature(4, 4, "common")
        val CUT = EditorTimeSignature(2, 2, "cut")
        val PRESETS = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8, 9 to 8, 12 to 8)
            .map { (beats, beatType) -> EditorTimeSignature(beats, beatType) }
    }
}

data class NoteInsertionAnchor(
    val restIdentity: RestIdentity,
    /** Offset from the start of the rest, in the measure's MusicXML divisions. */
    val offsetDivisions: Int = 0
)

sealed interface PreferredEditSelection {
    data class Note(val identity: NoteIdentity) : PreferredEditSelection
    data class Clef(val identity: ClefIdentity) : PreferredEditSelection
    data class TimeSignature(val identity: TimeSignatureIdentity) : PreferredEditSelection
    data class Measure(val identity: MeasureIdentity) : PreferredEditSelection
    data object None : PreferredEditSelection
}

data class MusicXmlEditResult(
    val musicXmlBytes: ByteArray,
    val identityIndex: EditableScoreIdentityIndex,
    val preferredSelection: PreferredEditSelection
)

class MusicXmlEditException(message: String) : IllegalArgumentException(message)

object InsertNote {
    fun apply(
        scoreId: Long,
        sourceBytes: ByteArray,
        anchor: NoteInsertionAnchor,
        duration: EditorNoteDuration,
        pitchStep: String,
        pitchOctave: Int
    ): MusicXmlEditResult = MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
        val rest = before.rests.unique(anchor.restIdentity, "insertion rest")
        rejectGrace(rest.isGrace)
        val measureRef = before.measureFor(rest.source)
        val requestedDuration = duration.divisions(measureRef.divisions)
            ?: fail("The selected duration cannot be represented by this measure's divisions.")
        val restDuration = rest.durationDivisions
        if (restDuration <= 0) fail("The insertion rest has no valid duration.")
        if (anchor.offsetDivisions < 0 || anchor.offsetDivisions + requestedDuration > restDuration) {
            fail("The note does not fit inside the selected rest.")
        }
        val step = pitchStep.trim().uppercase().takeIf { it in NATURAL_STEPS }
            ?: fail("The insertion pitch step is invalid.")
        if (pitchOctave !in -1..9) fail("The insertion pitch is outside the supported range.")

        val measure = document.measure(rest.source)
        val original = measure.note(rest.source)
        rejectUnsupportedRhythm(original, allowRest = true)
        val newId = document.uniqueSheetSightId("note", anchor.restIdentity.value)
        val replacements = buildList {
            if (anchor.offsetDivisions > 0) {
                add(document.createRestLike(original, anchor.offsetDivisions, measureRef.divisions, "before-$newId"))
            }
            add(document.createPitchedNoteLike(original, requestedDuration, duration.musicXmlType, step, pitchOctave, newId))
            val trailing = restDuration - anchor.offsetDivisions - requestedDuration
            if (trailing > 0) add(document.createRestLike(original, trailing, measureRef.divisions, "after-$newId"))
        }
        replacements.forEach { measure.insertBefore(it, original) }
        measure.removeChild(original)
        PreferredEditSelection.Note(noteIdentityForExplicitId(scoreId, newId))
    }
}

object DeleteNote {
    fun apply(scoreId: Long, sourceBytes: ByteArray, noteIdentity: NoteIdentity): MusicXmlEditResult =
        MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
            val selected = before.notes.unique(noteIdentity, "selected note")
            val chord = before.chords.singleOrNull { it.notes.any { note -> note.identity == noteIdentity } }
                ?: fail("The selected note does not resolve to exactly one chord.")
            rejectGrace(chord.isGrace)
            val measureRef = before.measureFor(selected.source)
            val measure = document.measure(selected.source)
            val element = measure.note(selected.source)
            rejectUnsupportedRhythm(element, allowRest = false)
            if (hasTie(element)) fail("Deleting tied notes is not supported yet; remove the tie first.")

            if (chord.notes.size > 1) {
                val selectedIndex = requireNotNull(selected.source.noteElementIndex)
                val rootIndex = requireNotNull(chord.source.noteElementIndex)
                val remainingRef = chord.notes.first { it.identity != noteIdentity }
                var remainingIdentity = remainingRef.identity
                measure.removeChild(element)
                if (selectedIndex == rootIndex) {
                    val next = measure.directChildren("note").getOrNull(rootIndex)
                        ?: fail("The remaining chord root could not be found.")
                    next.directChildren("chord").forEach(next::removeChild)
                    if (remainingRef.source.explicitId == null) {
                        val id = document.uniqueSheetSightId("note", remainingRef.identity.value)
                        next.setAttribute("id", id)
                        remainingIdentity = noteIdentityForExplicitId(scoreId, id)
                    }
                }
                PreferredEditSelection.Note(remainingIdentity)
            } else {
                val restId = document.uniqueSheetSightId("rest", noteIdentity.value)
                val rest = document.createRestLike(element, chord.durationDivisions, measureRef.divisions, restId)
                measure.replaceChild(rest, element)
                PreferredEditSelection.Measure(measureRef.identity)
            }
        }
}

object ReplaceClef {
    fun apply(
        scoreId: Long,
        sourceBytes: ByteArray,
        clefIdentity: ClefIdentity,
        clef: EditorClef
    ): MusicXmlEditResult = MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
        val selected = before.clefs.unique(clefIdentity, "clef")
        val element = document.attributeElement(selected.source, "clef")
        element.setOrCreateTextChild("sign", clef.sign, before = "line")
        element.setOrCreateTextChild("line", clef.line.toString())
        PreferredEditSelection.Clef(clefIdentity)
    }
}

object InsertClef {
    fun apply(
        scoreId: Long,
        sourceBytes: ByteArray,
        measureIdentity: MeasureIdentity,
        clef: EditorClef,
        staff: Int = 1,
        rangeEndMeasureIdentity: MeasureIdentity? = null
    ): MusicXmlEditResult = MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
        if (staff <= 0) fail("The staff number must be positive.")
        val target = before.measures.unique(measureIdentity, "measure")
        if (target.clefs.any { it.staff == staff && it.onsetDivisions == 0 }) {
            fail("This measure already has a clef; edit the existing clef instead.")
        }
        val previous = before.activeClefBefore(target, staff)
        val id = document.uniqueSheetSightId("clef", measureIdentity.value)
        document.insertBoundaryAttribute(target.source, "clef", id) { element ->
            if (staff != 1) element.setAttribute("number", staff.toString())
            element.appendTextChild("sign", clef.sign)
            element.appendTextChild("line", clef.line.toString())
        }
        if (rangeEndMeasureIdentity != null) {
            val restoreAt = before.measureAfter(rangeEndMeasureIdentity, target.source.partIndex)
                ?: fail("A bounded clef range must end before the score ends.")
            val restore = previous ?: fail("No preceding clef is available to restore after the selected range.")
            val restoreId = document.uniqueSheetSightId("clef-restore", rangeEndMeasureIdentity.value)
            document.insertBoundaryAttribute(restoreAt.source, "clef", restoreId) { element ->
                if (staff != 1) element.setAttribute("number", staff.toString())
                element.appendTextChild("sign", restore.sign ?: fail("The preceding clef is unsupported."))
                element.appendTextChild("line", restore.line?.toString() ?: fail("The preceding clef line is missing."))
            }
        }
        PreferredEditSelection.Clef(clefIdentityForExplicitId(scoreId, id))
    }
}

object DeleteClefChange {
    fun apply(scoreId: Long, sourceBytes: ByteArray, clefIdentity: ClefIdentity): MusicXmlEditResult =
        MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
            val selected = before.clefs.unique(clefIdentity, "clef")
            val samePartStaff = before.clefs.filter {
                it.source.partIndex == selected.source.partIndex && it.staff == selected.staff
            }.sortedWith(compareBy({ it.source.measureIndex }, { it.onsetDivisions }, { it.occurrenceInMeasure }))
            if (samePartStaff.firstOrNull()?.identity == clefIdentity) {
                fail("The score's initial clef must be replaced, not deleted.")
            }
            val element = document.attributeElement(selected.source, "clef")
            val parent = element.parentNode
            parent.removeChild(element)
            document.removeEmptyAttributes(parent as Element)
            PreferredEditSelection.Measure(before.measureFor(selected.source).identity)
        }
}

object ReplaceTimeSignature {
    fun apply(
        scoreId: Long,
        sourceBytes: ByteArray,
        timeSignatureIdentity: TimeSignatureIdentity,
        timeSignature: EditorTimeSignature,
        rangeEndMeasureIdentity: MeasureIdentity? = null
    ): MusicXmlEditResult = MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
        val selected = before.timeSignatures.unique(timeSignatureIdentity, "time signature")
        val target = before.measureFor(selected.source)
        before.validateTimeChange(target, timeSignature, rangeEndMeasureIdentity)
        val element = document.attributeElement(selected.source, "time")
        element.writeTimeSignature(timeSignature)
        if (rangeEndMeasureIdentity != null) {
            document.restoreTimeAfterRange(before, target, rangeEndMeasureIdentity, selected)
        }
        PreferredEditSelection.TimeSignature(timeSignatureIdentity)
    }
}

object InsertTimeSignature {
    fun apply(
        scoreId: Long,
        sourceBytes: ByteArray,
        measureIdentity: MeasureIdentity,
        timeSignature: EditorTimeSignature,
        staff: Int = 1,
        rangeEndMeasureIdentity: MeasureIdentity? = null
    ): MusicXmlEditResult = MusicXmlEditTransaction.run(scoreId, sourceBytes) { document, before ->
        if (staff <= 0) fail("The staff number must be positive.")
        val target = before.measures.unique(measureIdentity, "measure")
        if (target.timeSignatures.any { it.staff == staff && it.onsetDivisions == 0 }) {
            fail("This measure already has a time signature; edit the existing signature instead.")
        }
        before.validateTimeChange(target, timeSignature, rangeEndMeasureIdentity)
        val id = document.uniqueSheetSightId("time", measureIdentity.value)
        document.insertBoundaryAttribute(target.source, "time", id) { element ->
            if (staff != 1) element.setAttribute("number", staff.toString())
            element.writeTimeSignature(timeSignature)
        }
        if (rangeEndMeasureIdentity != null) {
            document.restoreTimeAfterRange(before, target, rangeEndMeasureIdentity, null)
        }
        PreferredEditSelection.TimeSignature(timeIdentityForExplicitId(scoreId, id))
    }
}

private object MusicXmlEditTransaction {
    fun run(
        scoreId: Long,
        sourceBytes: ByteArray,
        mutation: (Document, EditableScoreIdentityIndex) -> PreferredEditSelection
    ): MusicXmlEditResult {
        if (sourceBytes.isEmpty()) fail("The MusicXML document is empty.")
        val document = MusicXmlParser.parseBytes(sourceBytes)
        val before = MusicXmlIdentityBuilder.build(scoreId, document)
        val selection = mutation(document, before)
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().apply {
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, Charsets.UTF_8.name())
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
        val bytes = output.toByteArray().takeIf { it.isNotEmpty() } ?: fail("Editing produced an empty document.")
        val reparsed = MusicXmlParser.parseBytes(bytes)
        val after = MusicXmlIdentityBuilder.build(scoreId, reparsed)
        after.validateNoOverlappingVoiceEvents()
        if (!selection.resolves(after)) fail("The preferred post-edit selection did not resolve after validation.")
        return MusicXmlEditResult(bytes, after, selection)
    }
}

private fun PreferredEditSelection.resolves(index: EditableScoreIdentityIndex): Boolean = when (this) {
    is PreferredEditSelection.Note -> index.notes.count { it.identity == identity } == 1
    is PreferredEditSelection.Clef -> index.clefs.count { it.identity == identity } == 1
    is PreferredEditSelection.TimeSignature -> index.timeSignatures.count { it.identity == identity } == 1
    is PreferredEditSelection.Measure -> index.measures.count { it.identity == identity } == 1
    PreferredEditSelection.None -> true
}

private fun EditableScoreIdentityIndex.validateNoOverlappingVoiceEvents() {
    measures.forEach { measure ->
        measure.events.groupBy { it.staff to it.voice }.forEach { (_, events) ->
            events.filterNot { it.isGrace }.sortedBy { it.onsetDivisions }.zipWithNext().forEach { (left, right) ->
                if (left.onsetDivisions + left.durationDivisions > right.onsetDivisions) {
                    fail("Editing would create overlapping events in measure ${measure.source.measureNumber}.")
                }
            }
        }
    }
}

private fun EditableScoreIdentityIndex.validateTimeChange(
    target: EditableMeasureRef,
    signature: EditorTimeSignature,
    rangeEndIdentity: MeasureIdentity?
) {
    val partMeasures = measures.filter { it.source.partIndex == target.source.partIndex }
        .sortedBy { it.source.measureIndex }
    val start = partMeasures.indexOfFirst { it.identity == target.identity }
    if (start < 0) fail("The time-signature measure no longer exists.")
    val explicitEnd = rangeEndIdentity?.let { identity ->
        partMeasures.indexOfFirst { it.identity == identity }.takeIf { it >= start }
            ?: fail("The selected measure range is invalid.")
    }
    val nextChange = partMeasures.indexOfFirst { measure ->
        measure.source.measureIndex > target.source.measureIndex && measure.timeSignatures.any { it.onsetDivisions == 0 }
    }.takeIf { it >= 0 }
    val end = explicitEnd ?: nextChange?.minus(1) ?: partMeasures.lastIndex
    for (index in start..end) {
        val measure = partMeasures[index]
        val capacityNumerator = measure.divisions.toLong() * 4L * signature.beats
        if (capacityNumerator % signature.beatType != 0L) {
            fail("${signature.beats}/${signature.beatType} cannot be represented by measure ${measure.source.measureNumber}'s divisions.")
        }
        val capacity = capacityNumerator / signature.beatType
        val used = measure.events.filterNot { it.isGrace }.groupBy { it.staff to it.voice }
            .values.maxOfOrNull { voice -> voice.maxOfOrNull { it.onsetDivisions + it.durationDivisions } ?: 0 } ?: 0
        if (used > capacity) {
            fail("Measure ${measure.source.measureNumber} contains $used divisions but ${signature.beats}/${signature.beatType} allows only $capacity.")
        }
    }
}

private fun Document.restoreTimeAfterRange(
    index: EditableScoreIdentityIndex,
    target: EditableMeasureRef,
    rangeEndIdentity: MeasureIdentity,
    replacing: com.sheetsight.app.ui.editor.identity.EditableTimeSignatureRef?
) {
    val end = index.measures.unique(rangeEndIdentity, "range end")
    if (end.source.partIndex != target.source.partIndex || end.source.measureIndex < target.source.measureIndex) {
        fail("The selected time-signature range is invalid.")
    }
    val restoreAt = index.measureAfter(rangeEndIdentity, target.source.partIndex)
        ?: fail("A bounded time-signature range must end before the score ends.")
    val previous = replacing ?: index.activeTimeBefore(target)
        ?: fail("No preceding time signature is available to restore after the selected range.")
    val id = uniqueSheetSightId("time-restore", rangeEndIdentity.value)
    insertBoundaryAttribute(restoreAt.source, "time", id) { element ->
        if (previous.staff != 1) element.setAttribute("number", previous.staff.toString())
        element.writeTimeSignature(EditorTimeSignature(previous.beats, previous.beatType, previous.symbol))
    }
}

private fun EditableScoreIdentityIndex.activeTimeBefore(target: EditableMeasureRef) = timeSignatures
    .filter { it.source.partIndex == target.source.partIndex && it.source.measureIndex <= target.source.measureIndex }
    .maxWithOrNull(compareBy({ it.source.measureIndex }, { it.onsetDivisions }, { it.occurrenceInMeasure }))

private fun EditableScoreIdentityIndex.activeClefBefore(target: EditableMeasureRef, staff: Int) = clefs
    .filter {
        it.source.partIndex == target.source.partIndex && it.staff == staff &&
            it.source.measureIndex <= target.source.measureIndex
    }.maxWithOrNull(compareBy({ it.source.measureIndex }, { it.onsetDivisions }, { it.occurrenceInMeasure }))

private fun EditableScoreIdentityIndex.measureAfter(identity: MeasureIdentity, partIndex: Int): EditableMeasureRef? {
    val current = measures.unique(identity, "range end")
    return measures.singleOrNull {
        it.source.partIndex == partIndex && it.source.measureIndex == current.source.measureIndex + 1
    }
}

private fun EditableScoreIdentityIndex.measureFor(source: MusicXmlElementRef): EditableMeasureRef = measures.singleOrNull {
    it.source.partIndex == source.partIndex && it.source.measureIndex == source.measureIndex
} ?: fail("The target measure is missing or ambiguous.")

private fun <T> List<T>.unique(identity: Any, label: String): T {
    val matches = filter { candidate ->
        when (candidate) {
            is com.sheetsight.app.ui.editor.identity.EditableNoteRef -> candidate.identity == identity
            is com.sheetsight.app.ui.editor.identity.EditableRestRef -> candidate.identity == identity
            is com.sheetsight.app.ui.editor.identity.EditableClefRef -> candidate.identity == identity
            is com.sheetsight.app.ui.editor.identity.EditableTimeSignatureRef -> candidate.identity == identity
            is EditableMeasureRef -> candidate.identity == identity
            else -> false
        }
    }
    return matches.singleOrNull() ?: fail("The $label identity is missing or ambiguous.")
}

private fun Document.measure(source: MusicXmlElementRef): Element {
    val part = documentElement?.directChildren("part")?.getOrNull(source.partIndex)
        ?: fail("The target part no longer exists.")
    return part.directChildren("measure").getOrNull(source.measureIndex)
        ?: fail("The target measure no longer exists.")
}

private fun Element.note(source: MusicXmlElementRef): Element = source.noteElementIndex
    ?.let { directChildren("note").getOrNull(it) }
    ?: fail("The target note no longer exists.")

private fun Document.attributeElement(source: MusicXmlElementRef, tag: String): Element {
    val occurrence = source.elementOccurrenceIndex ?: fail("The target $tag occurrence is missing.")
    return measure(source).directChildren("attributes").flatMap { it.directChildren(tag) }.getOrNull(occurrence)
        ?: fail("The target $tag no longer exists.")
}

private fun Document.insertBoundaryAttribute(
    source: MusicXmlElementRef,
    tag: String,
    id: String,
    configure: (Element) -> Unit
) {
    val measure = measure(source)
    val attributes = measure.directChildren("attributes").firstOrNull()
        ?: createElement("attributes").also { created ->
            measure.insertBefore(created, measure.firstChild)
        }
    val element = createElement(tag).apply { setAttribute("id", id) }
    configure(element)
    val order = mapOf("divisions" to 0, "key" to 1, "time" to 2, "staves" to 3, "clef" to 4)
    val targetOrder = order[tag] ?: Int.MAX_VALUE
    val before = attributes.directElements().firstOrNull { (order[it.tagName] ?: Int.MAX_VALUE) > targetOrder }
    attributes.insertBefore(element, before)
}

private fun Document.createPitchedNoteLike(
    template: Element,
    duration: Int,
    type: String,
    step: String,
    octave: Int,
    id: String
): Element = createElement("note").apply {
    setAttribute("id", id)
    appendChild(createElement("pitch").apply {
        appendTextChild("step", step)
        appendTextChild("octave", octave.toString())
    })
    appendTextChild("duration", duration.toString())
    template.directChild("voice")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTextChild("voice", it) }
    appendTextChild("type", type)
    template.directChild("staff")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTextChild("staff", it) }
}

private fun Document.createRestLike(template: Element, duration: Int, divisions: Int, idSeed: String): Element =
    createElement("note").apply {
        setAttribute("id", uniqueSheetSightId("rest", idSeed))
        appendChild(createElement("rest"))
        appendTextChild("duration", duration.toString())
        template.directChild("voice")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTextChild("voice", it) }
        durationType(duration, divisions)?.let { appendTextChild("type", it) }
        template.directChild("staff")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTextChild("staff", it) }
    }

private fun durationType(duration: Int, divisions: Int): String? = when (duration) {
    divisions * 4 -> "whole"
    divisions * 2 -> "half"
    divisions -> "quarter"
    else -> when {
        divisions % 2 == 0 && duration == divisions / 2 -> "eighth"
        divisions % 4 == 0 && duration == divisions / 4 -> "16th"
        else -> null
    }
}

private fun Element.writeTimeSignature(signature: EditorTimeSignature) {
    if (signature.symbol == null) removeAttribute("symbol") else setAttribute("symbol", signature.symbol)
    directChildren("beats").forEach(::removeChild)
    directChildren("beat-type").forEach(::removeChild)
    appendTextChild("beats", signature.beats.toString())
    appendTextChild("beat-type", signature.beatType.toString())
}

private fun Element.setOrCreateTextChild(name: String, value: String, before: String? = null) {
    val child = directChild(name) ?: ownerDocument.createElement(name).also { created ->
        insertBefore(created, before?.let(::directChild))
    }
    child.textContent = value
}

private fun Node.appendTextChild(name: String, value: String): Element = ownerDocument.createElement(name).also {
    it.textContent = value
    appendChild(it)
}

private fun Document.removeEmptyAttributes(attributes: Element) {
    if (attributes.directElements().isEmpty() && !attributes.hasAttributes()) {
        attributes.parentNode?.removeChild(attributes)
    }
}

private fun Document.uniqueSheetSightId(kind: String, seed: String): String {
    val normalized = seed.hashCode().toUInt().toString(16)
    val base = "sheetsight-$kind-$normalized"
    val existing = allElements().map { it.getAttribute("id") }.filter { it.isNotBlank() }.toSet()
    if (base !in existing) return base
    var suffix = 2
    while ("$base-$suffix" in existing) suffix++
    return "$base-$suffix"
}

private fun Document.allElements(): Sequence<Element> = sequence {
    val nodes = getElementsByTagName("*")
    for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.let { yield(it) }
}

private fun rejectUnsupportedRhythm(note: Element, allowRest: Boolean) {
    if (note.directChild("grace") != null) fail("Grace-note editing is not supported in this phase.")
    if (note.directChild("time-modification") != null) fail("Tuplet editing is not supported in this phase.")
    if (note.directChildren("dot").isNotEmpty()) fail("Editing dotted durations is not supported in this phase.")
    if (!allowRest && note.directChild("rest") != null) fail("The selected element is not a pitched note.")
}

private fun rejectGrace(grace: Boolean) {
    if (grace) fail("Grace-note editing is not supported in this phase.")
}

private fun hasTie(note: Element): Boolean = note.directChildren("tie").isNotEmpty() ||
    note.directChild("notations")?.directChildren("tied")?.isNotEmpty() == true

private fun noteIdentityForExplicitId(scoreId: Long, id: String): NoteIdentity =
    NoteIdentity("score/$scoreId/musicxml-id/${id.length}:$id/note")

private fun clefIdentityForExplicitId(scoreId: Long, id: String): ClefIdentity =
    ClefIdentity("score/$scoreId/musicxml-id/${id.length}:$id/clef")

private fun timeIdentityForExplicitId(scoreId: Long, id: String): TimeSignatureIdentity =
    TimeSignatureIdentity("score/$scoreId/musicxml-id/${id.length}:$id/time")

private fun Element.directElements(): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }

private fun Element.directChild(name: String): Element? = directChildren(name).firstOrNull()

private fun Element.directChildren(name: String): List<Element> = directElements().filter { it.tagName == name }

private val NATURAL_STEPS = setOf("A", "B", "C", "D", "E", "F", "G")

private fun fail(message: String): Nothing = throw MusicXmlEditException(message)
