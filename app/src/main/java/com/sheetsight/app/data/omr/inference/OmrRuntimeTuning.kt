package com.sheetsight.app.data.omr.inference

/** Execution-provider profiles used for controlled device diagnostics. */
enum class OmrExecutionProfile {
    NNAPI_THEN_XNNPACK,
    NNAPI_ONLY,
    XNNPACK_ONLY,
    CPU_ONLY
}

/** Process-local selection made before any OMR session is created. */
object OmrRuntimeTuning {
    @Volatile
    var executionProfile: OmrExecutionProfile = OmrExecutionProfile.XNNPACK_ONLY
        private set

    @Volatile
    var inferenceBatchSize: Int = 1
        private set

    fun selectExecutionProfile(profile: OmrExecutionProfile) {
        executionProfile = profile
    }

    fun selectInferenceBatchSize(batchSize: Int) {
        require(batchSize > 0)
        inferenceBatchSize = batchSize
    }
}
