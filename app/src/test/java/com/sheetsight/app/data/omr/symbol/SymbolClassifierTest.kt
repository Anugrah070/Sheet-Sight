package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SymbolClassifierTest {

    @Test
    fun `feature extraction emits oemer 40 by 70 raw intensities`() {
        val width = 8
        val height = 8
        val mask = BooleanArray(width * height)
        for (y in 2 until 6) {
            for (x in 1 until 5) mask[y * width + x] = true
        }

        val feature = SvmFeatureExtractor.extract(
            mask,
            width,
            height,
            BoundingBox(1, 2, 5, 6),
            SvmModelDescriptor.forKind(SvmModelKind.CLEF)
        )

        assertEquals(40 * 70, feature.size)
        assertTrue(feature.all { it == 255f })
    }

    @Test
    fun `missing trained model fails explicitly`() {
        val loader = SymbolClassifierLoader(modelSource = SymbolModelSource { null })

        try {
            loader.load(SvmModelKind.ACCIDENTAL)
            fail("Expected UnsupportedModelException")
        } catch (error: UnsupportedModelException) {
            assertTrue(error.message.orEmpty().contains("sfn.model"))
        }
    }

    @Test
    fun `loader delegates present bytes to an SVM backend`() {
        var receivedPath: String? = null
        val loader = SymbolClassifierLoader(
            modelSource = SymbolModelSource { byteArrayOf(1, 2, 3) },
            backend = SvmClassifierBackend { descriptor, bytes ->
                receivedPath = descriptor.assetPath
                assertEquals(3, bytes.size)
                SymbolClassifier { "gclef" }
            }
        )

        val classifier = loader.load(SvmModelKind.CLEF)

        assertEquals("sklearn_models/clef.model", receivedPath)
        assertEquals("gclef", classifier.classify(FloatArray(40 * 70)))
    }

    @Test
    fun `default backend rejects sklearn pickle instead of predicting`() {
        val loader = SymbolClassifierLoader(
            modelSource = SymbolModelSource { byteArrayOf(0x80.toByte(), 0x04) }
        )

        try {
            loader.load(SvmModelKind.REST)
            fail("Expected UnsupportedModelException")
        } catch (error: UnsupportedModelException) {
            assertTrue(error.message.orEmpty().contains("sklearn pickle"))
        }
    }
}
