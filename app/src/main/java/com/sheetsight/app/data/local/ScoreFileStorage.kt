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