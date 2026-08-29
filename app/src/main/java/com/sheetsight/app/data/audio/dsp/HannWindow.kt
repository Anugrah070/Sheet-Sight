package com.sheetsight.app.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos

object HannWindow {
    fun apply(samples: FloatArray): DoubleArray {
        if (samples.size <= 1) return DoubleArray(samples.size) { samples[it].toDouble() }
        return DoubleArray(samples.size) { index ->
            samples[index] * (0.5 - 0.5 * cos(2.0 * PI * index / (samples.size - 1)))
        }
    }
}
