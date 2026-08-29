package com.sheetsight.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
            .addMigrations(MIGRATION_4_5)
            // Older pre-v4 development schemas still use the existing fallback;
            // v4 score data is preserved by the explicit lifecycle migration.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideScoreDao(database: AppDatabase): ScoreDao =
        database.scoreDao()

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE scores ADD COLUMN original_music_xml_path TEXT")
            database.execSQL("ALTER TABLE scores ADD COLUMN current_music_xml_path TEXT")
            database.execSQL(
                """
                UPDATE scores
                SET original_music_xml_path = music_xml_path,
                    current_music_xml_path = music_xml_path
                WHERE music_xml_path IS NOT NULL
                """.trimIndent()
            )
        }
    }
}
