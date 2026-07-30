package com.sheetsight.app.data.omr.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TensorBufferSizingTest {

    @Test
    fun `tileByteCount matches windowSize squared times channel count`() {
        // STAFF_AND_SYMBOLS: 256*256*3
        assertEquals(196_608, TensorBufferSizing.tileByteCount(OmrModelSpec.STAFF_AND_SYMBOLS))
        // SYMBOL_DETAIL: 288*288*3 (input channels are always 3, regardless of output channel count)
        assertEquals(248_832, TensorBufferSizing.tileByteCount(OmrModelSpec.SYMBOL_DETAIL))
    }

    @Test
    fun `requiredCapacityBytes scales linearly with batch size`() {
        val perTile = TensorBufferSizing.tileByteCount(OmrModelSpec.STAFF_AND_SYMBOLS)
        assertEquals(perTile * 8, TensorBufferSizing.requiredCapacityBytes(OmrModelSpec.STAFF_AND_SYMBOLS, 8))
        assertEquals(perTile * 1, TensorBufferSizing.requiredCapacityBytes(OmrModelSpec.STAFF_AND_SYMBOLS, 1))
    }

    @Test
    fun `requiredCapacityBytes rejects a non-positive batch size`() {
        assertThrows(IllegalArgumentException::class.java) {
            TensorBufferSizing.requiredCapacityBytes(OmrModelSpec.STAFF_AND_SYMBOLS, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TensorBufferSizing.requiredCapacityBytes(OmrModelSpec.STAFF_AND_SYMBOLS, -1)
        }
    }

    @Test
    fun `maxBatchSizeForRun caps at maxBatchSize when there are more tiles than the cap`() {
        assertEquals(8, TensorBufferSizing.maxBatchSizeForRun(totalTiles = 20, maxBatchSize = 8))
        assertEquals(8, TensorBufferSizing.maxBatchSizeForRun(totalTiles = 9, maxBatchSize = 8))
    }

    @Test
    fun `maxBatchSizeForRun returns the tile count when it is smaller than the cap`() {
        assertEquals(5, TensorBufferSizing.maxBatchSizeForRun(totalTiles = 5, maxBatchSize = 8))
        assertEquals(1, TensorBufferSizing.maxBatchSizeForRun(totalTiles = 1, maxBatchSize = 8))
    }

    @Test
    fun `maxBatchSizeForRun rejects non-positive inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            TensorBufferSizing.maxBatchSizeForRun(totalTiles = 0, maxBatchSize = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TensorBufferSizing.maxBatchSizeForRun(totalTiles = 20, maxBatchSize = 0)
        }
    }
}