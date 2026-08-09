package com.sheetsight.app.ui.editor.notation

import kotlin.math.abs
import kotlin.math.max

/** Stable renderer identity shared by Editor notation and Practice targets. */
object NotationSourceIds {
    fun note(
        measureIndex: Int,
        measureNumber: String,
        staffNumber: Int,
        sourceOrder: Int,
        pitchIndex: Int
    ): String = "measure-index:$measureIndex:measure:$measureNumber:staff:$staffNumber:" +
        "event:$sourceOrder:pitch:$pitchIndex"
}

data class NotationBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f

    fun union(other: NotationBounds): NotationBounds = NotationBounds(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )
}

data class RenderedNotehead(
    val sourceId: String,
    val measureNumber: String,
    val staffNumber: Int,
    val systemIndex: Int,
    val bounds: NotationBounds
)

internal data class NotationEventPlacement(val event: NotationEvent, val x: Float)

internal data class NotationStaffGeometry(
    val staff: NotationStaff,
    val staffIndex: Int,
    val staffTop: Float,
    val drawHeader: Boolean,
    val placements: List<NotationEventPlacement>
)

internal data class NotationMeasureGeometry(
    val measure: NotationMeasure,
    val measureIndex: Int,
    val left: Float,
    val right: Float,
    val staffs: List<NotationStaffGeometry>
)

internal data class NotationSystemGeometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val staffGap: Float,
    val staffSpace: Float,
    val measures: List<NotationMeasureGeometry>,
    val noteheads: List<RenderedNotehead>
)

/**
 * Single source of truth for Canvas placement and Practice hit geometry.
 * It is recomputed only when score width/density changes, never for audio frames.
 */
internal object NotationGeometry {
    private const val LEFT_DP = 31f
    private const val RIGHT_DP = 12f
    private const val TOP_DP = 32f
    private const val STAFF_GAP_DP = 54f
    private const val STAFF_SPACE_DP = 6.5f
    private const val NOTEHEAD_HALF_WIDTH_DP = 7.5f
    private const val NOTEHEAD_HALF_HEIGHT_DP = 5.5f

    fun systemHeightDp(staffCount: Int): Float = 48f + (max(1, staffCount) - 1) * 54f + 46f

    fun layoutSystem(system: NotationSystem, widthPx: Float, density: Float): NotationSystemGeometry {
        val safeDensity = density.coerceAtLeast(0.01f)
        val staffCount = max(1, system.staffCount)
        val left = LEFT_DP * safeDensity
        val right = max(left + safeDensity, widthPx - RIGHT_DP * safeDensity)
        val top = TOP_DP * safeDensity
        val staffGap = STAFF_GAP_DP * safeDensity
        val space = STAFF_SPACE_DP * safeDensity
        val bottom = top + (staffCount - 1) * staffGap + 4f * space
        if (system.measures.isEmpty()) {
            return NotationSystemGeometry(left, right, top, bottom, staffGap, space, emptyList(), emptyList())
        }

        val measureWidth = (right - left) / system.measures.size
        val noteheads = mutableListOf<RenderedNotehead>()
        val measures = system.measures.mapIndexed { measureIndex, measure ->
            val measureLeft = left + measureIndex * measureWidth
            val measureRight = measureLeft + measureWidth
            val staffs = (0 until staffCount).mapNotNull { staffIndex ->
                val staff = measure.staffs.getOrNull(staffIndex) ?: return@mapNotNull null
                val staffTop = top + staffIndex * staffGap
                val drawHeader = measureIndex == 0 ||
                    headerChanged(system.measures[measureIndex - 1], measure, staffIndex)
                var prefix = 5f * safeDensity
                if (drawHeader) {
                    prefix += 23f * safeDensity
                    staff.keyFifths?.takeIf { it != 0 }?.let { fifths ->
                        prefix += (abs(fifths).coerceAtMost(7) * 6.2f + 3f) * safeDensity
                    }
                    if (staff.timeSignature != null) prefix += 17f * safeDensity
                }
                val placements = layoutEvents(
                    staff.events,
                    measureLeft + prefix + 3f * safeDensity,
                    measureRight - 4f * safeDensity
                )
                placements.forEach { placement ->
                    val chord = placement.event as? NotationChord ?: return@forEach
                    chord.pitches.forEachIndexed { pitchIndex, pitch ->
                        val y = pitchY(pitch, staffTop, space, staff.clef)
                        noteheads += RenderedNotehead(
                            sourceId = NotationSourceIds.note(
                                measure.sourceIndex,
                                measure.number,
                                staff.number,
                                chord.sourceOrder,
                                pitchIndex
                            ),
                            measureNumber = measure.number,
                            staffNumber = staff.number,
                            systemIndex = system.index,
                            bounds = NotationBounds(
                                placement.x - NOTEHEAD_HALF_WIDTH_DP * safeDensity,
                                y - NOTEHEAD_HALF_HEIGHT_DP * safeDensity,
                                placement.x + NOTEHEAD_HALF_WIDTH_DP * safeDensity,
                                y + NOTEHEAD_HALF_HEIGHT_DP * safeDensity
                            )
                        )
                    }
                }
                NotationStaffGeometry(staff, staffIndex, staffTop, drawHeader, placements)
            }
            NotationMeasureGeometry(measure, measureIndex, measureLeft, measureRight, staffs)
        }
        return NotationSystemGeometry(left, right, top, bottom, staffGap, space, measures, noteheads)
    }

    fun pitchY(pitch: NotationPitch, top: Float, space: Float, clef: NotationClef): Float {
        val noteIndex = pitch.octave * 7 + diatonicIndex(pitch.step)
        val bottomLineIndex = if (clef == NotationClef.BASS) 18 else 30
        return top + 4 * space - (noteIndex - bottomLineIndex) * (space / 2f)
    }

    private fun layoutEvents(events: List<NotationEvent>, start: Float, end: Float): List<NotationEventPlacement> {
        if (events.isEmpty()) return emptyList()
        val weights = events.map(::durationWeight)
        val total = weights.sum().coerceAtLeast(0.25f)
        val width = (end - start).coerceAtLeast(1f)
        var cursor = start
        return events.mapIndexed { index, event ->
            val eventWidth = width * weights[index] / total
            NotationEventPlacement(event, cursor + eventWidth / 2f).also { cursor += eventWidth }
        }
    }

    private fun durationWeight(event: NotationEvent): Float {
        val base = when (event.durationType) {
            NotationDurationType.WHOLE -> 4f
            NotationDurationType.HALF -> 2f
            NotationDurationType.QUARTER -> 1f
            NotationDurationType.EIGHTH -> 0.62f
            NotationDurationType.SIXTEENTH -> 0.48f
            NotationDurationType.THIRTY_SECOND -> 0.4f
            NotationDurationType.SIXTY_FOURTH -> 0.36f
            NotationDurationType.UNKNOWN -> 1f
        }
        val dotMultiplier = when (event.dots.coerceAtMost(3)) {
            1 -> 1.5f
            2 -> 1.75f
            3 -> 1.875f
            else -> 1f
        }
        return base * dotMultiplier
    }

    private fun headerChanged(previous: NotationMeasure, current: NotationMeasure, staffIndex: Int): Boolean {
        val before = previous.staffs.getOrNull(staffIndex)
        val after = current.staffs.getOrNull(staffIndex)
        return before?.clef != after?.clef || before?.keyFifths != after?.keyFifths ||
            before?.timeSignature != after?.timeSignature
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
}
