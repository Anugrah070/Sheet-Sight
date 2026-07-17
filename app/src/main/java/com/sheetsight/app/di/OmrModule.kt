package com.sheetsight.app.di

import com.sheetsight.app.data.omr.OmrEngine
import com.sheetsight.app.data.omr.OnnxOmrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [OmrEngine] to [OnnxOmrEngine], following the same
 * interface-to-implementation pattern [RepositoryModule] uses for
 * [com.sheetsight.app.domain.repository.ScoreRepository]. [OnnxOmrEngine]
 * is a Phase 4.1 placeholder — see its KDoc — so this module exists purely
 * to make the DI graph resolve ahead of Phase 4.2's real implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OmrModule {

    @Binds
    abstract fun bindOmrEngine(impl: OnnxOmrEngine): OmrEngine
}