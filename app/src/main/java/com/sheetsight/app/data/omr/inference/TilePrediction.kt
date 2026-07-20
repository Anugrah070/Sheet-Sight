package com.sheetsight.app.data.omr.inference

/**
 * One tile's raw model output, positioned back in the canonical page's
 * coordinate space via [originX]/[originY] — the same origins
 * [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler] produced
 * for the input tile this came from.
 *
 * @property values Row-major `[y][x][channel]` prediction scores for this
 *   tile, exactly as returned by the model (no argmax/threshold applied).
 */
data class TilePrediction(
    val originX: Int,
    val originY: Int,
    val windowSize: Int,
    val channels: Int,
    val values: Array<Array<FloatArray>>
)