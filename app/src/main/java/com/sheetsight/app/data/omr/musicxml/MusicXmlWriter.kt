package com.sheetsight.app.data.omr.musicxml

import com.sheetsight.app.data.omr.semantic.AccidentalAlteration
import com.sheetsight.app.data.omr.semantic.MeasureBoundaryEvidence
import com.sheetsight.app.data.omr.semantic.PitchStep
import com.sheetsight.app.data.omr.semantic.SemanticBarline
import com.sheetsight.app.data.omr.semantic.SemanticChord
import com.sheetsight.app.data.omr.semantic.SemanticClef
import com.sheetsight.app.data.omr.semantic.SemanticClefChange
import com.sheetsight.app.data.omr.semantic.SemanticDuration
import com.sheetsight.app.data.omr.semantic.SemanticEvent
import com.sheetsight.app.data.omr.semantic.SemanticKeySignature
import com.sheetsight.app.data.omr.semantic.SemanticMeasure
import com.sheetsight.app.data.omr.semantic.SemanticNote
import com.sheetsight.app.data.omr.semantic.SemanticPart
import com.sheetsight.app.data.omr.semantic.SemanticRest
import com.sheetsight.app.data.omr.semantic.SemanticRhythmState
import com.sheetsight.app.data.omr.semantic.SemanticScore
import com.sheetsight.app.data.omr.semantic.SemanticSourceKind
import com.sheetsight.app.data.omr.semantic.SemanticStemDirection
import com.sheetsight.app.data.omr.semantic.SemanticSystem
import com.sheetsight.app.data.omr.semantic.SemanticTimeSignature
import com.sheetsight.app.data.omr.semantic.SemanticValidationCode
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Deterministic, Android-UI-independent MusicXML 4.0 serializer.
 *
 * Staff simultaneity, voices, cursor movement, and checkpoint balancing are
 * verified against oemer 0.1.8 `build_system.py::Measure.align_symbols` and
 * `MusicXMLBuilder.build` (wheel lines 251-385 and 607-665). The upstream
 * `ete.py` delegates MusicXML construction to that builder.
 */
object MusicXmlWriter {
    const val DEFAULT_PART_NAME = "Music"

    private const val MUSICXML_PUBLIC_ID = "-//Recordare//DTD MusicXML 4.0 Partwise//EN"
    private const val MUSICXML_SYSTEM_ID = "http://www.musicxml.org/dtds/partwise.dtd"
    private val xmlIdPattern = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

    fun serialize(
        score: SemanticScore,
        partName: String = DEFAULT_PART_NAME
    ): MusicXmlSerializationResult {
        val structuralErrors = validateSemanticStructure(score)
        if (structuralErrors.isNotEmpty()) {
            return invalidResult(structuralErrors)
        }

        val part = score.parts.single()
        val measureContexts = part.systems.flatMap { system ->
            system.measures.map { measure -> MeasureContext(system, measure) }
        }
        val durations = resolvedDurations(measureContexts)
        val divisions = divisionsFor(durations.values)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val render = RenderState(
            document = document,
            divisions = divisions,
            durationByEventId = durations,
            warnings = semanticWarnings(score)
        )

        val root = document.createElement("score-partwise").apply {
            setAttribute("version", "4.0")
        }
        document.appendChild(root)
        root.child("part-list").child("score-part").apply {
            setAttribute("id", part.id)
            child("part-name", partName.ifBlank { DEFAULT_PART_NAME })
        }
        val musicXmlPart = root.child("part").apply { setAttribute("id", part.id) }

        var previousStaffCount: Int? = null
        measureContexts.forEachIndexed { index, context ->
            val measureElement = musicXmlPart.child("measure").apply {
                setAttribute("number", (index + 1).toString())
                if (context.measure.boundary.leftEvidence == MeasureBoundaryEvidence.STAFF_EXTENT ||
                    context.measure.boundary.rightEvidence == MeasureBoundaryEvidence.STAFF_EXTENT
                ) {
                    setAttribute("implicit", "yes")
                }
            }
            render.exportedMeasureCount++

            if (index > 0 && measureContexts[index - 1].system.id != context.system.id) {
                measureElement.child("print").setAttribute("new-system", "yes")
            }

            val staffCount = context.system.staffs.size
            if (index == 0 || staffCount != previousStaffCount) {
                measureElement.child("attributes").apply {
                    if (index == 0) child("divisions", divisions)
                    child("staves", staffCount)
                    if (staffCount > 1) child("part-symbol", "brace")
                }
            }
            previousStaffCount = staffCount

            render.writeMeasure(measureElement, context)
        }

        val generatedErrors = validateGeneratedDocument(document, part.id, render)
        if (generatedErrors.isNotEmpty()) {
            return invalidResult(generatedErrors, render.sortedWarnings())
        }

        return MusicXmlSerializationResult(
            xml = serializeDocument(document),
            exportedMeasureCount = render.exportedMeasureCount,
            exportedBarlineCount = render.exportedBarlineCount,
            exportedBarlineLocations = render.exportedBarlineLocations.toList(),
            exportedNoteCount = render.exportedNoteCount,
            exportedChordCount = render.exportedChordCount,
            exportedRestCount = render.exportedRestCount,
            omittedUnresolvedEventCount = render.omittedSemanticIds.size,
            warnings = render.sortedWarnings(),
            validationStatus = MusicXmlValidationStatus.VALID
        )
    }

    private fun invalidResult(
        errors: List<String>,
        warnings: List<MusicXmlExportWarning> = emptyList()
    ) = MusicXmlSerializationResult(
        xml = null,
        exportedMeasureCount = 0,
        exportedBarlineCount = 0,
        exportedBarlineLocations = emptyList(),
        exportedNoteCount = 0,
        exportedChordCount = 0,
        exportedRestCount = 0,
        omittedUnresolvedEventCount = 0,
        warnings = warnings,
        validationStatus = MusicXmlValidationStatus.INVALID,
        validationErrors = errors.sorted()
    )

    private fun validateSemanticStructure(score: SemanticScore): List<String> {
        val errors = mutableListOf<String>()
        if (score.parts.size != 1) {
            errors += "MusicXML export requires exactly one semantic part; found ${score.parts.size}"
            return errors
        }

        val part = score.parts.single()
        if (!xmlIdPattern.matches(part.id)) {
            errors += "semantic part id '${part.id}' is not a valid stable XML ID"
        }
        val measureContexts = part.systems.flatMap { system ->
            system.measures.map { measure -> MeasureContext(system, measure) }
        }
        if (measureContexts.isEmpty()) {
            errors += "semantic score contains no measures"
        }
        if (part.systems.map { it.index } != part.systems.map { it.index }.sorted() ||
            part.systems.map { it.index }.distinct().size != part.systems.size
        ) {
            errors += "semantic systems are not in unique ascending index order"
        }
        val measureIndices = measureContexts.map { it.measure.index }
        if (measureIndices.zipWithNext().any { (left, right) -> left >= right }) {
            errors += "semantic measures are not in strictly ascending index order"
        }

        val semanticIds = mutableListOf(part.id)
        part.systems.forEach { system ->
            semanticIds += system.id
            if (system.staffs.map { it.index } != system.staffs.indices.toList()) {
                errors += "staffs in ${system.id} are not in contiguous semantic order"
            }
            system.staffs.forEach { staff ->
                semanticIds += staff.id
                if (staff.systemId != system.id) {
                    errors += "${staff.id} declares ${staff.systemId} but is stored in ${system.id}"
                }
            }
            system.measures.forEach { measure ->
                semanticIds += measure.id
                if (measure.systemId != system.id) {
                    errors += "${measure.id} declares ${measure.systemId} but is stored in ${system.id}"
                }
                if (measure.events.zipWithNext().any { (left, right) ->
                        left.horizontalPosition > right.horizontalPosition
                    }
                ) {
                    errors += "events in ${measure.id} do not preserve source ordering"
                }
                measure.events.forEach { event ->
                    semanticIds += event.id
                    if (event.measureId != measure.id) {
                        errors += "${event.id} declares ${event.measureId} but is stored in ${measure.id}"
                    }
                    if (event is SemanticChord) {
                        event.notes.forEach { note ->
                            semanticIds += note.id
                            if (note.measureId != measure.id) {
                                errors += "${note.id} declares ${note.measureId} but is stored in ${measure.id}"
                            }
                        }
                    }
                }
            }
        }
        semanticIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach { id ->
            errors += "duplicate semantic id '$id' cannot be exported stably"
        }
        return errors.distinct()
    }

    private fun semanticWarnings(score: SemanticScore): MutableList<MusicXmlExportWarning> =
        score.validationWarnings
            .mapTo(mutableListOf()) {
                MusicXmlExportWarning(
                    when (it.code) {
                        SemanticValidationCode.UNRESOLVED_PITCH -> MusicXmlExportWarningCode.UNRESOLVED_PITCH
                        SemanticValidationCode.UNRESOLVED_DURATION -> MusicXmlExportWarningCode.UNRESOLVED_DURATION
                        else -> MusicXmlExportWarningCode.SEMANTIC_VALIDATION
                    },
                    "${it.code}: ${it.message}",
                    it.semanticId
                )
            }

    private fun resolvedDurations(contexts: List<MeasureContext>): Map<String, DurationInfo> = buildMap {
        contexts.forEach { context ->
            context.measure.events.forEach { event ->
                when (event) {
                    is SemanticChord -> if (event.rhythmState == SemanticRhythmState.RESOLVED) {
                        event.duration?.let { duration ->
                            event.augmentationDots?.let { dots ->
                                durationInfo(duration, dots)?.let { put(event.id, it) }
                            }
                        }
                    }
                    is SemanticRest -> if (event.rhythmState == SemanticRhythmState.RESOLVED) {
                        event.duration?.let { duration ->
                            durationInfo(duration, event.augmentationDots)?.let { put(event.id, it) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun durationInfo(duration: SemanticDuration, dots: Int): DurationInfo? {
        if (dots !in 0..8) return null
        val dotPower = 1L shl dots
        val multiplierNumerator = dotPower * 2L - 1L
        var baseNumerator = duration.numerator.toLong() * dotPower
        var baseDenominator = duration.denominator.toLong() * multiplierNumerator
        val divisor = gcd(baseNumerator, baseDenominator)
        baseNumerator /= divisor
        baseDenominator /= divisor
        if (baseNumerator != 1L) return null
        val type = when (baseDenominator) {
            1L -> "whole"
            2L -> "half"
            4L -> "quarter"
            8L -> "eighth"
            16L -> "16th"
            32L -> "32nd"
            64L -> "64th"
            else -> return null
        }
        return DurationInfo(duration, type, dots)
    }

    private fun durationInfoForUnits(units: Long, divisions: Long): DurationInfo? {
        if (units <= 0L) return null
        val types = listOf(
            1L to "whole",
            2L to "half",
            4L to "quarter",
            8L to "eighth",
            16L to "16th",
            32L to "32nd",
            64L to "64th"
        )
        types.forEach { (baseDenominator, type) ->
            for (dots in 0..8) {
                val dotPower = 1L shl dots
                val numerator = dotPower * 2L - 1L
                val denominator = baseDenominator * dotPower
                val scaledNumerator = numerator * 4L * divisions
                if (scaledNumerator % denominator != 0L || scaledNumerator / denominator != units) continue
                val divisor = gcd(numerator, denominator)
                return DurationInfo(
                    SemanticDuration(
                        (numerator / divisor).toInt(),
                        (denominator / divisor).toInt()
                    ),
                    type,
                    dots
                )
            }
        }
        return null
    }

    private fun divisionsFor(durations: Collection<DurationInfo>): Long =
        durations.fold(1L) { divisions, info ->
            val quarterNumerator = info.duration.numerator.toLong() * 4L
            val denominator = info.duration.denominator.toLong()
            val reducedDenominator = denominator / gcd(quarterNumerator, denominator)
            lcm(divisions, reducedDenominator)
        }

    private fun validateGeneratedDocument(
        document: Document,
        partId: String,
        render: RenderState
    ): List<String> {
        val errors = mutableListOf<String>()
        val root = document.documentElement
        if (root.tagName != "score-partwise" || root.getAttribute("version") != "4.0") {
            errors += "generated root is not score-partwise MusicXML 4.0"
        }
        val scoreParts = document.getElementsByTagName("score-part")
        val parts = document.getElementsByTagName("part")
        if (scoreParts.length != 1 || parts.length != 1) {
            errors += "generated document must contain exactly one score-part and one part"
        } else if ((scoreParts.item(0) as Element).getAttribute("id") != partId ||
            (parts.item(0) as Element).getAttribute("id") != partId
        ) {
            errors += "generated part IDs are not stable and matching"
        }

        val measures = document.getElementsByTagName("measure")
        for (index in 0 until measures.length) {
            val number = (measures.item(index) as Element).getAttribute("number").toIntOrNull()
            if (number != index + 1) errors += "generated measure numbers are not sequential"
        }
        if (measures.length != render.exportedMeasureCount) {
            errors += "generated measure count does not match export accounting"
        }
        val barlines = document.getElementsByTagName("barline")
        val barlineLocations = (0 until barlines.length).map { index ->
            (barlines.item(index) as Element).getAttribute("location")
        }
        if (barlines.length != render.exportedBarlineCount ||
            barlineLocations != render.exportedBarlineLocations
        ) {
            errors += "generated barline count/locations do not match export accounting"
        }
        if (barlineLocations.any { it !in setOf("left", "middle", "right") }) {
            errors += "generated barline has an unsupported location"
        }

        var generatedNoteCount = 0
        for (measureIndex in 0 until measures.length) {
            val measure = measures.item(measureIndex)
            var precedingPitchedNote = false
            for (childIndex in 0 until measure.childNodes.length) {
                val child = measure.childNodes.item(childIndex)
                if (child.nodeType != Node.ELEMENT_NODE) continue
                val element = child as Element
                if (element.tagName != "note") {
                    precedingPitchedNote = false
                    continue
                }
                val isChordFollower = element.directChild("chord") != null
                if (isChordFollower && !precedingPitchedNote) {
                    errors += "generated chord follower has no preceding pitched base note"
                }
                val duration = element.directChild("duration")?.textContent?.toLongOrNull()
                if (duration == null || duration <= 0) {
                    errors += "generated note/rest has a non-positive duration"
                }
                val pitch = element.directChild("pitch")
                if (pitch != null) {
                    generatedNoteCount++
                    val step = pitch.directChild("step")?.textContent
                    val octave = pitch.directChild("octave")?.textContent?.toIntOrNull()
                    if (step !in PitchStep.entries.map { it.name }) {
                        errors += "generated pitch step '$step' is invalid"
                    }
                    if (octave !in 0..9) errors += "generated pitch octave '$octave' is invalid"
                }
                precedingPitchedNote = pitch != null
            }
        }
        if (generatedNoteCount != render.exportedNoteCount) {
            errors += "generated note count does not match export accounting"
        }
        if (document.documentElement.textContent.contains("NaN") ||
            document.documentElement.textContent.contains("Infinity")
        ) {
            errors += "generated document contains a non-finite numeric value"
        }
        return errors.distinct()
    }

    private fun serializeDocument(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, MUSICXML_PUBLIC_ID)
            setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, MUSICXML_SYSTEM_ID)
            runCatching { setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
        }
        val output = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(output))
        return output.toString().replace("\r\n", "\n").trimEnd() + "\n"
    }

    private class RenderState(
        val document: Document,
        val divisions: Long,
        val durationByEventId: Map<String, DurationInfo>,
        val warnings: MutableList<MusicXmlExportWarning>
    ) {
        var exportedMeasureCount = 0
        var exportedBarlineCount = 0
        val exportedBarlineLocations = mutableListOf<String>()
        var exportedNoteCount = 0
        var exportedChordCount = 0
        var exportedRestCount = 0
        val omittedSemanticIds = linkedSetOf<String>()
        private var cursorUnits = 0L

        fun writeMeasure(parent: Element, context: MeasureContext) {
            cursorUnits = 0L
            val rhythmInputs = context.measure.events.mapIndexedNotNull { sourceOrder, event ->
                if (event !is SemanticChord && event !is SemanticRest) return@mapIndexedNotNull null
                val duration = durationByEventId[event.id] ?: return@mapIndexedNotNull null
                val staffIndex = context.system.staffs.indexOfFirst { it.id == event.staffId }
                if (staffIndex < 0) return@mapIndexedNotNull null
                MusicXmlRhythmInput(
                    eventId = event.id,
                    staffIndex = staffIndex,
                    horizontalPosition = event.horizontalPosition,
                    durationUnits = duration.units(divisions),
                    sourceOrder = sourceOrder
                )
            }
            val tolerance = context.system.staffs
                .mapNotNull { it.alignmentUnitSize?.takeIf { size -> size > 0.0 } }
                .average()
                .takeUnless { it.isNaN() }
                ?: 1.0
            val aligned = MusicXmlRhythmPlanner.plan(
                rhythmInputs,
                context.system.staffs.size,
                tolerance
            )
            val plan = if (aligned.entries.all { durationInfoForUnits(it.durationUnits, divisions) != null }) {
                aligned
            } else {
                warnings += MusicXmlExportWarning(
                    MusicXmlExportWarningCode.BEAT_ALIGNMENT_UNREPRESENTABLE,
                    "${context.measure.id} requires a compensating duration with no supported MusicXML type; " +
                        "staff cursor separation was preserved without beat adjustment",
                    context.measure.id
                )
                MusicXmlRhythmPlanner.plan(
                    rhythmInputs,
                    context.system.staffs.size,
                    tolerance,
                    adjustBeats = false
                )
            }
            val plannedByEventId = plan.entries
                .filter { it.eventId != null }
                .associateBy { requireNotNull(it.eventId) }
            val items = buildList {
                context.measure.events.forEachIndexed { sourceOrder, event ->
                    add(PlannedRenderItem(event.horizontalPosition, sourceOrder, event, plannedByEventId[event.id]))
                }
                plan.entries.filter { it.generatedRest }.forEach { entry ->
                    add(PlannedRenderItem(entry.horizontalPosition, entry.sourceOrder, null, entry))
                }
            }.sortedWith(
                compareBy<PlannedRenderItem> { it.horizontalPosition }
                    .thenBy { it.sourceOrder }
            )

            items.forEach { item ->
                val entry = item.rhythmEntry
                val event = item.event
                if (entry == null) {
                    if (event != null) writeEvent(parent, context, event)
                    return@forEach
                }
                val duration = requireNotNull(durationInfoForUnits(entry.durationUnits, divisions))
                moveCursor(parent, entry.onsetUnits)
                val emitted = when {
                    entry.generatedRest -> {
                        writeGeneratedRest(parent, entry.staffIndex + 1, duration, entry.voice)
                        true
                    }
                    event is SemanticChord -> writeChord(parent, context, event, duration, entry.voice)
                    event is SemanticRest -> writeRest(parent, context, event, duration, entry.voice)
                    else -> false
                }
                if (emitted) cursorUnits += entry.durationUnits
            }
        }

        fun writeEvent(parent: Element, context: MeasureContext, event: SemanticEvent) {
            when (event) {
                is SemanticClefChange -> writeClef(parent, context, event)
                is SemanticKeySignature -> writeKey(parent, context, event)
                is SemanticTimeSignature -> writeTime(parent, context, event)
                is SemanticChord -> writeChord(parent, context, event)
                is SemanticRest -> writeRest(parent, context, event)
                is SemanticBarline -> writeBarline(parent, context.measure, event)
                is SemanticNote -> omit(
                    event.id,
                    MusicXmlExportWarningCode.TOP_LEVEL_NOTE_UNSUPPORTED,
                    "${event.id} has no event-level duration; semantic notes are only safely exportable inside a chord"
                )
            }
        }

        private fun moveCursor(parent: Element, targetUnits: Long) {
            when {
                targetUnits < cursorUnits -> parent.child("backup").child("duration", cursorUnits - targetUnits)
                targetUnits > cursorUnits -> parent.child("forward").child("duration", targetUnits - cursorUnits)
            }
            cursorUnits = targetUnits
        }

        private fun writeClef(parent: Element, context: MeasureContext, event: SemanticClefChange) {
            val staffNumber = staffNumber(context.system, event.staffId) ?: return unknownStaff(event)
            parent.child("attributes").child("clef").apply {
                if (context.system.staffs.size > 1) setAttribute("number", staffNumber.toString())
                when (event.clef) {
                    SemanticClef.TREBLE -> {
                        child("sign", "G")
                        child("line", 2)
                    }
                    SemanticClef.BASS -> {
                        child("sign", "F")
                        child("line", 4)
                    }
                }
            }
        }

        private fun writeKey(parent: Element, context: MeasureContext, event: SemanticKeySignature) {
            val staffNumber = staffNumber(context.system, event.staffId) ?: return unknownStaff(event)
            val fifths = fifthsFor(event.alterations)
            if (fifths == null) {
                omit(
                    event.id,
                    MusicXmlExportWarningCode.UNRESOLVED_KEY_SIGNATURE,
                    "${event.id} is not a complete canonical sharp/flat key signature and was omitted"
                )
                return
            }
            parent.child("attributes").child("key").apply {
                if (context.system.staffs.size > 1) setAttribute("number", staffNumber.toString())
                child("fifths", fifths)
            }
        }

        private fun writeTime(parent: Element, context: MeasureContext, event: SemanticTimeSignature) {
            val staffNumber = staffNumber(context.system, event.staffId) ?: return unknownStaff(event)
            if (event.beats <= 0 || event.beatUnit <= 0) {
                omit(
                    event.id,
                    MusicXmlExportWarningCode.INVALID_TIME_SIGNATURE,
                    "${event.id} has a non-positive beats or beat-unit value and was omitted"
                )
                return
            }
            parent.child("attributes").child("time").apply {
                if (context.system.staffs.size > 1) setAttribute("number", staffNumber.toString())
                child("beats", event.beats)
                child("beat-type", event.beatUnit)
            }
        }

        private fun writeChord(
            parent: Element,
            context: MeasureContext,
            chord: SemanticChord,
            plannedDuration: DurationInfo? = null,
            voice: Int = 1
        ): Boolean {
            if (staffNumber(context.system, chord.staffId) == null) {
                unknownStaff(chord)
                return false
            }
            val duration = plannedDuration
                ?: checkedDuration(chord.id, chord.duration, chord.rhythmState)
                ?: return false
            val exportableNotes = chord.notes.mapNotNull { note ->
                checkedNote(context, note)
            }
            if (exportableNotes.isEmpty()) {
                if (chord.notes.isEmpty()) {
                    omit(
                        chord.id,
                        MusicXmlExportWarningCode.EMPTY_CHORD,
                        "${chord.id} contains no semantic note members"
                    )
                } else {
                    warnings += MusicXmlExportWarning(
                        MusicXmlExportWarningCode.EMPTY_CHORD,
                        "${chord.id} has no safely exportable pitched members",
                        chord.id
                    )
                }
                return false
            }

            exportableNotes.forEachIndexed { index, resolved ->
                val pitch = requireNotNull(resolved.note.pitch)
                val noteElement = parent.child("note")
                if (index > 0) noteElement.child("chord")
                noteElement.child("pitch").apply {
                    child("step", pitch.step.name)
                    child("alter", pitch.alteration.semitones)
                    child("octave", pitch.octave)
                }
                noteElement.child("duration", duration.units(divisions))
                noteElement.child("voice", voice)
                noteElement.child("type", duration.type)
                repeat(duration.dots) { noteElement.child("dot") }
                if (resolved.note.sourceRefs.any { it.kind == SemanticSourceKind.ACCIDENTAL }) {
                    noteElement.child("accidental", pitch.alteration.musicXmlName())
                }
                when (chord.stemDirection) {
                    SemanticStemDirection.UP -> noteElement.child("stem", "up")
                    SemanticStemDirection.DOWN -> noteElement.child("stem", "down")
                    SemanticStemDirection.NONE -> noteElement.child("stem", "none")
                    SemanticStemDirection.AMBIGUOUS -> Unit
                }
                noteElement.child("staff", resolved.staffNumber)
                exportedNoteCount++
            }
            exportedChordCount++
            return true
        }

        private fun checkedNote(context: MeasureContext, note: SemanticNote): ResolvedNote? {
            if (note.activeClef == null) {
                omit(
                    note.id,
                    MusicXmlExportWarningCode.MISSING_CLEF,
                    "${note.id} has no verified active clef; its pitch was not exported"
                )
                return null
            }
            val pitch = note.pitch
            if (pitch == null) {
                omit(
                    note.id,
                    MusicXmlExportWarningCode.UNRESOLVED_PITCH,
                    "${note.id} has no resolved pitch and was omitted"
                )
                return null
            }
            if (pitch.octave !in 0..9) {
                omit(
                    note.id,
                    MusicXmlExportWarningCode.INVALID_PITCH,
                    "${note.id} has MusicXML-incompatible octave ${pitch.octave} and was omitted"
                )
                return null
            }
            val staffNumber = staffNumber(context.system, note.staffId)
            if (staffNumber == null) {
                unknownStaff(note)
                return null
            }
            return ResolvedNote(note, staffNumber)
        }

        private fun writeRest(
            parent: Element,
            context: MeasureContext,
            rest: SemanticRest,
            plannedDuration: DurationInfo? = null,
            voice: Int = 1
        ): Boolean {
            val staffNumber = staffNumber(context.system, rest.staffId)
            if (staffNumber == null) {
                unknownStaff(rest)
                return false
            }
            val duration = plannedDuration
                ?: checkedDuration(rest.id, rest.duration, rest.rhythmState)
                ?: return false
            parent.child("note").apply {
                child("rest")
                child("duration", duration.units(divisions))
                child("voice", voice)
                child("type", duration.type)
                repeat(duration.dots) { child("dot") }
                child("staff", staffNumber)
            }
            exportedRestCount++
            return true
        }

        private fun writeGeneratedRest(
            parent: Element,
            staffNumber: Int,
            duration: DurationInfo,
            voice: Int
        ) {
            parent.child("note").apply {
                child("rest")
                child("duration", duration.units(divisions))
                child("voice", voice)
                child("type", duration.type)
                repeat(duration.dots) { child("dot") }
                child("staff", staffNumber)
            }
            exportedRestCount++
        }

        private fun checkedDuration(
            eventId: String,
            duration: SemanticDuration?,
            rhythmState: SemanticRhythmState
        ): DurationInfo? {
            if (duration == null || rhythmState != SemanticRhythmState.RESOLVED) {
                omit(
                    eventId,
                    MusicXmlExportWarningCode.UNRESOLVED_DURATION,
                    "$eventId has no fully resolved duration and was omitted"
                )
                return null
            }
            val info = durationByEventId[eventId]
            if (info == null) {
                omit(
                    eventId,
                    MusicXmlExportWarningCode.UNSUPPORTED_DURATION,
                    "$eventId has a duration/dot combination with no safe MusicXML note type and was omitted"
                )
            }
            return info
        }

        private fun writeBarline(parent: Element, measure: SemanticMeasure, event: SemanticBarline) {
            val location = when (event.horizontalPosition) {
                measure.boundary.left -> "left"
                measure.boundary.right -> "right"
                else -> "middle"
            }
            parent.child("barline").setAttribute("location", location)
            exportedBarlineCount++
            exportedBarlineLocations += location
        }

        private fun staffNumber(system: SemanticSystem, staffId: String?): Int? =
            system.staffs.indexOfFirst { it.id == staffId }.takeIf { it >= 0 }?.plus(1)

        private fun unknownStaff(event: SemanticEvent) {
            omit(
                event.id,
                MusicXmlExportWarningCode.UNKNOWN_STAFF,
                "${event.id} references staff '${event.staffId}' outside its semantic system"
            )
        }

        private fun omit(id: String, code: MusicXmlExportWarningCode, message: String) {
            omittedSemanticIds += id
            warnings += MusicXmlExportWarning(code, message, id)
        }

        fun sortedWarnings(): List<MusicXmlExportWarning> =
            warnings.distinctBy {
                Triple(
                    it.code,
                    it.semanticId,
                    if (it.code == MusicXmlExportWarningCode.SEMANTIC_VALIDATION) it.message else ""
                )
            }.sortedWith(exportWarningComparator)
    }

    private fun fifthsFor(alterations: Map<PitchStep, AccidentalAlteration>): Int? {
        if (alterations.isEmpty()) return null
        val sharpOrder = listOf(PitchStep.F, PitchStep.C, PitchStep.G, PitchStep.D, PitchStep.A, PitchStep.E, PitchStep.B)
        val flatOrder = listOf(PitchStep.B, PitchStep.E, PitchStep.A, PitchStep.D, PitchStep.G, PitchStep.C, PitchStep.F)
        val sharpCount = canonicalPrefixSize(alterations, sharpOrder, AccidentalAlteration.SHARP)
        if (sharpCount != null) return sharpCount
        return canonicalPrefixSize(alterations, flatOrder, AccidentalAlteration.FLAT)?.let { -it }
    }

    private fun canonicalPrefixSize(
        alterations: Map<PitchStep, AccidentalAlteration>,
        order: List<PitchStep>,
        expected: AccidentalAlteration
    ): Int? {
        if (alterations.values.any { it != expected }) return null
        val size = alterations.size
        return size.takeIf { alterations.keys == order.take(size).toSet() }
    }

    private fun AccidentalAlteration.musicXmlName(): String = when (this) {
        AccidentalAlteration.FLAT -> "flat"
        AccidentalAlteration.NATURAL -> "natural"
        AccidentalAlteration.SHARP -> "sharp"
    }

    private fun DurationInfo.units(divisions: Long): Long =
        duration.numerator.toLong() * 4L * divisions / duration.denominator.toLong()

    private fun Element.child(name: String, value: Any? = null): Element =
        ownerDocument.createElement(name).also { child ->
            if (value != null) child.textContent = value.toString()
            appendChild(child)
        }

    private fun Element.directChild(name: String): Element? =
        (0 until childNodes.length)
            .asSequence()
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.tagName == name }

    private tailrec fun gcd(left: Long, right: Long): Long =
        if (right == 0L) left else gcd(right, left % right)

    private fun lcm(left: Long, right: Long): Long = left / gcd(left, right) * right

    private data class MeasureContext(
        val system: SemanticSystem,
        val measure: SemanticMeasure
    )

    private data class DurationInfo(
        val duration: SemanticDuration,
        val type: String,
        val dots: Int
    )

    private data class PlannedRenderItem(
        val horizontalPosition: Int,
        val sourceOrder: Int,
        val event: SemanticEvent?,
        val rhythmEntry: MusicXmlRhythmEntry?
    )

    private data class ResolvedNote(
        val note: SemanticNote,
        val staffNumber: Int
    )
}
