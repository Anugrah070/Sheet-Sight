package com.sheetsight.app.data.repository

import com.sheetsight.app.data.local.ArtifactDeletionStage
import com.sheetsight.app.data.local.ArtifactDeletionToken
import com.sheetsight.app.data.local.FileMusicXmlArtifactStore
import com.sheetsight.app.data.local.MusicXmlArtifactMetadata
import com.sheetsight.app.data.local.MusicXmlArtifactStore
import com.sheetsight.app.data.local.dao.ScoreDao
import com.sheetsight.app.data.local.entity.ScoreEntity
import com.sheetsight.app.domain.repository.GeneratedScoreDeletionResult
import com.sheetsight.app.domain.repository.MusicXmlVersionPersistenceResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScoreRepositoryImplTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `Editor candidates require a genuine current artifact and survive repository recreation`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val valid = File(root, "valid.musicxml").apply { writeText("<score-partwise/>") }
        val empty = File(root, "empty.musicxml").apply { createNewFile() }
        val dao = FakeScoreDao(
            entity(1L, null),
            entity(2L, " "),
            entity(3L, File(root, "missing.musicxml").path),
            entity(4L, empty.path),
            entity(5L, valid.path)
        )
        val store = FileMusicXmlArtifactStore(root)

        val firstRepository = repository(dao, store)
        assertEquals(listOf(5L), firstRepository.observeEditorScores().first().map { it.id })

        val recreatedRepository = repository(dao, store)
        assertEquals(listOf(5L), recreatedRepository.observeEditorScores().first().map { it.id })
    }

    @Test
    fun `generated result persists one canonical managed non-empty path into original and current`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val generated = File(root, "generated.musicxml").apply { writeText("<score-partwise/>") }
        val nonCanonical = File(root, ".${File.separator}generated.musicxml").path
        val dao = FakeScoreDao(entity(1L, null))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        repository.setGeneratedMusicXmlPath(1L, nonCanonical)

        val persisted = dao.getByIdOnce(1L)!!
        assertTrue(generated.isFile)
        assertTrue(generated.length() > 0L)
        assertEquals(generated.canonicalPath, persisted.currentMusicXmlPath)
        assertEquals(generated.canonicalPath, persisted.originalMusicXmlPath)
    }

    @Test
    fun `empty missing and outside files cannot be persisted as generated current MusicXML`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val empty = File(root, "empty.musicxml").apply { createNewFile() }
        val outside = temporaryFolder.newFile("outside.musicxml").apply { writeText("xml") }
        val dao = FakeScoreDao(entity(1L, null))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        listOf(empty.path, File(root, "missing.musicxml").path, outside.path).forEach { invalid ->
            val rejected = runCatching { repository.setGeneratedMusicXmlPath(1L, invalid) }.isFailure
            assertTrue("Expected invalid artifact to be rejected: $invalid", rejected)
        }
        assertNull(dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertNull(dao.getByIdOnce(1L)?.originalMusicXmlPath)
    }

    @Test
    fun `shared original and current deletion removes MusicXML clears both paths and keeps Library source`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val source = File(root, "source.pdf").apply { writeText("source") }
        val generated = File(root, "shared.musicxml").apply { writeText("xml") }
        val dao = FakeScoreDao(entity(1L, generated.path, generated.path, source.path))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        val result = repository.deleteGeneratedScore(1L)

        assertTrue(result is GeneratedScoreDeletionResult.Success)
        assertFalse(generated.exists())
        assertTrue(source.exists())
        assertNull(dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertNull(dao.getByIdOnce(1L)?.originalMusicXmlPath)
        assertTrue(repository.observeEditorScores().first().isEmpty())
        assertEquals(1, repository.getAllScores().first().size)
    }

    @Test
    fun `distinct current deletion preserves immutable original and other score artifacts`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val original = File(root, "original.musicxml").apply { writeText("original") }
        val current = File(root, "current.musicxml").apply { writeText("current") }
        val scoreB = File(root, "score-b.musicxml").apply { writeText("B") }
        val dao = FakeScoreDao(
            entity(1L, current.path, original.path),
            entity(2L, scoreB.path, scoreB.path)
        )
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        repository.deleteGeneratedScore(1L)

        assertFalse(current.exists())
        assertTrue(original.exists())
        assertTrue(scoreB.exists())
        assertNull(dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertEquals(original.path, dao.getByIdOnce(1L)?.originalMusicXmlPath)
        assertEquals(listOf(2L), repository.observeEditorScores().first().map { it.id })
    }

    @Test
    fun `already missing artifact clears stale paths and OMR rerun restores eligibility`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val path = File(root, "regenerated.musicxml").path
        val dao = FakeScoreDao(entity(1L, path, path))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        val deleted = repository.deleteGeneratedScore(1L)
        assertEquals(GeneratedScoreDeletionResult.Success(fileWasAlreadyMissing = true), deleted)
        assertNull(dao.getByIdOnce(1L)?.currentMusicXmlPath)

        File(path).writeText("new xml")
        repository.setGeneratedMusicXmlPath(1L, path)
        assertEquals(listOf(1L), repository.observeEditorScores().first().map { it.id })
        assertEquals(path, dao.getByIdOnce(1L)?.currentMusicXmlPath)
    }

    @Test
    fun `failed file staging reports failure and does not clear database paths`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val current = File(root, "current.musicxml").apply { writeText("xml") }
        val dao = FakeScoreDao(entity(1L, current.path, current.path))
        val repository = repository(dao, FailingArtifactStore(FileMusicXmlArtifactStore(root)))

        val result = repository.deleteGeneratedScore(1L)

        assertTrue(result is GeneratedScoreDeletionResult.Failure)
        assertTrue(current.exists())
        assertEquals(current.path, dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertEquals(current.path, dao.getByIdOnce(1L)?.originalMusicXmlPath)
    }

    @Test
    fun `failed final file deletion restores staged file and database paths`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val current = File(root, "commit-failure.musicxml").apply { writeText("xml") }
        val dao = FakeScoreDao(entity(1L, current.path, current.path))
        val repository = repository(
            dao,
            CommitFailingArtifactStore(FileMusicXmlArtifactStore(root))
        )

        val result = repository.deleteGeneratedScore(1L)

        assertTrue(result is GeneratedScoreDeletionResult.Failure)
        assertTrue(current.exists())
        assertEquals(current.path, dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertEquals(current.path, dao.getByIdOnce(1L)?.originalMusicXmlPath)
    }

    @Test
    fun `edited version changes only current path after safe write and parse`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val original = File(root, "original.musicxml").apply { writeText("<score-partwise/>") }
        val current = File(root, "current.musicxml").apply { writeText("<score-partwise/>") }
        val dao = FakeScoreDao(entity(1L, current.path, original.path))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        val result = repository.persistEditedMusicXmlVersion(
            1L,
            current.path,
            "<score-partwise version=\"4.0\"/>".toByteArray()
        )

        assertTrue(result is MusicXmlVersionPersistenceResult.Success)
        val persisted = requireNotNull(dao.getByIdOnce(1L))
        assertEquals(original.path, persisted.originalMusicXmlPath)
        assertTrue(current.isFile)
        assertTrue(File(requireNotNull(persisted.currentMusicXmlPath)).isFile)
        assertTrue(persisted.currentMusicXmlPath != current.path)
    }

    @Test
    fun `invalid or stale edited version leaves Room path and artifacts unchanged`() = runTest {
        val root = temporaryFolder.newFolder("scores")
        val current = File(root, "current.musicxml").apply { writeText("<score-partwise/>") }
        val dao = FakeScoreDao(entity(1L, current.path, current.path))
        val repository = repository(dao, FileMusicXmlArtifactStore(root))

        assertTrue(
            repository.persistEditedMusicXmlVersion(1L, current.path, "not xml".toByteArray())
                is MusicXmlVersionPersistenceResult.Failure
        )
        assertTrue(
            repository.persistEditedMusicXmlVersion(1L, "stale.musicxml", "<score-partwise/>".toByteArray())
                is MusicXmlVersionPersistenceResult.Failure
        )
        assertEquals(current.path, dao.getByIdOnce(1L)?.currentMusicXmlPath)
        assertEquals(listOf(current.name), root.listFiles()?.map(File::getName))
    }

    private fun repository(dao: ScoreDao, store: MusicXmlArtifactStore) =
        ScoreRepositoryImpl(dao, store, Dispatchers.Unconfined)

    private fun entity(
        id: Long,
        current: String?,
        original: String? = null,
        source: String = "source-$id.pdf"
    ) = ScoreEntity(
        id = id,
        title = "Score $id",
        originalFilePath = source,
        originalMusicXmlPath = original,
        currentMusicXmlPath = current,
        importDate = id,
        pageCount = 1
    )

    private class FailingArtifactStore(
        private val delegate: MusicXmlArtifactStore
    ) : MusicXmlArtifactStore {
        override fun inspect(path: String?): MusicXmlArtifactMetadata? = delegate.inspect(path)
        override fun stageDeletion(path: String): ArtifactDeletionStage =
            ArtifactDeletionStage.Failure("simulated deletion failure")
        override fun commitDeletion(token: ArtifactDeletionToken): Boolean = false
        override fun rollbackDeletion(token: ArtifactDeletionToken): Boolean = false
    }

    private class CommitFailingArtifactStore(
        private val delegate: MusicXmlArtifactStore
    ) : MusicXmlArtifactStore {
        override fun inspect(path: String?): MusicXmlArtifactMetadata? = delegate.inspect(path)
        override fun stageDeletion(path: String): ArtifactDeletionStage = delegate.stageDeletion(path)
        override fun commitDeletion(token: ArtifactDeletionToken): Boolean = false
        override fun rollbackDeletion(token: ArtifactDeletionToken): Boolean = delegate.rollbackDeletion(token)
    }

    private class FakeScoreDao(vararg initial: ScoreEntity) : ScoreDao {
        private val values = MutableStateFlow(initial.toList())

        override suspend fun insert(score: ScoreEntity): Long {
            val id = if (score.id == 0L) (values.value.maxOfOrNull { it.id } ?: 0L) + 1L else score.id
            values.value = values.value + score.copy(id = id)
            return id
        }

        override suspend fun update(score: ScoreEntity) = replace(score.id) { score }
        override suspend fun delete(score: ScoreEntity) { values.value = values.value.filterNot { it.id == score.id } }
        override fun getById(id: Long): Flow<ScoreEntity?> = values.map { rows -> rows.firstOrNull { it.id == id } }
        override suspend fun getByIdOnce(id: Long): ScoreEntity? = values.value.firstOrNull { it.id == id }
        override fun getAll(): Flow<List<ScoreEntity>> = values.map { it.sortedByDescending(ScoreEntity::importDate) }
        override fun getEditorCandidates(): Flow<List<ScoreEntity>> = values.map { rows ->
            rows.filter { !it.currentMusicXmlPath.isNullOrBlank() }.sortedByDescending(ScoreEntity::importDate)
        }
        override fun getFavorites(): Flow<List<ScoreEntity>> = values.map { rows -> rows.filter { it.isFavorite } }
        override suspend fun updateLastOpened(id: Long, timestamp: Long) = replace(id) { it.copy(lastOpenedDate = timestamp) }
        override suspend fun updateLastViewedPage(id: Long, page: Int) = replace(id) { it.copy(lastViewedPage = page) }
        override suspend fun updateLastViewedZoom(id: Long, zoom: Float) = replace(id) { it.copy(lastViewedZoom = zoom) }
        override suspend fun updatePracticeProgress(id: Long, progress: Float) = replace(id) { it.copy(practiceProgress = progress) }
        override suspend fun setGeneratedMusicXmlPath(id: Long, path: String) = replace(id) {
            it.copy(originalMusicXmlPath = it.originalMusicXmlPath ?: path, currentMusicXmlPath = path)
        }
        override suspend fun updateCurrentMusicXmlPath(id: Long, path: String) = replace(id) {
            it.copy(currentMusicXmlPath = path)
        }
        override suspend fun replaceCurrentMusicXmlPath(
            id: Long,
            expectedCurrentPath: String,
            newPath: String
        ): Int {
            val row = getByIdOnce(id) ?: return 0
            if (row.currentMusicXmlPath != expectedCurrentPath) return 0
            replace(id) { it.copy(currentMusicXmlPath = newPath) }
            return 1
        }
        override suspend fun clearGeneratedCurrentPath(id: Long, expectedCurrentPath: String): Int {
            val row = getByIdOnce(id) ?: return 0
            if (row.currentMusicXmlPath != expectedCurrentPath) return 0
            replace(id) {
                it.copy(
                    originalMusicXmlPath = if (it.originalMusicXmlPath == expectedCurrentPath) null else it.originalMusicXmlPath,
                    currentMusicXmlPath = null
                )
            }
            return 1
        }
        override suspend fun restoreGeneratedPaths(id: Long, originalPath: String?, currentPath: String): Int {
            val row = getByIdOnce(id) ?: return 0
            if (row.currentMusicXmlPath != null) return 0
            replace(id) { it.copy(originalMusicXmlPath = originalPath, currentMusicXmlPath = currentPath) }
            return 1
        }

        private fun replace(id: Long, transform: (ScoreEntity) -> ScoreEntity) {
            values.value = values.value.map { if (it.id == id) transform(it) else it }
        }
    }
}
