package com.sheetsight.app.domain.model

/**
 * A single imported piece of sheet music, as seen by ViewModels and UI.
 * Deliberately independent of [com.sheetsight.app.data.local.entity.ScoreEntity]
 * so Room-specific annotations never leak outside the data layer.
 *
 * @property id Room-assigned identifier; 0 for a score not yet persisted.
 * @property title Display name for the score (e.g. "Moonlight Sonata").
 * @property originalFilePath Absolute path to the source PDF/JPG/PNG on device storage.
 * @property musicXmlPath Absolute path to the corrected MusicXML file, once OMR (Phase 4)
 *   and/or manual editing (Phase 5) have produced one. Null until then.
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
    val musicXmlPath: String? = null,
    val importDate: Long,
    val lastOpenedDate: Long? = null,
    val pageCount: Int,
    val isFavorite: Boolean = false,
    val practiceProgress: Float = 0f,
    val notes: String? = null
)
