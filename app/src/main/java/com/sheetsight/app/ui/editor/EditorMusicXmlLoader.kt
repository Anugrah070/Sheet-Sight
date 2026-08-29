package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlNotationParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlTextDecoder
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import com.sheetsight.app.ui.editor.identity.EditableScoreIdentityIndex
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EditorLoadTimings(
    val fileLoadMs: Long,
    val xmlParseMs: Long,
    val semanticConversionMs: Long,
    val notationLayoutMs: Long
)

data class EditorLoadResult(
    val document: NotationDocument,
    val musicXml: String,
    val fileSizeBytes: Long,
    val timings: EditorLoadTimings,
    val identityIndex: EditableScoreIdentityIndex? = null
)

/** Loads only persisted MusicXML bytes and produces bounded render-ready systems. */
@Singleton
open class EditorMusicXmlLoader @Inject constructor() {
    open fun load(file: File): EditorLoadResult = loadInternal(file, scoreId = null)

    open fun load(file: File, scoreId: Long): EditorLoadResult = loadInternal(file, scoreId)

    open fun loadBytes(bytes: ByteArray, scoreId: Long): EditorLoadResult =
        loadBytesInternal(bytes, scoreId, fileLoadMs = 0L)

    private fun loadInternal(file: File, scoreId: Long?): EditorLoadResult {
        val extension = file.extension.lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) {
            throw UnsupportedEditorScoreException("Only uncompressed .musicxml and .xml files are supported.")
        }
        if (file.length() <= 0L) throw EmptyEditorScoreException()
        if (file.length() > MAX_MUSIC_XML_BYTES) {
            throw UnsupportedEditorScoreException("MusicXML files larger than 20 MB are not supported.")
        }
        lateinit var bytes: ByteArray
        val fileLoadMs = timedMs {
            bytes = file.inputStream().use(::readLimited)
            if (bytes.isEmpty()) throw EmptyEditorScoreException()
            if (bytes.size > MAX_MUSIC_XML_BYTES) {
                throw UnsupportedEditorScoreException("MusicXML files larger than 20 MB are not supported.")
            }
        }
        return loadBytesInternal(bytes, scoreId, fileLoadMs)
    }

    private fun loadBytesInternal(bytes: ByteArray, scoreId: Long?, fileLoadMs: Long): EditorLoadResult {
        if (bytes.isEmpty()) throw EmptyEditorScoreException()
        if (bytes.size > MAX_MUSIC_XML_BYTES) {
            throw UnsupportedEditorScoreException("MusicXML files larger than 20 MB are not supported.")
        }
        lateinit var parsedDocument: org.w3c.dom.Document
        val parseMs = timedMs { parsedDocument = MusicXmlParser.parseBytes(bytes) }
        lateinit var parsedScore: com.sheetsight.app.ui.editor.notation.ParsedNotationScore
        val conversionMs = timedMs { parsedScore = MusicXmlNotationParser.parse(parsedDocument) }
        lateinit var notation: NotationDocument
        val layoutMs = timedMs { notation = NotationLayoutEngine.layout(parsedScore) }
        return EditorLoadResult(
            document = notation,
            musicXml = MusicXmlTextDecoder.decode(bytes),
            fileSizeBytes = bytes.size.toLong(),
            timings = EditorLoadTimings(fileLoadMs, parseMs, conversionMs, layoutMs),
            identityIndex = scoreId?.let { MusicXmlIdentityBuilder.build(it, parsedDocument) }
        )
    }

    private inline fun timedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_MUSIC_XML_BYTES) {
                throw UnsupportedEditorScoreException("MusicXML files larger than 20 MB are not supported.")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_MUSIC_XML_BYTES = 20 * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("musicxml", "xml")
    }
}

class EmptyEditorScoreException : IOException("The MusicXML file is empty.")

class UnsupportedEditorScoreException(message: String) : IOException(message)
