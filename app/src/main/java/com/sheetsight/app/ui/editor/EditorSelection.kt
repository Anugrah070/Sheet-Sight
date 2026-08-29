@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.model.Beat
import alphaTab.model.Bar
import alphaTab.model.Note
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapping
import com.sheetsight.app.ui.editor.identity.BarlineSide
import com.sheetsight.app.ui.editor.identity.ChordIdentity
import com.sheetsight.app.ui.editor.identity.EditableBarlineRef
import com.sheetsight.app.ui.editor.identity.EditableClefRef
import com.sheetsight.app.ui.editor.identity.EditableChordRef
import com.sheetsight.app.ui.editor.identity.EditableMeasureRef
import com.sheetsight.app.ui.editor.identity.EditableNoteRef
import com.sheetsight.app.ui.editor.identity.EditableRestRef
import com.sheetsight.app.ui.editor.identity.EditableScoreIdentityIndex

/** Stable Editor selection. Renderer objects and coordinates never leave the UI mapping seam. */
sealed interface EditorSelection {
    val sourceKey: EditorSourceKey

    data class NoteSelection(
        override val sourceKey: EditorSourceKey,
        val chordIdentity: ChordIdentity,
        val note: EditableNoteRef
    ) : EditorSelection

    data class ChordSelection(
        override val sourceKey: EditorSourceKey,
        val chord: EditableChordRef
    ) : EditorSelection

    data class RestSelection(
        override val sourceKey: EditorSourceKey,
        val rest: EditableRestRef
    ) : EditorSelection

    data class ClefSelection(
        override val sourceKey: EditorSourceKey,
        val clef: EditableClefRef
    ) : EditorSelection

    data class BarlineSelection(
        override val sourceKey: EditorSourceKey,
        val barline: EditableBarlineRef
    ) : EditorSelection

    data class MeasureSelection(
        override val sourceKey: EditorSourceKey,
        val measure: EditableMeasureRef
    ) : EditorSelection
}

internal sealed interface AlphaTabSelectionHit {
    data class NoteHit(val note: Note) : AlphaTabSelectionHit
    data class ChordHit(val beat: Beat) : AlphaTabSelectionHit
    data class RestHit(val beat: Beat) : AlphaTabSelectionHit
    data class ClefHit(val bar: Bar) : AlphaTabSelectionHit
    data class BarlineHit(val bar: Bar, val side: BarlineSide) : AlphaTabSelectionHit
    data class MeasureHit(val bar: Bar) : AlphaTabSelectionHit
    data object Empty : AlphaTabSelectionHit
}

internal sealed interface AlphaTabRenderSelection {
    data class NoteSelection(val note: Note) : AlphaTabRenderSelection
    data class ChordSelection(val beat: Beat) : AlphaTabRenderSelection
    data class RestSelection(val beat: Beat) : AlphaTabRenderSelection
    data class ClefSelection(val bars: List<Bar>) : AlphaTabRenderSelection
    data class BarlineSelection(val bars: List<Bar>, val side: BarlineSide) : AlphaTabRenderSelection
    data class MeasureSelection(val bars: List<Bar>) : AlphaTabRenderSelection
}

internal data class EditorSelectionResolution(
    val selection: EditorSelection?,
    val diagnostic: String? = null
)

internal data class ExactNoteHeadBounds<T : Any>(
    val note: T,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

internal data class ExactHitPoint(val x: Double, val y: Double)

internal data class ExactElementBounds<T>(
    val element: T,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

internal object ExactElementHitTester {
    fun <T> findUnique(x: Double, y: Double, bounds: Iterable<ExactElementBounds<T>>): T? {
        val matches = linkedSetOf<T>()
        bounds.forEach { candidate ->
            if (candidate.isUsable() && x >= candidate.x && x <= candidate.x + candidate.width &&
                y >= candidate.y && y <= candidate.y + candidate.height
            ) {
                matches += candidate.element
            }
        }
        return matches.singleOrNull()
    }

    fun <T> findUniquePoint(element: T, bounds: Iterable<ExactElementBounds<T>>): ExactHitPoint? {
        val all = bounds.filter { it.isUsable() }.toList()
        all.filter { it.element == element }.forEach { target ->
            val right = target.x + target.width
            val bottom = target.y + target.height
            val overlapping = all.filter { candidate ->
                candidate.element != element && candidate.x < right && candidate.x + candidate.width > target.x &&
                    candidate.y < bottom && candidate.y + candidate.height > target.y
            }
            val xEdges = (listOf(target.x, right) + overlapping.flatMap {
                listOf(it.x.coerceIn(target.x, right), (it.x + it.width).coerceIn(target.x, right))
            }).distinct().sorted()
            val yEdges = (listOf(target.y, bottom) + overlapping.flatMap {
                listOf(it.y.coerceIn(target.y, bottom), (it.y + it.height).coerceIn(target.y, bottom))
            }).distinct().sorted()
            for (xi in 0 until xEdges.lastIndex) for (yi in 0 until yEdges.lastIndex) {
                val point = ExactHitPoint(
                    (xEdges[xi] + xEdges[xi + 1]) / 2.0,
                    (yEdges[yi] + yEdges[yi + 1]) / 2.0
                )
                if (findUnique(point.x, point.y, all) == element) return point
            }
        }
        return null
    }

    private fun <T> ExactElementBounds<T>.isUsable(): Boolean =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0
}

/** Exact alphaTab note-head matching with no padding or nearest-note fallback. */
internal object ExactNoteHeadHitTester {
    fun <T : Any> findUnique(
        x: Double,
        y: Double,
        bounds: Iterable<ExactNoteHeadBounds<T>>
    ): T? {
        val matches = java.util.IdentityHashMap<T, Unit>()
        bounds.forEach { candidate ->
            if (
                candidate.width > 0.0 && candidate.height > 0.0 &&
                x >= candidate.x && x <= candidate.x + candidate.width &&
                y >= candidate.y && y <= candidate.y + candidate.height
            ) {
                matches[candidate.note] = Unit
            }
        }
        return matches.keys.singleOrNull()
    }

    /** Finds a point inside the exact head rectangle that belongs to only this note. */
    fun <T : Any> findUniquePoint(
        note: T,
        bounds: Iterable<ExactNoteHeadBounds<T>>
    ): ExactHitPoint? {
        val all = bounds.toList()
        all.filter { it.note === note && it.width > 0.0 && it.height > 0.0 }.forEach { target ->
            val right = target.x + target.width
            val bottom = target.y + target.height
            val overlapping = all.filter { candidate ->
                candidate.note !== note && candidate.width > 0.0 && candidate.height > 0.0 &&
                    candidate.x < right && candidate.x + candidate.width > target.x &&
                    candidate.y < bottom && candidate.y + candidate.height > target.y
            }
            val xEdges = buildList {
                add(target.x)
                add(right)
                overlapping.forEach { candidate ->
                    add(candidate.x.coerceIn(target.x, right))
                    add((candidate.x + candidate.width).coerceIn(target.x, right))
                }
            }.distinct().sorted()
            val yEdges = buildList {
                add(target.y)
                add(bottom)
                overlapping.forEach { candidate ->
                    add(candidate.y.coerceIn(target.y, bottom))
                    add((candidate.y + candidate.height).coerceIn(target.y, bottom))
                }
            }.distinct().sorted()
            for (xIndex in 0 until xEdges.lastIndex) {
                for (yIndex in 0 until yEdges.lastIndex) {
                    val point = ExactHitPoint(
                        (xEdges[xIndex] + xEdges[xIndex + 1]) / 2.0,
                        (yEdges[yIndex] + yEdges[yIndex + 1]) / 2.0
                    )
                    if (findUnique(point.x, point.y, all) === note) return point
                }
            }
        }
        return null
    }
}

/** Resolves exact alphaTab objects through the score-scoped Phase 8.2 identity mapping. */
internal object EditorSelectionResolver {
    fun resolve(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        hit: AlphaTabSelectionHit
    ): EditorSelectionResolution {
        if (sourceKey.scoreId != identityIndex.scoreId || mapping.scoreId != identityIndex.scoreId) {
            return failure("Selection mapping belongs to another score.")
        }

        return when (hit) {
            AlphaTabSelectionHit.Empty -> EditorSelectionResolution(null)
            is AlphaTabSelectionHit.RestHit -> resolveRest(sourceKey, identityIndex, mapping, hit.beat)
            is AlphaTabSelectionHit.ClefHit -> resolveClef(sourceKey, identityIndex, mapping, hit.bar)
            is AlphaTabSelectionHit.BarlineHit -> resolveBarline(sourceKey, identityIndex, mapping, hit.bar, hit.side)
            is AlphaTabSelectionHit.MeasureHit -> resolveMeasure(sourceKey, identityIndex, mapping, hit.bar)
            is AlphaTabSelectionHit.NoteHit -> resolveNote(sourceKey, identityIndex, mapping, hit.note)
            is AlphaTabSelectionHit.ChordHit -> resolveChord(sourceKey, identityIndex, mapping, hit.beat)
        }
    }

    fun renderSelection(
        selection: EditorSelection?,
        sourceKey: EditorSourceKey,
        mapping: AlphaTabIdentityMapping
    ): AlphaTabRenderSelection? {
        if (selection == null || selection.sourceKey != sourceKey || mapping.scoreId != sourceKey.scoreId) {
            return null
        }
        return when (selection) {
            is EditorSelection.NoteSelection -> mapping.note(selection.note.identity)
                ?.let(AlphaTabRenderSelection::NoteSelection)
            is EditorSelection.ChordSelection -> mapping.chord(selection.chord.identity)
                ?.let(AlphaTabRenderSelection::ChordSelection)
            is EditorSelection.RestSelection -> mapping.rest(selection.rest.identity)
                ?.let(AlphaTabRenderSelection::RestSelection)
            is EditorSelection.ClefSelection -> mapping.clef(selection.clef.identity)
                .takeIf { it.isNotEmpty() }?.let(AlphaTabRenderSelection::ClefSelection)
            is EditorSelection.BarlineSelection -> mapping.barline(selection.barline.identity)
                .takeIf { it.isNotEmpty() }
                ?.let { AlphaTabRenderSelection.BarlineSelection(it, selection.barline.side) }
            is EditorSelection.MeasureSelection -> mapping.measure(selection.measure.identity)
                .takeIf { it.isNotEmpty() }?.let(AlphaTabRenderSelection::MeasureSelection)
        }
    }

    private fun resolveNote(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        note: Note
    ): EditorSelectionResolution {
        val noteIdentity = mapping.noteIdentity(note)
            ?: return failure("Rendered note has no unique stable NoteIdentity.")
        val editableNote = identityIndex.notes.singleOrNull { it.identity == noteIdentity }
            ?: return failure("Stable NoteIdentity did not resolve to exactly one EditableNoteRef.")
        val chord = identityIndex.chords.singleOrNull { candidate ->
            candidate.notes.any { it.identity == noteIdentity }
        } ?: return failure("Selected note did not resolve to exactly one stable chord.")
        return EditorSelectionResolution(
            EditorSelection.NoteSelection(sourceKey, chord.identity, editableNote)
        )
    }

    private fun resolveChord(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        beat: Beat
    ): EditorSelectionResolution {
        val chordIdentity = mapping.chordIdentity(beat)
            ?: return failure("Rendered beat has no stable ChordIdentity.")
        val chord = identityIndex.chords.singleOrNull { it.identity == chordIdentity }
            ?: return failure("Stable ChordIdentity did not resolve to exactly one EditableChordRef.")
        return EditorSelectionResolution(EditorSelection.ChordSelection(sourceKey, chord))
    }

    private fun resolveRest(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        beat: Beat
    ): EditorSelectionResolution {
        val identity = mapping.restIdentity(beat)
            ?: return failure("Rendered rest has no stable RestIdentity.")
        val rest = identityIndex.rests.singleOrNull { it.identity == identity }
            ?: return failure("Stable RestIdentity did not resolve to exactly one MusicXML rest.")
        return EditorSelectionResolution(EditorSelection.RestSelection(sourceKey, rest))
    }

    private fun resolveClef(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        bar: Bar
    ): EditorSelectionResolution {
        val identity = mapping.clefIdentity(bar)
            ?: return failure("Rendered clef has no stable ClefIdentity.")
        val clef = identityIndex.clefs.singleOrNull { it.identity == identity }
            ?: return failure("Stable ClefIdentity did not resolve to exactly one MusicXML clef occurrence.")
        return EditorSelectionResolution(EditorSelection.ClefSelection(sourceKey, clef))
    }

    private fun resolveBarline(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        bar: Bar,
        side: BarlineSide
    ): EditorSelectionResolution {
        val identity = mapping.barlineIdentity(bar, side)
            ?: return failure("Rendered barline has no stable BarlineIdentity.")
        val barline = identityIndex.barlines.singleOrNull { it.identity == identity }
            ?: return failure("Stable BarlineIdentity did not resolve to exactly one MusicXML/structural barline.")
        return EditorSelectionResolution(EditorSelection.BarlineSelection(sourceKey, barline))
    }

    private fun resolveMeasure(
        sourceKey: EditorSourceKey,
        identityIndex: EditableScoreIdentityIndex,
        mapping: AlphaTabIdentityMapping,
        bar: Bar
    ): EditorSelectionResolution {
        val identity = mapping.measureIdentity(bar)
            ?: return failure("Rendered measure region has no stable MeasureIdentity.")
        val measure = identityIndex.measures.singleOrNull { it.identity == identity }
            ?: return failure("Stable MeasureIdentity did not resolve to exactly one MusicXML measure.")
        return EditorSelectionResolution(EditorSelection.MeasureSelection(sourceKey, measure))
    }

    private fun failure(message: String) = EditorSelectionResolution(null, message)
}
