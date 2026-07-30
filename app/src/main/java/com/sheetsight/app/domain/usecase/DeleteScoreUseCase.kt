package com.sheetsight.app.domain.usecase

import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.domain.model.Score
import com.sheetsight.app.domain.repository.ScoreRepository
import javax.inject.Inject

/**
 * Deletes a [Score]: removes its Room row and both local files it owns
 * (the original PDF/image and, once Phase 4/5 exist, the MusicXML). File
 * deletion failures are swallowed by [ScoreFileStorage.deleteFile] rather
 * than surfaced — an orphaned file is a minor storage leak, not something
 * worth blocking or failing the delete action over.
 */
class DeleteScoreUseCase @Inject constructor(
    private val scoreRepository: ScoreRepository,
    private val scoreFileStorage: ScoreFileStorage
) {
    suspend operator fun invoke(score: Score) {
        scoreRepository.deleteScore(score)
        scoreFileStorage.deleteFile(score.originalFilePath)
        scoreFileStorage.deleteFile(score.musicXmlPath)
    }
}