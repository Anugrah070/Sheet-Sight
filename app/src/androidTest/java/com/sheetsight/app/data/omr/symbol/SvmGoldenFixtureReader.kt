package com.sheetsight.app.data.omr.symbol

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/** Streams one deterministic sklearn/desktop-ONNX parity fixture. */
internal class SvmGoldenFixtureReader(source: InputStream) : AutoCloseable {
    private val input = DataInputStream(
        BufferedInputStream(source)
    )
    private val header = readHeader()

    val vectorCount: Int get() = header.vectorCount

    /** Reads every record without retaining the complete fixture in memory. */
    fun forEachRecord(block: (index: Int, record: SvmGoldenRecord) -> Unit) {
        repeat(header.vectorCount) { index ->
            block(index, readRecord())
        }
        check(input.read() == -1) { "Unexpected trailing fixture bytes" }
    }

    override fun close() {
        input.close()
    }

    private fun readHeader(): SvmGoldenHeader {
        val magic = ByteArray(FIXTURE_MAGIC.size)
        input.readFully(magic)
        check(magic.contentEquals(FIXTURE_MAGIC)) { "Invalid SVM fixture magic" }
        val version = input.readInt()
        check(version == FIXTURE_VERSION) { "Unsupported SVM fixture version $version" }
        val featureCount = input.readInt()
        check(featureCount == SvmModelSpec.FEATURE_COUNT) {
            "Expected ${SvmModelSpec.FEATURE_COUNT} features, found $featureCount"
        }
        return SvmGoldenHeader(
            featureCount = featureCount,
            sklearnScoreCount = input.readInt(),
            onnxScoreCount = input.readInt(),
            vectorCount = input.readInt()
        )
    }

    private fun readRecord(): SvmGoldenRecord = SvmGoldenRecord(
        expectedClassId = input.readInt(),
        features = FloatArray(header.featureCount) { input.readFloat() },
        sklearnScores = DoubleArray(header.sklearnScoreCount) { input.readDouble() },
        desktopOnnxScores = FloatArray(header.onnxScoreCount) { input.readFloat() }
    )

    private companion object {
        val FIXTURE_MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte())
        const val FIXTURE_VERSION = 2
    }
}

internal data class SvmGoldenRecord(
    val expectedClassId: Int,
    val features: FloatArray,
    val sklearnScores: DoubleArray,
    val desktopOnnxScores: FloatArray
)

private data class SvmGoldenHeader(
    val featureCount: Int,
    val sklearnScoreCount: Int,
    val onnxScoreCount: Int,
    val vectorCount: Int
)
