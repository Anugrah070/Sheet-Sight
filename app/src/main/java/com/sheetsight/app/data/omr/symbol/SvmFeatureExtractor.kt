package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.BoundingBox
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Feature pipeline expected by oemer `classifier.py::predict()`:
 *
 * 1. crop the binary symbol region;
 * 2. convert `1` to raw intensity `255`;
 * 3. resize to the model's stored `w=40`, `h=70`;
 * 4. flatten row-major without normalization.
 *
 * **Unverified raster deviation:** oemer delegates resize to Pillow's
 * `Image.resize`, whose current default for an `L` image is bicubic. This
 * pure-JVM implementation uses the matching cubic coefficient `a=-0.5`
 * and center-based coordinates, but byte-for-byte equivalence with every
 * Pillow version's downsampling/edge quantization has not been verified.
 * The deviation is isolated here so it can be replaced without changing
 * classifier or extraction architecture. Until compatible trained models
 * are installed, [SymbolClassifierLoader] prevents these features from
 * being used to fabricate predictions.
 */
object SvmFeatureExtractor {

    fun extract(
        mask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        boundingBox: BoundingBox,
        descriptor: SvmModelDescriptor
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
            descriptor.featureWidth,
            descriptor.featureHeight
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

        val output = FloatArray(targetWidth * targetHeight)
        val scaleX = sourceWidth.toDouble() / targetWidth
        val scaleY = sourceHeight.toDouble() / targetHeight
        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY + 0.5) * scaleY - 0.5
            val baseY = floor(sourceY).toInt()
            for (targetX in 0 until targetWidth) {
                val sourceX = (targetX + 0.5) * scaleX - 0.5
                val baseX = floor(sourceX).toInt()
                var sum = 0.0
                var weightSum = 0.0
                for (offsetY in -1..2) {
                    val sampleY = (baseY + offsetY).coerceIn(0, sourceHeight - 1)
                    val weightY = cubicWeight(sourceY - (baseY + offsetY))
                    for (offsetX in -1..2) {
                        val sampleX = (baseX + offsetX).coerceIn(0, sourceWidth - 1)
                        val weight = weightY * cubicWeight(sourceX - (baseX + offsetX))
                        sum += source[sampleY * sourceWidth + sampleX] * weight
                        weightSum += weight
                    }
                }
                output[targetY * targetWidth + targetX] =
                    (sum / weightSum).roundToInt().coerceIn(0, 255).toFloat()
            }
        }
        return output
    }

    private fun cubicWeight(distance: Double): Double {
        val x = abs(distance)
        return when {
            x <= 1.0 -> (1.5 * x - 2.5) * x * x + 1.0
            x < 2.0 -> ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0
            else -> 0.0
        }
    }
}
