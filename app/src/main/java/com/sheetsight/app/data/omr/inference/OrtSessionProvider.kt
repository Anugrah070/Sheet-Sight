package com.sheetsight.app.data.omr.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily creates and caches one [OrtSession] per [OnnxAssetSpec]. Session
 * creation reads the model's bytes out of `assets/` (Android has no plain
 * filesystem path into the APK) and is expensive, so each session is built
 * once and reused for every subsequent inference call.
 *
 * Sessions are not explicitly closed: they are process-lifetime singletons,
 * same as [OrtEnvironment] itself, and are freed when the process dies.
 *
 * **Session tuning (this phase).** Every session is now built with an
 * explicit, documented [OrtSession.SessionOptions] instead of the bare
 * default — see [buildSessionOptions] for what each setting trades off.
 * Every tuning call is wrapped in `runCatching` and logged rather than
 * allowed to throw: ONNX Runtime Mobile's Java *method* surface (verified
 * against the `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
 * source, tag `v1.27.0`) is not proof that every native execution
 * provider (NNAPI, XNNPACK) was actually compiled into this specific
 * published AAR, and NNAPI's own availability additionally varies by
 * device/OS version. A failed tuning call must degrade to ORT's own
 * default for that setting, never crash session creation.
 */
@Singleton
class OrtSessionProvider @Inject constructor(
    private val ortEnvironment: OrtEnvironment,
    @ApplicationContext private val context: android.content.Context
) {

    private val sessions = mutableMapOf<String, OrtSession>()
    private val sessionLock = Any()

    /** Returns the (lazily created, cached) [OrtSession] for [spec]. */
    fun sessionFor(spec: OnnxAssetSpec): OrtSession = synchronized(sessionLock) {
        sessions.getOrPut(spec.assetPath) { createSession(spec) }
    }

    private fun createSession(spec: OnnxAssetSpec): OrtSession {
        val modelBytes = context.assets.open(spec.assetPath).use { it.readBytes() }
        // SessionOptions is a separate native handle from the OrtSession it
        // configures; ortEnvironment.createSession(...) copies the config
        // into the session, so it's safe (and correct — avoids a native
        // handle leak that existed implicitly before this change) to close
        // it immediately after use via `.use { }`.
        val session = buildSessionOptions().use { options ->
            ortEnvironment.createSession(modelBytes, options)
        }
        if (spec is OmrModelSpec) {
            val contract = runCatching { OmrModelContractVerifier.verify(session, spec) }
                .getOrElse { failure ->
                    session.close()
                    throw IllegalStateException(
                        "Loaded ${spec.assetPath}, but its tensor contract is incompatible",
                        failure
                    )
                }
            Log.i(TAG, "Verified ${spec.name} tensor contract: $contract")
        }
        return session
    }

    /**
     * Builds one tuned [OrtSession.SessionOptions]. Every setting here is a
     * deliberate choice, documented inline, rather than an implicit
     * default — per this phase's requirement.
     */
    private fun buildSessionOptions(): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()

        // Intra-op threads (parallelism *within* one graph's ops, e.g. a
        // single conv): capped at MAX_INTRA_OP_THREADS regardless of how
        // many cores the device has. Trade-off: more threads can speed up
        // a single inference call, but this pipeline already runs on a
        // background dispatcher and an uncapped thread count risks
        // starving the rest of the app (UI, GC, other coroutines) on a
        // busy low-core device — the same "conservative mobile-safety"
        // bias as TileInferenceRunner.DEFAULT_BATCH_SIZE.
        runCatching { options.setIntraOpNumThreads(INTRA_OP_THREAD_COUNT) }
            .onFailure { Log.w(TAG, "setIntraOpNumThreads unavailable; using ORT default", it) }

        // Inter-op threads (parallelism *across* independent graph
        // branches): this pipeline uses ORT's default SEQUENTIAL execution
        // mode, where inter-op parallelism does nothing useful, so a
        // second thread pool here would only cost memory/thread overhead
        // for no benefit. Pinned to 1 rather than left at ORT's own
        // default to make that "no benefit, don't pay for it" decision
        // explicit rather than accidental.
        runCatching { options.setInterOpNumThreads(INTER_OP_THREAD_COUNT) }
            .onFailure { Log.w(TAG, "setInterOpNumThreads unavailable; using ORT default", it) }

        // Graph optimization: ALL_OPT applies every optimization ORT
        // ships (constant folding, node fusion, layout optimizations).
        // Trade-off: slightly longer one-time session-creation cost in
        // exchange for a leaner, faster compiled graph on every
        // subsequent session.run() — a good trade for a session that's
        // built once and reused for the process lifetime.
        runCatching { options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            .onFailure { Log.w(TAG, "setOptimizationLevel unavailable; using ORT default", it) }

        // Memory pattern optimization: lets ORT precompute a reusable
        // allocation pattern for repeated same-shape runs (our mini-batch
        // loop mostly repeats one shape — DEFAULT_BATCH_SIZE tiles — with
        // only the final batch differing). Trade-off: a touch of
        // bookkeeping at session-build time for fewer/larger internal
        // allocations (less allocator churn/fragmentation) at inference
        // time, which is the axis this app has actually crashed on before.
        runCatching { options.setMemoryPatternOptimization(true) }
            .onFailure { Log.w(TAG, "setMemoryPatternOptimization unavailable; using ORT default", it) }

        // CPU arena allocator: explicitly enabled (ORT default) for maximum inference speed.
        // Memory pool reuse eliminates per-kernel OS allocation churn during session.run().
        runCatching { options.setCPUArenaAllocator(true) }
            .onFailure { Log.w(TAG, "setCPUArenaAllocator unavailable; using ORT default", it) }

        val profile = OmrRuntimeTuning.executionProfile
        if (profile == OmrExecutionProfile.NNAPI_THEN_XNNPACK || profile == OmrExecutionProfile.NNAPI_ONLY) {
            runCatching { options.addNnapi() }
                .onFailure { Log.w(TAG, "NNAPI unavailable for $profile; falling back", it) }
        }
        if (profile == OmrExecutionProfile.NNAPI_THEN_XNNPACK || profile == OmrExecutionProfile.XNNPACK_ONLY) {
            runCatching {
                options.addXnnpack(
                    mapOf("intra_op_num_threads" to INTRA_OP_THREAD_COUNT.toString())
                )
            }.onFailure { Log.w(TAG, "XNNPACK unavailable for $profile; falling back", it) }
        }
        Log.i(TAG, "Creating OMR session with execution profile=$profile")

        return options
    }

    private companion object {
        private const val TAG = "OrtSessionProvider"

        /** Hard cap on intra-op threads regardless of device core count — see [buildSessionOptions]. */
        private const val MAX_INTRA_OP_THREADS = 4

        /** Computed once; the same budget is applied to every ONNX session. */
        val INTRA_OP_THREAD_COUNT: Int =
            Runtime.getRuntime().availableProcessors().coerceAtMost(MAX_INTRA_OP_THREADS)

        /** See [buildSessionOptions]'s inter-op KDoc: sequential execution mode gets no benefit from more. */
        private const val INTER_OP_THREAD_COUNT = 1
    }
}
