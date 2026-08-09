package com.sheetsight.app.data.omr.musicxml

enum class MusicXmlValidationStatus {
    VALID,
    INVALID
}

enum class MusicXmlExportWarningCode {
    SEMANTIC_VALIDATION,
    MISSING_CLEF,
    UNRESOLVED_PITCH,
    INVALID_PITCH,
    UNRESOLVED_DURATION,
    UNSUPPORTED_DURATION,
    UNRESOLVED_KEY_SIGNATURE,
    INVALID_TIME_SIGNATURE,
    UNKNOWN_STAFF,
    EMPTY_CHORD,
    TOP_LEVEL_NOTE_UNSUPPORTED,
    BEAT_ALIGNMENT_UNREPRESENTABLE,
    STORAGE_WRITE_FAILED
}

data class MusicXmlExportWarning(
    val code: MusicXmlExportWarningCode,
    val message: String,
    val semanticId: String? = null
)

data class MusicXmlSerializationResult(
    val xml: String?,
    val exportedMeasureCount: Int,
    val exportedBarlineCount: Int,
    val exportedBarlineLocations: List<String>,
    val exportedNoteCount: Int,
    val exportedChordCount: Int,
    val exportedRestCount: Int,
    val omittedUnresolvedEventCount: Int,
    val warnings: List<MusicXmlExportWarning>,
    val validationStatus: MusicXmlValidationStatus,
    val validationErrors: List<String> = emptyList()
)

data class MusicXmlExportResult(
    val outputFilePath: String?,
    val fileSizeBytes: Long,
    val exportedMeasureCount: Int,
    val exportedBarlineCount: Int,
    val exportedBarlineLocations: List<String>,
    val exportedNoteCount: Int,
    val exportedChordCount: Int,
    val exportedRestCount: Int,
    val omittedUnresolvedEventCount: Int,
    val warnings: List<MusicXmlExportWarning>,
    val validationStatus: MusicXmlValidationStatus,
    val validationErrors: List<String> = emptyList(),
    val failureMessage: String? = null
) {
    val success: Boolean
        get() = outputFilePath != null &&
            validationStatus == MusicXmlValidationStatus.VALID &&
            failureMessage == null
}
