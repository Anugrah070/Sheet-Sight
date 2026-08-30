package com.sheetsight.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactNoteHeadHitTesterTest {
    private val lower = Any()
    private val upper = Any()
    private val bounds = listOf(
        ExactNoteHeadBounds(lower, x = 10.0, y = 20.0, width = 8.0, height = 6.0),
        ExactNoteHeadBounds(upper, x = 10.0, y = 8.0, width = 8.0, height = 6.0)
    )

    @Test
    fun exactNoteHeadTapsSelectOnlyTheIntendedChordTone() {
        assertSame(lower, ExactNoteHeadHitTester.findUnique(14.0, 23.0, bounds))
        assertSame(upper, ExactNoteHeadHitTester.findUnique(14.0, 11.0, bounds))
    }

    @Test
    fun stemsBeamsStaffLinesChordWhitespaceAndEmptySpaceDoNotSelect() {
        assertNull(ExactNoteHeadHitTester.findUnique(18.5, 18.0, bounds)) // stem
        assertNull(ExactNoteHeadHitTester.findUnique(14.0, 16.0, bounds)) // beam/chord whitespace
        assertNull(ExactNoteHeadHitTester.findUnique(30.0, 23.0, bounds)) // staff line
        assertNull(ExactNoteHeadHitTester.findUnique(100.0, 100.0, bounds)) // empty
    }

    @Test
    fun hitAreaHasNoPaddingAndNearbyNotesDoNotSelectEachOther() {
        assertNull(ExactNoteHeadHitTester.findUnique(9.999, 23.0, bounds))
        assertNull(ExactNoteHeadHitTester.findUnique(18.001, 23.0, bounds))
        assertSame(lower, ExactNoteHeadHitTester.findUnique(10.0, 20.0, bounds))
    }

    @Test
    fun overlappingUnisonBoundsAreRejectedAsAmbiguous() {
        val unison = Any()
        val overlapping = bounds + ExactNoteHeadBounds(unison, 10.0, 20.0, 8.0, 6.0)
        assertNull(ExactNoteHeadHitTester.findUnique(14.0, 23.0, overlapping))
        assertNull(ExactNoteHeadHitTester.findUniquePoint(lower, overlapping))
    }

    @Test
    fun partiallyOverlappingHeadsStillExposeOnlyTheirExactUniquePixels() {
        val left = Any()
        val right = Any()
        val partial = listOf(
            ExactNoteHeadBounds(left, 10.0, 10.0, 8.0, 6.0),
            ExactNoteHeadBounds(right, 16.0, 10.0, 8.0, 6.0)
        )

        val leftPoint = requireNotNull(ExactNoteHeadHitTester.findUniquePoint(left, partial))
        val rightPoint = requireNotNull(ExactNoteHeadHitTester.findUniquePoint(right, partial))
        assertSame(left, ExactNoteHeadHitTester.findUnique(leftPoint.x, leftPoint.y, partial))
        assertSame(right, ExactNoteHeadHitTester.findUnique(rightPoint.x, rightPoint.y, partial))
    }

    @Test
    fun uniquePointFindsANarrowExactSliverWithoutHeuristicSampling() {
        val low = Any()
        val high = Any()
        val nearlyCovered = listOf(
            ExactNoteHeadBounds(low, 0.0, 0.0, 100.0, 10.0),
            ExactNoteHeadBounds(high, 0.0, 0.0, 99.0, 10.0)
        )

        val lowPoint = requireNotNull(ExactNoteHeadHitTester.findUniquePoint(low, nearlyCovered))
        assertSame(low, ExactNoteHeadHitTester.findUnique(lowPoint.x, lowPoint.y, nearlyCovered))
        assertNull(ExactNoteHeadHitTester.findUniquePoint(high, nearlyCovered))
    }

    @Test
    fun accessibleNoteTargetsSelectNearAHeadWithoutTurningTheWholeStaffIntoAHitArea() {
        assertSame(
            lower,
            AccessibleNoteHeadHitTester.findNearestUnique(
                x = 14.0,
                y = 28.0,
                bounds = bounds,
                minimumTargetSize = 18.0
            )
        )
        assertNull(
            AccessibleNoteHeadHitTester.findNearestUnique(
                x = 30.0,
                y = 23.0,
                bounds = bounds,
                minimumTargetSize = 18.0
            )
        )
    }

    @Test
    fun accessibleOverlappingHeadsChooseTheNearestCenterButRejectAUnisonTie() {
        val left = Any()
        val right = Any()
        val overlapping = listOf(
            ExactNoteHeadBounds(left, 10.0, 10.0, 8.0, 6.0),
            ExactNoteHeadBounds(right, 16.0, 10.0, 8.0, 6.0)
        )

        assertSame(left, AccessibleNoteHeadHitTester.findNearestUnique(15.0, 13.0, overlapping, 18.0))
        assertSame(right, AccessibleNoteHeadHitTester.findNearestUnique(19.0, 13.0, overlapping, 18.0))

        val unison = Any()
        assertNull(
            AccessibleNoteHeadHitTester.findNearestUnique(
                14.0,
                13.0,
                bounds = listOf(
                    ExactNoteHeadBounds(left, 10.0, 10.0, 8.0, 6.0),
                    ExactNoteHeadBounds(unison, 10.0, 10.0, 8.0, 6.0)
                )
            )
        )
    }

    @Test
    fun selectionPointerIsCenteredBelowTheSelectedNoteHead() {
        assertEquals(
            SelectionPointerAnchor(centerX = 14.0, tipY = 29.0),
            SelectionPointerGeometry.below(
                RendererRect(x = 10.0, y = 20.0, width = 8.0, height = 6.0),
                gap = 3.0
            )
        )
        assertNull(
            SelectionPointerGeometry.below(
                RendererRect(x = Double.NaN, y = 20.0, width = 8.0, height = 6.0)
            )
        )
    }

    @Test
    fun enlargedSelectionPointerIsDraggableAcrossItsWholeVisibleBody() {
        val anchor = SelectionPointerAnchor(centerX = 50.0, tipY = 70.0)

        assertTrue(
            SelectionPointerHitTester.contains(
                anchor, x = 50.0, y = 84.9,
                halfWidth = 18.0, height = 15.0, topPadding = 8.0, bottomPadding = 10.0
            )
        )
        assertTrue(
            SelectionPointerHitTester.contains(
                anchor, x = 67.9, y = 70.0,
                halfWidth = 18.0, height = 15.0, topPadding = 8.0, bottomPadding = 10.0
            )
        )
        assertFalse(
            SelectionPointerHitTester.contains(
                anchor, x = 69.0, y = 70.0,
                halfWidth = 18.0, height = 15.0, topPadding = 8.0, bottomPadding = 10.0
            )
        )
    }

    @Test
    fun exactSemanticBoundsUseNoPaddingAndRejectAmbiguousOverlap() {
        val rest = "rest"
        val clef = "clef"
        val exact = listOf(
            ExactElementBounds(rest, 10.0, 20.0, 8.0, 6.0),
            ExactElementBounds(clef, 30.0, 20.0, 7.0, 18.0)
        )

        assertSame(rest, ExactElementHitTester.findUnique(10.0, 20.0, exact))
        assertNull(ExactElementHitTester.findUnique(9.999, 23.0, exact))
        assertNull(ExactElementHitTester.findUnique(18.001, 23.0, exact))
        assertSame(clef, ExactElementHitTester.findUnique(33.0, 28.0, exact))

        val ambiguous = exact + ExactElementBounds("other-rest", 10.0, 20.0, 8.0, 6.0)
        assertNull(ExactElementHitTester.findUnique(14.0, 23.0, ambiguous))
        assertNull(ExactElementHitTester.findUniquePoint(rest, ambiguous))
    }
}
