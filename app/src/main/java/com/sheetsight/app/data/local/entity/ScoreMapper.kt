package com.sheetsight.app.data.local.entity

import com.sheetsight.app.domain.model.Score

/** Maps a persisted row to the domain model used by ViewModels/UI. */
fun ScoreEntity.toDomain(): Score = Score(
    id = id,
    title = title,
    originalFilePath = originalFilePath,
    musicXmlPath = musicXmlPath,
    importDate = importDate,
    lastOpenedDate = lastOpenedDate,
    pageCount = pageCount,
    isFavorite = isFavorite,
    lastViewedPage = lastViewedPage,
    lastViewedZoom = lastViewedZoom,
    practiceProgress = practiceProgress,
    notes = notes
)

/** Maps a domain model to the Room row shape for persistence. */
fun Score.toEntity(): ScoreEntity = ScoreEntity(
    id = id,
    title = title,
    originalFilePath = originalFilePath,
    musicXmlPath = musicXmlPath,
    importDate = importDate,
    lastOpenedDate = lastOpenedDate,
    pageCount = pageCount,
    isFavorite = isFavorite,
    lastViewedPage = lastViewedPage,
    lastViewedZoom = lastViewedZoom,
    practiceProgress = practiceProgress,
    notes = notes
)
