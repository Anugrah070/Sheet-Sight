package com.sheetsight.app.data.omr.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureConstructorTest {
    @Test
    fun `simple measure uses validated staff extents`() {
        val measures = MeasureConstructor.construct(10, 110, emptyList())

        assertEquals(1, measures.size)
        assertEquals(10, measures.single().left)
        assertEquals(110, measures.single().right)
        assertEquals(MeasureBoundaryEvidence.STAFF_EXTENT, measures.single().leftEvidence)
        assertEquals(MeasureBoundaryEvidence.STAFF_EXTENT, measures.single().rightEvidence)
    }

    @Test
    fun `multiple barlines create ordered non-overlapping measures`() {
        val measures = MeasureConstructor.construct(
            0,
            300,
            listOf(barline(200), barline(100))
        )

        assertEquals(listOf(0 to 100, 100 to 200, 200 to 300), measures.map { it.left to it.right })
        assertTrue(measures.zipWithNext().all { (left, right) -> left.right <= right.left })
    }

    @Test
    fun `leading interval is preserved as pickup measure`() {
        val measures = MeasureConstructor.construct(20, 220, listOf(barline(70), barline(170)))

        assertEquals(20 to 70, measures.first().left to measures.first().right)
        assertEquals(MeasureBoundaryEvidence.STAFF_EXTENT, measures.first().leftEvidence)
        assertEquals(MeasureBoundaryEvidence.DETECTED_BARLINE, measures.first().rightEvidence)
    }

    @Test
    fun `trailing interval is preserved as incomplete final measure`() {
        val measures = MeasureConstructor.construct(0, 250, listOf(barline(100), barline(200)))

        assertEquals(200 to 250, measures.last().left to measures.last().right)
        assertEquals(MeasureBoundaryEvidence.DETECTED_BARLINE, measures.last().leftEvidence)
        assertEquals(MeasureBoundaryEvidence.STAFF_EXTENT, measures.last().rightEvidence)
    }

    private fun barline(x: Int) = DetectedMeasureBarline(
        x,
        SemanticSourceRef(SemanticSourceKind.BARLINE, x.toString())
    )
}

