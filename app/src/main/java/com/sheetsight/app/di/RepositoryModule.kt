package com.sheetsight.app.di

import com.sheetsight.app.data.repository.ScoreRepositoryImpl
import com.sheetsight.app.domain.repository.ScoreRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds `domain.repository` interfaces to their `data.repository`
 * implementations. This is the one place in the app that knows both sides
 * exist — every other class depends only on the interface.
 *
 * As more repositories are added in later phases (FingeringRepository,
 * PracticeStatsRepository, ...), add one `@Binds` function per interface
 * here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindScoreRepository(
        impl: ScoreRepositoryImpl
    ): ScoreRepository
}

