package com.sheetsight.app.domain.repository

import com.sheetsight.app.domain.model.Score
import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading and persisting [Score]s. ViewModels depend on this
 * interface only — [com.sheetsight.app.data.repository.ScoreRepositoryImpl]
 * is the Room-backed implementation, bound in
 * [com.sheetsight.app.di.RepositoryModule]. PDF import / OMR (Phase 3/4)
 * will call [addScore]; this phase only defines storage.
 */
interface ScoreRepository {

    /** All scores, most recently imported first. */
    fun getAllScores(): Flow<List<Score>>

    /** Scores whose authoritative current MusicXML is present, readable, and non-empty. */
    fun observeEditorScores(): Flow<List<Score>>

    /** Scores the user has flagged as favorites. */
    fun getFavoriteScores(): Flow<List<Score>>

    /** A single score by id, or null if it doesn't exist. */
    fun getScoreById(id: Long): Flow<Score?>

    /** Persists a new score and returns its assigned id. */
    suspend fun addScore(score: Score): Long

    /** Persists changes to an existing score (id must be non-zero). */
    suspend fun updateScore(score: Score)

    suspend fun deleteScore(score: Score)

    /** Stamps [id] with the current time as its last-opened time. */
    suspend fun markOpened(id: Long, timestamp: Long)

    /** Updates the last viewed page for the score with [id]. */
    suspend fun updateLastViewedPage(id: Long, page: Int)

    /** Updates the last viewed zoom level for the score with [id]. */
    suspend fun updateLastViewedZoom(id: Long, zoom: Float)

    suspend fun updatePracticeProgress(id: Long, progress: Float)

    /** Records an OMR output as current while preserving the first output as original. */
    suspend fun setGeneratedMusicXmlPath(id: Long, path: String)

    /** Future save seam: changes only the authoritative current MusicXML path. */
    suspend fun setCurrentMusicXmlPath(id: Long, path: String)

    /** Writes a new version and redirects current only if [expectedCurrentPath] is still authoritative. */
    suspend fun persistEditedMusicXmlVersion(
        id: Long,
        expectedCurrentPath: String,
        musicXmlBytes: ByteArray
    ): MusicXmlVersionPersistenceResult =
        MusicXmlVersionPersistenceResult.Failure("MusicXML version persistence is not supported.")

    /** Removes only the generated/current artifact while preserving the Library source. */
    suspend fun deleteGeneratedScore(id: Long): GeneratedScoreDeletionResult
}

sealed interface MusicXmlVersionPersistenceResult {
    data class Success(val currentMusicXmlPath: String) : MusicXmlVersionPersistenceResult
    data class Failure(val message: String) : MusicXmlVersionPersistenceResult
}

sealed interface GeneratedScoreDeletionResult {
    data class Success(val fileWasAlreadyMissing: Boolean) : GeneratedScoreDeletionResult
    data class Failure(val message: String) : GeneratedScoreDeletionResult
}
