package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import com.sheetsight.app.ui.editor.identity.NoteIdentity
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

data class SelectedNotePitchEdit(
    val musicXmlBytes: ByteArray,
    val noteIdentity: NoteIdentity,
    val pitchMidi: Int,
    val pitchStep: String,
    val pitchOctave: Int
)

enum class NaturalNoteDirection { DOWN, UP }

class SelectedNotePitchEditException(message: String) : IllegalArgumentException(message)

/** Mutates one structurally identified pitched MusicXML note and nothing else. */
internal object SelectedNotePitchEditor {
    fun edit(
        scoreId: Long,
        sourceBytes: ByteArray,
        noteIdentity: NoteIdentity,
        direction: NaturalNoteDirection
    ): SelectedNotePitchEdit {
        val document = MusicXmlParser.parseBytes(sourceBytes)
        val index = MusicXmlIdentityBuilder.build(scoreId, document)
        val noteRef = index.notes.singleOrNull { it.identity == noteIdentity }
            ?: throw SelectedNotePitchEditException("The selected note identity is missing or ambiguous.")
        if (noteRef.pitchMidi == null) throw SelectedNotePitchEditException(
            "The selected MusicXML element is not a pitched note."
        )

        val root = document.documentElement
            ?: throw SelectedNotePitchEditException("MusicXML has no document element.")
        val part = root.directChildren("part").getOrNull(noteRef.source.partIndex)
            ?: throw SelectedNotePitchEditException("The selected MusicXML part no longer exists.")
        val measure = part.directChildren("measure").getOrNull(noteRef.source.measureIndex)
            ?: throw SelectedNotePitchEditException("The selected MusicXML measure no longer exists.")
        val note = noteRef.source.noteElementIndex?.let { measure.directChildren("note").getOrNull(it) }
            ?: throw SelectedNotePitchEditException("The selected MusicXML note no longer exists.")
        val pitch = note.directChildren("pitch").singleOrNull()
            ?: throw SelectedNotePitchEditException("The selected note does not contain exactly one pitch.")
        val step = pitch.directChildren("step").singleOrNull()
            ?: throw SelectedNotePitchEditException("The selected pitch does not contain exactly one step.")
        val octave = pitch.directChildren("octave").singleOrNull()
            ?: throw SelectedNotePitchEditException("The selected pitch does not contain exactly one octave.")
        val currentStep = step.textContent.trim().uppercase()
        val currentOctave = octave.textContent.trim().toIntOrNull()
            ?: throw SelectedNotePitchEditException("The selected pitch octave is invalid.")
        val currentIndex = NATURAL_STEPS.indexOf(currentStep).takeIf { it >= 0 }
            ?: throw SelectedNotePitchEditException("The selected pitch step is invalid.")
        val targetIndex = when (direction) {
            NaturalNoteDirection.UP -> (currentIndex + 1) % NATURAL_STEPS.size
            NaturalNoteDirection.DOWN -> (currentIndex - 1 + NATURAL_STEPS.size) % NATURAL_STEPS.size
        }
        val targetOctave = currentOctave + when {
            direction == NaturalNoteDirection.UP && currentStep == "B" -> 1
            direction == NaturalNoteDirection.DOWN && currentStep == "C" -> -1
            else -> 0
        }
        val targetStep = NATURAL_STEPS[targetIndex]
        val targetMidi = (targetOctave + 1) * 12 + NATURAL_SEMITONES.getValue(targetStep)
        if (targetMidi !in MIN_MIDI..MAX_MIDI) {
            throw SelectedNotePitchEditException("The requested natural note is outside the supported MIDI range.")
        }

        step.textContent = targetStep
        octave.textContent = targetOctave.toString()
        pitch.directChildren("alter").forEach(pitch::removeChild)
        note.directChildren("accidental").forEach(note::removeChild)

        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().apply {
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, Charsets.UTF_8.name())
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
        val editedBytes = output.toByteArray()
        if (editedBytes.isEmpty()) throw SelectedNotePitchEditException("Pitch editing produced an empty MusicXML document.")

        val validated = MusicXmlParser.parseBytes(editedBytes)
        val validatedIndex = MusicXmlIdentityBuilder.build(scoreId, validated)
        val validatedNote = validatedIndex.notes.singleOrNull { it.identity == noteIdentity }
            ?: throw SelectedNotePitchEditException("The edited note identity could not be validated uniquely.")
        if (validatedNote.pitchMidi != targetMidi) {
            throw SelectedNotePitchEditException("The edited pitch did not validate.")
        }
        if (validatedNote.pitchStep != targetStep || validatedNote.pitchOctave != targetOctave) {
            throw SelectedNotePitchEditException("The edited natural-note spelling did not validate.")
        }
        val validatedRoot = validated.documentElement
        val validatedPart = validatedRoot.directChildren("part")[noteRef.source.partIndex]
        val validatedMeasure = validatedPart.directChildren("measure")[noteRef.source.measureIndex]
        val validatedElement = validatedMeasure.directChildren("note")[requireNotNull(noteRef.source.noteElementIndex)]
        val validatedPitch = validatedElement.directChildren("pitch").single()
        if (validatedPitch.directChildren("alter").isNotEmpty() ||
            validatedElement.directChildren("accidental").isNotEmpty()
        ) {
            throw SelectedNotePitchEditException("The edited note still contains an accidental.")
        }
        return SelectedNotePitchEdit(editedBytes, noteIdentity, targetMidi, targetStep, targetOctave)
    }

    private val NATURAL_STEPS = listOf("C", "D", "E", "F", "G", "A", "B")
    private val NATURAL_SEMITONES = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
    private const val MIN_MIDI = 0
    private const val MAX_MIDI = 127

    private fun Element.directChildren(tag: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName == tag }
}
