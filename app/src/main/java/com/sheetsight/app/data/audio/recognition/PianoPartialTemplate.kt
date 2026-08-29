package com.sheetsight.app.data.audio.recognition

import kotlin.math.sqrt

/** Stiff-string partial locations, normalized so [firstPartialHz] remains the first partial. */
object PianoPartialTemplate {
    val coefficientBank: DoubleArray = doubleArrayOf(0.0, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3)

    fun partialFrequency(firstPartialHz: Double, partial: Int, coefficient: Double): Double {
        require(firstPartialHz > 0.0 && partial > 0 && coefficient >= 0.0)
        return partial * firstPartialHz * sqrt((1.0 + coefficient * partial * partial) / (1.0 + coefficient))
    }
}
