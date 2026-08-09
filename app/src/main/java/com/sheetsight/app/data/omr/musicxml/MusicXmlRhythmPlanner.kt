package com.sheetsight.app.data.omr.musicxml

import kotlin.math.abs

/** One resolved semantic chord/rest presented to [MusicXmlRhythmPlanner]. */
internal data class MusicXmlRhythmInput(
    val eventId: String,
    val staffIndex: Int,
    val horizontalPosition: Int,
    val durationUnits: Long,
    val sourceOrder: Int
)

/** One cursor-positioned rhythmic item, including an oemer-compatible generated rest. */
internal data class MusicXmlRhythmEntry(
    val eventId: String?,
    val staffIndex: Int,
    val horizontalPosition: Int,
    val onsetUnits: Long,
    val durationUnits: Long,
    val voice: Int,
    val sourceOrder: Int,
    val generatedRest: Boolean
)

internal data class MusicXmlRhythmPlan(
    val entries: List<MusicXmlRhythmEntry>,
    val slotDurationsBefore: List<List<Long>>,
    val slotDurationsAfter: List<List<Long>>
)

/**
 * Plans MusicXML cursor positions and two-staff beat correction.
 *
 * The two-track state machine is a direct immutable port of oemer 0.1.8
 * `build_system.py::Measure.align_symbols` (wheel lines 251-385): symbols
 * within one staff unit share a time slot, each slot uses the shortest voice
 * per track, and any outstanding duration difference is resolved at the next
 * checkpoint (or measure end) by extending the lagging symbol or inserting a
 * rest. Cursor/voice assignment mirrors `MusicXMLBuilder.build` lines 607-665.
 * oemer explicitly handles one or two tracks only, so scores with more staves
 * retain independent staff streams without invented cross-staff adjustment.
 */
internal object MusicXmlRhythmPlanner {
    fun plan(
        inputs: List<MusicXmlRhythmInput>,
        staffCount: Int,
        horizontalTolerance: Double,
        adjustBeats: Boolean = true
    ): MusicXmlRhythmPlan {
        require(staffCount > 0)
        require(horizontalTolerance > 0.0)
        inputs.forEach { require(it.staffIndex in 0 until staffCount) }
        if (inputs.isEmpty()) return MusicXmlRhythmPlan(emptyList(), emptyList(), emptyList())

        val slots = cluster(inputs, horizontalTolerance)
        val before = slotDurations(slots, staffCount)
        val after = before.map { it.toMutableList() }.toMutableList()

        if (staffCount == 2 && adjustBeats) alignTwoStaffs(slots, before, after)

        val cursors = LongArray(staffCount)
        val entries = mutableListOf<MusicXmlRhythmEntry>()
        slots.forEachIndexed { slotIndex, slot ->
            for (staffIndex in 0 until staffCount) {
                val staffItems = slot.items.filter { it.staffIndex == staffIndex }
                staffItems.forEachIndexed { voiceIndex, item ->
                    entries += MusicXmlRhythmEntry(
                        eventId = item.eventId,
                        staffIndex = staffIndex,
                        horizontalPosition = item.horizontalPosition,
                        onsetUnits = cursors[staffIndex],
                        durationUnits = item.durationUnits,
                        voice = if (voiceIndex == 0) 1 else 2,
                        sourceOrder = item.sourceOrder,
                        generatedRest = item.generatedRest
                    )
                }
                cursors[staffIndex] += after[slotIndex][staffIndex]
            }
        }
        return MusicXmlRhythmPlan(
            entries = entries.sortedWith(
                compareBy<MusicXmlRhythmEntry> { it.horizontalPosition }
                    .thenBy { it.sourceOrder }
                    .thenBy { it.staffIndex }
            ),
            slotDurationsBefore = before.map { it.toList() },
            slotDurationsAfter = after.map { it.toList() }
        )
    }

    private fun cluster(
        inputs: List<MusicXmlRhythmInput>,
        horizontalTolerance: Double
    ): MutableList<Slot> {
        val ordered = inputs.sortedWith(
            compareBy<MusicXmlRhythmInput> { it.horizontalPosition }
                .thenBy { it.sourceOrder }
        )
        val slots = mutableListOf<Slot>()
        ordered.forEach { input ->
            val current = slots.lastOrNull()
            if (current == null || abs(input.horizontalPosition - current.anchorX) >= horizontalTolerance) {
                slots += Slot(input.horizontalPosition, mutableListOf(input.toItem()))
            } else {
                current.items += input.toItem()
            }
        }
        return slots
    }

    private fun slotDurations(slots: List<Slot>, staffCount: Int): List<List<Long>> =
        slots.map { slot ->
            List(staffCount) { staffIndex ->
                slot.items
                    .asSequence()
                    .filter { it.staffIndex == staffIndex }
                    .minOfOrNull { it.durationUnits }
                    ?: 0L
            }
        }

    private fun alignTwoStaffs(
        slots: MutableList<Slot>,
        before: List<List<Long>>,
        after: MutableList<MutableList<Long>>
    ) {
        var difference = 0L
        var leadingStaff: Int? = null
        var adjustmentSlot: Int? = null
        var solved = true

        fun modify() {
            val slotIndex = requireNotNull(adjustmentSlot)
            val lead = requireNotNull(leadingStaff)
            val laggingStaff = 1 - lead
            val originalDuration = before[slotIndex][laggingStaff]
            if (originalDuration != 0L) {
                val adjustedDuration = difference + originalDuration
                slots[slotIndex].items
                    .filter { it.staffIndex == laggingStaff }
                    .forEach { it.durationUnits = adjustedDuration }
                after[slotIndex][laggingStaff] = adjustedDuration
            } else {
                val anchor = slots[slotIndex]
                anchor.items += MutableRhythmItem(
                    eventId = null,
                    staffIndex = laggingStaff,
                    horizontalPosition = anchor.anchorX,
                    durationUnits = difference,
                    sourceOrder = Int.MAX_VALUE,
                    generatedRest = true
                )
                after[slotIndex][laggingStaff] = difference
            }
        }

        before.forEachIndexed { slotIndex, durations ->
            val upper = durations[0]
            val lower = durations[1]
            when {
                upper > 0L && lower > 0L -> {
                    if (difference > 0L) modify()
                    if (upper > lower) {
                        difference = upper - lower
                        leadingStaff = 0
                    } else {
                        difference = lower - upper
                        leadingStaff = 1
                    }
                    adjustmentSlot = slotIndex
                    solved = true
                }
                upper > 0L -> {
                    when {
                        leadingStaff == 0 -> difference += upper
                        difference >= upper -> difference -= upper
                        else -> {
                            difference = if (difference in 1 until upper) upper - difference else upper
                            adjustmentSlot = slotIndex
                            leadingStaff = 0
                        }
                    }
                    solved = false
                }
                lower > 0L -> {
                    when {
                        leadingStaff == 1 -> difference += lower
                        difference >= lower -> difference -= lower
                        else -> {
                            difference = if (difference in 1 until lower) lower - difference else lower
                            adjustmentSlot = slotIndex
                            leadingStaff = 1
                        }
                    }
                    solved = false
                }
            }
        }
        if (!solved && difference > 0L) modify()
    }

    private data class Slot(
        val anchorX: Int,
        val items: MutableList<MutableRhythmItem>
    )

    private data class MutableRhythmItem(
        val eventId: String?,
        val staffIndex: Int,
        val horizontalPosition: Int,
        var durationUnits: Long,
        val sourceOrder: Int,
        val generatedRest: Boolean
    )

    private fun MusicXmlRhythmInput.toItem() = MutableRhythmItem(
        eventId = eventId,
        staffIndex = staffIndex,
        horizontalPosition = horizontalPosition,
        durationUnits = durationUnits,
        sourceOrder = sourceOrder,
        generatedRest = false
    )
}
