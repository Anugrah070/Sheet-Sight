package com.sheetsight.app.data.omr.grouping

import com.sheetsight.app.data.omr.dewarp.ConnectedComponents
import com.sheetsight.app.data.omr.notehead.NoteheadCandidate
import com.sheetsight.app.data.omr.track.BoundingBox

/**
 * Phase 4.7B port of the connected-component grouping and stem-direction
 * portions of oemer `note_group_extraction.py` at commit
 * `dbe2a933d630d0f74805d717960eb259473f5978`.
 *
 * It reuses the existing 4-connected [ConnectedComponents] implementation,
 * matching `scipy.ndimage.label` used by oemer. The caller's stem mask is
 * borrowed read-only. One temporary integer foreground map and its label
 * result are created; no page mask is duplicated and connected components
 * are extracted only once.
 *
 * **Unverified input-contract adaptations:**
 *
 * 1. oemer groups with the complete `notehead_pred` mask. Phase 4.7B's
 *    required input is only `List<NoteheadCandidate>` plus a stem mask, so
 *    the notehead foreground is reconstructed from each candidate's
 *    already-retained source pixels. Filtered-out notehead pixels cannot
 *    bridge groups in this port.
 * 2. oemer's singleton fallback scans toward nearby component ids within
 *    the two closest staff bounds. The required Phase 4.7B input contains
 *    no staff geometry, so that refinement is not performed here.
 * 3. If one connected component crosses two staff assignments, this port
 *    splits it by `(track, group)` instead of oemer's nearest-staff
 *    reassignment, which likewise requires the absent staff geometry.
 */
object NoteGrouper {

    fun group(
        noteheads: List<NoteheadCandidate>,
        stemMask: BooleanArray,
        width: Int,
        height: Int
    ): List<ChordCandidate> =
        groupWithMap(noteheads, stemMask, width, height).chords

    /**
     * Groups noteheads and retains the component occupancy required by
     * oemer `symbol_extraction.py::parse_barlines()`/`parse_rests()`.
     */
    fun groupWithMap(
        noteheads: List<NoteheadCandidate>,
        stemMask: BooleanArray,
        width: Int,
        height: Int
    ): NoteGroupingResult {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(stemMask.size == width * height) {
            "stemMask size ${stemMask.size} doesn't match ${width}x$height"
        }
        if (noteheads.isEmpty()) {
            return NoteGroupingResult(emptyList(), IntArray(width * height) { -1 }, width, height)
        }

        // -1 is background, 0 is foreground, exactly what the existing
        // 4-connected labeler consumes.
        val foreground = IntArray(width * height) { -1 }
        noteheads.forEach { note ->
            note.sourcePixelIndices.forEach { index ->
                if (index in foreground.indices) foreground[index] = 0
            }
        }
        addDilatedStems(foreground, stemMask, width, height)
        val labels = ConnectedComponents.label(foreground, width, height)

        val groups = linkedMapOf<Int, MutableList<NoteheadCandidate>>()
        for (note in noteheads.sortedBy { it.id }) {
            val pixelBounds = sourcePixelBounds(note, width) ?: continue
            val top = (pixelBounds.top - 3).coerceAtLeast(0)
            val bottom = (pixelBounds.bottom + 3).coerceAtMost(height)
            val componentIds = linkedSetOf<Int>()
            for (y in top until bottom) {
                for (x in pixelBounds.left until pixelBounds.right) {
                    val label = labels[y * width + x]
                    if (label > 0) componentIds += label
                }
            }
            if (componentIds.isEmpty()) continue

            val existing = componentIds.filter(groups::containsKey)
            val selected = when {
                existing.isEmpty() -> componentIds.first()
                else -> existing.first()
            }
            if (existing.size > 1) {
                for (other in existing.drop(1)) {
                    groups.getOrPut(selected) { mutableListOf() }.addAll(groups.remove(other).orEmpty())
                    replaceLabel(labels, other, selected)
                }
            }
            for (other in componentIds) {
                if (other != selected) replaceLabel(labels, other, selected)
            }
            groups.getOrPut(selected) { mutableListOf() } += note
        }

        val componentStats = componentBounds(labels, width)
        val chords = mutableListOf<ChordCandidate>()
        for ((label, connectedNotes) in groups) {
            if (connectedNotes.isEmpty()) continue
            // See the class KDoc: prevent a cross-staff component from
            // silently changing track/group ownership.
            val assignmentPartitions = connectedNotes.groupBy {
                it.staffAssignment.track to it.staffAssignment.group
            }
            for ((assignment, notes) in assignmentPartitions) {
                val component = componentStats[label] ?: noteUnion(notes)
                val noteBounds = noteUnion(notes)
                val averageHeight = notes.map { it.boundingBox.height }.average()
                val tolerance = averageHeight * 0.2
                val extendsAbove = component.top < noteBounds.top - tolerance
                // component.bottom is the inclusive max y, as in np.max;
                // noteBounds.bottom is exclusive, as in oemer's bbox.
                val extendsBelow = component.bottom > noteBounds.bottom + tolerance
                val direction = when {
                    extendsAbove && !extendsBelow -> StemDirection.UP
                    !extendsAbove && extendsBelow -> StemDirection.DOWN
                    extendsAbove && extendsBelow -> StemDirection.AMBIGUOUS
                    else -> {
                        val componentHeight = component.bottom - component.top
                        val noteHeight = noteBounds.bottom - noteBounds.top
                        if (kotlin.math.abs(componentHeight - noteHeight) > averageHeight.toInt() / 5) {
                            StemDirection.AMBIGUOUS
                        } else {
                            StemDirection.NONE
                        }
                    }
                }
                chords += ChordCandidate(
                    id = chords.size,
                    noteheads = notes.sortedBy { it.staffAssignment.staffLinePosition },
                    boundingBox = BoundingBox(
                        left = component.left,
                        top = component.top,
                        right = component.right + 1,
                        bottom = component.bottom + 1
                    ),
                    stemDirection = direction,
                    hasStem = direction != StemDirection.NONE,
                    track = assignment.first,
                    group = assignment.second
                )
            }
        }
        return NoteGroupingResult(
            chords = chords,
            groupMap = buildGroupMap(labels, groups.keys),
            width = width,
            height = height
        )
    }

    private fun buildGroupMap(
        componentLabels: IntArray,
        ownedLabels: Set<Int>
    ): IntArray {
        val groupMap = IntArray(componentLabels.size) { -1 }
        componentLabels.forEachIndexed { index, componentLabel ->
            if (componentLabel in ownedLabels) groupMap[index] = 0
        }
        return groupMap
    }

    /**
     * Writes oemer's `cv2.dilate(stems, ones((3, 2)))` directly into the
     * existing foreground map, avoiding a second page-sized BooleanArray.
     */
    private fun addDilatedStems(
        foreground: IntArray,
        stemMask: BooleanArray,
        width: Int,
        height: Int
    ) {
        val anchorX = 1 // OpenCV default anchor for width 2
        val anchorY = 1 // OpenCV default anchor for height 3
        stemMask.forEachIndexed { index, on ->
            if (!on) return@forEachIndexed
            val sourceX = index % width
            val sourceY = index / width
            for (ky in 0 until 3) {
                for (kx in 0 until 2) {
                    // Dilation output p is on when source p + kernelOffset
                    // is on, hence the anchor-minus-kernel offset.
                    val outputX = sourceX + anchorX - kx
                    val outputY = sourceY + anchorY - ky
                    if (outputX in 0 until width && outputY in 0 until height) {
                        foreground[outputY * width + outputX] = 0
                    }
                }
            }
        }
    }

    private fun sourcePixelBounds(note: NoteheadCandidate, width: Int): BoundingBox? {
        if (note.sourcePixelIndices.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        note.sourcePixelIndices.forEach { index ->
            val x = index % width
            val y = index / width
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        return BoundingBox(minX, minY, maxX + 1, maxY + 1)
    }

    private fun replaceLabel(labels: IntArray, from: Int, to: Int) {
        if (from == to) return
        for (index in labels.indices) {
            if (labels[index] == from) labels[index] = to
        }
    }

    private data class InclusiveBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun componentBounds(labels: IntArray, width: Int): Map<Int, InclusiveBounds> {
        val mutable = mutableMapOf<Int, IntArray>()
        labels.forEachIndexed { index, label ->
            if (label <= 0) return@forEachIndexed
            val x = index % width
            val y = index / width
            val bounds = mutable.getOrPut(label) {
                intArrayOf(x, y, x, y)
            }
            bounds[0] = minOf(bounds[0], x)
            bounds[1] = minOf(bounds[1], y)
            bounds[2] = maxOf(bounds[2], x)
            bounds[3] = maxOf(bounds[3], y)
        }
        return mutable.mapValues { (_, value) ->
            InclusiveBounds(value[0], value[1], value[2], value[3])
        }
    }

    private fun noteUnion(notes: List<NoteheadCandidate>): InclusiveBounds =
        InclusiveBounds(
            left = notes.minOf { it.boundingBox.left },
            top = notes.minOf { it.boundingBox.top },
            right = notes.maxOf { it.boundingBox.right } - 1,
            bottom = notes.maxOf { it.boundingBox.bottom }
        )
}
