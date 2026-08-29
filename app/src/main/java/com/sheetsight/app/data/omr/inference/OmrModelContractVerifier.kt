package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec

/**
 * Verifies the tensor contract from the loaded ONNX graph itself.
 *
 * The model file, not comments or historical implementation notes, is the
 * source of truth. A layout mismatch is fatal because continuing with a
 * plausible-looking transposed prediction map is much harder to diagnose
 * than failing at session creation with the actual graph metadata.
 */
object OmrModelContractVerifier {
    fun verify(session: OrtSession, spec: OmrModelSpec): OmrModelContract {
        val input = session.inputInfo[spec.inputTensorName]?.info as? TensorInfo
            ?: error("${spec.name} has no tensor input named '${spec.inputTensorName}'")
        val output = session.outputInfo[spec.outputTensorName]?.info as? TensorInfo
            ?: error("${spec.name} has no tensor output named '${spec.outputTensorName}'")

        val expectedInput = longArrayOf(-1, spec.windowSize.toLong(), spec.windowSize.toLong(), 3)
        val expectedOutput = longArrayOf(
            -1,
            spec.windowSize.toLong(),
            spec.windowSize.toLong(),
            spec.outputChannels.toLong()
        )
        require(input.type == OnnxJavaType.UINT8) {
            "${spec.name} input type is ${input.type}; expected UINT8"
        }
        require(output.type == OnnxJavaType.FLOAT) {
            "${spec.name} output type is ${output.type}; expected FLOAT32"
        }
        require(matchesNhwc(input.shape, expectedInput)) {
            "${spec.name} input shape ${input.shape.contentToString()} is not NHWC " +
                expectedInput.contentToString()
        }
        require(matchesNhwc(output.shape, expectedOutput)) {
            "${spec.name} output shape ${output.shape.contentToString()} is not NHWC " +
                expectedOutput.contentToString()
        }
        return OmrModelContract(
            inputName = spec.inputTensorName,
            inputType = input.type,
            inputShape = input.shape,
            outputName = spec.outputTensorName,
            outputType = output.type,
            outputShape = output.shape,
            layout = "NHWC"
        )
    }

    private fun matchesNhwc(actual: LongArray, expected: LongArray): Boolean =
        actual.size == expected.size && actual.indices.all { index ->
            index == 0 || actual[index] == expected[index]
        }
}

data class OmrModelContract(
    val inputName: String,
    val inputType: OnnxJavaType,
    val inputShape: LongArray,
    val outputName: String,
    val outputType: OnnxJavaType,
    val outputShape: LongArray,
    val layout: String
) {
    override fun toString(): String =
        "input=$inputName/$inputType/${inputShape.contentToString()}, " +
            "output=$outputName/$outputType/${outputShape.contentToString()}, layout=$layout"
}
