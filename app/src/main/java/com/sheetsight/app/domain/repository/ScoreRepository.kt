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

    suspend fun updatePracticeProgress(id: Long, progress: Float)

    /** Called once OMR/editing has produced a MusicXML file for this score. */
    suspend fun setMusicXmlPath(id: Long, path: String)
}
