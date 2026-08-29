package com.sheetsight.app.data.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Clean-room iterative radix-2 FFT used to avoid Android/native dependencies in JVM tests. */
object Radix2Fft {
    fun powerSpectrum(windowedSamples: DoubleArray): DoubleArray {
        val size = windowedSamples.size
        require(size > 0 && size.countOneBits() == 1) { "FFT size must be a positive power of two." }
        val real = windowedSamples.copyOf()
        val imaginary = DoubleArray(size)
        bitReverse(real, imaginary)

        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var offset = 0
            while (offset < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                val half = length / 2
                for (index in 0 until half) {
                    val even = offset + index
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                offset += length
            }
            length *= 2
        }
        return DoubleArray(size / 2 + 1) { index ->
            (real[index] * real[index] + imaginary[index] * imaginary[index]) / (size * size)
        }
    }

    private fun bitReverse(real: DoubleArray, imaginary: DoubleArray) {
        var target = 0
        for (source in 1 until real.size) {
            var bit = real.size shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (source < target) {
                val realValue = real[source]
                real[source] = real[target]
                real[target] = realValue
                val imaginaryValue = imaginary[source]
                imaginary[source] = imaginary[target]
                imaginary[target] = imaginaryValue
            }
        }
    }
}
