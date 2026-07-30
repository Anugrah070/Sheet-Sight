package com.sheetsight.app.data.omr.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.sheetsight.app.data.omr.OmrPipelineException
import com.sheetsight.app.data.omr.OmrProgressCalculator
import com.sheetsight.app.data.omr.OmrProgressListener
import com.sheetsight.app.data.omr.OmrStage
import com.sheetsight.app.data.omr.dewarp.DewarpPipeline
import com.sheetsight.app.data.omr.dewarp.ImageMaskAligner
import com.sheetsight.app.data.omr.inference.ClassMaskExtractor
import com.sheetsight.app.data.omr.inference.OmrClassMasks
import com.sheetsight.app.data.omr.inference.TileInferenceRunner
import com.sheetsight.app.data.omr.grouping.NoteGrouper
import com.sheetsight.app.data.omr.notehead.NoteheadExtractor
import com.sheetsight.app.data.omr.preprocessing.CanonicalImageResizer
import com.sheetsight.app.data.omr.preprocessing.ImagePreprocessing
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler
import com.sheetsight.app.data.omr.track.OmrStaffGridAssembler
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceMasks
import com.sheetsight.app.data.omr.rhythm.RhythmExtractor
import com.sheetsight.app.data.omr.symbol.AssetManagerSymbolModelSource
import com.sheetsight.app.data.omr.symbol.SvmModelKind
import com.sheetsight.app.data.omr.symbol.SymbolClassifierLoader
import com.sheetsight.app.data.omr.symbol.UnsupportedModelException
import com.sheetsight.app.di.DefaultDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * `TileInferenceRunner`, `ClassMaskExtractor`, `DewarpPipeline`,
 * `OmrStaffGridAssembler`) — nothing here reimplements any OMR algorithm —
 * but calls them directly, one stage at a time, instead of through
 * [com.sheetsight.app.data.omr.inference.OmrPageInferenceRunner]
 * / [com.sheetsight.app.data.omr.dewarp.OmrPageDewarpRunner], which
 * bundle multiple stages into one call and give no seam to stop at.
 *
 * Every run executes stages 1..N **from the beginning** and deliberately
 * stops right after [stopAfter] rather than resuming from a previous
 * run's in-memory state — if a stage's own memory/CPU load is what kills
 * the process, no such state would have survived to resume from anyway,
 * and starting fresh keeps each run's numbers comparable.
 *
 * **Memory fix history (see [TileInferenceRunner]'s own class KDoc for
 * the full account of fixes #1–#2).** Fix #3, reflected in this file:
 * `MODEL1_INFERENCE`/`MODEL2_INFERENCE` used to build a `List<ImageTile>`
 * for the *entire page* up front (as its own `TILING` stage), then run
 * that whole model's inference into a `List<TilePrediction>` covering
 * every tile before a separate `PREDICTION_MERGING` stage reduced it —
 * exactly the pattern that produced the confirmed heap-cap crash. Every
 * stage below now calls [TileInferenceRunner.runStreaming], which tiles,
 * infers, and merges one small batch at a time. Three consequences,
 * documented at each stage below rather than silently:
 *  - `TILING` no longer allocates any tile [Mat]s at all — it only
 *    computes how many tiles each model *will* need, via
 *    [SlidingWindowTiler.computeOrigins] (pure coordinate math), purely
 *    for reporting.
 *  - `MODEL1_INFERENCE`/`MODEL2_INFERENCE` now each produce their
 *    model's fully-merged prediction map directly — tiling, inference,
 *    and merging all happen inside that one traced stage.
 *  - `PREDICTION_MERGING` is now a fast, already-done confirmation step
 *    (both maps are merged by the time it runs) rather than doing real
 *    work — kept in the sequence purely so "stop after PREDICTION_MERGING"
 *    remains a valid, meaningful checkpoint in the stage picker.
 *
 * **Fix #4 (immediate per-model argmax).** Each model's raw
 * [com.sheetsight.app.data.omr.inference.OmrPredictionMap] (44.1 MB for
 * model 1, 58.8 MB for model 2) is now argmax'd into a 14.7 MB `IntArray`
 * **immediately** after its streaming inference finishes, and the
 * `FloatArray`-backed map is discarded — before the next model starts.
 * This prevents both full float maps from ever coexisting on the heap,
 * saving ~73.5 MB at the critical Stage 5 peak. The two `IntArray`s feed
 * [ClassMaskExtractor.extractFromArgmaxed] in `CLASS_MASK_EXTRACTION`.
 *
 * Every stage is bracketed by `[OMR_SMOKE] START/END <stage>` / `MEM`
 * lines (unchanged). Two additions for the memory investigation: `TILING`,
 * `MODEL1_INFERENCE`, and `MODEL2_INFERENCE` now also report tile counts
 * and the largest single-batch tensor size each model will allocate, and
 * every returned [OmrSmokeTestDiagnosticResult] is preceded by a
 * `[OMR_SMOKE] PEAK usedMB=...` log line derived from every stage's own
 * memory reading so far — a quick, temporary way to confirm the fix's
 * effect on-device without needing to eyeball every individual stage line.
 */
@Singleton
class OmrSmokeTestRunner @Inject constructor(
    private val tileInferenceRunner: TileInferenceRunner,
    @ApplicationContext private val context: Context,
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
    suspend fun run(
        imagePath: String,
        stopAfter: SmokeTestStage,
        listener: OmrProgressListener? = null
    ): OmrSmokeTestDiagnosticResult =
        withContext(defaultDispatcher) {
            val timings = mutableListOf<OmrSmokeTestStageTiming>()
            val previews = mutableMapOf<SmokeTestStage, List<OmrSmokeTestPreview>>()
            val details = mutableMapOf<SmokeTestStage, List<String>>()
            var lastCompletedStage: SmokeTestStage? = null
            val calculator = listener?.let { OmrProgressCalculator(it) }

            // Retained-between-stages state. Each is released in `finally`
            // below and nulled out the moment an earlier stage no longer
            // needs it, so a stop-after-stage-N run never holds stage-N+1's
            // inputs alive longer than necessary.
            var bitmap: Bitmap? = null
            var oemerOrdered: Mat? = null
            var resized: Mat? = null

            try {
                // STAGE 1 — Input decode
                calculator?.updateStage(OmrStage.INPUT_DECODE, 0.1f)
                bitmap = traceStage(SmokeTestStage.INPUT_DECODE, timings) {
                    BitmapFactory.decodeFile(imagePath)
                        ?: throw OmrPipelineException("Could not decode an image from '$imagePath'")
                }
                calculator?.updateStage(OmrStage.INPUT_DECODE, 1f)
                previews[SmokeTestStage.INPUT_DECODE] =
                    listOf(OmrSmokeTestPreview("input", OmrSmokeTestBitmaps.thumbnailOf(bitmap)))
                lastCompletedStage = SmokeTestStage.INPUT_DECODE
                if (stopAfter == SmokeTestStage.INPUT_DECODE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 2 — Preprocessing (oemer byte-order conversion + canonical resize)
                calculator?.updateStage(OmrStage.PREPROCESSING, 0.1f)
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
                calculator?.updateStage(OmrStage.PREPROCESSING, 1f)
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

                // STAGE 3 — Tiling. Computed only: how many tiles each model will
                // need, via pure coordinate math (SlidingWindowTiler.computeOrigins),
                // with zero Mat allocation. Actual tile generation now happens lazily,
                // batch by batch, inside MODEL1_INFERENCE/MODEL2_INFERENCE below.
                calculator?.updateStage(OmrStage.TILING, 0.1f)
                val tileCounts = traceStage(SmokeTestStage.TILING, timings) {
                    OmrModelSpec.entries.associateWith { spec -> tileCountFor(spec, canonicalWidth, canonicalHeight) }
                }
                calculator?.updateStage(OmrStage.TILING, 1f)
                details[SmokeTestStage.TILING] = tileCounts.map { (spec, count) ->
                    "${spec.name}: $count tiles @ ${spec.windowSize}x${spec.windowSize} " +
                            "(count only -- no tiles allocated yet)"
                }
                lastCompletedStage = SmokeTestStage.TILING
                if (stopAfter == SmokeTestStage.TILING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 4 — Model 1 inference (staff_and_symbols). Tiling, inference,
                // and merging all happen inside this one streaming call — see this
                // file's class KDoc "Memory fix history" for why.
                // Fix #4: the raw OmrPredictionMap is argmax'd immediately and
                // discarded, so its 44.1 MB FloatArray is GC-eligible before
                // model 2 starts.
                data class ArgmaxedMap(val classes: IntArray, val width: Int, val height: Int)
                val argmaxedModel1: ArgmaxedMap = traceStage(SmokeTestStage.MODEL1_INFERENCE, timings) {
                    val map = tileInferenceRunner.runStreaming(
                        spec = OmrModelSpec.STAFF_AND_SYMBOLS,
                        source = resized!!,
                        canonicalWidth = canonicalWidth,
                        canonicalHeight = canonicalHeight,
                        onProgress = { current, total ->
                            calculator?.updateStage(OmrStage.MODEL1_INFERENCE, current.toFloat() / total, total, current)
                        }
                    )
                    val result = ArgmaxedMap(
                        classes = ClassMaskExtractor.argmaxMap(map),
                        width = map.width,
                        height = map.height
                    )
                    // map (and its FloatArray data) is now unreferenced
                    result
                }
                details[SmokeTestStage.MODEL1_INFERENCE] = listOf(
                    "tiles processed: ${tileCounts.getValue(OmrModelSpec.STAFF_AND_SYMBOLS)}",
                    "largest single-batch output tensor: " +
                            "${largestBatchOutputTensorBytes(OmrModelSpec.STAFF_AND_SYMBOLS)} bytes",
                    "argmaxed size: ${argmaxedModel1.width}x${argmaxedModel1.height} (IntArray, float map discarded)"
                )
                lastCompletedStage = SmokeTestStage.MODEL1_INFERENCE
                if (stopAfter == SmokeTestStage.MODEL1_INFERENCE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 5 — Model 2 inference (symbol_detail), same streaming shape as
                // model 1. Fix #4: argmax'd immediately, same as model 1.
                // `resized` is no longer needed by anything past this point —
                // released immediately rather than waiting for the whole run to finish.
                val argmaxedModel2: ArgmaxedMap = traceStage(SmokeTestStage.MODEL2_INFERENCE, timings) {
                    val map = tileInferenceRunner.runStreaming(
                        spec = OmrModelSpec.SYMBOL_DETAIL,
                        source = resized!!,
                        canonicalWidth = canonicalWidth,
                        canonicalHeight = canonicalHeight,
                        onProgress = { current, total ->
                            calculator?.updateStage(OmrStage.MODEL2_INFERENCE, current.toFloat() / total, total, current)
                        }
                    )
                    val result = ArgmaxedMap(
                        classes = ClassMaskExtractor.argmaxMap(map),
                        width = map.width,
                        height = map.height
                    )
                    // map (and its FloatArray data) is now unreferenced
                    result
                }
                resized?.release()
                resized = null
                details[SmokeTestStage.MODEL2_INFERENCE] = listOf(
                    "tiles processed: ${tileCounts.getValue(OmrModelSpec.SYMBOL_DETAIL)}",
                    "largest single-batch output tensor: " +
                            "${largestBatchOutputTensorBytes(OmrModelSpec.SYMBOL_DETAIL)} bytes",
                    "argmaxed size: ${argmaxedModel2.width}x${argmaxedModel2.height} (IntArray, float map discarded)"
                )
                lastCompletedStage = SmokeTestStage.MODEL2_INFERENCE
                if (stopAfter == SmokeTestStage.MODEL2_INFERENCE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 6 — Prediction-map merging. Both models are already fully
                // merged and argmax'd as part of their own inference stages above;
                // there is no separate merge left to perform. This stage is kept as a
                // fast confirmation checkpoint so it remains a valid, selectable stop point.
                traceStage(SmokeTestStage.PREDICTION_MERGING, timings) {
                    // Intentionally empty: see this file's class KDoc.
                }
                details[SmokeTestStage.PREDICTION_MERGING] = listOf(
                    "staffAndSymbols: ${argmaxedModel1.width}x${argmaxedModel1.height} (argmaxed IntArray)",
                    "symbolDetail: ${argmaxedModel2.width}x${argmaxedModel2.height} (argmaxed IntArray)",
                    "(already merged + argmaxed during MODEL1_INFERENCE / MODEL2_INFERENCE above)"
                )
                lastCompletedStage = SmokeTestStage.PREDICTION_MERGING
                if (stopAfter == SmokeTestStage.PREDICTION_MERGING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 7 — Class-mask extraction (from pre-argmaxed IntArrays)
                calculator?.updateStage(OmrStage.POST_PROCESSING, 0.2f)
                val masks = traceStage(SmokeTestStage.CLASS_MASK_EXTRACTION, timings) {
                    ClassMaskExtractor.extractFromArgmaxed(
                        staffAndSymbolsClasses = argmaxedModel1.classes,
                        symbolDetailClasses = argmaxedModel2.classes,
                        width = argmaxedModel1.width,
                        height = argmaxedModel1.height
                    )
                }
                previews[SmokeTestStage.CLASS_MASK_EXTRACTION] = maskPreviews(masks)
                lastCompletedStage = SmokeTestStage.CLASS_MASK_EXTRACTION
                if (stopAfter == SmokeTestStage.CLASS_MASK_EXTRACTION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 8 — Dewarping
                calculator?.updateStage(OmrStage.POST_PROCESSING, 0.4f)
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
                calculator?.updateStage(OmrStage.POST_PROCESSING, 0.8f)
                val gridResult = traceStage(SmokeTestStage.STAFF_GRID_ASSEMBLY, timings) {
                    OmrStaffGridAssembler.assemble(dewarped)
                }
                calculator?.updateStage(OmrStage.POST_PROCESSING, 1.0f)
                details[SmokeTestStage.STAFF_GRID_ASSEMBLY] = listOf(
                    "trackNums=${gridResult.trackVote.trackNums}",
                    "barlineBoxes=${gridResult.trackVote.barlineBoxes.size}",
                    "votes cast=${gridResult.trackVote.votes.size}",
                    "validatedZones=${gridResult.validatedGrid.size}",
                    "validatedStaffs=${gridResult.validatedGrid.sumOf { it.size }}"
                )
                lastCompletedStage = SmokeTestStage.STAFF_GRID_ASSEMBLY
                if (stopAfter == SmokeTestStage.STAFF_GRID_ASSEMBLY) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 10 — Notehead extraction. The extractor borrows the
                // dewarped masks and retains only per-note source pixels.
                val noteheads = traceStage(SmokeTestStage.NOTEHEAD_EXTRACTION, timings) {
                    NoteheadExtractor.extract(dewarped.masks, gridResult.validatedGrid)
                }
                details[SmokeTestStage.NOTEHEAD_EXTRACTION] = listOf(
                    "detected notehead count=${noteheads.size}"
                )
                reusedDewarpedMaskPreview(previews, "dewarped_noteheads")?.let {
                    previews[SmokeTestStage.NOTEHEAD_EXTRACTION] =
                        listOf(it.copy(label = "detected noteheads"))
                }
                lastCompletedStage = SmokeTestStage.NOTEHEAD_EXTRACTION
                if (stopAfter == SmokeTestStage.NOTEHEAD_EXTRACTION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 11 — Grouping only; no rhythm labels are created.
                val chords = traceStage(SmokeTestStage.NOTE_GROUPING, timings) {
                    NoteGrouper.group(
                        noteheads = noteheads,
                        stemMask = dewarped.masks.stemsRests,
                        width = dewarped.width,
                        height = dewarped.height
                    )
                }
                details[SmokeTestStage.NOTE_GROUPING] = listOf(
                    "grouped chord count=${chords.size}"
                )
                reusedDewarpedMaskPreview(previews, "dewarped_stemsRests")?.let {
                    previews[SmokeTestStage.NOTE_GROUPING] =
                        listOf(it.copy(label = "grouping stems"))
                }
                lastCompletedStage = SmokeTestStage.NOTE_GROUPING
                if (stopAfter == SmokeTestStage.NOTE_GROUPING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 12 — Validate model loading only. Missing/unsupported
                // sklearn models are an expected documented state, never a
                // reason to invent symbol predictions.
                val classifierStatuses = traceStage(SmokeTestStage.SYMBOL_CLASSIFICATION, timings) {
                    val loader = SymbolClassifierLoader(
                        AssetManagerSymbolModelSource(context.assets)
                    )
                    SvmModelKind.entries.map { kind ->
                        try {
                            loader.load(kind)
                            "${kind.name}: loaded"
                        } catch (error: UnsupportedModelException) {
                            "${kind.name}: unsupported (${error.message})"
                        }
                    }
                }
                details[SmokeTestStage.SYMBOL_CLASSIFICATION] =
                    listOf("classified symbols=0 (no fabricated predictions)") + classifierStatuses
                reusedDewarpedMaskPreview(previews, "dewarped_clefsKeys")?.let {
                    previews[SmokeTestStage.SYMBOL_CLASSIFICATION] =
                        listOf(it.copy(label = "unclassified clef/accidental mask"))
                }
                lastCompletedStage = SmokeTestStage.SYMBOL_CLASSIFICATION
                if (stopAfter == SmokeTestStage.SYMBOL_CLASSIFICATION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 13 — Framework records only. Beam/flag/dot extraction
                // is not ported, so evidence is explicitly incomplete and
                // every duration remains null.
                val rhythmCandidates = traceStage(SmokeTestStage.RHYTHM_FRAMEWORK, timings) {
                    RhythmExtractor.prepareCandidates(
                        noteheads = noteheads,
                        chords = chords,
                        evidence = RhythmEvidenceMasks(
                            width = dewarped.width,
                            height = dewarped.height,
                            stems = dewarped.masks.stemsRests,
                            beams = null,
                            flags = null,
                            dots = null
                        )
                    )
                }
                details[SmokeTestStage.RHYTHM_FRAMEWORK] = listOf(
                    "rhythm candidates=${rhythmCandidates.size}",
                    "resolved durations=0 (framework only; evidence incomplete)"
                )
                reusedDewarpedMaskPreview(previews, "dewarped_symbols")?.let {
                    previews[SmokeTestStage.RHYTHM_FRAMEWORK] =
                        listOf(it.copy(label = "raw rhythm evidence source"))
                }
                lastCompletedStage = SmokeTestStage.RHYTHM_FRAMEWORK
                diagnosticResult(lastCompletedStage, timings, previews, details, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "[OMR_SMOKE] Pipeline aborted; last completed stage=$lastCompletedStage", t)
                diagnosticResult(
                    lastCompletedStage, timings, previews, details,
                    errorMessage = "${t::class.java.name}: ${t.message}"
                )
            } finally {
                oemerOrdered?.release()
                resized?.release()
                bitmap?.recycle()
            }
        }

    /** Number of tiles [spec] will require for a `canonicalWidth`x`canonicalHeight` page — pure math, no allocation. */
    private fun tileCountFor(spec: OmrModelSpec, canonicalWidth: Int, canonicalHeight: Int): Int {
        val paddedWidth = maxOf(canonicalWidth, spec.windowSize)
        val paddedHeight = maxOf(canonicalHeight, spec.windowSize)
        return SlidingWindowTiler.computeOrigins(
            paddedWidth, paddedHeight, spec.windowSize, spec.windowSize
        ).size
    }

    /** Byte size of the largest single output tensor [spec] will ever produce in one batch (FLOAT32). */
    private fun largestBatchOutputTensorBytes(spec: OmrModelSpec): Long =
        TileInferenceRunner.DEFAULT_BATCH_SIZE.toLong() *
                spec.windowSize.toLong() * spec.windowSize.toLong() *
                spec.outputChannels.toLong() * Float.SIZE_BYTES

    private fun diagnosticResult(
        lastCompletedStage: SmokeTestStage?,
        timings: List<OmrSmokeTestStageTiming>,
        previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>,
        details: Map<SmokeTestStage, List<String>>,
        errorMessage: String?
    ): OmrSmokeTestDiagnosticResult {
        val peakUsedMb = timings.maxOfOrNull { it.memoryAfter.javaUsedMb + it.memoryAfter.nativeUsedMb } ?: 0L
        Log.d(
            TAG,
            "[OMR_SMOKE] PEAK (Java+Native) usedMB=$peakUsedMb across ${timings.size} completed stage(s); " +
                    "lastCompletedStage=$lastCompletedStage"
        )
        return OmrSmokeTestDiagnosticResult(
            lastCompletedStage = lastCompletedStage,
            stageDurations = timings,
            previews = previews,
            stageDetails = details,
            errorMessage = errorMessage
        )
    }

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

    /** Reuses an existing tiny Stage 8 bitmap; no new full-page or thumbnail bitmap is allocated. */
    private fun reusedDewarpedMaskPreview(
        previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>,
        label: String
    ): OmrSmokeTestPreview? =
        previews[SmokeTestStage.DEWARPING]?.firstOrNull { it.label == label }

    /**
     * Logs `[OMR_SMOKE] START/END <stage>` (plus `MEM before/after`) around
     * [block], records its [OmrSmokeTestStageTiming], and logs
     * `[OMR_SMOKE] FAILED <stage> error=...` before rethrowing on failure.
     */
    private fun <T> traceStage(
        stage: SmokeTestStage,
        timings: MutableList<OmrSmokeTestStageTiming>,
        block: () -> T
    ): T {
        Log.d(TAG, "[OMR_SMOKE] START ${stage.logName}")
        MemoryTracker.log("${stage.label} (Before)", MemoryTracker.capture())

        val startMs = SystemClock.elapsedRealtime()
        val result = try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "[OMR_SMOKE] FAILED ${stage.logName} error=${t::class.java.name}: ${t.message}")
            throw t
        }
        val elapsed = SystemClock.elapsedRealtime() - startMs

        val snapshot = MemoryTracker.capture()
        Log.d(TAG, "[OMR_SMOKE] END ${stage.logName} durationMs=$elapsed")
        MemoryTracker.log("${stage.label} (After)", snapshot)

        timings.add(OmrSmokeTestStageTiming(stage, elapsed, snapshot))
        return result
    }
}
