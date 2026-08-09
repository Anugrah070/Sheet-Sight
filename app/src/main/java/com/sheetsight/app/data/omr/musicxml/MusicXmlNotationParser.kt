package com.sheetsight.app.data.omr.musicxml

import com.sheetsight.app.ui.editor.notation.NotationAccidental
import com.sheetsight.app.ui.editor.notation.NotationChord
import com.sheetsight.app.ui.editor.notation.NotationClef
import com.sheetsight.app.ui.editor.notation.NotationDurationType
import com.sheetsight.app.ui.editor.notation.NotationEvent
import com.sheetsight.app.ui.editor.notation.NotationMeasure
import com.sheetsight.app.ui.editor.notation.NotationArticulation
import com.sheetsight.app.ui.editor.notation.NotationNoteSemantics
import com.sheetsight.app.ui.editor.notation.NotationPitch
import com.sheetsight.app.ui.editor.notation.NotationRest
import com.sheetsight.app.ui.editor.notation.NotationStaff
import com.sheetsight.app.ui.editor.notation.NotationStatistics
import com.sheetsight.app.ui.editor.notation.NotationStem
import com.sheetsight.app.ui.editor.notation.NotationTimeSignature
import com.sheetsight.app.ui.editor.notation.ParsedNotationScore
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Converts a MusicXML 4.0 partwise DOM into the immutable notation subset used by the Editor. */
object MusicXmlNotationParser {
    internal fun parse(document: Document): ParsedNotationScore {
        val root = document.documentElement
            ?: throw UnsupportedMusicXmlException("The MusicXML document has no root element.")
        if (root.tagName != "score-partwise") {
            throw UnsupportedMusicXmlException("Only score-partwise MusicXML is supported.")
        }

        val parts = root.directChildren("part")
        if (parts.isEmpty()) throw UnsupportedMusicXmlException("The MusicXML contains no score part.")

        val unsupported = linkedMapOf<String, Int>()
        if (parts.size > 1) unsupported.add("part", parts.size - 1)
        val measures = mutableListOf<NotationMeasure>()
        val inheritedClefs = mutableMapOf<Int, NotationClef>()
        val inheritedKeys = mutableMapOf<Int, Int?>()
        val inheritedTimes = mutableMapOf<Int, NotationTimeSignature?>()
        var inheritedDivisions: Int? = null
        var detectedTempoBpm: Double? = null
        var declaredStaffCount = 1
        var noteCount = 0
        var chordCount = 0
        var restCount = 0
        var explicitBarlineCount = 0
        val explicitBarlineLocations = mutableListOf<String>()
        var maxStaffCount = 1

        parts.first().directChildren("measure").forEachIndexed { measureIndex, measureElement ->
            var startsNewSystem = false
            var startsNewPage = false
            val staffEvents = linkedMapOf<Int, MutableList<NotationEvent>>()
            val measureClefs = inheritedClefs.toMutableMap()
            val measureKeys = inheritedKeys.toMutableMap()
            val measureTimes = inheritedTimes.toMutableMap()
            var currentDivisions = inheritedDivisions
            var musicCursor = 0
            var sourceOrder = 0

            measureElement.directChildren().forEach { child ->
                when (child.tagName) {
                    "print" -> {
                        startsNewSystem = child.getAttribute("new-system") == "yes"
                        startsNewPage = child.getAttribute("new-page") == "yes"
                    }
                    "attributes" -> {
                        child.directChild("divisions")?.intText()?.takeIf { it > 0 }?.let {
                            currentDivisions = it
                        }
                        child.directChild("staves")?.intText()?.takeIf { it > 0 }?.let {
                            declaredStaffCount = it
                        }
                        child.directChildren("clef").forEach { clef ->
                            val staff = clef.staffNumber()
                            val parsed = when (clef.directChild("sign")?.textContent?.trim()) {
                                "G" -> NotationClef.TREBLE
                                "F" -> NotationClef.BASS
                                else -> NotationClef.UNSUPPORTED.also { unsupported.add("clef") }
                            }
                            measureClefs[staff] = parsed
                        }
                        child.directChildren("key").forEach { key ->
                            val staff = key.staffNumber()
                            measureKeys[staff] = key.directChild("fifths")?.intText()
                        }
                        child.directChildren("time").forEach { time ->
                            val staff = time.staffNumber()
                            val beats = time.directChild("beats")?.intText()
                            val beatType = time.directChild("beat-type")?.intText()
                            if (beats != null && beats > 0 && beatType != null && beatType > 0) {
                                measureTimes[staff] = NotationTimeSignature(beats, beatType)
                            } else {
                                unsupported.add("time")
                            }
                        }
                        val supported = setOf("divisions", "staves", "part-symbol", "clef", "key", "time")
                        child.directChildren().filter { it.tagName !in supported }.forEach {
                            unsupported.add("attributes/${it.tagName}")
                        }
                    }
                    "direction" -> {
                        val tempo = child.verifiedTempoBpm()
                        if (tempo != null) {
                            if (detectedTempoBpm == null) detectedTempoBpm = tempo
                        } else {
                            unsupported.add("direction")
                        }
                    }
                    "note" -> {
                        val staff = child.directChild("staff")?.intText()?.takeIf { it > 0 } ?: 1
                        declaredStaffCount = maxOf(declaredStaffCount, staff)
                        val isChordMember = child.directChild("chord") != null
                        val durationDivisions = child.directChild("duration")?.intText()?.takeIf { it > 0 }
                        val duration = child.directChild("type")?.textContent?.trim().toDurationType()
                        if (duration == NotationDurationType.UNKNOWN) {
                            unsupported.add("note/type")
                            if (!isChordMember) musicCursor += durationDivisions ?: 0
                            return@forEach
                        }
                        val dots = child.directChildren("dot").size
                        val voice = child.directChild("voice")?.intText()?.takeIf { it > 0 } ?: 1
                        val events = staffEvents.getOrPut(staff) { mutableListOf() }
                        val previousChord = events.lastOrNull() as? NotationChord
                        val onsetDivisions = if (isChordMember && previousChord != null) {
                            previousChord.onsetDivisions
                        } else {
                            musicCursor
                        }
                        if (child.directChild("rest") != null) {
                            events += NotationRest(
                                durationType = duration,
                                dots = dots,
                                voice = voice,
                                onsetDivisions = onsetDivisions,
                                sourceOrder = sourceOrder,
                                durationDivisions = durationDivisions,
                                divisionsPerQuarter = currentDivisions
                            )
                            restCount++
                        } else {
                            val pitch = child.directChild("pitch")?.toPitch(child, unsupported)
                            val noteSemantics = child.toNoteSemantics(unsupported)
                            if (pitch == null) {
                                unsupported.add("note/pitch")
                            } else {
                                noteCount++
                                if (isChordMember && previousChord != null) {
                                    events[events.lastIndex] = previousChord.copy(
                                        pitches = previousChord.pitches + pitch,
                                        noteSemantics = previousChord.noteSemantics + noteSemantics
                                    )
                                } else {
                                    if (isChordMember) unsupported.add("orphan-chord")
                                    events += NotationChord(
                                        pitches = listOf(pitch),
                                        durationType = duration,
                                        dots = dots,
                                        voice = voice,
                                        stem = child.directChild("stem")?.textContent?.trim().toStem(),
                                        onsetDivisions = onsetDivisions,
                                        sourceOrder = sourceOrder,
                                        durationDivisions = durationDivisions,
                                        divisionsPerQuarter = currentDivisions,
                                        noteSemantics = listOf(noteSemantics)
                                    )
                                    chordCount++
                                }
                            }
                        }
                        val supported = setOf(
                            "chord", "pitch", "rest", "duration", "voice", "type", "dot",
                            "accidental", "stem", "staff", "tie", "notations"
                        )
                        child.directChildren().filter { it.tagName !in supported }.forEach {
                            unsupported.add("note/${it.tagName}")
                        }
                        sourceOrder++
                        if (!isChordMember) musicCursor += durationDivisions ?: 0
                    }
                    "barline" -> {
                        val location = child.getAttribute("location").ifBlank { "right" }
                        if (location in setOf("left", "middle", "right")) {
                            explicitBarlineCount++
                            explicitBarlineLocations += location
                        } else {
                            unsupported.add("barline/location")
                        }
                    }
                    "backup" -> {
                        val duration = child.directChild("duration")?.intText()?.coerceAtLeast(0) ?: 0
                        musicCursor = (musicCursor - duration).coerceAtLeast(0)
                    }
                    "forward" -> {
                        val duration = child.directChild("duration")?.intText()?.coerceAtLeast(0) ?: 0
                        musicCursor += duration
                    }
                    else -> unsupported.add(child.tagName)
                }
            }

            maxStaffCount = maxOf(maxStaffCount, declaredStaffCount)
            val staffs = (1..declaredStaffCount).map { number ->
                NotationStaff(
                    number = number,
                    clef = measureClefs[number] ?: NotationClef.UNKNOWN,
                    keyFifths = measureKeys[number],
                    timeSignature = measureTimes[number],
                    events = staffEvents[number]?.toList().orEmpty()
                )
            }
            measures += NotationMeasure(
                number = measureElement.getAttribute("number").ifBlank { (measureIndex + 1).toString() },
                staffs = staffs,
                startsNewSystem = startsNewSystem,
                startsNewPage = startsNewPage,
                sourceIndex = measureIndex
            )
            inheritedClefs.putAll(measureClefs)
            inheritedKeys.putAll(measureKeys)
            inheritedTimes.putAll(measureTimes)
            inheritedDivisions = currentDivisions
        }

        if (measures.isEmpty()) throw UnsupportedMusicXmlException("The MusicXML contains no measures.")
        return ParsedNotationScore(
            measures = measures.toList(),
            statistics = NotationStatistics(
                measureCount = measures.size,
                staffCount = maxStaffCount,
                noteCount = noteCount,
                chordCount = chordCount,
                restCount = restCount,
                explicitBarlineCount = explicitBarlineCount,
                explicitBarlineLocations = explicitBarlineLocations.toList()
            ),
            unsupportedElements = unsupported.toSortedMap(),
            detectedTempoBpm = detectedTempoBpm
        )
    }

    /** MusicXML sound tempo is already quarter-note BPM; metronome marks are converted exactly. */
    private fun Element.verifiedTempoBpm(): Double? {
        directChildren("sound").firstNotNullOfOrNull { sound ->
            sound.getAttribute("tempo").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        }?.let { return it }

        val metronome = directChildren("direction-type")
            .flatMap { it.directChildren("metronome") }
            .firstOrNull() ?: return null
        val perMinute = metronome.directChild("per-minute")?.textContent?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val baseQuarterBeats = when (metronome.directChild("beat-unit")?.textContent?.trim()) {
            "whole" -> 4.0
            "half" -> 2.0
            "quarter" -> 1.0
            "eighth" -> 0.5
            "16th" -> 0.25
            "32nd" -> 0.125
            "64th" -> 0.0625
            else -> return null
        }
        val dotMultiplier = when (metronome.directChildren("beat-unit-dot").size) {
            0 -> 1.0
            1 -> 1.5
            2 -> 1.75
            3 -> 1.875
            else -> return null
        }
        return perMinute * baseQuarterBeats * dotMultiplier
    }

    private fun Element.toPitch(note: Element, unsupported: MutableMap<String, Int>): NotationPitch? {
        val step = directChild("step")?.textContent?.trim()?.singleOrNull()?.uppercaseChar()
            ?.takeIf { it in 'A'..'G' } ?: return null
        val octave = directChild("octave")?.intText()?.takeIf { it in 0..9 } ?: return null
        val alteration = directChild("alter")?.intText() ?: 0
        if (alteration !in -1..1) unsupported.add("pitch/alter")
        val accidental = when (note.directChild("accidental")?.textContent?.trim()) {
            null, "" -> null
            "flat" -> NotationAccidental.FLAT
            "natural" -> NotationAccidental.NATURAL
            "sharp" -> NotationAccidental.SHARP
            else -> null.also { unsupported.add("note/accidental") }
        }
        return NotationPitch(step, alteration, octave, accidental)
    }

    private fun Element.toNoteSemantics(unsupported: MutableMap<String, Int>): NotationNoteSemantics {
        val directTies = directChildren("tie").mapNotNull { it.getAttribute("type").ifBlank { null } }.toSet()
        val notationElements = directChildren("notations")
        val notationTies = notationElements.flatMap { it.directChildren("tied") }
            .mapNotNull { it.getAttribute("type").ifBlank { null } }
            .toSet()
        val slurs = notationElements.flatMap { it.directChildren("slur") }
            .mapNotNull { it.getAttribute("type").ifBlank { null } }
            .toSet()
        val articulationNames = notationElements
            .flatMap { it.directChildren("articulations") }
            .flatMap { it.directChildren() }
        val unknownArticulation = articulationNames.any { marking ->
            marking.tagName !in setOf("staccato", "tenuto", "accent", "strong-accent", "staccatissimo")
        }
        val articulations = articulationNames.mapNotNull { marking ->
            when (marking.tagName) {
                "staccato" -> NotationArticulation.STACCATO
                "tenuto" -> NotationArticulation.TENUTO
                "accent" -> NotationArticulation.ACCENT
                "strong-accent" -> NotationArticulation.STRONG_ACCENT
                "staccatissimo" -> NotationArticulation.STACCATISSIMO
                else -> null.also { unsupported.add("note/notations/articulations/${marking.tagName}") }
            }
        }.toSet() + notationElements.flatMap { it.directChildren("fermata") }.map {
            NotationArticulation.FERMATA
        }

        notationElements.forEach { notations ->
            notations.directChildren().filter {
                it.tagName !in setOf("tied", "slur", "articulations", "fermata")
            }.forEach { unsupported.add("note/notations/${it.tagName}") }
        }
        val knownDirectTieTypes = directTies.all { it == "start" || it == "stop" }
        val knownNotationTieTypes = notationTies.all { it == "start" || it == "stop" }
        val knownSlurTypes = slurs.all { it in setOf("start", "stop", "continue") }
        if (!knownDirectTieTypes) unsupported.add("note/tie/type")
        if (!knownNotationTieTypes) unsupported.add("note/notations/tied/type")
        if (!knownSlurTypes) unsupported.add("note/notations/slur/type")
        return NotationNoteSemantics(
            tieStart = "start" in directTies || "start" in notationTies,
            tieStop = "stop" in directTies || "stop" in notationTies,
            articulations = articulations,
            slurStart = "start" in slurs,
            slurStop = "stop" in slurs,
            hasUnknownNotation = unknownArticulation || !knownDirectTieTypes || !knownNotationTieTypes || !knownSlurTypes ||
                notationElements.any { notation ->
                    notation.directChildren().any {
                        it.tagName !in setOf("tied", "slur", "articulations", "fermata")
                    }
                }
        )
    }

    private fun String?.toDurationType(): NotationDurationType = when (this) {
        "whole" -> NotationDurationType.WHOLE
        "half" -> NotationDurationType.HALF
        "quarter" -> NotationDurationType.QUARTER
        "eighth" -> NotationDurationType.EIGHTH
        "16th" -> NotationDurationType.SIXTEENTH
        "32nd" -> NotationDurationType.THIRTY_SECOND
        "64th" -> NotationDurationType.SIXTY_FOURTH
        else -> NotationDurationType.UNKNOWN
    }

    private fun String?.toStem(): NotationStem = when (this) {
        "up" -> NotationStem.UP
        "down" -> NotationStem.DOWN
        "none" -> NotationStem.NONE
        else -> NotationStem.UNSPECIFIED
    }

    private fun Element.staffNumber(): Int = getAttribute("number").toIntOrNull()?.takeIf { it > 0 } ?: 1
    private fun Element.intText(): Int? = textContent.trim().toIntOrNull()
    private fun Element.directChild(name: String): Element? = directChildren(name).firstOrNull()
    private fun Element.directChildren(name: String): List<Element> = directChildren().filter { it.tagName == name }
    private fun Element.directChildren(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
    private fun MutableMap<String, Int>.add(name: String, count: Int = 1) {
        this[name] = getOrDefault(name, 0) + count
    }
}

class UnsupportedMusicXmlException(message: String) : Exception(message)
