package com.sheetsight.app.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sheetsight.app.data.omr.musicxml.UnsupportedMusicXmlException
import com.sheetsight.app.data.practice.PracticeMusicXmlLoader
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.practice.PracticeSequence
import com.sheetsight.app.ui.editor.notation.NotationDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

sealed interface PracticeScoreLoadOutcome {
    data class Success(
        val sequence: PracticeSequence,
        val notation: NotationDocument
    ) : PracticeScoreLoadOutcome
    data class Failure(val message: String) : PracticeScoreLoadOutcome
}

/** Opens a SAF document and delegates MusicXML parsing/conversion outside Compose. */
class LoadPracticeScoreUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: PracticeMusicXmlLoader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(uri: Uri): PracticeScoreLoadOutcome = withContext(ioDispatcher) {
        try {
            val resolver = context.contentResolver
            val fileName = queryDisplayName(resolver, uri) ?: "Imported score.musicxml"
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = resolver.getType(uri)?.lowercase()
            val typeSupported = extension in SUPPORTED_EXTENSIONS || mimeType in SUPPORTED_MIME_TYPES
            if (!typeSupported || extension == "mxl") {
                return@withContext PracticeScoreLoadOutcome.Failure("Choose an uncompressed .musicxml or .xml file.")
            }
            val bytes = resolver.openInputStream(uri)?.use(::readLimited)
                ?: throw IOException("The selected MusicXML file could not be opened.")
            val loaded = loader.load(fileName, bytes)
            PracticeScoreLoadOutcome.Success(loaded.sequence, loaded.notation)
        } catch (unsupported: UnsupportedMusicXmlException) {
            PracticeScoreLoadOutcome.Failure(unsupported.message ?: "This MusicXML score is not supported.")
        } catch (security: SecurityException) {
            PracticeScoreLoadOutcome.Failure("Permission was denied while reading the MusicXML file.")
        } catch (failure: Exception) {
            PracticeScoreLoadOutcome.Failure(failure.message ?: "The MusicXML score could not be read.")
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_MUSIC_XML_BYTES) throw IOException("MusicXML file is larger than 20 MB.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_MUSIC_XML_BYTES = 20 * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("musicxml", "xml")
        val SUPPORTED_MIME_TYPES = setOf(
            "application/vnd.recordare.musicxml+xml",
            "application/musicxml+xml",
            "application/xml",
            "text/xml"
        )
    }
}
