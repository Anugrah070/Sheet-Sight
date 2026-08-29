package com.sheetsight.app.domain.model

/**
 * A single imported piece of sheet music, as seen by ViewModels and UI.
 * Deliberately independent of [com.sheetsight.app.data.local.entity.ScoreEntity]
 * so Room-specific annotations never leak outside the data layer.
 *
 * @property id Room-assigned identifier; 0 for a score not yet persisted.
 * @property title Display name for the score (e.g. "Moonlight Sonata").
 * @property originalFilePath Absolute path to the source PDF/JPG/PNG on device storage.
 * @property originalMusicXmlPath Immutable path to the first MusicXML produced by OMR.
 * @property currentMusicXmlPath Authoritative path to the latest saved MusicXML version.
 *   Editor and future score consumers must resolve this field rather than the original.
 * @property importDate Epoch-millis timestamp of when the score was first imported.
 * @property lastOpenedDate Epoch-millis timestamp of the most recent open, for
 *   "continue where you left off" / recency sorting. Null if never opened.
 * @property pageCount Number of pages in the original source document.
 * @property isFavorite User-toggleable favorite flag, for filtering/sorting in the Library.
 * @property practiceProgress Overall completion of Practice Mode for this score, 0f–1f.
 *   Populated starting Phase 6/9; always 0f until then.
 * @property notes Freeform user annotations about the score. Placeholder field for now —
 *   a structured annotations model may replace this in Phase 5/7.
 */
data class Score(
    val id: Long = 0,
    val title: String,
    val originalFilePath: String,
    val originalMusicXmlPath: String? = null,
    val currentMusicXmlPath: String? = null,
    val importDate: Long,
    val lastOpenedDate: Long? = null,
    val pageCount: Int,
    val isFavorite: Boolean = false,
    val lastViewedPage: Int = 0,
    val lastViewedZoom: Float = 1f,
    val practiceProgress: Float = 0f,
    val notes: String? = null
) {
    /** True if this score has at least one recognized OMR version available. */
    val hasOmrResult: Boolean get() = !currentMusicXmlPath.isNullOrBlank()
}
