package com.sheetsight.app.di

import ai.onnxruntime.OrtEnvironment
import com.sheetsight.app.data.omr.OmrEngine
import com.sheetsight.app.data.omr.OnnxOmrEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [OmrEngine] to [OnnxOmrEngine], following the same
 * interface-to-implementation pattern [RepositoryModule] uses for
 * [com.sheetsight.app.domain.repository.ScoreRepository]. [OnnxOmrEngine]
 * is a Phase 4.1 placeholder — see its KDoc — so this module exists purely
 * to make the DI graph resolve ahead of Phase 4.2's real implementation.
 *
 * Also provides [OrtEnvironment] (added in Phase 4.2): ONNX Runtime's
 * environment handle, needed by
 * [com.sheetsight.app.data.omr.preprocessing.OmrTensorFactory] to build
 * input tensors, and later by Phase 4.3's inference step.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OmrModule {

    @Binds
    abstract fun bindOmrEngine(impl: OnnxOmrEngine): OmrEngine

    companion object {

        /**
         * [OrtEnvironment.getEnvironment] is itself a process-wide
         * singleton internally; wrapping it here lets OMR classes receive
         * it via constructor injection instead of every call site reaching
         * for the static getter directly.
         */
        @Provides
        @Singleton
        fun provideOrtEnvironment(): OrtEnvironment = OrtEnvironment.getEnvironment()
    }
}