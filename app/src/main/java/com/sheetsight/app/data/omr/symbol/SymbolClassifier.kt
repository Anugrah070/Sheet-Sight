package com.sheetsight.app.data.omr.symbol

import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/** Backend-neutral execution contract for one trained oemer SVM. */
fun interface SymbolClassifier {
    fun classify(featureVector: FloatArray): SymbolClassification
}

/** Creates an executable classifier for one bundled model. */
fun interface SvmClassifierBackend {
    fun load(spec: SvmModelSpec): SymbolClassifier
}

/**
 * Process-lifetime classifier cache.
 *
 * No fallback prediction exists: loading or execution failures propagate
 * as explicit errors.
 */
@Singleton
class SymbolClassifierLoader @Inject constructor(
    private val backend: SvmClassifierBackend
) {
    private val classifiers = EnumMap<SvmModelKind, SymbolClassifier>(SvmModelKind::class.java)
    private val classifierLock = Any()

    /** Returns the cached executable classifier for [kind]. */
    fun load(kind: SvmModelKind): SymbolClassifier = synchronized(classifierLock) {
        classifiers.getOrPut(kind) {
            backend.load(SvmModelSpec.forKind(kind))
        }
    }
}
