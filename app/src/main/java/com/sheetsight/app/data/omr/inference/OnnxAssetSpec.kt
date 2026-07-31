package com.sheetsight.app.data.omr.inference

/**
 * Identifies an ONNX model bundled in the application assets.
 *
 * Implementations may describe segmentation or symbol-classification
 * models; [OrtSessionProvider] uses only the stable asset path.
 */
interface OnnxAssetSpec {
    val assetPath: String
}
