package com.sheetsight.app.data.omr

/**
 * The outcome of a completed [OmrEngine] run for a single page/image.
 *
 * Deliberately holds the produced MusicXML as an opaque file reference
 * rather than a parsed document — interpreting MusicXML content (building
 * a music domain model for the Editor to render/correct) is Phase 5's
 * concern, not OMR's. This type only describes what OMR handed back.
 *
 * @property musicXmlPath Absolute path to the MusicXML file written by the
 *   engine. No parsing or validation of its contents happens here.
 * @property warnings Non-fatal issues the engine surfaced while recognizing
 *   the page (e.g. low-confidence regions), for later display alongside
 *   the result. Empty until Phase 4.2 actually populates it.
 */
data class OmrResult(
    val musicXmlPath: String,
    val warnings: List<String> = emptyList()
)