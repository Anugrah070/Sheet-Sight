package com.sheetsight.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MusicXmlArtifactMetadata(
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

sealed interface ArtifactDeletionStage {
    data object AlreadyMissing : ArtifactDeletionStage
    data class Staged(val token: ArtifactDeletionToken) : ArtifactDeletionStage
    data class Failure(val message: String) : ArtifactDeletionStage
}

data class ArtifactDeletionToken(
    val original: File,
    val staged: File
)

/**
 * Owns validation and deletion of generated MusicXML in app-private score storage.
 * It never follows a database path outside `filesDir/scores` and never accepts a
 * non-MusicXML target for deletion.
 */
interface MusicXmlArtifactStore {
    fun inspect(path: String?): MusicXmlArtifactMetadata?
    fun writeVersion(scoreId: Long, bytes: ByteArray): MusicXmlArtifactMetadata =
        throw UnsupportedOperationException("Writing MusicXML versions is not supported.")
    fun discard(path: String): Boolean = false
    fun stageDeletion(path: String): ArtifactDeletionStage
    fun commitDeletion(token: ArtifactDeletionToken): Boolean
    fun rollbackDeletion(token: ArtifactDeletionToken): Boolean
}

@Singleton
class AppMusicXmlArtifactStore @Inject constructor(
    @ApplicationContext context: Context
) : MusicXmlArtifactStore by FileMusicXmlArtifactStore(File(context.filesDir, "scores"))

internal class FileMusicXmlArtifactStore(
    private val scoresRoot: File
) : MusicXmlArtifactStore {
    init {
        scoresRoot.mkdirs()
    }

    override fun inspect(path: String?): MusicXmlArtifactMetadata? {
        val file = managedMusicXml(path) ?: return null
        if (!file.isFile || !file.canRead()) return null
        val size = runCatching { file.length() }.getOrDefault(0L)
        if (size <= 0L) return null
        return MusicXmlArtifactMetadata(
            canonicalPath = file.path,
            sizeBytes = size,
            lastModifiedMillis = runCatching { file.lastModified() }.getOrDefault(0L)
        )
    }

    override fun writeVersion(scoreId: Long, bytes: ByteArray): MusicXmlArtifactMetadata {
        require(scoreId > 0L) { "A persisted score id is required." }
        require(bytes.isNotEmpty()) { "MusicXML output must not be empty." }
        val token = UUID.randomUUID().toString()
        val destination = File(scoresRoot, "score-${scoreId}-edit-$token.musicxml")
        val temporary = File(scoresRoot, ".${destination.name}.pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (temporary.length() <= 0L || !temporary.renameTo(destination)) {
                throw IOException("The edited MusicXML could not be committed.")
            }
            return requireNotNull(inspect(destination.path)) {
                "The committed MusicXML artifact is unreadable or empty."
            }
        } catch (failure: Exception) {
            temporary.delete()
            destination.delete()
            throw failure
        }
    }

    override fun discard(path: String): Boolean {
        val file = managedMusicXml(path) ?: return false
        return !file.exists() || file.delete()
    }

    override fun stageDeletion(path: String): ArtifactDeletionStage {
        val original = managedMusicXml(path)
            ?: return ArtifactDeletionStage.Failure("The generated score is outside app-managed storage.")
        if (!original.exists()) return ArtifactDeletionStage.AlreadyMissing
        if (!original.isFile) {
            return ArtifactDeletionStage.Failure("The generated score path is not a file.")
        }
        val staged = File(
            original.parentFile,
            ".${original.name}.pending-delete-${UUID.randomUUID()}"
        )
        return if (original.renameTo(staged)) {
            ArtifactDeletionStage.Staged(ArtifactDeletionToken(original, staged))
        } else {
            ArtifactDeletionStage.Failure("The generated MusicXML file could not be deleted.")
        }
    }

    override fun commitDeletion(token: ArtifactDeletionToken): Boolean =
        !token.staged.exists() || token.staged.delete()

    override fun rollbackDeletion(token: ArtifactDeletionToken): Boolean {
        if (!token.staged.exists()) return token.original.isFile
        if (token.original.exists()) return false
        return token.staged.renameTo(token.original)
    }

    private fun managedMusicXml(path: String?): File? {
        if (path.isNullOrBlank()) return null
        return try {
            val root = scoresRoot.canonicalFile
            val candidate = File(path.trim()).canonicalFile
            val supportedExtension = candidate.extension.lowercase() in SUPPORTED_EXTENSIONS
            candidate.takeIf { supportedExtension && it.parentFile == root }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("musicxml", "xml")
    }
}
