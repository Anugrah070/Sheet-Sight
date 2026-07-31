package com.sheetsight.app.data.omr.symbol

import com.sheetsight.app.data.omr.track.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            BoundingBox(1, 2, 5, 6)
        )

        assertEquals(SvmModelSpec.FEATURE_COUNT, feature.size)
        assertTrue(feature.all { it == 255f })
    }

    @Test
    fun `model specifications preserve trained class-map order`() {
        assertEquals(
            listOf("rest_whole", "rest_quarter", "rest_8th"),
            SvmModelSpec.REST.labels.map { it.sourceName }
        )
        assertEquals(
            listOf("rest_8th", "rest_16th", "rest_32nd", "rest_64th"),
            SvmModelSpec.REST_ABOVE_EIGHTH.labels.map { it.sourceName }
        )
    }

    @Test
    fun `loader delegates once and caches classifier by model kind`() {
        var loadCount = 0
        val expected = SymbolClassifier {
            SymbolClassification(
                SvmModelKind.CLEF,
                0,
                ClefSymbolLabel.G_CLEF,
                emptyList()
            )
        }
        val loader = SymbolClassifierLoader(
            SvmClassifierBackend { spec ->
                assertEquals(SvmModelSpec.CLEF, spec)
                loadCount++
                expected
            }
        )

        val first = loader.load(SvmModelKind.CLEF)
        val second = loader.load(SvmModelKind.CLEF)

        assertSame(expected, first)
        assertSame(first, second)
        assertEquals(1, loadCount)
    }
}
