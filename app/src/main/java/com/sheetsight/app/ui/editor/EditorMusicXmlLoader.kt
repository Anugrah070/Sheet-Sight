package com.sheetsight.app.ui.editor

import com.sheetsight.app.data.omr.musicxml.MusicXmlNotationParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.notation.NotationDocument
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import java.io.File
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
    val fileSizeBytes: Long,
    val timings: EditorLoadTimings
)

/** Loads only persisted MusicXML bytes and produces bounded render-ready systems. */
@Singleton
class EditorMusicXmlLoader @Inject constructor() {
    fun load(file: File): EditorLoadResult {
        lateinit var bytes: ByteArray
        val fileLoadMs = timedMs { bytes = file.readBytes() }
        lateinit var parsedDocument: org.w3c.dom.Document
        val parseMs = timedMs { parsedDocument = MusicXmlParser.parseBytes(bytes) }
        lateinit var parsedScore: com.sheetsight.app.ui.editor.notation.ParsedNotationScore
        val conversionMs = timedMs { parsedScore = MusicXmlNotationParser.parse(parsedDocument) }
        lateinit var notation: NotationDocument
        val layoutMs = timedMs { notation = NotationLayoutEngine.layout(parsedScore) }
        return EditorLoadResult(
            document = notation,
            fileSizeBytes = bytes.size.toLong(),
            timings = EditorLoadTimings(fileLoadMs, parseMs, conversionMs, layoutMs)
        )
    }

    private inline fun timedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }
}
