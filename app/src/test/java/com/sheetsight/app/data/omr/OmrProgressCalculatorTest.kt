package com.sheetsight.app.data.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmrProgressCalculatorTest {

    @Test
    fun `progress sums to 100 percent across all stages`() {
        var lastPercentage = 0
        val listener = object : OmrProgressListener {
            override fun onProgressUpdate(update: OmrProgressUpdate) {
                lastPercentage = update.overallPercentage
            }
        }
        val calculator = OmrProgressCalculator(listener)

        OmrStage.entries.forEach { stage ->
            calculator.updateStage(stage, 1.0f)
        }

        assertEquals(100, lastPercentage)
    }

    @Test
    fun `progress never moves backward even if stage progress decreases`() {
        val percentages = mutableListOf<Int>()
        val listener = object : OmrProgressListener {
            override fun onProgressUpdate(update: OmrProgressUpdate) {
                percentages.add(update.overallPercentage)
            }
        }
        val calculator = OmrProgressCalculator(listener)

        calculator.updateStage(OmrStage.MODEL1_INFERENCE, 0.5f)
        val afterHalf = percentages.last()
        
        calculator.updateStage(OmrStage.MODEL1_INFERENCE, 0.1f) // Regression
        
        assertEquals("Progress should not have decreased", afterHalf, percentages.last())
    }

    @Test
    fun `overall progress calculation matches weights`() {
        var currentPercentage = 0
        val listener = object : OmrProgressListener {
            override fun onProgressUpdate(update: OmrProgressUpdate) {
                currentPercentage = update.overallPercentage
            }
        }
        val calculator = OmrProgressCalculator(listener)

        // Stage 1 (5% weight)
        calculator.updateStage(OmrStage.INPUT_DECODE, 1.0f)
        assertEquals(5, currentPercentage)

        // Stage 2 (10% weight)
        calculator.updateStage(OmrStage.PREPROCESSING, 0.5f)
        // 5% + (0.5 * 10%) = 10%
        assertEquals(10, currentPercentage)
    }
}
