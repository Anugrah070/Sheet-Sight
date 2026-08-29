package com.sheetsight.app.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stateful second-order Butterworth high-pass filter for DC and sub-audible rumble. */
class BiquadHighPassFilter(sampleRateHz: Int, cutoffHz: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        require(sampleRateHz > 0 && cutoffHz in 0.0..sampleRateHz / 2.0)
        val omega = 2.0 * PI * cutoffHz / sampleRateHz
        val alpha = sin(omega) / (2.0 * BUTTERWORTH_Q)
        val scale = 1.0 / (1.0 + alpha)
        b0 = (1.0 + cos(omega)) * 0.5 * scale
        b1 = -(1.0 + cos(omega)) * scale
        b2 = b0
        a1 = -2.0 * cos(omega) * scale
        a2 = (1.0 - alpha) * scale
    }

    fun process(input: FloatArray): FloatArray = FloatArray(input.size) { index ->
        val x0 = input[index].toDouble()
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        y0.toFloat()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    private companion object {
        const val BUTTERWORTH_Q = 0.7071067811865476
    }
}
