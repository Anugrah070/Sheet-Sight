package com.sheetsight.app.data.omr.symbol

import android.content.res.AssetManager
import java.io.IOException

/**
 * oemer's four serialized SVM classifier roles from `classifier.py` and
 * `symbol_extraction.py`. Rests use REST first and REST_ABOVE_EIGHTH when
 * the first label contains `8th`.
 */
enum class SvmModelKind {
    CLEF,
    ACCIDENTAL,
    REST,
    REST_ABOVE_EIGHTH
}

data class SvmModelDescriptor(
    val kind: SvmModelKind,
    val assetPath: String,
    val featureWidth: Int = 40,
    val featureHeight: Int = 70,
    val classLabels: Set<String>
) {
    companion object {
        fun forKind(kind: SvmModelKind): SvmModelDescriptor = when (kind) {
            SvmModelKind.CLEF -> SvmModelDescriptor(
                kind,
                "sklearn_models/clef.model",
                classLabels = setOf("gclef", "fclef")
            )
            SvmModelKind.ACCIDENTAL -> SvmModelDescriptor(
                kind,
                "sklearn_models/sfn.model",
                classLabels = setOf("sharp", "flat", "natural")
            )
            SvmModelKind.REST -> SvmModelDescriptor(
                kind,
                "sklearn_models/rests.model",
                classLabels = setOf("rest_whole", "rest_quarter", "rest_8th")
            )
            SvmModelKind.REST_ABOVE_EIGHTH -> SvmModelDescriptor(
                kind,
                "sklearn_models/rests_above8.model",
                classLabels = setOf("rest_8th", "rest_16th", "rest_32nd", "rest_64th")
            )
        }
    }
}

/** A backend-neutral classifier contract; no neural-network substitution is permitted. */
fun interface SymbolClassifier {
    fun classify(featureVector: FloatArray): String
}

/** Supplies serialized classifier bytes without coupling the core API to Android assets. */
fun interface SymbolModelSource {
    fun load(assetPath: String): ByteArray?
}

/** Android asset-backed source used by the smoke test and future production wiring. */
class AssetManagerSymbolModelSource(
    private val assets: AssetManager
) : SymbolModelSource {
    override fun load(assetPath: String): ByteArray? =
        try {
            assets.open(assetPath).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
}

/**
 * Pluggable decoder/runtime for an SVM model. A future Android-compatible
 * conversion can implement this interface without changing extraction or
 * classification call sites.
 */
fun interface SvmClassifierBackend {
    fun load(descriptor: SvmModelDescriptor, serializedModel: ByteArray): SymbolClassifier
}

class UnsupportedModelException(message: String, cause: Throwable? = null) :
    UnsupportedOperationException(message, cause)

/**
 * Loading infrastructure for oemer-compatible SVM classifiers.
 *
 * It never guesses a label. Missing bytes and unsupported sklearn pickle
 * payloads both produce [UnsupportedModelException].
 */
class SymbolClassifierLoader(
    private val modelSource: SymbolModelSource,
    private val backend: SvmClassifierBackend = UnsupportedSklearnPickleBackend
) {
    fun load(kind: SvmModelKind): SymbolClassifier {
        val descriptor = SvmModelDescriptor.forKind(kind)
        val bytes = modelSource.load(descriptor.assetPath)
            ?: throw UnsupportedModelException(
                "Missing oemer SVM model '${descriptor.assetPath}' for ${kind.name}"
            )
        return backend.load(descriptor, bytes)
    }
}

/**
 * The reference `.model` files are Python pickle dictionaries containing
 * sklearn `SVC` instances. Android has no compatible sklearn pickle
 * runtime in this project, so the default backend stops explicitly.
 */
object UnsupportedSklearnPickleBackend : SvmClassifierBackend {
    override fun load(
        descriptor: SvmModelDescriptor,
        serializedModel: ByteArray
    ): SymbolClassifier {
        throw UnsupportedModelException(
            "Model '${descriptor.assetPath}' is a Python sklearn pickle; " +
                    "no Android-compatible SVM backend/model conversion is installed"
        )
    }
}
