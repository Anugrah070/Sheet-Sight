package com.sheetsight.app.data.omr.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.sheetsight.app.data.omr.OmrPipelineException
import com.sheetsight.app.data.omr.dewarp.DewarpPipeline
import com.sheetsight.app.data.omr.dewarp.ImageMaskAligner
import com.sheetsight.app.data.omr.inference.ClassMaskExtractor
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.inference.OmrPredictionMap
import com.sheetsight.app.data.omr.inference.PredictionMapMerger
import com.sheetsight.app.data.omr.inference.TileInferenceRunner
import com.sheetsight.app.data.omr.preprocessing.CanonicalImageResizer
import com.sheetsight.app.data.omr.preprocessing.ImagePreprocessing
import com.sheetsight.app.data.omr.preprocessing.ImageTile
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler
import com.sheetsight.app.data.omr.track.OmrStaffGridAssembler
import com.sheetsight.app.di.DefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer-only, stage-by-stage OMR diagnostic. Runs the same,
 * already-implemented pipeline algorithms production code uses
 * (`ImagePreprocessing`, `CanonicalImageResizer`, `SlidingWindowTiler`,
 * `TileInferenceRunner`, `PredictionMapMerger`, `ClassMaskExtractor`,
 * `DewarpPipeline`, `OmrStaffGridAssembler`) — nothing here reimplements
 * any OMR algorithm — but calls them directly, one stage at a time,
 * instead of through [com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner]
 * / [com.sheetsight.app.data.omr.dewarp.OmrPageDewarpRunner], which
 * bundle multiple stages into one call and give no seam to stop at.
 *
 * Every run executes stages 1..N **from the beginning** and deliberately
 * stops right after [stopAfter] rather than resuming from a previous
 * run's in-memory state — if a stage's own memory/CPU load is what kills
 * the process, no such state would have survived to resume from anyway,
 * and starting fresh keeps each run's numbers comparable.
 *
 * **Memory note (important, see history of OOM crashes around Stage 5).**
 * [com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner] (the
 * production path) processes each [OmrModelSpec] sequentially and merges
 * its raw per-tile predictions into a flat [OmrPredictionMap] *before*
 * moving to the next model — so only one model's huge, overlap-duplicated,
 * deeply-nested `Array<Array<Array<FloatArray>>>` raw output is ever
 * resident at once. Earlier versions of this runner broke that property
 * by holding model 1's entire raw prediction list alive across model 2's
 * inference (to report both stages' tile counts before merging in one
 * later "stage"), which reliably OOMs on constrained devices right as
 * model 2's own inference needs its own scratch memory. Model 1's raw
 * predictions are now merged into [mergedStaffSymbols] — scoped inside a
 * `run {}` block — immediately after model 1's inference and before
 * model 2 ever runs, so that raw list is GC-eligible the moment model 2
 * starts. This does mean a run stopped exactly after
 * [SmokeTestStage.MODEL2_INFERENCE] will already show model 1's merge
 * having happened (it has to, to avoid the OOM) even though its timing
 * entry appears earlier in [OmrSmokeTestDiagnosticResult.stageDurations]
 * than the [SmokeTestStage.MODEL2_INFERENCE] entry — the label is still
 * correct, only the chronological position shifted earlier.
 *
 * Every stage is bracketed by `[OMR_SMOKE] START <stage>` / `[OMR_SMOKE]
 * END <stage> durationMs=<value>` (plus a `MEM` line before/after with
 * used/total/free heap). If the process is killed by Android mid-stage,
 * the last `START` line in logcat with no matching `END`/`FAILED` line
 * is the stage that was executing — that's an observation made by
 * reading logcat afterward, not something this code can catch, since a
 * process kill is not a catchable Kotlin exception.
 */
@Singleton
class OmrSmokeTestRunner @Inject constructor(
    private val tileInferenceRunner: TileInferenceRunner,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "OmrSmokeTest"
    }

    /**
     * Runs stages 1..[stopAfter] against the image at [imagePath].
     * Returns a diagnostic result describing exactly how far it got,
     * even on failure — never a fabricated success.
     */
    suspend fun run(imagePath: String, stopAfter: SmokeTestStage): OmrSmokeTestDiagnosticResult =
        withContext(defaultDispatcher) {
            val timings = mutableListOf<OmrSmokeTestStageTiming>()
            val previews = mutableMapOf<SmokeTestStage, List<OmrSmokeTestPreview>>()
            val details = mutableMapOf<SmokeTestStage, List<String>>()
            var lastCompletedStage: SmokeTestStage? = null

            // Retained-between-stages state. Each is released in `finally`
            // below and nulled out the moment an earlier stage no longer
            // needs it, so a stop-after-stage-N run never holds stage-N+1's
            // inputs alive longer than necessary.
            var bitmap: Bitmap? = null
            var oemerOrdered: Mat? = null
            var resized: Mat? = null
            var tilesByModel: Map<OmrModelSpec, List<ImageTile>>? = null

            try {
                // STAGE 1 — Input decode
                bitmap = traceStage(SmokeTestStage.INPUT_DECODE, timings) {
                    BitmapFactory.decodeFile(imagePath)
                        ?: throw OmrPipelineException("Could not decode an image from '$imagePath'")
                }
                previews[SmokeTestStage.INPUT_DECODE] =
                    listOf(OmrSmokeTestPreview("input", OmrSmokeTestBitmaps.thumbnailOf(bitmap)))
                lastCompletedStage = SmokeTestStage.INPUT_DECODE
                if (stopAfter == SmokeTestStage.INPUT_DECODE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 2 — Preprocessing (oemer byte-order conversion + canonical resize)
                traceStage(SmokeTestStage.PREPROCESSING, timings) {
                    oemerOrdered = ImagePreprocessing.toOemerOrderedMat(bitmap!!)
                    resized = CanonicalImageResizer.resize(oemerOrdered!!)
                    oemerOrdered?.release()
                    oemerOrdered = null
                }
                // Nothing after this point reads `bitmap` again (canonical
                // size/pixels come from `resized`) — recycle it now rather
                // than holding it alive in `finally` for the rest of a
                // potentially multi-hundred-megabyte run.
                bitmap?.recycle()
                bitmap = null
                val canonicalWidth = resized!!.width()
                val canonicalHeight = resized!!.height()
                val canonicalImageChannels = ImagePreprocessing.extractChannels(resized!!)
                previews[SmokeTestStage.PREPROCESSING] = listOf(
                    OmrSmokeTestPreview(
                        "canonical",
                        OmrSmokeTestBitmaps.channelsToThumbnail(canonicalImageChannels, canonicalWidth, canonicalHeight)
                    )
                )
                details[SmokeTestStage.PREPROCESSING] = listOf("canonical size: ${canonicalWidth}x$canonicalHeight")
                lastCompletedStage = SmokeTestStage.PREPROCESSING
                if (stopAfter == SmokeTestStage.PREPROCESSING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 3 — Tiling (both models)
                tilesByModel = traceStage(SmokeTestStage.TILING, timings) {
                    OmrModelSpec.entries.associateWith { spec -> SlidingWindowTiler.tile(resized!!, spec.windowSize) }
                }
                resized?.release()
                resized = null
                details[SmokeTestStage.TILING] = tilesByModel!!.map { (spec, tiles) ->
                    "${spec.name}: ${tiles.size} tiles @ ${spec.windowSize}x${spec.windowSize}"
                }
                lastCompletedStage = SmokeTestStage.TILING
                if (stopAfter == SmokeTestStage.TILING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 4 — Model 1 inference (staff_and_symbols), merged immediately.
                // `model1Predictions` is scoped to this `run {}` block so its raw,
                // overlap-duplicated, deeply-nested tile output is out of scope —
                // and GC-eligible — before STAGE 5 (model 2 inference) starts. See
                // the class KDoc's "Memory note" for why this matters.
                val mergedStaffSymbols: OmrPredictionMap = run {
                    val model1Predictions = traceStage(SmokeTestStage.MODEL1_INFERENCE, timings) {
                        tileInferenceRunner.run(
                            OmrModelSpec.STAFF_AND_SYMBOLS,
                            tilesByModel!!.getValue(OmrModelSpec.STAFF_AND_SYMBOLS)
                        )
                    }
                    details[SmokeTestStage.MODEL1_INFERENCE] = listOf("tiles processed: ${model1Predictions.size}")
                    lastCompletedStage = SmokeTestStage.MODEL1_INFERENCE
                    if (stopAfter == SmokeTestStage.MODEL1_INFERENCE) {
                        return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                    }
                    traceStage(SmokeTestStage.PREDICTION_MERGING, timings) {
                        PredictionMapMerger.merge(canonicalWidth, canonicalHeight, model1Predictions)
                    }
                }

                // STAGE 5 — Model 2 inference (symbol_detail)
                val model2Predictions = traceStage(SmokeTestStage.MODEL2_INFERENCE, timings) {
                    tileInferenceRunner.run(
                        OmrModelSpec.SYMBOL_DETAIL,
                        tilesByModel!!.getValue(OmrModelSpec.SYMBOL_DETAIL)
                    )
                }
                details[SmokeTestStage.MODEL2_INFERENCE] = listOf("tiles processed: ${model2Predictions.size}")
                // No later stage touches the tiles themselves (only the
                // predictions) — release the native Mats now regardless of
                // whether we stop here or continue.
                tilesByModel?.values?.forEach { tiles -> tiles.forEach { it.release() } }
                tilesByModel = null
                lastCompletedStage = SmokeTestStage.MODEL2_INFERENCE
                if (stopAfter == SmokeTestStage.MODEL2_INFERENCE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 6 — Prediction-map merging (model 2 only; model 1 was
                // already merged above, before model 2 ran).
                val mergedSymbolDetail = traceStage(SmokeTestStage.PREDICTION_MERGING, timings) {
                    PredictionMapMerger.merge(canonicalWidth, canonicalHeight, model2Predictions)
                }
                details[SmokeTestStage.PREDICTION_MERGING] = listOf(
                    "staffAndSymbols: ${mergedStaffSymbols.width}x${mergedStaffSymbols.height}x${mergedStaffSymbols.channels}",
                    "symbolDetail: ${mergedSymbolDetail.width}x${mergedSymbolDetail.height}x${mergedSymbolDetail.channels}"
                )
                lastCompletedStage = SmokeTestStage.PREDICTION_MERGING
                if (stopAfter == SmokeTestStage.PREDICTION_MERGING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 7 — Class-mask extraction
                val masks = traceStage(SmokeTestStage.CLASS_MASK_EXTRACTION, timings) {
                    ClassMaskExtractor.extract(mergedStaffSymbols, mergedSymbolDetail)
                }
                previews[SmokeTestStage.CLASS_MASK_EXTRACTION] = maskPreviews(masks)
                lastCompletedStage = SmokeTestStage.CLASS_MASK_EXTRACTION
                if (stopAfter == SmokeTestStage.CLASS_MASK_EXTRACTION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 8 — Dewarping
                val alignedImageChannels = ImageMaskAligner.alignToMaskSize(
                    channels = canonicalImageChannels,
                    sourceWidth = canonicalWidth,
                    sourceHeight = canonicalHeight,
                    targetWidth = masks.width,
                    targetHeight = masks.height
                )
                val dewarped = traceStage(SmokeTestStage.DEWARPING, timings) {
                    DewarpPipeline.run(alignedImageChannels, masks)
                }
                previews[SmokeTestStage.DEWARPING] = listOf(
                    OmrSmokeTestPreview(
                        "dewarpedImage",
                        OmrSmokeTestBitmaps.channelsToThumbnail(dewarped.imageChannels, dewarped.width, dewarped.height)
                    )
                ) + maskPreviews(dewarped.masks, prefix = "dewarped_")
                details[SmokeTestStage.DEWARPING] = listOf("wasDewarped=${dewarped.wasDewarped}")
                lastCompletedStage = SmokeTestStage.DEWARPING
                if (stopAfter == SmokeTestStage.DEWARPING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 9 — Staffline/grid assembly
                val gridResult = traceStage(SmokeTestStage.STAFF_GRID_ASSEMBLY, timings) {
                    OmrStaffGridAssembler.assemble(dewarped)
                }
                details[SmokeTestStage.STAFF_GRID_ASSEMBLY] = listOf(
                    "trackNums=${gridResult.trackVote.trackNums}",
                    "barlineBoxes=${gridResult.trackVote.barlineBoxes.size}",
                    "votes cast=${gridResult.trackVote.votes.size}",
                    "validatedZones=${gridResult.validatedGrid.size}",
                    "validatedStaffs=${gridResult.validatedGrid.sumOf { it.size }}"
                )
                lastCompletedStage = SmokeTestStage.STAFF_GRID_ASSEMBLY

                diagnosticResult(lastCompletedStage, timings, previews, details, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "[OMR_SMOKE] Pipeline aborted; last completed stage=$lastCompletedStage", t)
                diagnosticResult(
                    lastCompletedStage, timings, previews, details,
                    errorMessage = "${t::class.java.name}: ${t.message}"
                )
            } finally {
                tilesByModel?.values?.forEach { tiles -> tiles.forEach { it.release() } }
                oemerOrdered?.release()
                resized?.release()
                bitmap?.recycle()
            }
        }

    private fun diagnosticResult(
        lastCompletedStage: SmokeTestStage?,
        timings: List<OmrSmokeTestStageTiming>,
        previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>,
        details: Map<SmokeTestStage, List<String>>,
        errorMessage: String?
    ) = OmrSmokeTestDiagnosticResult(
        lastCompletedStage = lastCompletedStage,
        stageDurations = timings,
        previews = previews,
        stageDetails = details,
        errorMessage = errorMessage
    )

    private fun maskPreviews(masks: OmrClassMasks, prefix: String = ""): List<OmrSmokeTestPreview> {
        val thumbs = OmrSmokeTestBitmaps.masksToThumbnails(masks)
        return listOf(
            OmrSmokeTestPreview("${prefix}staff", thumbs.staff),
            OmrSmokeTestPreview("${prefix}symbols", thumbs.symbols),
            OmrSmokeTestPreview("${prefix}stemsRests", thumbs.stemsRests),
            OmrSmokeTestPreview("${prefix}noteheads", thumbs.noteheads),
            OmrSmokeTestPreview("${prefix}clefsKeys", thumbs.clefsKeys)
        )
    }

    /**
     * Logs `[OMR_SMOKE] START/END <stage>` (plus `MEM before/after`) around
     * [block], records its [OmrSmokeTestStageTiming], and logs
     * `[OMR_SMOKE] FAILED <stage> error=...` before rethrowing on failure.
     * A process kill mid-[block] has no corresponding `END`/`FAILED` line —
     * that gap is the diagnostic signal, not something this function
     * detects itself.
     */
    private fun <T> traceStage(
        stage: SmokeTestStage,
        timings: MutableList<OmrSmokeTestStageTiming>,
        block: () -> T
    ): T {
        Log.d(TAG, "[OMR_SMOKE] START ${stage.logName}")
        val (usedBefore, totalBefore, freeBefore) = memStatsMb()
        Log.d(TAG, "[OMR_SMOKE] MEM before ${stage.logName} usedMB=$usedBefore totalMB=$totalBefore freeMB=$freeBefore")

        val startMs = SystemClock.elapsedRealtime()
        val result = try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "[OMR_SMOKE] FAILED ${stage.logName} error=${t::class.java.name}: ${t.message}")
            throw t
        }
        val elapsed = SystemClock.elapsedRealtime() - startMs

        val (usedAfter, totalAfter, freeAfter) = memStatsMb()
        Log.d(TAG, "[OMR_SMOKE] END ${stage.logName} durationMs=$elapsed")
        Log.d(TAG, "[OMR_SMOKE] MEM after ${stage.logName} usedMB=$usedAfter totalMB=$totalAfter freeMB=$freeAfter")

        timings.add(OmrSmokeTestStageTiming(stage, elapsed, usedAfter, totalAfter, freeAfter))
        return result
    }

    private fun memStatsMb(): Triple<Long, Long, Long> {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        return Triple(totalMb - freeMb, totalMb, freeMb)
    }
}