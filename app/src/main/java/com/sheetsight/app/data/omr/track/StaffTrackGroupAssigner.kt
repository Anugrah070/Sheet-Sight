package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.ZoneStaff
import kotlin.math.roundToInt

object StaffTrackGroupAssigner {

    fun assign(staffGrid: List<List<ZoneStaff>>, numTrack: Int): List<List<AssignedStaff>> {
        require(numTrack >= 1) { "numTrack must be >= 1, was $numTrack" }

        val slots = stableRowSlots(staffGrid, numTrack)
        val completed = completeSingleMissingRow(staffGrid, slots, numTrack)
        val completedSlots = if (completed.interpolatedSlot == null) slots
        else IntArray(completed.grid.maxOf { it.size }) { it }

        return completed.grid.map { zoneStaffs ->
            zoneStaffs.mapIndexed { index, staff ->
                val slot = completedSlots.getOrElse(index) { index }
                AssignedStaff(
                    staff = staff,
                    track = slot % numTrack,
                    group = slot / numTrack,
                    isInterpolated = slot == completed.interpolatedSlot
                )
            }
        }
    }

    /**
     * Keeps later piano systems stable when one complete staff row is absent
     * from every horizontal zone. Plain modulo indexing shifts every row
     * after the omission (treble becomes bass, then the following bass starts
     * a new system). For a repeated two-staff layout we test each possible
     * single missing slot and accept it only when the remaining alternating
     * within-system/inter-system gaps become substantially more consistent.
     *
     * Once the missing slot is unambiguous, [assign] restores only its staff
     * geometry from neighboring repeated-system spacing and marks it
     * [AssignedStaff.isInterpolated]. It never creates a note or other musical
     * event. Edge omissions are left alone because their slot cannot be
     * established from surrounding geometry.
     */
    internal fun stableRowSlots(staffGrid: List<List<ZoneStaff>>, numTrack: Int): IntArray {
        val rowCount = staffGrid.maxOfOrNull { it.size } ?: return IntArray(0)
        val identity = IntArray(rowCount) { it }
        if (numTrack != 2 || rowCount < 5 || rowCount % 2 == 0) return identity

        val centers = DoubleArray(rowCount) { row ->
            median(staffGrid.mapNotNull { it.getOrNull(row)?.yCenter })
        }
        if (centers.asList().zipWithNext().any { (a, b) -> b <= a }) return identity

        val identityScore = alternatingGapScore(centers, identity) ?: return identity
        val candidates = (1 until rowCount).mapNotNull { missingSlot ->
            val mapping = IntArray(rowCount) { row -> if (row < missingSlot) row else row + 1 }
            val score = alternatingGapScore(centers, mapping) ?: return@mapNotNull null
            MissingSlotCandidate(mapping, score)
        }.sortedBy { it.score }
        val best = candidates.firstOrNull() ?: return identity
        val second = candidates.getOrNull(1)
        val materiallyBetter = best.score <= identityScore * 0.55
        val unambiguous = second == null || best.score <= second.score * 0.8
        return if (materiallyBetter && unambiguous) best.mapping else identity
    }

    private fun alternatingGapScore(centers: DoubleArray, slots: IntArray): Double? {
        val intra = mutableListOf<Double>()
        val inter = mutableListOf<Double>()
        var skippedGap: Double? = null
        for (row in 0 until centers.lastIndex) {
            val slotDelta = slots[row + 1] - slots[row]
            val gap = centers[row + 1] - centers[row]
            when {
                slotDelta == 2 -> skippedGap = gap
                slotDelta != 1 -> return null
                slots[row] % 2 == 0 -> intra += gap
                else -> inter += gap
            }
        }
        if (intra.size < 2 || inter.size < 2) return null
        val typicalIntra = median(intra)
        val typicalInter = median(inter)
        if (typicalIntra <= 0.0 || typicalInter / typicalIntra <= 1.25) return null
        if (skippedGap != null) {
            val expected = typicalIntra + typicalInter
            if (skippedGap !in expected * 0.7..expected * 1.3) return null
        }
        return intra.sumOf { kotlin.math.abs(it / typicalIntra - 1.0) } +
            inter.sumOf { kotlin.math.abs(it / typicalInter - 1.0) }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun completeSingleMissingRow(
        staffGrid: List<List<ZoneStaff>>,
        slots: IntArray,
        numTrack: Int
    ): CompletedGrid {
        if (numTrack != 2 || staffGrid.isEmpty() || slots.isEmpty()) return CompletedGrid(staffGrid, null)
        val missing = (0..slots.max()).filterNot { it in slots }
        if (missing.size != 1) return CompletedGrid(staffGrid, null)
        val missingSlot = missing.single()
        if (missingSlot == 0 || missingSlot > slots.max()) return CompletedGrid(staffGrid, null)

        val centers = DoubleArray(slots.size) { row ->
            median(staffGrid.mapNotNull { it.getOrNull(row)?.yCenter })
        }
        val intraGaps = mutableListOf<Double>()
        val interGaps = mutableListOf<Double>()
        for (row in 0 until slots.lastIndex) {
            if (slots[row + 1] - slots[row] != 1) continue
            val gap = centers[row + 1] - centers[row]
            if (slots[row] % 2 == 0) intraGaps += gap else interGaps += gap
        }
        if (intraGaps.isEmpty() || interGaps.isEmpty()) return CompletedGrid(staffGrid, null)
        val typicalIntra = median(intraGaps)
        val typicalInter = median(interGaps)
        val insertionIndex = slots.indexOfFirst { it > missingSlot }.takeIf { it >= 0 } ?: return CompletedGrid(staffGrid, null)

        val completed = staffGrid.map { zone ->
            val beforeIndex = (insertionIndex - 1).takeIf { it in zone.indices }
            val afterIndex = insertionIndex.takeIf { it in zone.indices }
            val targetCandidates = buildList {
                if (missingSlot % 2 == 1 && beforeIndex != null) {
                    add(zone[beforeIndex].yCenter + typicalIntra)
                }
                if (missingSlot % 2 == 0 && afterIndex != null) {
                    add(zone[afterIndex].yCenter - typicalIntra)
                }
                if (missingSlot % 2 == 1 && afterIndex != null) {
                    add(zone[afterIndex].yCenter - typicalInter)
                }
                if (missingSlot % 2 == 0 && beforeIndex != null) {
                    add(zone[beforeIndex].yCenter + typicalInter)
                }
            }
            if (targetCandidates.isEmpty()) return@map zone
            val targetCenter = targetCandidates.average()
            val templateIndex = slots.indices
                .filter { slots[it] % numTrack == missingSlot % numTrack && it in zone.indices }
                .minByOrNull { kotlin.math.abs(slots[it] - missingSlot) }
                ?: return@map zone
            val template = zone[templateIndex]
            val yOffset = targetCenter - template.yCenter
            val interpolated = ZoneStaff(
                template.lines.map { line ->
                    line.copy(
                        points = line.points.map { point ->
                            point.copy(y = (point.y + yOffset).roundToInt())
                        }
                    )
                }
            )
            zone.toMutableList().apply { add(insertionIndex, interpolated) }
        }
        if (completed.any { it.size != slots.size + 1 }) return CompletedGrid(staffGrid, null)
        return CompletedGrid(completed, missingSlot)
    }

    private data class MissingSlotCandidate(val mapping: IntArray, val score: Double)
    private data class CompletedGrid(val grid: List<List<ZoneStaff>>, val interpolatedSlot: Int?)
}

data class AssignedStaff(
    val staff: ZoneStaff,
    val track: Int,
    val group: Int,
    val isInterpolated: Boolean = false
)
