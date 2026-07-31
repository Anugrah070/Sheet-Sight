package com.sheetsight.app.data.local

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies user-selected score files (PDF/JPG/PNG) into app-private storage
 * so the app never depends on a `content://` URI outliving the picker
 * session, and reads basic file facts (PDF page count) needed for the
 * [com.sheetsight.app.domain.model.Score] metadata.
 *
 * Files live under `filesDir/scores/` — private to the app, removed
 * automatically on uninstall, no runtime storage permission required
 * since access always goes through the Storage Access Framework picker.
 */
@Singleton
class ScoreFileStorage @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    private val scoresDir: File
        get() = File(context.filesDir, "scores").apply { mkdirs() }

    /**
     * Copies the content behind [uri] into local storage under a name
     * derived from [displayName]. If a file with that name already exists,
     * appends " (1)", " (2)", etc. until a free name is found — duplicate
     * filenames are handled by disambiguation, not by overwriting or failing.
     *
     * @throws IOException if the source can't be opened or the copy fails.
     */
    fun copyToLocalStorage(resolver: ContentResolver, uri: Uri, displayName: String): File {
        val target = resolveAvailableFile(sanitizeFileName(displayName))
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected file.")
        input.use { source ->
            target.outputStream().use { destination -> source.copyTo(destination) }
        }
        return target
    }

    /** Number of pages in a local PDF file. Callers should treat non-PDF files as 1 page. */
    fun countPdfPages(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer -> renderer.pageCount }
        }

    /**
     * Writes UTF-8 MusicXML bytes beside imported scores in app-private storage.
     * The stable target name is replaced on a repeated export so a persisted
     * [com.sheetsight.app.domain.model.Score.musicXmlPath] never drifts.
     */
    fun writeMusicXml(outputName: String, utf8Bytes: ByteArray): File {
        val requestedName = sanitizeFileName(outputName).ifBlank { "score" }
        val fileName = if (requestedName.endsWith(".musicxml", ignoreCase = true)) {
            requestedName
        } else {
            "$requestedName.musicxml"
        }
        return File(scoresDir, fileName).also { target ->
            target.outputStream().use { it.write(utf8Bytes) }
        }
    }

    /**
     * Copies a generated app-private MusicXML file to a document chosen through
     * Android's file explorer. The destination copy is user-owned and remains
     * available independently of this app's private storage lifecycle.
     */
    fun copyMusicXmlToDocument(sourcePath: String, destinationUri: Uri): Long {
        val source = File(sourcePath).canonicalFile
        val storageRoot = scoresDir.canonicalFile
        require(source.parentFile == storageRoot) {
            "MusicXML debug export must originate from app-local score storage"
        }
        require(source.isFile && source.extension.equals("musicxml", ignoreCase = true)) {
            "MusicXML debug export source does not exist or has an unsupported extension"
        }
        val output = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("Unable to open the selected MusicXML destination.")
        return source.inputStream().use { input ->
            output.use { destination -> input.copyTo(destination) }
        }
    }

    private fun resolveAvailableFile(fileName: String): File {
        val dir = scoresDir
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate

        val dotIndex = fileName.lastIndexOf('.')
        val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var suffix = 1
        do {
            candidate = File(dir, "$base ($suffix)$extension")
            suffix++
        } while (candidate.exists())
        return candidate
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "score" }

    /**
     * Deletes the file at [path] if it exists, under the app-local scores
     * directory. Never throws — a missing or already-deleted file is not an
     * error from the caller's perspective (e.g. deleting a [Score] whose
     * file was already removed some other way).
     */
    fun deleteFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
