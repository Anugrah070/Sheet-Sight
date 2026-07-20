package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily creates and caches one [OrtSession] per [OmrModelSpec]. Session
 * creation reads the model's bytes out of `assets/` (Android has no plain
 * filesystem path into the APK) and is expensive, so each session is built
 * once and reused for every subsequent [OmrModelSpec] inference call.
 *
 * Sessions are not explicitly closed: they are process-lifetime singletons,
 * same as [OrtEnvironment] itself, and are freed when the process dies.
 */
@Singleton
class OrtSessionProvider @Inject constructor(
    private val ortEnvironment: OrtEnvironment,
    @ApplicationContext private val context: android.content.Context
) {

    private val sessions = mutableMapOf<OmrModelSpec, OrtSession>()
    private val sessionLock = Any()

    /** Returns the (lazily created, cached) [OrtSession] for [spec]. */
    fun sessionFor(spec: OmrModelSpec): OrtSession = synchronized(sessionLock) {
        sessions.getOrPut(spec) { createSession(spec) }
    }

    private fun createSession(spec: OmrModelSpec): OrtSession {
        val modelBytes = context.assets.open(spec.assetPath).use { it.readBytes() }
        return ortEnvironment.createSession(modelBytes, OrtSession.SessionOptions())
    }
}