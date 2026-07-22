package com.sheetsight.app.data.omr.staffline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [PeakFinder]. The `golden*` fixtures are the exact
 * output of `scipy.signal.find_peaks(x, height=0.8, distance=8,
 * prominence=1)` on scipy 1.17.1 — if the port drifts from scipy, these
 * fail. Plateau and prominence cases are additionally hand-verifiable.
 */
class PeakFinderTest {

    @Test
    fun `matches scipy on a continuous multi-peak signal`() {
        // scipy golden vector 1 (damped sine + noise, 60 samples).
        val g1 = floatArrayOf(
            0.1490f, 0.5867f, 1.3870f, 2.0935f, 1.8446f, 1.9291f, 2.3552f, 1.8033f, 0.9648f, 0.6890f,
            -0.2455f, -0.8680f, -1.2039f, -2.2695f, -2.4603f, -2.1623f, -2.1465f, -1.4109f, -1.2877f, -0.8465f,
            0.6523f, 0.7587f, 1.3769f, 1.3221f, 1.8021f, 2.0156f, 1.4533f, 1.5456f, 0.7420f, 0.2306f,
            -0.4986f, -0.3665f, -1.4370f, -2.1159f, -1.7355f, -2.3316f, -1.6869f, -1.9445f, -1.2249f, -0.1535f,
            0.6443f, 1.0667f, 1.4704f, 1.7523f, 1.5501f, 1.7269f, 1.5573f, 1.5936f, 0.8314f, -0.4225f,
            -0.4290f, -1.2211f, -1.7762f, -1.6979f, -1.6900f, -1.6355f, -1.8884f, -1.2855f, -0.5288f, 0.2927f
        )
        val peaks = PeakFinder.findPeaks(g1, height = 0.8f, distance = 8, prominence = 1.0f)
        assertArrayEquals(intArrayOf(6, 25, 43), peaks)
    }

    @Test
    fun `matches scipy on low-amplitude noise (no peaks pass the filters)`() {
        // scipy golden vector 3 (randn*0.4, 50 samples) -> no peak clears height 0.8 + prominence 1.
        val g3 = floatArrayOf(
            0.7155f, 0.1746f, 0.0386f, -0.7454f, -0.1110f, -0.1419f, -0.0331f, -0.2508f, -0.0175f, -0.1909f,
            -0.5255f, 0.3538f, 0.3525f, 0.6838f, 0.0200f, -0.1619f, -0.2181f, -0.6186f, 0.3929f, -0.4404f,
            -0.4740f, -0.0823f, 0.5945f, 0.0947f, -0.4095f, -0.2852f, 0.2501f, -0.0642f, -0.3075f, -0.0920f,
            0.2980f, 0.7904f, -0.4976f, -0.2506f, -0.3215f, -0.9676f, -0.3695f, -0.4096f, 0.4496f, -0.0528f,
            -0.6493f, 0.2587f, -0.1425f, -0.6973f, -0.2387f, -0.2354f, -0.3496f, 0.0119f, -0.8993f, -0.1071f
        )
        val peaks = PeakFinder.findPeaks(g3, height = 0.8f, distance = 8, prominence = 1.0f)
        assertArrayEquals(intArrayOf(), peaks)
    }

    @Test
    fun `prominence values match scipy peak_prominences`() {
        // scipy: find_peaks([0,1,0,2,0,3,0], prominence=0) -> peaks [1,3,5], prominences [1,2,3].
        val x = floatArrayOf(0f, 1f, 0f, 2f, 0f, 3f, 0f)
        val peaks = PeakFinder.findPeaks(x, prominence = 0f)
        assertArrayEquals(intArrayOf(1, 3, 5), peaks)
        val proms = PeakFinder.prominences(x, peaks.toList())
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), proms, 1e-5f)
    }

    @Test
    fun `plateau peak reports the integer midpoint`() {
        // Flat top over indices 3,4,5 -> scipy reports (3+5)/2 = 4.
        val x = floatArrayOf(0f, 1f, 2f, 5f, 5f, 5f, 2f, 1f, 0f)
        assertEquals(listOf(4), PeakFinder.localMaxima1d(x))
    }

    @Test
    fun `edges are never peaks`() {
        val x = floatArrayOf(9f, 1f, 2f, 1f, 9f)
        assertEquals(listOf(2), PeakFinder.localMaxima1d(x))
    }

    @Test
    fun `distance keeps the taller of two close peaks`() {
        // Peaks at index 2 (h=3) and 5 (h=5), 3 apart < distance 8 -> keep the taller (index 5).
        val x = floatArrayOf(0f, 1f, 3f, 1f, 2f, 5f, 1f)
        val peaks = PeakFinder.findPeaks(x, distance = 8)
        assertArrayEquals(intArrayOf(5), peaks)
    }
}