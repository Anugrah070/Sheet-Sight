package com.sheetsight.app.data.omr.symbol

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.sheetsight.app.data.omr.inference.OrtSessionProvider
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android ONNX Runtime backend for the `skl2onnx` exports of oemer
 * 0.1.8's sklearn `SVC` models.
 *
 * This reproduces `oemer/classifier.py::predict()` after raster feature
 * creation: one unnormalised 2,800-value float row enters the exported
 * `ai.onnx.ml::SVMClassifier`, and its integer `label` output selects the
 * original pickle's class-map entry.
 *
 * **Export deviation:** sklearn executes with double intermediates while
 * ONNX Runtime's exported SVM consumes float32. Classification parity and
 * score tolerance are intentionally deferred to the separate Phase 4
 * verification gate.
 */
@Singleton
class OnnxSvmClassifierBackend @Inject constructor(
    private val environment: OrtEnvironment,
    private val sessionProvider: OrtSessionProvider
) : SvmClassifierBackend {

    override fun load(spec: SvmModelSpec): SymbolClassifier =
        OnnxSvmClassifier(environment, sessionProvider, spec)
}

private class OnnxSvmClassifier(
    private val environment: OrtEnvironment,
    private val sessionProvider: OrtSessionProvider,
    private val spec: SvmModelSpec
) : SymbolClassifier {

    override fun classify(featureVector: FloatArray): SymbolClassification {
        require(featureVector.size == SvmModelSpec.FEATURE_COUNT) {
            "Expected ${SvmModelSpec.FEATURE_COUNT} SVM features, got ${featureVector.size}"
        }
        val shape = longArrayOf(1, SvmModelSpec.FEATURE_COUNT.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(featureVector), shape).use { input ->
            runModel(input)
        }
    }

    private fun runModel(input: OnnxTensor): SymbolClassification {
        val session = sessionProvider.sessionFor(spec)
        return session.run(mapOf(SvmModelSpec.INPUT_TENSOR_NAME to input)).use { result ->
            val labelTensor = result[SvmModelSpec.LABEL_OUTPUT_TENSOR_NAME]
                .orElseThrow { missingOutput(SvmModelSpec.LABEL_OUTPUT_TENSOR_NAME) } as OnnxTensor
            val classId = labelTensor.longBuffer.get(0).toInt()
            val scores = readScores(result[SvmModelSpec.SCORE_OUTPUT_TENSOR_NAME].orElse(null))
            SymbolClassification(
                model = spec.kind,
                classId = classId,
                label = spec.labelFor(classId),
                decisionScores = scores
            )
        }
    }

    private fun readScores(value: ai.onnxruntime.OnnxValue?): List<Float> {
        val tensor = value as? OnnxTensor ?: return emptyList()
        val buffer = tensor.floatBuffer
        return List(buffer.remaining()) { buffer.get() }
    }

    private fun missingOutput(name: String): IllegalStateException =
        IllegalStateException("${spec.name} produced no output tensor named '$name'")
}
