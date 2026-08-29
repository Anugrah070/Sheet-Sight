package com.sheetsight.app.data.omr.musicxml

import java.nio.charset.Charset

/** Decodes validated MusicXML bytes for renderers while respecting BOMs and XML declarations. */
object MusicXmlTextDecoder {
    fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> Charset.forName("UTF-32BE")
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> Charset.forName("UTF-32LE")
            bytes.startsWith(0xFE, 0xFF) || bytes.startsWith(0x00, 0x3C, 0x00, 0x3F) -> Charsets.UTF_16BE
            bytes.startsWith(0xFF, 0xFE) || bytes.startsWith(0x3C, 0x00, 0x3F, 0x00) -> Charsets.UTF_16LE
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8
            else -> declaredCharset(bytes) ?: Charsets.UTF_8
        }
        return bytes.toString(charset).removePrefix("\uFEFF")
    }

    private fun declaredCharset(bytes: ByteArray): Charset? {
        val declaration = bytes.copyOfRange(0, minOf(bytes.size, XML_DECLARATION_SCAN_BYTES))
            .toString(Charsets.ISO_8859_1)
        val name = XML_ENCODING.find(declaration)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

    private const val XML_DECLARATION_SCAN_BYTES = 512
    private val XML_ENCODING = Regex("""encoding\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
}
