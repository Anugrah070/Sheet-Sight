package com.sheetsight.app.data.omr.musicxml

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for MusicXML files, primarily used to re-import or verify
 * exported scores.
 */
object MusicXmlParser {
    fun parseFile(file: File): Document {
        return file.inputStream().use { parseStream(it) }
    }

    fun parseBytes(bytes: ByteArray): Document =
        ByteArrayInputStream(bytes).use(::parseStream)

    fun parseString(xml: String): Document =
        parseBytes(xml.toByteArray(StandardCharsets.UTF_8))

    fun parseStream(inputStream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false

            // Android and the host JVM expose slightly different parser
            // implementations, so hardening features are applied when supported.
            val features = mapOf(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false
            )
            features.forEach { (feature, enabled) ->
                runCatching { setFeature(feature, enabled) }
            }
            runCatching { isXIncludeAware = false }
        }

        val builder = factory.newDocumentBuilder()

        // Use a dummy EntityResolver to prevent attempts to fetch external DTDs over the network
        builder.setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }

        return builder.parse(inputStream)
    }
}
