package com.sheetsight.app.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sheetsight.app.R
import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Imports a user-selected PDF/JPG/PNG: validates the type, copies it into
 * app-local storage (via [ScoreFileStorage]), determines page count, and
 * persists a new [Score] row via [ScoreRepository].
 *
 * Deliberately does **not** run OMR or produce a MusicXML file — that's
 * Phase 4. [Score.musicXmlPath] is left null here.
 *
 * Errors are caught and converted to a user-facing message rather than
 * thrown, so callers (the ViewModel) can render failure without a
 * try/catch of their own.
 *
 * Note: this use case depends on `android.content.Context`/`ContentResolver`
 * directly. That's a deliberate exception to keeping `domain` platform-free —
 * this is a single-platform Android app with no multiplatform target, and
 * importing from a content:// URI is inherently an Android/SAF concern.
 */
class ImportScoreUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scoreFileStorage: ScoreFileStorage,
    private val scoreRepository: ScoreRepository
) {
    private val supportedMimeTypes = setOf("application/pdf", "image/jpeg", "image/png")
    private val supportedExtensions = setOf("pdf", "jpg", "jpeg", "png")

    suspend operator fun invoke(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val displayName = queryDisplayName(resolver, uri)
            val mimeType = resolver.getType(uri)
            val extension = displayName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()

            if (mimeType !in supportedMimeTypes && extension !in supportedExtensions) {
                return@withContext ImportOutcome.Failure(context.getString(R.string.import_error_unsupported_type))
            }

            val fallbackName = "score_${System.currentTimeMillis()}"
            val localFile = scoreFileStorage.copyToLocalStorage(
                resolver = resolver,
                uri = uri,
                displayName = displayName ?: fallbackName
            )

            val isPdf = mimeType == "application/pdf" || extension == "pdf"
            val pageCount = if (isPdf) {
                runCatching { scoreFileStorage.countPdfPages(localFile) }.getOrDefault(1)
            } else {
                1
            }

            val score = Score(
                title = localFile.nameWithoutExtension,
                originalFilePath = localFile.absolutePath,
                importDate = System.currentTimeMillis(),
                pageCount = pageCount
            )
            val id = scoreRepository.addScore(score)
            ImportOutcome.Success(score.copy(id = id))
        } catch (e: SecurityException) {
            ImportOutcome.Failure(context.getString(R.string.import_error_permission))
        } catch (e: IOException) {
            ImportOutcome.Failure(context.getString(R.string.import_error_io))
        } catch (e: Exception) {
            ImportOutcome.Failure(context.getString(R.string.import_error_generic))
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
