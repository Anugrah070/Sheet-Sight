package com.sheetsight.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sheetsight.app.data.local.entity.ScoreEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data-access layer for [ScoreEntity]. Read queries return [Flow] so the
 * Library tab (and any future observers, e.g. Analysis) stay in sync with
 * the database automatically. Write operations are plain suspend functions.
 */
@Dao
interface ScoreDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(score: ScoreEntity): Long

    @Update
    suspend fun update(score: ScoreEntity)

    @Delete
    suspend fun delete(score: ScoreEntity)

    @Query("SELECT * FROM scores WHERE id = :id")
    fun getById(id: Long): Flow<ScoreEntity?>

    @Query("SELECT * FROM scores ORDER BY import_date DESC")
    fun getAll(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE is_favorite = 1 ORDER BY import_date DESC")
    fun getFavorites(): Flow<List<ScoreEntity>>

    @Query("UPDATE scores SET last_opened_date = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long)

    @Query("UPDATE scores SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE scores SET practice_progress = :progress WHERE id = :id")
    suspend fun updatePracticeProgress(id: Long, progress: Float)

    @Query("UPDATE scores SET music_xml_path = :path WHERE id = :id")
    suspend fun updateMusicXmlPath(id: Long, path: String)
}
