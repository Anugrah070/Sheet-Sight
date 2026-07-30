package com.sheetsight.app.di

import android.content.Context
import androidx.room.Room
import com.sheetsight.app.data.local.AppDatabase
import com.sheetsight.app.data.local.dao.ScoreDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the singleton [AppDatabase] instance and its DAOs. Scoped to
 * [SingletonComponent] so the same database survives for the process
 * lifetime, matching the offline-first, single-user-device model.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            // No real user data exists in the schema yet (Phase 2.1), so a
            // destructive fallback is safe. Replace with an explicit
            // Migration before this app ever ships with real score data.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideScoreDao(database: AppDatabase): ScoreDao =
        database.scoreDao()
}

