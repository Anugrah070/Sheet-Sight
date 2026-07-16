package com.sheetsight.app.data.repository

import com.sheetsight.app.data.local.dao.ScoreDao
import com.sheetsight.app.data.local.entity.toDomain
import com.sheetsight.app.data.local.entity.toEntity
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScoreRepository] implementation backed by Room via [ScoreDao]. All
 * entity<->domain mapping happens here so the rest of the app never
 * touches [com.sheetsight.app.data.local.entity.ScoreEntity] directly.
 */
@Singleton
class ScoreRepositoryImpl @Inject constructor(
    private val scoreDao: ScoreDao
) : ScoreRepository {

    override fun getAllScores(): Flow<List<Score>> =
        scoreDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun getFavoriteScores(): Flow<List<Score>> =
        scoreDao.getFavorites().map { list -> list.map { it.toDomain() } }

    override fun getScoreById(id: Long): Flow<Score?> =
        scoreDao.getById(id).map { it?.toDomain() }

    override suspend fun addScore(score: Score): Long =
        scoreDao.insert(score.toEntity())

    override suspend fun updateScore(score: Score) =
        scoreDao.update(score.toEntity())

    override suspend fun deleteScore(score: Score) =
        scoreDao.delete(score.toEntity())

    override suspend fun markOpened(id: Long, timestamp: Long) =
        scoreDao.updateLastOpened(id, timestamp)

    override suspend fun updateLastViewedPage(id: Long, page: Int) =
        scoreDao.updateLastViewedPage(id, page)

    override suspend fun updateLastViewedZoom(id: Long, zoom: Float) =
        scoreDao.updateLastViewedZoom(id, zoom)

    override suspend fun updatePracticeProgress(id: Long, progress: Float) =
        scoreDao.updatePracticeProgress(id, progress)

    override suspend fun setMusicXmlPath(id: Long, path: String) =
        scoreDao.updateMusicXmlPath(id, path)
}
