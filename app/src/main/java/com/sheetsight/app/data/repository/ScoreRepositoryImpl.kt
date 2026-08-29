package com.sheetsight.app.data.repository

import com.sheetsight.app.data.local.dao.ScoreDao
import com.sheetsight.app.data.local.ArtifactDeletionStage
import com.sheetsight.app.data.local.MusicXmlArtifactStore
import com.sheetsight.app.data.local.entity.toDomain
import com.sheetsight.app.data.local.entity.toEntity
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import com.sheetsight.app.domain.repository.GeneratedScoreDeletionResult
import com.sheetsight.app.domain.repository.MusicXmlVersionPersistenceResult
import com.sheetsight.app.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScoreRepository] implementation backed by Room via [ScoreDao]. All
 * entity<->domain mapping happens here so the rest of the app never
 * touches [com.sheetsight.app.data.local.entity.ScoreEntity] directly.
 */
@Singleton
class ScoreRepositoryImpl @Inject constructor(
    private val scoreDao: ScoreDao,
    private val artifactStore: MusicXmlArtifactStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScoreRepository {
    private val generatedArtifactMutex = Mutex()

    override fun getAllScores(): Flow<List<Score>> =
        scoreDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeEditorScores(): Flow<List<Score>> =
        scoreDao.getEditorCandidates()
            .map { candidates ->
                candidates.mapNotNull { entity ->
                    entity.toDomain().takeIf { score ->
                        artifactStore.inspect(score.currentMusicXmlPath) != null
                    }
                }
            }
            .flowOn(ioDispatcher)

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

    override suspend fun setGeneratedMusicXmlPath(id: Long, path: String) =
        generatedArtifactMutex.withLock {
            val artifact = requireNotNull(artifactStore.inspect(path)) {
                "Generated MusicXML must be a readable, non-empty file in app-managed score storage."
            }
            scoreDao.setGeneratedMusicXmlPath(id, artifact.canonicalPath)
        }

    override suspend fun setCurrentMusicXmlPath(id: Long, path: String) =
        generatedArtifactMutex.withLock {
            val artifact = requireNotNull(artifactStore.inspect(path)) {
                "Current MusicXML must be a readable, non-empty file in app-managed score storage."
            }
            scoreDao.updateCurrentMusicXmlPath(id, artifact.canonicalPath)
        }

    override suspend fun persistEditedMusicXmlVersion(
        id: Long,
        expectedCurrentPath: String,
        musicXmlBytes: ByteArray
    ): MusicXmlVersionPersistenceResult = generatedArtifactMutex.withLock {
        if (musicXmlBytes.isEmpty()) {
            return@withLock MusicXmlVersionPersistenceResult.Failure("The edited MusicXML is empty.")
        }
        runCatching { MusicXmlParser.parseBytes(musicXmlBytes) }.getOrElse { failure ->
            return@withLock MusicXmlVersionPersistenceResult.Failure(
                failure.message ?: "The edited MusicXML could not be parsed."
            )
        }
        val current = scoreDao.getByIdOnce(id)
            ?: return@withLock MusicXmlVersionPersistenceResult.Failure("Score not found.")
        if (current.currentMusicXmlPath != expectedCurrentPath) {
            return@withLock MusicXmlVersionPersistenceResult.Failure(
                "The score changed before the pitch edit could be saved."
            )
        }
        if (artifactStore.inspect(expectedCurrentPath) == null) {
            return@withLock MusicXmlVersionPersistenceResult.Failure(
                "The current MusicXML artifact is unavailable."
            )
        }

        val artifact = runCatching { artifactStore.writeVersion(id, musicXmlBytes) }
            .getOrElse { failure ->
                return@withLock MusicXmlVersionPersistenceResult.Failure(
                    failure.message ?: "The edited MusicXML artifact could not be written."
                )
            }
        runCatching { MusicXmlParser.parseFile(java.io.File(artifact.canonicalPath)) }
            .getOrElse { failure ->
                artifactStore.discard(artifact.canonicalPath)
                return@withLock MusicXmlVersionPersistenceResult.Failure(
                    failure.message ?: "The written MusicXML artifact could not be validated."
                )
            }
        val replaced = runCatching {
            scoreDao.replaceCurrentMusicXmlPath(id, expectedCurrentPath, artifact.canonicalPath)
        }.getOrElse { failure ->
            artifactStore.discard(artifact.canonicalPath)
            return@withLock MusicXmlVersionPersistenceResult.Failure(
                failure.message ?: "The edited MusicXML path could not be persisted."
            )
        }
        if (replaced != 1) {
            artifactStore.discard(artifact.canonicalPath)
            return@withLock MusicXmlVersionPersistenceResult.Failure(
                "The score changed before the pitch edit could be saved."
            )
        }
        MusicXmlVersionPersistenceResult.Success(artifact.canonicalPath)
    }

    override suspend fun deleteGeneratedScore(id: Long): GeneratedScoreDeletionResult =
        generatedArtifactMutex.withLock {
            val entity = scoreDao.getByIdOnce(id)
                ?: return@withLock GeneratedScoreDeletionResult.Failure("Score not found.")
            val currentPath = entity.currentMusicXmlPath?.takeIf { it.isNotBlank() }
                ?: return@withLock GeneratedScoreDeletionResult.Success(fileWasAlreadyMissing = true)

            when (val stage = artifactStore.stageDeletion(currentPath)) {
                is ArtifactDeletionStage.Failure ->
                    GeneratedScoreDeletionResult.Failure(stage.message)

                ArtifactDeletionStage.AlreadyMissing -> {
                    val cleared = runCatching {
                        scoreDao.clearGeneratedCurrentPath(id, currentPath)
                    }.getOrElse { failure ->
                        return@withLock GeneratedScoreDeletionResult.Failure(
                            failure.message ?: "The stale generated-score record could not be cleared."
                        )
                    }
                    if (cleared == 1) {
                        GeneratedScoreDeletionResult.Success(fileWasAlreadyMissing = true)
                    } else {
                        GeneratedScoreDeletionResult.Failure("The generated score changed before it could be deleted.")
                    }
                }

                is ArtifactDeletionStage.Staged -> {
                    val cleared = runCatching {
                        scoreDao.clearGeneratedCurrentPath(id, currentPath)
                    }.getOrElse { failure ->
                        artifactStore.rollbackDeletion(stage.token)
                        return@withLock GeneratedScoreDeletionResult.Failure(
                            failure.message ?: "The generated-score record could not be updated."
                        )
                    }
                    if (cleared != 1) {
                        artifactStore.rollbackDeletion(stage.token)
                        return@withLock GeneratedScoreDeletionResult.Failure(
                            "The generated score changed before it could be deleted."
                        )
                    }
                    if (artifactStore.commitDeletion(stage.token)) {
                        GeneratedScoreDeletionResult.Success(fileWasAlreadyMissing = false)
                    } else {
                        val restoredDatabase = runCatching {
                            scoreDao.restoreGeneratedPaths(
                                id = id,
                                originalPath = entity.originalMusicXmlPath,
                                currentPath = currentPath
                            ) == 1
                        }.getOrDefault(false)
                        val restoredFile = artifactStore.rollbackDeletion(stage.token)
                        val message = if (restoredDatabase && restoredFile) {
                            "The generated MusicXML file could not be deleted."
                        } else {
                            "The generated score could not be deleted safely."
                        }
                        GeneratedScoreDeletionResult.Failure(message)
                    }
                }
            }
        }
}
