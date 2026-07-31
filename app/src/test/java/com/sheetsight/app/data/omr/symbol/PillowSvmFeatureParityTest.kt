package com.sheetsight.app.data.omr.symbol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

class PillowSvmFeatureParityTest {

    @Test
    fun `bicubic feature bytes match Pillow 11_1_0`() {
        val resource = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH)
        ) { "Missing $FIXTURE_PATH" }
        DataInputStream(BufferedInputStream(GZIPInputStream(resource))).use { input ->
            verifyHeader(input)
            val caseCount = input.readInt()
            repeat(caseCount) { caseIndex -> verifyCase(input, caseIndex) }
            check(input.read() == -1) { "Unexpected trailing feature-fixture bytes" }
        }
    }

    private fun verifyHeader(input: DataInputStream) {
        val magic = ByteArray(FIXTURE_MAGIC.size)
        input.readFully(magic)
        assertArrayEquals(FIXTURE_MAGIC, magic)
        assertEquals(FIXTURE_VERSION, input.readInt())
    }

    private fun verifyCase(input: DataInputStream, caseIndex: Int) {
        val sourceWidth = input.readInt()
        val sourceHeight = input.readInt()
        val source = ByteArray(sourceWidth * sourceHeight)
        input.readFully(source)
        val expected = ByteArray(SvmModelSpec.FEATURE_COUNT)
        input.readFully(expected)
        val actual = SvmFeatureExtractor.resizeBicubic(
            FloatArray(source.size) { source[it].toUByte().toFloat() },
            sourceWidth,
            sourceHeight,
            SvmModelSpec.FEATURE_WIDTH,
            SvmModelSpec.FEATURE_HEIGHT
        )
        val actualBytes = ByteArray(actual.size) { actual[it].toInt().toByte() }
        assertArrayEquals("case $caseIndex ${sourceWidth}x$sourceHeight", expected, actualBytes)
    }

    private companion object {
        const val FIXTURE_PATH = "svm_feature_golden/pillow_bicubic.bin.gz"
        const val FIXTURE_VERSION = 1
        val FIXTURE_MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte())
    }
}
