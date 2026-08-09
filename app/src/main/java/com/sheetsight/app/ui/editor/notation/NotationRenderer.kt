package com.sheetsight.app.ui.editor.notation

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.sheetsight.app.R
import kotlin.math.abs
import kotlin.math.max

private val Paper = Color(0xFFFFFEFA)
private val Ink = Color(0xFF111111)

/** SMuFL glyphs supplied by the bundled Bravura music font. */
private object MusicGlyph {
    const val G_CLEF = "\uE050"
    const val F_CLEF = "\uE062"
    const val NOTEHEAD_WHOLE = "\uE0A2"
    const val NOTEHEAD_HALF = "\uE0A3"
    const val NOTEHEAD_BLACK = "\uE0A4"
    const val REST_WHOLE = "\uE4E3"
    const val REST_HALF = "\uE4E4"
    const val REST_QUARTER = "\uE4E5"
    const val REST_EIGHTH = "\uE4E6"
    const val REST_SIXTEENTH = "\uE4E7"
    const val REST_THIRTY_SECOND = "\uE4E8"
    const val REST_SIXTY_FOURTH = "\uE4E9"
    const val ACCIDENTAL_FLAT = "\uE260"
    const val ACCIDENTAL_NATURAL = "\uE261"
    const val ACCIDENTAL_SHARP = "\uE262"
    const val FLAG_EIGHTH_UP = "\uE240"
    const val FLAG_EIGHTH_DOWN = "\uE241"
    const val FLAG_SIXTEENTH_UP = "\uE242"
    const val FLAG_SIXTEENTH_DOWN = "\uE243"
    const val FLAG_THIRTY_SECOND_UP = "\uE244"
    const val FLAG_THIRTY_SECOND_DOWN = "\uE245"
    const val FLAG_SIXTY_FOURTH_UP = "\uE246"
    const val FLAG_SIXTY_FOURTH_DOWN = "\uE247"

    fun timeDigit(character: Char): String = (0xE080 + character.digitToInt()).toChar().toString()
}

private data class NotationPaints(
    val music: Paint,
    val measure: Paint
)

private data class ChordGeometry(
    val chord: NotationChord,
    val x: Float,
    val noteYs: List<Float>,
    val stemDown: Boolean,
    val stemX: Float,
    val stemAnchorY: Float,
    val defaultStemEndY: Float
)

@Composable
fun NotationSystemCard(
    system: NotationSystem,
    modifier: Modifier = Modifier,
    highlightedSourceIds: Set<String> = emptySet(),
    successSourceIds: Set<String> = emptySet(),
    showPracticePointer: Boolean = false
) {
    val context = LocalContext.current
    val paints = remember(context) {
        val musicTypeface = ResourcesCompat.getFont(context, R.font.bravura) ?: Typeface.DEFAULT
        NotationPaints(
            music = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = android.graphics.Color.rgb(17, 17, 17)
                typeface = musicTypeface
                textAlign = Paint.Align.LEFT
            },
            measure = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = android.graphics.Color.rgb(45, 45, 45)
                textSize = 17f
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
        )
    }
    val canvasHeight = NotationGeometry.systemHeightDp(system.staffCount)

    Surface(modifier = modifier, color = Paper, shadowElevation = 0.dp) {
        Column {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight.dp)
                    .background(Paper)
                    .then(
                        if (highlightedSourceIds.isNotEmpty()) Modifier.testTag("practice_highlight_overlay")
                        else Modifier
                    )
            ) {
                drawNotationSystem(
                    system = system,
                    paints = paints,
                    highlightedSourceIds = highlightedSourceIds,
                    successSourceIds = successSourceIds,
                    showPracticePointer = showPracticePointer
                )
            }
        }
    }
}

private fun DrawScope.drawNotationSystem(
    system: NotationSystem,
    paints: NotationPaints,
    highlightedSourceIds: Set<String>,
    successSourceIds: Set<String>,
    showPracticePointer: Boolean
) {
    if (system.measures.isEmpty()) return

    val geometry = NotationGeometry.layoutSystem(system, size.width, density)
    val left = geometry.left
    val right = geometry.right
    val top = geometry.top
    val staffGap = geometry.staffGap
    val space = geometry.staffSpace
    val bottom = geometry.bottom
    val hairline = max(0.7f, 0.65.dp.toPx())

    repeat(system.staffCount) { staffIndex ->
        val staffTop = top + staffIndex * staffGap
        repeat(5) { line ->
            val y = staffTop + line * space
            drawLine(Ink, Offset(left, y), Offset(right, y), strokeWidth = hairline)
        }
    }

    drawLine(Ink, Offset(left, top), Offset(left, bottom), strokeWidth = 1.1.dp.toPx())
    if (system.staffCount > 1) drawBrace(left - 8.5.dp.toPx(), top, bottom)

    geometry.measures.forEach { measureGeometry ->
        val measureIndex = measureGeometry.measureIndex
        val measure = measureGeometry.measure
        val measureLeft = measureGeometry.left
        val measureRight = measureGeometry.right

        if (measureIndex > 0) {
            drawLine(Ink, Offset(measureLeft, top), Offset(measureLeft, bottom), strokeWidth = hairline)
        }
        drawContext.canvas.nativeCanvas.drawText(
            measure.number,
            measureLeft + 2.5.dp.toPx(),
            top - 6.dp.toPx(),
            paints.measure
        )

        measureGeometry.staffs.forEach { staffGeometry ->
            val staff = staffGeometry.staff
            val staffTop = staffGeometry.staffTop
            var prefix = 5.dp.toPx()

            if (staffGeometry.drawHeader) {
                drawClef(staff.clef, measureLeft + prefix + 8.dp.toPx(), staffTop, space, paints.music)
                prefix += 23.dp.toPx()
                staff.keyFifths?.takeIf { it != 0 }?.let { fifths ->
                    drawKeySignature(fifths, staff.clef, measureLeft + prefix, staffTop, space, paints.music)
                    prefix += (abs(fifths).coerceAtMost(7) * 6.2f + 3f).dp.toPx()
                }
                staff.timeSignature?.let { time ->
                    drawTimeSignature(time, measureLeft + prefix + 6.dp.toPx(), staffTop, space, paints.music)
                    prefix += 17.dp.toPx()
                }
            }

            drawEvents(staffGeometry.placements, staffTop, space, staff.clef, paints.music)
        }

        if (measureIndex == system.measures.lastIndex) {
            drawLine(Ink, Offset(measureRight, top), Offset(measureRight, bottom), strokeWidth = 1.15.dp.toPx())
        }
    }

    drawPracticeOverlay(geometry.noteheads, successSourceIds, Color(0xFF2E7D32), showPointer = false)
    drawPracticeOverlay(
        geometry.noteheads,
        highlightedSourceIds,
        Color(0xFFFFB300),
        showPointer = showPracticePointer
    )
}

private fun DrawScope.drawPracticeOverlay(
    noteheads: List<RenderedNotehead>,
    sourceIds: Set<String>,
    color: Color,
    showPointer: Boolean
) {
    val targets = noteheads.filter { it.sourceId in sourceIds }
    if (targets.isEmpty()) return
    val padding = 3.5.dp.toPx()
    targets.forEach { notehead ->
        val bounds = notehead.bounds
        drawRoundRect(
            color = color.copy(alpha = 0.24f),
            topLeft = Offset(bounds.left - padding, bounds.top - padding),
            size = Size(bounds.width + padding * 2f, bounds.height + padding * 2f),
            cornerRadius = CornerRadius(5.dp.toPx())
        )
        drawRoundRect(
            color = color.copy(alpha = 0.9f),
            topLeft = Offset(bounds.left - padding, bounds.top - padding),
            size = Size(bounds.width + padding * 2f, bounds.height + padding * 2f),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = Stroke(1.4.dp.toPx())
        )
    }
    if (showPointer) {
        val union = targets.map { it.bounds }.reduce(NotationBounds::union)
        val pointerY = union.top - 5.dp.toPx()
        val pointer = Path().apply {
            moveTo(union.centerX, pointerY + 4.dp.toPx())
            lineTo(union.centerX - 3.5.dp.toPx(), pointerY - 1.dp.toPx())
            lineTo(union.centerX + 3.5.dp.toPx(), pointerY - 1.dp.toPx())
            close()
        }
        drawPath(pointer, color)
    }
}

private fun DrawScope.drawEvents(
    placements: List<NotationEventPlacement>,
    staffTop: Float,
    space: Float,
    clef: NotationClef,
    musicPaint: Paint
) {
    val beamGroups = beamGroups(placements)
    val beamedIndices = beamGroups.flatten().toSet()
    val geometries = mutableMapOf<Int, ChordGeometry>()

    placements.forEachIndexed { index, placement ->
        when (val event = placement.event) {
            is NotationRest -> drawRest(event, placement.x, staffTop, space, musicPaint)
            is NotationChord -> {
                val geometry = chordGeometry(event, placement.x, staffTop, space, clef)
                geometries[index] = geometry
                drawChordHeads(geometry, staffTop, space, musicPaint)
                if (index !in beamedIndices) drawUnbeamedStem(geometry, musicPaint)
            }
        }
    }

    beamGroups.forEach { group ->
        drawBeamGroup(group.mapNotNull(geometries::get))
    }
}

private fun beamGroups(placements: List<NotationEventPlacement>): List<List<Int>> {
    val result = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    fun flush() {
        if (current.size >= 2) result += current.toList()
        current = mutableListOf()
    }
    placements.forEachIndexed { index, placement ->
        val chord = placement.event as? NotationChord
        val previous = current.lastOrNull()?.let { placements[it].event as? NotationChord }
        val compatible = chord != null && chord.beamCount() > 0 &&
            (previous == null || (previous.voice == chord.voice && previous.resolvedStemDown() == chord.resolvedStemDown()))
        if (!compatible) flush()
        if (chord != null && chord.beamCount() > 0) current += index
    }
    flush()
    return result
}

private fun chordGeometry(
    chord: NotationChord,
    x: Float,
    staffTop: Float,
    space: Float,
    clef: NotationClef
): ChordGeometry {
    val ys = chord.pitches.map { NotationGeometry.pitchY(it, staffTop, space, clef) }
    val down = chord.resolvedStemDown()
    val anchor = if (down) ys.minOrNull() ?: staffTop else ys.maxOrNull() ?: staffTop
    val stemX = x + if (down) -0.63f * space else 0.63f * space
    val stemEnd = anchor + if (down) 4f * space else -4f * space
    return ChordGeometry(chord, x, ys, down, stemX, anchor, stemEnd)
}

private fun NotationChord.resolvedStemDown(): Boolean = when (stem) {
    NotationStem.DOWN -> true
    NotationStem.UP -> false
    else -> pitches.map { it.octave * 7 + diatonicIndex(it.step) }.average().let { it > 34.0 }
}

private fun NotationChord.beamCount(): Int = when (durationType) {
    NotationDurationType.EIGHTH -> 1
    NotationDurationType.SIXTEENTH -> 2
    NotationDurationType.THIRTY_SECOND -> 3
    NotationDurationType.SIXTY_FOURTH -> 4
    else -> 0
}

private fun DrawScope.drawChordHeads(
    geometry: ChordGeometry,
    staffTop: Float,
    space: Float,
    musicPaint: Paint
) {
    val glyph = when (geometry.chord.durationType) {
        NotationDurationType.WHOLE -> MusicGlyph.NOTEHEAD_WHOLE
        NotationDurationType.HALF -> MusicGlyph.NOTEHEAD_HALF
        else -> MusicGlyph.NOTEHEAD_BLACK
    }
    geometry.noteYs.forEachIndexed { index, y ->
        drawLedgerLines(geometry.x, y, staffTop, space)
        geometry.chord.pitches[index].displayedAccidental?.let {
            drawAccidental(it, geometry.x - 10.5.dp.toPx(), y, musicPaint)
        }
        drawMusicGlyph(glyph, geometry.x, y, 19.dp.toPx(), musicPaint)
        repeat(geometry.chord.dots.coerceAtMost(3)) { dot ->
            val dotY = if (((y - staffTop) / (space / 2f)).toInt() % 2 == 0) y - space / 2f else y
            drawCircle(Ink, 1.05.dp.toPx(), Offset(geometry.x + (7.2f + dot * 3.4f).dp.toPx(), dotY))
        }
    }
}

private fun DrawScope.drawUnbeamedStem(geometry: ChordGeometry, musicPaint: Paint) {
    if (geometry.chord.durationType in setOf(NotationDurationType.WHOLE, NotationDurationType.UNKNOWN) ||
        geometry.chord.stem == NotationStem.NONE || geometry.noteYs.isEmpty()
    ) return
    drawLine(
        Ink,
        Offset(geometry.stemX, geometry.stemAnchorY),
        Offset(geometry.stemX, geometry.defaultStemEndY),
        1.dp.toPx(),
        cap = StrokeCap.Square
    )
    val flag = flagGlyph(geometry.chord.beamCount(), geometry.stemDown) ?: return
    val flagCenterY = geometry.defaultStemEndY + if (geometry.stemDown) -1.dp.toPx() else 1.dp.toPx()
    drawMusicGlyph(flag, geometry.stemX, flagCenterY, 22.dp.toPx(), musicPaint)
}

private fun flagGlyph(count: Int, down: Boolean): String? = when (count to down) {
    1 to false -> MusicGlyph.FLAG_EIGHTH_UP
    1 to true -> MusicGlyph.FLAG_EIGHTH_DOWN
    2 to false -> MusicGlyph.FLAG_SIXTEENTH_UP
    2 to true -> MusicGlyph.FLAG_SIXTEENTH_DOWN
    3 to false -> MusicGlyph.FLAG_THIRTY_SECOND_UP
    3 to true -> MusicGlyph.FLAG_THIRTY_SECOND_DOWN
    4 to false -> MusicGlyph.FLAG_SIXTY_FOURTH_UP
    4 to true -> MusicGlyph.FLAG_SIXTY_FOURTH_DOWN
    else -> null
}

private fun DrawScope.drawBeamGroup(group: List<ChordGeometry>) {
    if (group.size < 2) return
    val down = group.first().stemDown
    val first = group.first()
    val last = group.last()
    val rawFirstY = if (down) group.maxOf { it.stemAnchorY } + 24.dp.toPx()
        else group.minOf { it.stemAnchorY } - 24.dp.toPx()
    val pitchSlope = ((last.stemAnchorY - first.stemAnchorY) * 0.18f)
        .coerceIn(-4.dp.toPx(), 4.dp.toPx())
    val beamThickness = 3.2.dp.toPx()
    val beamGap = 4.6.dp.toPx()

    fun beamY(chord: ChordGeometry): Float {
        val progress = if (last.stemX == first.stemX) 0f else
            (chord.stemX - first.stemX) / (last.stemX - first.stemX)
        return rawFirstY + pitchSlope * progress
    }

    group.forEach { chord ->
        drawLine(
            Ink,
            Offset(chord.stemX, chord.stemAnchorY),
            Offset(chord.stemX, beamY(chord)),
            1.dp.toPx(),
            cap = StrokeCap.Square
        )
    }

    drawBeamSegment(first.stemX, beamY(first), last.stemX, beamY(last), beamThickness, down)
    for (level in 2..4) {
        group.zipWithNext().forEach { (a, b) ->
            if (a.chord.beamCount() >= level && b.chord.beamCount() >= level) {
                val offset = (level - 1) * beamGap * if (down) -1f else 1f
                drawBeamSegment(a.stemX, beamY(a) + offset, b.stemX, beamY(b) + offset, beamThickness, down)
            }
        }
    }
}

private fun DrawScope.drawBeamSegment(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    thickness: Float,
    down: Boolean
) {
    val direction = if (down) -1f else 1f
    val path = Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
        lineTo(endX, endY + direction * thickness)
        lineTo(startX, startY + direction * thickness)
        close()
    }
    drawPath(path, Ink)
}

private fun DrawScope.drawRest(rest: NotationRest, x: Float, staffTop: Float, space: Float, musicPaint: Paint) {
    val (glyph, verticalPosition) = when (rest.durationType) {
        NotationDurationType.WHOLE -> MusicGlyph.REST_WHOLE to (staffTop + space)
        NotationDurationType.HALF -> MusicGlyph.REST_HALF to (staffTop + 2 * space)
        NotationDurationType.QUARTER -> MusicGlyph.REST_QUARTER to (staffTop + 2 * space)
        NotationDurationType.EIGHTH -> MusicGlyph.REST_EIGHTH to (staffTop + 2 * space)
        NotationDurationType.SIXTEENTH -> MusicGlyph.REST_SIXTEENTH to (staffTop + 2 * space)
        NotationDurationType.THIRTY_SECOND -> MusicGlyph.REST_THIRTY_SECOND to (staffTop + 2 * space)
        NotationDurationType.SIXTY_FOURTH -> MusicGlyph.REST_SIXTY_FOURTH to (staffTop + 2 * space)
        NotationDurationType.UNKNOWN -> return
    }
    drawMusicGlyph(glyph, x, verticalPosition, 23.dp.toPx(), musicPaint)
    repeat(rest.dots.coerceAtMost(3)) { dot ->
        drawCircle(Ink, 1.05.dp.toPx(), Offset(x + (7.5f + dot * 3.4f).dp.toPx(), staffTop + 1.5f * space))
    }
}

private fun DrawScope.drawClef(clef: NotationClef, x: Float, top: Float, space: Float, musicPaint: Paint) {
    when (clef) {
        NotationClef.TREBLE -> drawMusicGlyph(MusicGlyph.G_CLEF, x, top + 2 * space, 42.dp.toPx(), musicPaint)
        NotationClef.BASS -> drawMusicGlyph(MusicGlyph.F_CLEF, x, top + 2 * space, 31.dp.toPx(), musicPaint)
        NotationClef.UNKNOWN, NotationClef.UNSUPPORTED -> drawRect(
            Ink,
            Offset(x - 3.dp.toPx(), top + space),
            Size(6.dp.toPx(), 2 * space),
            style = Stroke(0.8.dp.toPx())
        )
    }
}

private fun DrawScope.drawKeySignature(
    fifths: Int,
    clef: NotationClef,
    x: Float,
    top: Float,
    space: Float,
    musicPaint: Paint
) {
    val sharpTreble = intArrayOf(0, 3, -1, 2, 5, 1, 4)
    val flatTreble = intArrayOf(4, 1, 5, 2, 6, 3, 7)
    val clefShift = if (clef == NotationClef.BASS) 2 else 0
    val positions = if (fifths > 0) sharpTreble else flatTreble
    val glyph = if (fifths > 0) MusicGlyph.ACCIDENTAL_SHARP else MusicGlyph.ACCIDENTAL_FLAT
    repeat(abs(fifths).coerceAtMost(7)) { index ->
        val y = top + (positions[index] + clefShift) * space / 2f
        drawMusicGlyph(glyph, x + index * 6.2.dp.toPx(), y, 20.dp.toPx(), musicPaint)
    }
}

private fun DrawScope.drawTimeSignature(
    time: NotationTimeSignature,
    x: Float,
    top: Float,
    space: Float,
    musicPaint: Paint
) {
    drawTimeNumber(time.beats, x, top + space, musicPaint)
    drawTimeNumber(time.beatType, x, top + 3 * space, musicPaint)
}

private fun DrawScope.drawTimeNumber(number: Int, centerX: Float, centerY: Float, musicPaint: Paint) {
    val glyphs = number.toString().map(MusicGlyph::timeDigit)
    val glyphSize = 22.dp.toPx()
    val glyphAdvance = 7.dp.toPx()
    val start = centerX - (glyphs.size - 1) * glyphAdvance / 2f
    glyphs.forEachIndexed { index, glyph ->
        drawMusicGlyph(glyph, start + index * glyphAdvance, centerY, glyphSize, musicPaint)
    }
}

private fun DrawScope.drawAccidental(accidental: NotationAccidental, x: Float, y: Float, musicPaint: Paint) {
    val glyph = when (accidental) {
        NotationAccidental.FLAT -> MusicGlyph.ACCIDENTAL_FLAT
        NotationAccidental.NATURAL -> MusicGlyph.ACCIDENTAL_NATURAL
        NotationAccidental.SHARP -> MusicGlyph.ACCIDENTAL_SHARP
    }
    drawMusicGlyph(glyph, x, y, 21.dp.toPx(), musicPaint)
}

private fun DrawScope.drawMusicGlyph(glyph: String, centerX: Float, centerY: Float, size: Float, paint: Paint) {
    paint.textSize = size
    val bounds = Rect()
    paint.getTextBounds(glyph, 0, glyph.length, bounds)
    drawContext.canvas.nativeCanvas.drawText(
        glyph,
        centerX - bounds.exactCenterX(),
        centerY - bounds.exactCenterY(),
        paint
    )
}

private fun DrawScope.drawLedgerLines(x: Float, y: Float, top: Float, space: Float) {
    val bottom = top + 4 * space
    if (y < top) {
        var ledger = top - space
        while (ledger >= y - 1.dp.toPx()) {
            drawLine(Ink, Offset(x - 6.5.dp.toPx(), ledger), Offset(x + 6.5.dp.toPx(), ledger), 0.8.dp.toPx())
            ledger -= space
        }
    } else if (y > bottom) {
        var ledger = bottom + space
        while (ledger <= y + 1.dp.toPx()) {
            drawLine(Ink, Offset(x - 6.5.dp.toPx(), ledger), Offset(x + 6.5.dp.toPx(), ledger), 0.8.dp.toPx())
            ledger += space
        }
    }
}

private fun diatonicIndex(step: Char): Int = when (step) {
    'C' -> 0
    'D' -> 1
    'E' -> 2
    'F' -> 3
    'G' -> 4
    'A' -> 5
    else -> 6
}

private fun DrawScope.drawBrace(x: Float, top: Float, bottom: Float) {
    val middle = (top + bottom) / 2f
    val path = Path().apply {
        moveTo(x + 4.dp.toPx(), top)
        cubicTo(x - 3.dp.toPx(), top + 7.dp.toPx(), x + 2.dp.toPx(), middle - 5.dp.toPx(), x - 4.dp.toPx(), middle)
        cubicTo(x + 2.dp.toPx(), middle + 5.dp.toPx(), x - 3.dp.toPx(), bottom - 7.dp.toPx(), x + 4.dp.toPx(), bottom)
    }
    drawPath(path, Ink, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
}
