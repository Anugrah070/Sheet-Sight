package com.sheetsight.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sheetsight.app.data.local.dao.ScoreDao
import com.sheetsight.app.data.local.entity.ScoreEntity

/**
 * App-wide Room database. Entirely local — no cloud sync — per the
 * offline-first requirement.
 *
 * v2: replaces the Phase 1 PlaceholderEntity/PlaceholderDao with the real
 * score storage schema (Phase 2.1). Since the placeholder table never
 * shipped to real users, this is a destructive bump rather than a
 * migration — see [com.sheetsight.app.di.DatabaseModule] for the
 * fallback-to-destructive-migration wiring. Future schema changes once
 * the app has real user data must add a proper Migration instead.
 */
@Database(
    entities = [ScoreEntity::class],
    version = 2,
    // TODO: set to true and configure room.schemaLocation in build.gradle.kts
    // once the schema stabilizes further, so migrations can be tested.
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao

    companion object {
        const val DATABASE_NAME = "sheetsight.db"
    }
}

