package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.BoundingBox
import kotlin.math.abs

/**
 * Feature pipeline expected by oemer `classifier.py::predict()`:
 *
 * 1. crop the binary symbol region;
 * 2. convert `1` to raw intensity `255`;
 * 3. resize to the model's stored `w=40`, `h=70`;
 * 4. flatten row-major without normalization.
 *
 * [resizeBicubic] ports Pillow 11.1.0 `src/libImaging/Resample.c` for an
 * 8-bit grayscale image: separable horizontal/vertical passes, widened
 * downsampling support, edge-truncated and renormalized coefficients, and
 * Pillow's 22-bit fixed-point rounding/clipping. Phase 4 golden fixtures
 * verify the resulting bytes against `PIL.Image.resize()`.
 */
object SvmFeatureExtractor {

    fun extract(
        mask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        boundingBox: BoundingBox
    ): FloatArray {
        require(mask.size == imageWidth * imageHeight) {
            "mask size ${mask.size} doesn't match ${imageWidth}x$imageHeight"
        }
        require(
            boundingBox.left >= 0 &&
                    boundingBox.top >= 0 &&
                    boundingBox.right <= imageWidth &&
                    boundingBox.bottom <= imageHeight &&
                    boundingBox.width > 0 &&
                    boundingBox.height > 0
        ) { "boundingBox must be a non-empty region inside the mask" }

        val source = FloatArray(boundingBox.width * boundingBox.height)
        for (y in 0 until boundingBox.height) {
            for (x in 0 until boundingBox.width) {
                source[y * boundingBox.width + x] =
                    if (mask[(boundingBox.top + y) * imageWidth + boundingBox.left + x]) 255f else 0f
            }
        }
        return resizeBicubic(
            source,
            boundingBox.width,
            boundingBox.height,
            SvmModelSpec.FEATURE_WIDTH,
            SvmModelSpec.FEATURE_HEIGHT
        )
    }

    internal fun resizeBicubic(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        require(source.size == sourceWidth * sourceHeight)
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source.copyOf()

        val sourcePixels = IntArray(source.size) { source[it].toInt().coerceIn(0, 255) }
        val horizontal = if (sourceWidth == targetWidth) {
            sourcePixels
        } else {
            resampleHorizontal(sourcePixels, sourceWidth, sourceHeight, targetWidth)
        }
        val horizontalWidth = targetWidth
        val resized = if (sourceHeight == targetHeight) {
            horizontal
        } else {
            resampleVertical(horizontal, horizontalWidth, sourceHeight, targetHeight)
        }
        return FloatArray(resized.size) { resized[it].toFloat() }
    }

    private fun resampleHorizontal(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int
    ): IntArray {
        val coefficients = precomputeCoefficients(sourceWidth, targetWidth)
        val output = IntArray(targetWidth * sourceHeight)
        for (y in 0 until sourceHeight) {
            for (targetX in 0 until targetWidth) {
                output[y * targetWidth + targetX] = filteredPixel(
                    coefficients,
                    targetX
                ) { sourceX -> source[y * sourceWidth + sourceX] }
            }
        }
        return output
    }

    private fun resampleVertical(
        source: IntArray,
        width: Int,
        sourceHeight: Int,
        targetHeight: Int
    ): IntArray {
        val coefficients = precomputeCoefficients(sourceHeight, targetHeight)
        val output = IntArray(width * targetHeight)
        for (targetY in 0 until targetHeight) {
            for (x in 0 until width) {
                output[targetY * width + x] = filteredPixel(
                    coefficients,
                    targetY
                ) { sourceY -> source[sourceY * width + x] }
            }
        }
        return output
    }

    private fun filteredPixel(
        coefficients: AxisCoefficients,
        outputIndex: Int,
        sourcePixel: (Int) -> Int
    ): Int {
        val first = coefficients.firstSourceIndices[outputIndex]
        val weights = coefficients.weights[outputIndex]
        var sum = FIXED_POINT_ROUNDING
        weights.forEachIndexed { offset, weight ->
            sum += sourcePixel(first + offset) * weight
        }
        return (sum shr PRECISION_BITS).coerceIn(0, 255)
    }

    private fun precomputeCoefficients(
        sourceSize: Int,
        targetSize: Int
    ): AxisCoefficients {
        val scale = sourceSize.toDouble() / targetSize
        val filterScale = maxOf(scale, 1.0)
        val support = BICUBIC_SUPPORT * filterScale
        val firstIndices = IntArray(targetSize)
        val weights = Array(targetSize) { IntArray(0) }
        for (targetIndex in 0 until targetSize) {
            val center = (targetIndex + 0.5) * scale
            val first = (center - support + 0.5).toInt().coerceAtLeast(0)
            val end = (center + support + 0.5).toInt().coerceAtMost(sourceSize)
            firstIndices[targetIndex] = first
            weights[targetIndex] = normalizedWeights(first, end, center, filterScale)
        }
        return AxisCoefficients(firstIndices, weights)
    }

    private fun normalizedWeights(
        first: Int,
        end: Int,
        center: Double,
        filterScale: Double
    ): IntArray {
        val coefficients = DoubleArray(end - first) { offset ->
            cubicWeight((offset + first - center + 0.5) / filterScale)
        }
        val sum = coefficients.sum()
        return IntArray(coefficients.size) { index ->
            val normalized = if (sum == 0.0) coefficients[index] else coefficients[index] / sum
            val scaled = normalized * FIXED_POINT_SCALE
            if (scaled < 0.0) (scaled - 0.5).toInt() else (scaled + 0.5).toInt()
        }
    }

    private fun cubicWeight(distance: Double): Double {
        val x = abs(distance)
        return when {
            x <= 1.0 -> (1.5 * x - 2.5) * x * x + 1.0
            x < 2.0 -> ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0
            else -> 0.0
        }
    }

    private data class AxisCoefficients(
        val firstSourceIndices: IntArray,
        val weights: Array<IntArray>
    )

    private const val BICUBIC_SUPPORT = 2.0
    private const val PRECISION_BITS = 22
    private const val FIXED_POINT_SCALE = 1 shl PRECISION_BITS
    private const val FIXED_POINT_ROUNDING = 1 shl (PRECISION_BITS - 1)
}
