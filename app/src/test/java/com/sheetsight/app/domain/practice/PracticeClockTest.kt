package com.sheetsight.app.domain.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeClockTest {
    private val time = FakeTimeSource()
    private val clock = PracticeClock(time)

    @Test
    fun `start pause resume and stop use elapsed monotonic time`() {
        clock.start(60)
        time.now = 1_000L
        assertEquals(1_000L, clock.elapsedPracticeMillis())
        assertEquals(1.0, clock.currentBeat(), 0.0001)

        clock.pause()
        time.now = 6_000L
        assertEquals(1_000L, clock.elapsedPracticeMillis())
        assertEquals(1.0, clock.currentBeat(), 0.0001)

        clock.resume()
        time.now = 7_000L
        assertEquals(2_000L, clock.elapsedPracticeMillis())
        assertEquals(2.0, clock.currentBeat(), 0.0001)

        clock.stop()
        assertEquals(0L, clock.elapsedPracticeMillis())
        assertEquals(0.0, clock.currentBeat(), 0.0001)
    }

    @Test
    fun `beat calculation is correct at 60 and 120 BPM`() {
        clock.start(60)
        time.now = 500L
        assertEquals(0.5, clock.currentBeat(), 0.0001)
        clock.stop()

        time.now = 1_000L
        clock.start(120)
        time.now = 1_500L
        assertEquals(1.0, clock.currentBeat(), 0.0001)
    }

    @Test
    fun `new tempo while paused starts a new piecewise segment`() {
        clock.start(60)
        time.now = 1_000L
        clock.pause()
        time.now = 9_000L
        clock.resume(120)
        time.now = 9_500L

        assertEquals(1_500L, clock.elapsedPracticeMillis())
        assertEquals(2.0, clock.currentBeat(), 0.0001)
    }

    private class FakeTimeSource(var now: Long = 0L) : MonotonicTimeSource {
        override fun nowMillis(): Long = now
    }
}
