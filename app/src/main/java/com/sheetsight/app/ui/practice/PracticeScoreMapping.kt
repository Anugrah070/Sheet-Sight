package com.sheetsight.app.ui.practice

import com.sheetsight.app.domain.practice.PracticePhase
import com.sheetsight.app.domain.practice.PracticeProgress
import com.sheetsight.app.domain.practice.PracticeStep
import com.sheetsight.app.ui.editor.notation.NotationBounds
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationGeometry
import com.sheetsight.app.ui.editor.notation.RenderedNotehead

data class PracticeRenderedNotehead(
    val sourceId: String,
    val measureNumber: String,
    val staffNumber: Int,
    val systemIndex: Int,
    val pageIndex: Int,
    val bounds: NotationBounds
)

data class HighlightTarget(
    val practiceStepIndex: Int,
    val measureNumber: String,
    val staffNumbers: List<Int>,
    val systemIndex: Int,
    val pageIndex: Int,
    val bounds: NotationBounds,
    val noteheads: List<PracticeRenderedNotehead>
) {
    val sourceIds: Set<String> = noteheads.mapTo(linkedSetOf()) { it.sourceId }
}

/** Immutable ID index over the renderer's already-computed layout geometry. */
class PracticeRenderedScoreIndex private constructor(
    private val noteheadsBySourceId: Map<String, PracticeRenderedNotehead>
) {
    fun resolve(step: PracticeStep): HighlightTarget? {
        if (step.sourceNoteIds.isEmpty()) return null
        val noteheads = step.sourceNoteIds.map { noteheadsBySourceId[it] ?: return null }
        val first = noteheads.first()
        if (noteheads.any { it.systemIndex != first.systemIndex || it.pageIndex != first.pageIndex }) return null
        return HighlightTarget(
            practiceStepIndex = step.index,
            measureNumber = step.measureNumber,
            staffNumbers = noteheads.map { it.staffNumber }.distinct(),
            systemIndex = first.systemIndex,
            pageIndex = first.pageIndex,
            bounds = noteheads.map { it.bounds }.reduce(NotationBounds::union),
            noteheads = noteheads
        )
    }

    companion object {
        fun create(document: NotationDocument, systemWidthPx: Float, density: Float): PracticeRenderedScoreIndex {
            val indexed = linkedMapOf<String, PracticeRenderedNotehead>()
            var pageIndex = 0
            document.systems.forEachIndexed { listIndex, system ->
                if (listIndex > 0 && system.startsNewPage) pageIndex++
                NotationGeometry.layoutSystem(system, systemWidthPx, density).noteheads.forEach { notehead ->
                    indexed[notehead.sourceId] = notehead.toPracticeNotehead(pageIndex)
                }
            }
            return PracticeRenderedScoreIndex(indexed.toMap())
        }

        private fun RenderedNotehead.toPracticeNotehead(pageIndex: Int) = PracticeRenderedNotehead(
            sourceId = sourceId,
            measureNumber = measureNumber,
            staffNumber = staffNumber,
            systemIndex = systemIndex,
            pageIndex = pageIndex,
            bounds = bounds
        )
    }
}

object PracticeDisplayState {
    fun currentHighlight(progress: PracticeProgress, index: PracticeRenderedScoreIndex): HighlightTarget? =
        if (progress.phase == PracticePhase.Completed) null
        else progress.currentStep?.let(index::resolve)
}

data class VisibleSystem(
    val systemIndex: Int,
    val topPx: Float,
    val bottomPx: Float
)

data class PracticeViewport(
    val widthPx: Float,
    val heightPx: Float,
    val horizontalOffsetPx: Float,
    val visibleSystems: List<VisibleSystem>
)

data class PracticeScrollRequest(
    val systemIndex: Int,
    val horizontalOffsetPx: Float? = null
)

/** Pure auto-follow policy: keep still while the target is comfortably visible. */
object PracticeAutoFollow {
    fun isVisible(target: HighlightTarget, viewport: PracticeViewport, edgePaddingPx: Float = 24f): Boolean {
        val system = viewport.visibleSystems.firstOrNull { it.systemIndex == target.systemIndex } ?: return false
        val targetTop = system.topPx + target.bounds.top
        val targetBottom = system.topPx + target.bounds.bottom
        val horizontalStart = viewport.horizontalOffsetPx + edgePaddingPx
        val horizontalEnd = viewport.horizontalOffsetPx + viewport.widthPx - edgePaddingPx
        return targetTop >= edgePaddingPx && targetBottom <= viewport.heightPx - edgePaddingPx &&
            target.bounds.left >= horizontalStart && target.bounds.right <= horizontalEnd
    }

    fun request(target: HighlightTarget, viewport: PracticeViewport, edgePaddingPx: Float = 24f): PracticeScrollRequest? {
        if (isVisible(target, viewport, edgePaddingPx)) return null
        val system = viewport.visibleSystems.firstOrNull { it.systemIndex == target.systemIndex }
        if (system == null) return PracticeScrollRequest(target.systemIndex, horizontalTarget(target, viewport))

        val targetTop = system.topPx + target.bounds.top
        val targetBottom = system.topPx + target.bounds.bottom
        if (targetTop < edgePaddingPx || targetBottom > viewport.heightPx - edgePaddingPx) {
            return PracticeScrollRequest(target.systemIndex, horizontalTarget(target, viewport))
        }
        return horizontalTarget(target, viewport)?.let { PracticeScrollRequest(target.systemIndex, it) }
    }

    private fun horizontalTarget(target: HighlightTarget, viewport: PracticeViewport): Float? {
        val visibleLeft = viewport.horizontalOffsetPx
        val visibleRight = visibleLeft + viewport.widthPx
        if (target.bounds.left >= visibleLeft && target.bounds.right <= visibleRight) return null
        return (target.bounds.centerX - viewport.widthPx * 0.28f).coerceAtLeast(0f)
    }
}
