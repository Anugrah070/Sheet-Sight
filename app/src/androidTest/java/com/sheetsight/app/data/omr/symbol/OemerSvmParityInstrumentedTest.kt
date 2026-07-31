package com.sheetsight.app.data.omr.symbol

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheetsight.app.data.omr.inference.OrtSessionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Phase 4 parity gate for oemer 0.1.8's four trained SVMs.
 *
 * Each fixture contains original sklearn 1.2.0 labels/scores and the
 * desktop ONNX Runtime 1.27.0 float output bits for the same vectors.
 * Android labels must match exactly. ARM64 scores may differ from the
 * desktop x86-64 ONNX golden by at most one float ULP, the measured Phase
 * 4 cross-architecture drift. The sklearn score assertion uses `8e-6`,
 * the next decimal ceiling above the desktop fixture-wide maximum
 * (`7.736088525500673e-6`).
 */
@RunWith(AndroidJUnit4::class)
class OemerSvmParityInstrumentedTest {

    @Test
    fun allFourOnnxClassifiersMatchSklearnAndDesktopOnnx() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val fixtureAssets = instrumentation.context.assets
        val environment = OrtEnvironment.getEnvironment()
        val sessionProvider = OrtSessionProvider(environment, targetContext)
        val backend = OnnxSvmClassifierBackend(environment, sessionProvider)

        FIXTURES.forEach { (spec, fixturePath) ->
            val classifier = backend.load(spec)
            val metrics = DeviceScoreMetrics()
            fixtureAssets.open(fixturePath).use { fixtureSource ->
                SvmGoldenFixtureReader(fixtureSource).use { fixture ->
                    fixture.forEachRecord { index, record ->
                        verifyRecord(spec, classifier, index, record, metrics)
                    }
                }
            }
            Log.i(TAG, metrics.summary(spec))
            metrics.assertWithinReviewedTolerance(spec)
        }
    }

    private fun verifyRecord(
        spec: SvmModelSpec,
        classifier: SymbolClassifier,
        vectorIndex: Int,
        record: SvmGoldenRecord,
        metrics: DeviceScoreMetrics
    ) {
        val actual = classifier.classify(record.features)
        val context = "${spec.name} vector $vectorIndex"
        assertEquals("$context label", record.expectedClassId, actual.classId)
        assertEquals("$context model", spec.kind, actual.model)
        assertEquals("$context score count", record.desktopOnnxScores.size, actual.decisionScores.size)
        record.desktopOnnxScores.forEachIndexed { scoreIndex, expectedScore ->
            val actualScore = actual.decisionScores[scoreIndex]
            metrics.record(expectedScore, actualScore)
        }
        assertSklearnScoreTolerance(context, actual.decisionScores, record.sklearnScores)
    }

    private fun assertSklearnScoreTolerance(
        context: String,
        actualOnnxScores: List<Float>,
        sklearnScores: DoubleArray
    ) {
        sklearnScores.forEachIndexed { scoreIndex, sklearnScore ->
            val delta = abs(actualOnnxScores[scoreIndex].toDouble() - sklearnScore)
            assertTrue(
                "$context sklearn score[$scoreIndex] delta=$delta",
                delta <= MAX_SKLEARN_SCORE_DELTA
            )
        }
        if (sklearnScores.size == 1) {
            val inverseDelta = abs(actualOnnxScores[1].toDouble() + sklearnScores[0])
            assertTrue("$context binary inverse delta=$inverseDelta", inverseDelta <= MAX_SKLEARN_SCORE_DELTA)
        }
    }

    companion object {
        const val TAG = "SvmParity"
        const val MAX_SKLEARN_SCORE_DELTA = 8e-6
        const val MAX_ANDROID_DESKTOP_ULP_DELTA = 1L

        val FIXTURES = linkedMapOf(
            SvmModelSpec.CLEF to "svm_golden/clef.bin",
            SvmModelSpec.ACCIDENTAL to "svm_golden/sfn.bin",
            SvmModelSpec.REST to "svm_golden/rests.bin",
            SvmModelSpec.REST_ABOVE_EIGHTH to "svm_golden/rests_above8.bin"
        )
    }
}

private data class DeviceScoreMetrics(
    var comparedScores: Int = 0,
    var bitMismatches: Int = 0,
    var maxAbsoluteDelta: Double = 0.0,
    var maxUlpDelta: Long = 0
) {
    fun record(expected: Float, actual: Float) {
        comparedScores++
        if (expected.toRawBits() == actual.toRawBits()) return
        bitMismatches++
        maxAbsoluteDelta = maxOf(maxAbsoluteDelta, abs(expected.toDouble() - actual.toDouble()))
        maxUlpDelta = maxOf(maxUlpDelta, ulpDistance(expected, actual))
    }

    fun summary(spec: SvmModelSpec): String =
        "[SVM_PARITY] model=${spec.name} scores=$comparedScores " +
                "bitMismatches=$bitMismatches maxAbsDelta=$maxAbsoluteDelta " +
                "maxUlpDelta=$maxUlpDelta"

    fun assertWithinReviewedTolerance(spec: SvmModelSpec) {
        assertTrue(
            "${spec.name} Android/desktop maxUlpDelta=$maxUlpDelta",
            maxUlpDelta <= OemerSvmParityInstrumentedTest.MAX_ANDROID_DESKTOP_ULP_DELTA
        )
    }

    private fun ulpDistance(expected: Float, actual: Float): Long =
        abs(orderedBits(expected) - orderedBits(actual))

    private fun orderedBits(value: Float): Long {
        val bits = value.toRawBits().toLong()
        return if (bits < 0) 0x80000000L - bits else bits
    }
}
