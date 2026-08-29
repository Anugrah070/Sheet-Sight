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

    @Query("SELECT * FROM scores WHERE id = :id")
    suspend fun getByIdOnce(id: Long): ScoreEntity?

    @Query("SELECT * FROM scores ORDER BY import_date DESC")
    fun getAll(): Flow<List<ScoreEntity>>

    /** Database candidates only; repository storage validation decides final eligibility. */
    @Query(
        """
        SELECT * FROM scores
        WHERE current_music_xml_path IS NOT NULL
          AND TRIM(current_music_xml_path) != ''
        ORDER BY import_date DESC
        """
    )
    fun getEditorCandidates(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE is_favorite = 1 ORDER BY import_date DESC")
    fun getFavorites(): Flow<List<ScoreEntity>>

    @Query("UPDATE scores SET last_opened_date = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long)

    @Query("UPDATE scores SET last_viewed_page = :page WHERE id = :id")
    suspend fun updateLastViewedPage(id: Long, page: Int)

    @Query("UPDATE scores SET last_viewed_zoom = :zoom WHERE id = :id")
    suspend fun updateLastViewedZoom(id: Long, zoom: Float)

    @Query("UPDATE scores SET practice_progress = :progress WHERE id = :id")
    suspend fun updatePracticeProgress(id: Long, progress: Float)

    @Query(
        """
        UPDATE scores
        SET original_music_xml_path = COALESCE(original_music_xml_path, :path),
            current_music_xml_path = :path,
            music_xml_path = NULL
        WHERE id = :id
        """
    )
    suspend fun setGeneratedMusicXmlPath(id: Long, path: String)

    @Query("UPDATE scores SET current_music_xml_path = :path, music_xml_path = NULL WHERE id = :id")
    suspend fun updateCurrentMusicXmlPath(id: Long, path: String)

    @Query(
        """
        UPDATE scores
        SET current_music_xml_path = :newPath, music_xml_path = NULL
        WHERE id = :id AND current_music_xml_path = :expectedCurrentPath
        """
    )
    suspend fun replaceCurrentMusicXmlPath(
        id: Long,
        expectedCurrentPath: String,
        newPath: String
    ): Int

    @Query(
        """
        UPDATE scores
        SET original_music_xml_path = CASE
                WHEN original_music_xml_path = :expectedCurrentPath THEN NULL
                ELSE original_music_xml_path
            END,
            current_music_xml_path = NULL,
            music_xml_path = NULL
        WHERE id = :id AND current_music_xml_path = :expectedCurrentPath
        """
    )
    suspend fun clearGeneratedCurrentPath(id: Long, expectedCurrentPath: String): Int

    @Query(
        """
        UPDATE scores
        SET original_music_xml_path = :originalPath,
            current_music_xml_path = :currentPath,
            music_xml_path = NULL
        WHERE id = :id AND current_music_xml_path IS NULL
        """
    )
    suspend fun restoreGeneratedPaths(
        id: Long,
        originalPath: String?,
        currentPath: String
    ): Int
}
