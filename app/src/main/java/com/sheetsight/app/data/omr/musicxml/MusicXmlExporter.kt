package com.sheetsight.app.data.omr.musicxml

import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.data.omr.semantic.SemanticScore
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** Persists validated MusicXML through the app's existing private score storage. */
@Singleton
class MusicXmlExporter @Inject constructor(
    private val scoreFileStorage: ScoreFileStorage
) {
    fun export(
        score: SemanticScore,
        outputName: String,
        partName: String = MusicXmlWriter.DEFAULT_PART_NAME
    ): MusicXmlExportResult {
        val serialized = MusicXmlWriter.serialize(score, partName)
        val xml = serialized.xml
        if (serialized.validationStatus != MusicXmlValidationStatus.VALID || xml == null) {
            return serialized.toExportResult()
        }

        return try {
            val file = scoreFileStorage.writeMusicXml(
                outputName,
                xml.toByteArray(StandardCharsets.UTF_8)
            )
            serialized.toExportResult(
                outputFilePath = file.absolutePath,
                fileSizeBytes = file.length()
            )
        } catch (exception: Exception) {
            val message = exception.message ?: exception::class.java.simpleName
            serialized.toExportResult(
                warnings = serialized.warnings + MusicXmlExportWarning(
                    MusicXmlExportWarningCode.STORAGE_WRITE_FAILED,
                    "MusicXML could not be written to app-local storage: $message"
                ),
                failureMessage = message
            )
        }
    }

    private fun MusicXmlSerializationResult.toExportResult(
        outputFilePath: String? = null,
        fileSizeBytes: Long = 0,
        warnings: List<MusicXmlExportWarning> = this.warnings,
        failureMessage: String? = null
    ) = MusicXmlExportResult(
        outputFilePath = outputFilePath,
        fileSizeBytes = fileSizeBytes,
        exportedMeasureCount = exportedMeasureCount,
        exportedBarlineCount = exportedBarlineCount,
        exportedBarlineLocations = exportedBarlineLocations,
        exportedNoteCount = exportedNoteCount,
        exportedChordCount = exportedChordCount,
        exportedRestCount = exportedRestCount,
        omittedUnresolvedEventCount = omittedUnresolvedEventCount,
        warnings = warnings.sortedWith(exportWarningComparator),
        validationStatus = validationStatus,
        validationErrors = validationErrors,
        failureMessage = failureMessage
    )
}

internal val exportWarningComparator =
    compareBy<MusicXmlExportWarning> { it.code.ordinal }
        .thenBy { it.semanticId ?: "" }
        .thenBy { it.message }
