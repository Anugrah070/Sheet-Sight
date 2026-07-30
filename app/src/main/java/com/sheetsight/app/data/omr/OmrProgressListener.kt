package com.sheetsight.app.data.omr

/**
 * Stages of the OMR pipeline with assigned weights for overall progress calculation.
 * Weights should sum to 1.0 (100%).
 */
enum class OmrStage(val displayName: String, val weight: Float) {
    INPUT_DECODE("Loading image", 0.05f),
    PREPROCESSING("Preprocessing", 0.10f),
    TILING("Tiling", 0.05f),
    MODEL1_INFERENCE("Staff & Symbols Inference", 0.35f),
    MODEL2_INFERENCE("Symbol Detail Inference", 0.35f),
    POST_PROCESSING("Post-processing", 0.08f),
    MUSICXML_GENERATION("MusicXML Generation", 0.02f);

    companion object {
        val totalWeight = entries.sumOf { it.weight.toDouble() }.toFloat()
    }
}

/**
 * Data class representing a progress update from the OMR pipeline.
 */
data class OmrProgressUpdate(
    val stage: OmrStage,
    val overallPercentage: Int, // 0-100
    val currentTile: Int = 0,
    val totalTiles: Int = 0,
    val isIndeterminate: Boolean = false
)

/**
 * Interface for listening to OMR pipeline progress.
 */
interface OmrProgressListener {
    fun onProgressUpdate(update: OmrProgressUpdate)
}

/**
 * Helper to calculate cumulative progress based on stage weights.
 */
class OmrProgressCalculator(private val listener: OmrProgressListener) {
    private var currentProgress = 0f

    fun updateStage(stage: OmrStage, tileProgress: Float = 0f, totalTiles: Int = 0, currentTile: Int = 0) {
        val previousStagesWeight = OmrStage.entries
            .filter { it.ordinal < stage.ordinal }
            .sumOf { it.weight.toDouble() }.toFloat()
        
        val stageInternalProgress = stage.weight * tileProgress
        val totalProgress = (previousStagesWeight + stageInternalProgress).coerceIn(0f, 1f)
        
        // Ensure progress never moves backward
        val nextProgress = maxOf(currentProgress, totalProgress)
        currentProgress = nextProgress
        
        listener.onProgressUpdate(
            OmrProgressUpdate(
                stage = stage,
                overallPercentage = (nextProgress * 100).toInt(),
                currentTile = currentTile,
                totalTiles = totalTiles
            )
        )
    }
}
