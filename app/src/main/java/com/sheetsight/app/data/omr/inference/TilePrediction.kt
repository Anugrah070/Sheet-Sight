package com.sheetsight.app.data.omr.inference

/**
 * One tile's raw model output, positioned back in the canonical page's
 * coordinate space via [originX]/[originY] — the same origins
 * [com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler] produced
 * for the input tile this came from.
 *
 * @property values Row-major `[y][x][channel]` prediction scores for this
 *   tile, exactly as returned by the model (no argmax/threshold applied),
 *   flattened into one `windowSize*windowSize*channels`-length
 *   [FloatArray] — pixel `(x, y)` channel `c` lives at
 *   `(y * windowSize + x) * channels + c`, matching every other flat
 *   row-major buffer in this OMR pipeline (e.g.
 *   [OmrPredictionMap.data]). **Deliberately not** a nested
 *   `Array<Array<FloatArray>>`: at ~256x256 that shape would be 65,536
 *   individual [FloatArray] objects per tile, and the resulting
 *   per-object JVM header overhead was directly responsible for a
 *   confirmed Java-heap `OutOfMemoryError` during model inference (see
 *   [TileInferenceRunner]'s KDoc). A single flat array holds the exact
 *   same values with one object's worth of overhead instead of tens of
 *   thousands.
 */
data class TilePrediction(
    val originX: Int,
    val originY: Int,
    val windowSize: Int,
    val channels: Int,
    val values: FloatArray
)