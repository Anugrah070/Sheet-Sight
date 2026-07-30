package com.sheetsight.app.domain.usecase

import com.sheetsight.app.domain.model.Score

/** Result of [ImportScoreUseCase]. Failure carries an already-localized, user-facing message. */
sealed interface ImportOutcome {
    data class Success(val score: Score) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}
