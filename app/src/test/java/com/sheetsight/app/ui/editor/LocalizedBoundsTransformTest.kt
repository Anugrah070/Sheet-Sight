package com.sheetsight.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalizedBoundsTransformTest {
    @Test
    fun localizedBoundsUseTheExactBitmapTranslationAndScale() {
        val transformed = requireNotNull(
            LocalizedBoundsTransform.transform(
                bounds = RendererRect(30.0, 50.0, 8.0, 6.0),
                source = RendererRect(10.0, 20.0, 200.0, 100.0),
                target = RendererRect(100.0, 300.0, 400.0, 50.0)
            )
        )

        assertEquals(RendererRect(140.0, 315.0, 16.0, 3.0), transformed)
    }

    @Test
    fun unusableLocalizedRegionsNeverProduceHitTargets() {
        assertNull(
            LocalizedBoundsTransform.transform(
                RendererRect(1.0, 1.0, 2.0, 2.0),
                RendererRect(0.0, 0.0, 0.0, 20.0),
                RendererRect(0.0, 0.0, 20.0, 20.0)
            )
        )
        assertNull(
            LocalizedBoundsTransform.transform(
                RendererRect(Double.NaN, 1.0, 2.0, 2.0),
                RendererRect(0.0, 0.0, 20.0, 20.0),
                RendererRect(0.0, 0.0, 20.0, 20.0)
            )
        )
    }
}
