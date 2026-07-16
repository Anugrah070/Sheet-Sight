package com.sheetsight.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a single imported score. This is a storage model only —
 * ViewModels and UI never see this class directly, they see
 * [com.sheetsight.app.domain.model.Score]. Mapping between the two lives in
 * `ScoreMapper.kt`.
 *
 * Column layout matches the Library storage requirements: source file,
 * derived MusicXML, timestamps, page count, favorite flag, practice
 * progress, and a freeform annotations placeholder. PDF/OMR import itself
 * is not implemented yet (Phase 3/4) — this only defines where the result
 * of that work will be stored.
 */
@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "original_file_path")
    val originalFilePath: String,

    /** Null until OMR/editing has produced a corrected MusicXML file. */
    @ColumnInfo(name = "music_xml_path")
    val musicXmlPath: String? = null,

    @ColumnInfo(name = "import_date")
    val importDate: Long,

    /** Null if the score has never been opened since import. */
    @ColumnInfo(name = "last_opened_date")
    val lastOpenedDate: Long? = null,

    @ColumnInfo(name = "page_count")
    val pageCount: Int,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    /** 0f (not started) – 1f (fully mastered). Populated starting Phase 6/9. */
    @ColumnInfo(name = "practice_progress")
    val practiceProgress: Float = 0f,

    /** Freeform annotations placeholder; may become a structured table in a later phase. */
    @ColumnInfo(name = "notes")
    val notes: String? = null
)
