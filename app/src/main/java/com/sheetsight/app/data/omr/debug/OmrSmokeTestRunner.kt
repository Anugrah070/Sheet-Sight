package com.sheetsight.app.data.omr.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
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
import com.sheetsight.app.data.omr.inference.OmrRuntimeTuning
import com.sheetsight.app.data.omr.inference.TileInferenceRunner
import com.sheetsight.app.data.omr.grouping.NoteGrouper
import com.sheetsight.app.data.omr.musicxml.MusicXmlExporter
import com.sheetsight.app.data.omr.musicxml.MusicXmlNotationParser
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.data.omr.notehead.NoteheadExtractor
import com.sheetsight.app.data.omr.preprocessing.CanonicalImageResizer
import com.sheetsight.app.data.omr.preprocessing.ImagePreprocessing
import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec
import com.sheetsight.app.data.omr.preprocessing.SlidingWindowTiler
import com.sheetsight.app.data.omr.track.OmrStaffGridAssembler
import com.sheetsight.app.data.omr.track.StaffGeometryResolver
import com.sheetsight.app.data.omr.rhythm.RhythmEvidenceMasks
import com.sheetsight.app.data.omr.rhythm.RhythmCandidate
import com.sheetsight.app.data.omr.rhythm.RhythmExtractionResult
import com.sheetsight.app.data.omr.rhythm.RhythmExtractor
import com.sheetsight.app.data.omr.rhythm.RhythmResolutionState
import com.sheetsight.app.data.omr.rhythm.RhythmUnresolvedReason
import com.sheetsight.app.data.omr.rhythm.RestRhythmResult
import com.sheetsight.app.data.omr.rhythm.StemAssociationStatus
import com.sheetsight.app.data.omr.semantic.SemanticScoreConstructor
import com.sheetsight.app.data.omr.semantic.SemanticChord
import com.sheetsight.app.data.omr.semantic.SemanticRest
import com.sheetsight.app.data.omr.semantic.summary
import com.sheetsight.app.data.omr.symbol.MusicalBarlineDiagnostics
import com.sheetsight.app.data.omr.symbol.RestExtractionDiagnostics
import com.sheetsight.app.data.omr.symbol.RestWholeHalfPlacement
import com.sheetsight.app.data.omr.symbol.SymbolExtractor
import com.sheetsight.app.ui.editor.notation.NotationLayoutEngine
import com.sheetsight.app.di.DefaultDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import java.io.File
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
    private val symbolExtractor: SymbolExtractor,
    private val musicXmlExporter: MusicXmlExporter,
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "OmrSmokeTest"
        private const val UNRESOLVED_SUMMARY_KEY = "UNRESOLVED"
    }

    /**
     * Runs stages 1..[stopAfter] against the image at [imagePath].
     * Returns a diagnostic result describing exactly how far it got,
     * even on failure — never a fabricated success.
     */
    suspend fun run(
        imagePath: String,
        stopAfter: SmokeTestStage,
        listener: OmrProgressListener? = null,
        /** Developer parity experiment only; production callers leave this null. */
        inferenceStepSize: Int? = null,
        /** Reproduces the historical per-model no-overlap regression for before/after evaluation. */
        legacyNonOverlappingStride: Boolean = false,
        /** Debug comparison inside the checkpoints' trained pixel-count range. */
        inferenceTargetPixels: Int = CanonicalImageResizer.DEFAULT_TARGET_PIXELS
    ): OmrSmokeTestDiagnosticResult =
        withContext(defaultDispatcher) {
            val timings = mutableListOf<OmrSmokeTestStageTiming>()
            val previews = mutableMapOf<SmokeTestStage, List<OmrSmokeTestPreview>>()
            val details = mutableMapOf<SmokeTestStage, List<String>>()
            var lastCompletedStage: SmokeTestStage? = null
            val calculator = listener?.let { OmrProgressCalculator(it) }
            fun stepSizeFor(spec: OmrModelSpec): Int = when {
                inferenceStepSize != null -> inferenceStepSize
                legacyNonOverlappingStride -> spec.windowSize
                else -> spec.windowSize
            }

            // Retained-between-stages state. Each is released in `finally`
            // below and nulled out the moment an earlier stage no longer
            // needs it, so a stop-after-stage-N run never holds stage-N+1's
            // inputs alive longer than necessary.
            var bitmap: Bitmap? = null
            var oemerOrdered: Mat? = null
            var resized: Mat? = null
            var oemerOrderedPreview: Bitmap? = null

            try {
                // STAGE 1 — Input decode
                calculator?.updateStage(OmrStage.INPUT_DECODE, 0.1f)
                bitmap = traceStage(SmokeTestStage.INPUT_DECODE, timings) {
                    BitmapFactory.decodeFile(imagePath)
                        ?: throw OmrPipelineException("Could not decode an image from '$imagePath'")
                }
                val inputWidth = bitmap!!.width
                val inputHeight = bitmap!!.height
                val inputOrientation = describeExifOrientation(imagePath)
                calculator?.updateStage(OmrStage.INPUT_DECODE, 1f)
                previews[SmokeTestStage.INPUT_DECODE] =
                    listOf(OmrSmokeTestPreview("input", OmrSmokeTestBitmaps.thumbnailOf(bitmap)))
                details[SmokeTestStage.INPUT_DECODE] = listOf(
                    "input size: ${inputWidth}x$inputHeight",
                    "file bytes=${File(imagePath).length()}",
                    "EXIF orientation=$inputOrientation"
                )
                lastCompletedStage = SmokeTestStage.INPUT_DECODE
                if (stopAfter == SmokeTestStage.INPUT_DECODE) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 2 — Preprocessing (oemer byte-order conversion + canonical resize)
                calculator?.updateStage(OmrStage.PREPROCESSING, 0.1f)
                traceStage(SmokeTestStage.PREPROCESSING, timings) {
                    oemerOrdered = ImagePreprocessing.toOemerOrderedMat(bitmap!!)
                    oemerOrderedPreview = OmrSmokeTestBitmaps.channelsToThumbnail(
                        ImagePreprocessing.extractChannels(oemerOrdered!!),
                        oemerOrdered!!.width(),
                        oemerOrdered!!.height()
                    )
                    resized = CanonicalImageResizer.resize(oemerOrdered!!, inferenceTargetPixels)
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
                    OmrSmokeTestPreview("oemerOrderedBeforeResize", requireNotNull(oemerOrderedPreview)),
                    OmrSmokeTestPreview(
                        "canonicalInferenceImage",
                        OmrSmokeTestBitmaps.channelsToThumbnail(canonicalImageChannels, canonicalWidth, canonicalHeight)
                    )
                )
                details[SmokeTestStage.PREPROCESSING] = listOf(
                    "canonical size: ${canonicalWidth}x$canonicalHeight",
                    "target pixels=$inferenceTargetPixels",
                    "byte order=BGR",
                    "tensor input=UINT8; normalization=none"
                )
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
                    OmrModelSpec.entries.associateWith { spec ->
                        tileCountFor(
                            spec,
                            canonicalWidth,
                            canonicalHeight,
                            stepSizeFor(spec)
                        )
                    }
                }
                calculator?.updateStage(OmrStage.TILING, 1f)
                details[SmokeTestStage.TILING] = tileCounts.map { (spec, count) ->
                    "${spec.name}: $count tiles @ ${spec.windowSize}x${spec.windowSize}, " +
                            "stride=${stepSizeFor(spec)} " +
                            "(count only -- no tiles allocated yet)"
                }
                val canonicalPreview = previews.getValue(SmokeTestStage.PREPROCESSING)
                    .first { it.label == "canonicalInferenceImage" }.bitmap
                previews[SmokeTestStage.TILING] = OmrModelSpec.entries.map { spec ->
                    val stride = stepSizeFor(spec)
                    val origins = SlidingWindowTiler.computeOrigins(
                        maxOf(canonicalWidth, spec.windowSize),
                        maxOf(canonicalHeight, spec.windowSize),
                        spec.windowSize,
                        stride
                    )
                    OmrSmokeTestPreview(
                        "${spec.name.lowercase()}TileBoundaries",
                        OmrSmokeTestBitmaps.overlayThumbnail(
                            background = canonicalPreview,
                            sourceWidth = canonicalWidth,
                            sourceHeight = canonicalHeight,
                            boxes = origins.map { (x, y) ->
                                DebugOverlayBox(
                                    x,
                                    y,
                                    minOf(x + spec.windowSize, canonicalWidth),
                                    minOf(y + spec.windowSize, canonicalHeight),
                                    0x88FF0000.toInt()
                                )
                            }
                        )
                    )
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
                        stepSize = stepSizeFor(OmrModelSpec.STAFF_AND_SYMBOLS),
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
                    "verified graph contract: " +
                        tileInferenceRunner.verifiedContract(OmrModelSpec.STAFF_AND_SYMBOLS),
                    "tiles processed: ${tileCounts.getValue(OmrModelSpec.STAFF_AND_SYMBOLS)}",
                    "largest single-batch output tensor: " +
                            "${largestBatchOutputTensorBytes(OmrModelSpec.STAFF_AND_SYMBOLS)} bytes",
                    "argmaxed size: ${argmaxedModel1.width}x${argmaxedModel1.height} (IntArray, float map discarded)"
                )
                previews[SmokeTestStage.MODEL1_INFERENCE] = listOf(
                    OmrSmokeTestPreview(
                        "model1Argmax_backgroundStaffSymbols",
                        OmrSmokeTestBitmaps.classMapToThumbnail(
                            argmaxedModel1.classes,
                            argmaxedModel1.width,
                            argmaxedModel1.height,
                            intArrayOf(0xFFFFFFFF.toInt(), 0xFF2070FF.toInt(), 0xFF111111.toInt())
                        )
                    )
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
                        stepSize = stepSizeFor(OmrModelSpec.SYMBOL_DETAIL),
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
                    "verified graph contract: " +
                        tileInferenceRunner.verifiedContract(OmrModelSpec.SYMBOL_DETAIL),
                    "tiles processed: ${tileCounts.getValue(OmrModelSpec.SYMBOL_DETAIL)}",
                    "largest single-batch output tensor: " +
                            "${largestBatchOutputTensorBytes(OmrModelSpec.SYMBOL_DETAIL)} bytes",
                    "argmaxed size: ${argmaxedModel2.width}x${argmaxedModel2.height} (IntArray, float map discarded)"
                )
                previews[SmokeTestStage.MODEL2_INFERENCE] = listOf(
                    OmrSmokeTestPreview(
                        "model2Argmax_backgroundStemsRestsNoteheadsClefsKeys",
                        OmrSmokeTestBitmaps.classMapToThumbnail(
                            argmaxedModel2.classes,
                            argmaxedModel2.width,
                            argmaxedModel2.height,
                            intArrayOf(
                                0xFFFFFFFF.toInt(),
                                0xFFE53935.toInt(),
                                0xFF111111.toInt(),
                                0xFF8E24AA.toInt()
                            )
                        )
                    )
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
                    "rawBarlineCandidates=${gridResult.diagnostics.rawHoughLines.size} " +
                        "xs=${gridResult.diagnostics.rawHoughLines.map { (it.topX + it.btX) / 2 }.sorted()}",
                    "acceptedBarlineSegments=${gridResult.diagnostics.acceptedHoughLines.size} " +
                        "xs=${gridResult.diagnostics.acceptedHoughLines.map { (it.topX + it.btX) / 2 }.sorted()}",
                    "candidateComponents=${gridResult.trackVote.barlineBoxes.size}",
                    "tallBarlineCandidates=${gridResult.trackVote.heightRatios.size}",
                    "validatedZones=${gridResult.validatedGrid.size}",
                    "validatedStaffRows=${gridResult.validatedGrid.maxOfOrNull { it.size } ?: 0}",
                    "validatedStaffCells=${gridResult.validatedGrid.sumOf { it.size }}",
                    "assigned staff segments=" + gridResult.validatedGrid.flatten().map { assigned ->
                        val left = assigned.staff.lines.minOf { it.xLeft }
                        val right = assigned.staff.lines.maxOf { it.xRight }
                        "g${assigned.group}/t${assigned.track} x=$left..$right y=${"%.1f".format(assigned.staff.yCenter)} " +
                            "interpolated=${assigned.isInterpolated}"
                    }
                )
                reusedDewarpedImagePreview(previews)?.let { base ->
                    val staffLines = gridResult.validatedGrid.flatten()
                        .flatMap { it.staff.lines }
                        .distinctBy { listOf(it.xLeft, it.yCenter, it.xRight) }
                        .map {
                            val y = kotlin.math.round(it.yCenter).toInt()
                            DebugOverlayLine(it.xLeft, y, it.xRight, y, Color.rgb(0, 145, 70))
                        }
                    val acceptedLines = gridResult.diagnostics.acceptedHoughLines.map {
                        DebugOverlayLine(it.topX, it.topY, it.btX, it.btY, Color.MAGENTA)
                    }
                    val staffLabels = gridResult.validatedGrid.flatten().map { assigned ->
                        val left = assigned.staff.lines.minOf { it.xLeft }
                        val top = assigned.staff.lines.minOf { it.yUpper }
                        DebugOverlayLabel(
                            left,
                            top,
                            "g${assigned.group}/t${assigned.track} ($left,$top)" +
                                if (assigned.isInterpolated) " inferred" else "",
                            if (assigned.isInterpolated) Color.rgb(230, 120, 0) else Color.rgb(0, 100, 55)
                        )
                    }
                    previews[SmokeTestStage.STAFF_GRID_ASSEMBLY] = listOf(
                        OmrSmokeTestPreview(
                            "accepted staff lines + grid barline segments",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                lines = staffLines + acceptedLines,
                                labels = staffLabels
                            )
                        )
                    )
                }
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
                    "detected notehead count=${noteheads.size}",
                    "notehead evidence=" + noteheads.map { note ->
                        val box = note.boundingBox
                        val x = (box.left + box.right) / 2
                        val y = (box.top + box.bottom) / 2
                        "n${note.id}@($x,$y) g${note.staffAssignment.group}/t${note.staffAssignment.track} " +
                            "pos=${note.staffAssignment.staffLinePosition} head=${note.type}"
                    }
                )
                reusedDewarpedMaskPreview(previews, "dewarped_noteheads")?.let {
                    val maskPreview = it.copy(label = "notehead mask")
                    val detectedBoxes = noteheads.map { note ->
                        val box = note.boundingBox
                        DebugOverlayBox(box.left, box.top, box.right, box.bottom, Color.rgb(0, 90, 220))
                    }
                    val boxPreview = reusedDewarpedImagePreview(previews)?.let { base ->
                        OmrSmokeTestPreview(
                            "detected notehead boxes",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                boxes = detectedBoxes,
                                labels = noteheads.map { note ->
                                    val box = note.boundingBox
                                    val x = (box.left + box.right) / 2
                                    val y = (box.top + box.bottom) / 2
                                    DebugOverlayLabel(
                                        box.left,
                                        box.top,
                                        "n${note.id} g${note.staffAssignment.group}/t${note.staffAssignment.track} ($x,$y)",
                                        Color.rgb(0, 70, 190)
                                    )
                                }
                            )
                        )
                    }
                    previews[SmokeTestStage.NOTEHEAD_EXTRACTION] = listOfNotNull(maskPreview, boxPreview)
                }
                lastCompletedStage = SmokeTestStage.NOTEHEAD_EXTRACTION
                if (stopAfter == SmokeTestStage.NOTEHEAD_EXTRACTION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 11 — Grouping only; no rhythm labels are created.
                val groupingResult = traceStage(SmokeTestStage.NOTE_GROUPING, timings) {
                    NoteGrouper.groupWithMap(
                        noteheads = noteheads,
                        stemMask = dewarped.masks.stemsRests,
                        width = dewarped.width,
                        height = dewarped.height,
                        noteheadMask = dewarped.masks.noteheads
                    )
                }
                val chords = groupingResult.chords
                details[SmokeTestStage.NOTE_GROUPING] = listOf(
                    "grouped chord count=${chords.size}",
                    "chord assignments=" + chords.map { chord ->
                        val box = chord.boundingBox
                        val x = (box.left + box.right) / 2
                        val y = (box.top + box.bottom) / 2
                        "c${chord.id}@($x,$y) g${chord.group}/t${chord.track} notes=${chord.noteheads.map { it.id }}"
                    }
                )
                reusedDewarpedMaskPreview(previews, "dewarped_stemsRests")?.let { stemsPreview ->
                    val assignmentPreview = reusedDewarpedImagePreview(previews)?.let { base ->
                        OmrSmokeTestPreview(
                            "note-group and chord assignments",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                boxes = chords.map { chord ->
                                    val box = chord.boundingBox
                                    DebugOverlayBox(box.left, box.top, box.right, box.bottom, Color.rgb(220, 90, 0))
                                },
                                labels = chords.map { chord ->
                                    val box = chord.boundingBox
                                    DebugOverlayLabel(
                                        box.left,
                                        box.top,
                                        "c${chord.id} g${chord.group}/t${chord.track}",
                                        Color.rgb(180, 65, 0)
                                    )
                                }
                            )
                        )
                    }
                    previews[SmokeTestStage.NOTE_GROUPING] =
                        listOfNotNull(stemsPreview.copy(label = "grouping stems"), assignmentPreview)
                }
                lastCompletedStage = SmokeTestStage.NOTE_GROUPING
                if (stopAfter == SmokeTestStage.NOTE_GROUPING) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 12 — Validate model loading only. Missing/unsupported
                // sklearn models are an expected documented state, never a
                // reason to invent symbol predictions.
                val symbolResult = traceStage(SmokeTestStage.SYMBOL_CLASSIFICATION, timings) {
                    symbolExtractor.extract(
                        masks = dewarped.masks,
                        grouping = groupingResult,
                        staffGrid = gridResult.validatedGrid,
                        noteheads = noteheads
                    )
                }
                val musicalBarlineDiagnostics = symbolResult.barlineDiagnostics
                val barlineXsBySystem = symbolResult.barlines
                    .groupBy { it.group }
                    .toSortedMap()
                    .mapValues { (_, values) ->
                        values.map { (it.boundingBox.left + it.boundingBox.right) / 2 }.sorted()
                    }
                details[SmokeTestStage.SYMBOL_CLASSIFICATION] = listOf(
                    "barline filter counts=" + musicalBarlineFilterCounts(musicalBarlineDiagnostics),
                    "barlines=${symbolResult.barlines.size}",
                    "barlines by system=$barlineXsBySystem",
                    "barline structural confidence=" + symbolResult.barlines.map {
                        "x=${(it.boundingBox.left + it.boundingBox.right) / 2},score=${"%.2f".format(it.confidence)}"
                    },
                    "clefs=${symbolResult.clefs.groupingBy { it.label }.eachCount()}",
                    "accidentals=${symbolResult.accidentals.groupingBy { it.label }.eachCount()}",
                    "rests=${symbolResult.rests.groupingBy { it.label }.eachCount()}",
                    "rest candidate filters=" + restCandidateFilterCounts(symbolResult.restDiagnostics),
                    "rest rejected=" + symbolResult.restDiagnostics?.rejectedReasons?.map { (box, reason) ->
                        "$reason@(${(box.left + box.right) / 2},${(box.top + box.bottom) / 2})"
                    },
                    "rest evidence=" + symbolResult.rests.map {
                        "${it.label}@${(it.boundingBox.left + it.boundingBox.right) / 2}:" +
                            "wholeHalf=${it.wholeHalfPlacement}," +
                            "svmMargin=${it.classificationMargin ?: "n/a"}," +
                            "placement=${it.placementConfidence ?: "n/a"}"
                    }
                )
                reusedDewarpedMaskPreview(previews, "dewarped_clefsKeys")?.let {
                    val maskPreview = it.copy(label = "clefs/keys mask")
                    val barlinePreview = reusedDewarpedImagePreview(previews)?.let { base ->
                        OmrSmokeTestPreview(
                            "accepted musical barlines",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                boxes = symbolResult.barlines.map { barline ->
                                    val box = barline.boundingBox
                                    DebugOverlayBox(box.left, box.top, box.right, box.bottom, Color.MAGENTA)
                                }
                            )
                        )
                    }
                    previews[SmokeTestStage.SYMBOL_CLASSIFICATION] = listOfNotNull(maskPreview, barlinePreview)
                }
                lastCompletedStage = SmokeTestStage.SYMBOL_CLASSIFICATION
                if (stopAfter == SmokeTestStage.SYMBOL_CLASSIFICATION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 13 — Verified stem/dot/beam/flag extraction and
                // immutable duration results. Text summaries only: no
                // additional page-sized debug image is retained.
                val rhythmResult = traceStage(SmokeTestStage.RHYTHM_FRAMEWORK, timings) {
                    RhythmExtractor.extract(
                        noteheads = noteheads,
                        chords = chords,
                        evidence = RhythmEvidenceMasks.from(
                            masks = dewarped.masks,
                            staffGrid = gridResult.validatedGrid,
                            barlines = gridResult.trackVote.barlineBoxes
                        ),
                        rests = symbolResult.rests
                    )
                }
                details[SmokeTestStage.RHYTHM_FRAMEWORK] = rhythmSummary(rhythmResult) +
                    ("rhythm evidence=" + rhythmResult.noteGroups.map { candidate ->
                        val box = candidate.chord.boundingBox
                        val x = (box.left + box.right) / 2
                        val y = (box.top + box.bottom) / 2
                        "r${candidate.id}@($x,$y) g${candidate.chord.group}/t${candidate.chord.track} " +
                            "head=${candidate.noteheads.map { it.type }} stem=${candidate.stemDirection}/" +
                            "${candidate.stemAssociation.status} beam=${candidate.beamCount} flag=${candidate.flagCount} " +
                            "dots=${candidate.dotCount} duration=${candidate.dottedDuration} " +
                            "state=${candidate.resolutionState} reasons=${candidate.unresolvedReasons}"
                    })
                reusedDewarpedImagePreview(previews)?.let { base ->
                    previews[SmokeTestStage.RHYTHM_FRAMEWORK] = listOf(
                        OmrSmokeTestPreview(
                            "stem, beam, flag, dot, and duration evidence",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                labels = rhythmResult.noteGroups.map { candidate ->
                                    val box = candidate.chord.boundingBox
                                    val duration = candidate.dottedDuration?.toString() ?: "?"
                                    DebugOverlayLabel(
                                        box.left,
                                        box.top,
                                        "r${candidate.id} ${candidate.stemDirection} b${candidate.beamCount ?: "?"} d$duration",
                                        if (candidate.isResolved()) Color.rgb(0, 115, 55) else Color.RED
                                    )
                                }
                            )
                        )
                    )
                }
                lastCompletedStage = SmokeTestStage.RHYTHM_FRAMEWORK
                if (stopAfter == SmokeTestStage.RHYTHM_FRAMEWORK) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 14 — Image-independent score construction. Concise
                // text summaries only; no additional full-resolution image.
                val semanticScore = traceStage(SmokeTestStage.SEMANTIC_SCORE_CONSTRUCTION, timings) {
                    SemanticScoreConstructor.construct(
                        staffGrid = gridResult.validatedGrid,
                        symbols = symbolResult,
                        rhythm = rhythmResult
                    )
                }
                val semanticSummary = semanticScore.summary()
                val semanticMeasuresBySystem = semanticScore.systems.associate { system ->
                    system.id to system.measures.map { measure ->
                        "[${measure.boundary.left},${measure.boundary.right}) " +
                            "${measure.boundary.leftEvidence}->${measure.boundary.rightEvidence}"
                    }
                }
                details[SmokeTestStage.SEMANTIC_SCORE_CONSTRUCTION] = listOf(
                    "systems=${semanticSummary.systems}",
                    "staffs=${semanticSummary.staffs}",
                    "measures=${semanticSummary.measures}",
                    "semantic measures per system=$semanticMeasuresBySystem",
                    "semantic notes=${semanticSummary.notes}",
                    "semantic chords=${semanticSummary.chords}",
                    "semantic rests=${semanticSummary.rests}",
                    "unresolved events=${semanticSummary.unresolvedEvents}",
                    "validation warnings=${semanticSummary.validationWarnings}"
                )
                reusedDewarpedImagePreview(previews)?.let { base ->
                    val measureLines = semanticScore.systems.flatMap { system ->
                        system.measures.flatMap { measure ->
                            listOf(measure.boundary.left, measure.boundary.right).map { x ->
                                DebugOverlayLine(
                                    x,
                                    system.horizontalBounds.top,
                                    x,
                                    system.horizontalBounds.bottom,
                                    Color.RED
                                )
                            }
                        }
                    }.distinct()
                    val pitchLabels = semanticScore.measures
                        .flatMap { it.events }
                        .filterIsInstance<SemanticChord>()
                        .flatMap { it.notes }
                        .mapNotNull { note ->
                            val pitch = note.pitch ?: return@mapNotNull null
                            val bounds = note.sourceRefs.firstNotNullOfOrNull { it.bounds }
                                ?: return@mapNotNull null
                            DebugOverlayLabel(
                                bounds.left,
                                bounds.top,
                                "${pitch.step.name}${pitch.octave}",
                                Color.rgb(0, 70, 190)
                            )
                        }
                        .take(24)
                    previews[SmokeTestStage.SEMANTIC_SCORE_CONSTRUCTION] = listOf(
                        OmrSmokeTestPreview(
                            "measure boundaries + limited pitch labels",
                            OmrSmokeTestBitmaps.overlayThumbnail(
                                base.bitmap,
                                dewarped.width,
                                dewarped.height,
                                lines = measureLines,
                                labels = pitchLabels
                            )
                        )
                    )
                }
                lastCompletedStage = SmokeTestStage.SEMANTIC_SCORE_CONSTRUCTION
                if (stopAfter == SmokeTestStage.SEMANTIC_SCORE_CONSTRUCTION) {
                    return@withContext diagnosticResult(lastCompletedStage, timings, previews, details, null)
                }

                // STAGE 15 — Validated MusicXML 4.0 serialization and
                // app-private storage. Text summaries only; the XML body is
                // deliberately never included in diagnostics.
                val export = traceStage(SmokeTestStage.MUSICXML_EXPORT, timings) {
                    musicXmlExporter.export(
                        score = semanticScore,
                        outputName = "${File(imagePath).nameWithoutExtension}-smoke"
                    )
                }
                val editorRoundTrip = export.outputFilePath?.let { outputPath ->
                    val parsed = MusicXmlNotationParser.parse(MusicXmlParser.parseFile(File(outputPath)))
                    val notation = NotationLayoutEngine.layout(parsed)
                    EditorRoundTripCounts(
                        parsedMeasures = parsed.statistics.measureCount,
                        parsedBarlines = parsed.statistics.explicitBarlineCount,
                        parsedBarlineLocations = parsed.statistics.explicitBarlineLocations,
                        renderedMeasures = notation.renderedMeasureCount
                    )
                }
                details[SmokeTestStage.MUSICXML_EXPORT] = listOf(
                    "export success=${export.success}",
                    "output path=${export.outputFilePath ?: "<none>"}",
                    "file size=${export.fileSizeBytes} bytes",
                    "measures exported=${export.exportedMeasureCount}",
                    "barlines exported=${export.exportedBarlineCount}",
                    "barline locations=${export.exportedBarlineLocations}",
                    "notes exported=${export.exportedNoteCount}",
                    "chords exported=${export.exportedChordCount}",
                    "rests exported=${export.exportedRestCount}",
                    "unresolved events omitted=${export.omittedUnresolvedEventCount}",
                    "warning count=${export.warnings.size}",
                    "validation result=${export.validationStatus}",
                    "failure=${export.failureMessage ?: "<none>"}",
                    "Editor parsed measures=${editorRoundTrip?.parsedMeasures ?: 0}",
                    "Editor parsed barlines=${editorRoundTrip?.parsedBarlines ?: 0} " +
                        "locations=${editorRoundTrip?.parsedBarlineLocations.orEmpty()}",
                    "Editor rendered measures=${editorRoundTrip?.renderedMeasures ?: 0}"
                )
                val resolvedRhythmCount = rhythmResult.noteGroups.count { it.isResolved() } +
                    rhythmResult.rests.count { it.isResolved() }
                val unresolvedRhythmCount = rhythmResult.noteGroups.size + rhythmResult.rests.size -
                    resolvedRhythmCount
                val semanticChordLocationByNoteId = semanticScore.measures
                    .flatMap { measure ->
                        measure.events.filterIsInstance<SemanticChord>().flatMap { chord ->
                            chord.notes.map { note -> note.id to (chord to measure) }
                        }
                    }
                    .toMap()
                val rhythmByNoteheadId = rhythmResult.noteGroups
                    .flatMap { rhythm -> rhythm.noteheads.map { note -> note.id to rhythm } }
                    .toMap()
                val interpretedNoteEvents = noteheads.map { notehead ->
                    val noteId = "note-${notehead.id}"
                    val chordLocation = semanticChordLocationByNoteId[noteId]
                    val chord = chordLocation?.first
                    val measure = chordLocation?.second
                    val note = chord?.notes?.firstOrNull { it.id == noteId }
                    val rhythm = rhythmByNoteheadId[notehead.id]
                    val pitch = note?.pitch
                    val duration = chord?.duration
                    OmrInterpretedEventDetection(
                        eventId = noteId,
                        ownerEventId = chord?.id,
                        measureIndex = measure?.index,
                        kind = "NOTE",
                        x = (notehead.boundingBox.left + notehead.boundingBox.right) / 2,
                        y = (notehead.boundingBox.top + notehead.boundingBox.bottom) / 2,
                        group = notehead.staffAssignment.group,
                        track = notehead.staffAssignment.track,
                        staffStep = notehead.staffAssignment.staffLinePosition,
                        clef = note?.activeClef?.name,
                        accidental = pitch?.alteration?.name,
                        finalPitch = pitch?.let { "${it.step.name}${it.octave}" },
                        durationNumerator = duration?.numerator,
                        durationDenominator = duration?.denominator,
                        rhythmState = chord?.rhythmState?.name ?: "UNRESOLVED",
                        evidence = "head=${notehead.type};stem=${rhythm?.stemDirection};" +
                            "beam=${rhythm?.beamCount};flag=${rhythm?.flagCount};dots=${rhythm?.dotCount};" +
                            "reasons=${rhythm?.unresolvedReasons.orEmpty().joinToString(",")}",
                        confidence = null
                    )
                }
                val semanticRestLocationById = semanticScore.measures
                    .flatMap { measure ->
                        measure.events.filterIsInstance<SemanticRest>().map { rest ->
                            rest.id to (rest to measure)
                        }
                    }
                    .toMap()
                val interpretedRestEvents = rhythmResult.rests.map { rest ->
                    val eventId = "rest-${rest.restId}"
                    val restLocation = semanticRestLocationById[eventId]
                    val semanticRest = restLocation?.first
                    val duration = semanticRest?.duration
                    OmrInterpretedEventDetection(
                        eventId = eventId,
                        ownerEventId = semanticRest?.id,
                        measureIndex = restLocation?.second?.index,
                        kind = "REST",
                        x = (rest.boundingBox.left + rest.boundingBox.right) / 2,
                        y = (rest.boundingBox.top + rest.boundingBox.bottom) / 2,
                        group = rest.group,
                        track = rest.track,
                        durationNumerator = duration?.numerator,
                        durationDenominator = duration?.denominator,
                        rhythmState = semanticRest?.rhythmState?.name ?: "UNRESOLVED",
                        evidence = "label=${rest.label};placement=${rest.source.wholeHalfPlacement};" +
                            "dots=${rest.dotCount};reasons=${rest.unresolvedReasons.joinToString(",")}",
                        confidence = rest.source.placementConfidence
                            ?: rest.source.classificationMargin?.toDouble()
                    )
                }
                val accuracyReport = OmrAccuracyDiagnosticReport(
                    pageName = File(imagePath).name,
                    inputResolution = "${inputWidth}x$inputHeight",
                    inputOrientation = inputOrientation,
                    canonicalResolution = "${canonicalWidth}x$canonicalHeight",
                    executionProfile = OmrRuntimeTuning.executionProfile.name,
                    inferenceBatchSize = OmrRuntimeTuning.inferenceBatchSize,
                    tileCounts = tileCounts.mapKeys { it.key.name }.toSortedMap(),
                    modelInferenceTimingsMs = mapOf(
                        OmrModelSpec.STAFF_AND_SYMBOLS.name to durationMs(timings, SmokeTestStage.MODEL1_INFERENCE),
                        OmrModelSpec.SYMBOL_DETAIL.name to durationMs(timings, SmokeTestStage.MODEL2_INFERENCE)
                    ),
                    staffSystemCount = gridResult.validatedGrid.flatten()
                        .map { it.group }
                        .distinct()
                        .size,
                    staffCount = gridResult.validatedGrid.flatten()
                        .map { it.group to it.track }
                        .distinct()
                        .size,
                    trackVoteRawBarlineCount = gridResult.diagnostics.rawHoughLines.size,
                    trackVoteAcceptedBarlineCount = gridResult.diagnostics.acceptedHoughLines.size,
                    musicalBarlineFilterCounts = musicalBarlineFilterCounts(musicalBarlineDiagnostics),
                    musicalBarlineCount = symbolResult.barlines.size,
                    barlineXsBySystem = barlineXsBySystem,
                    barlineDetections = symbolResult.barlines.map {
                        OmrLocatedDetection(
                            x = (it.boundingBox.left + it.boundingBox.right) / 2,
                            y = (it.boundingBox.top + it.boundingBox.bottom) / 2,
                            label = "barline",
                            group = it.group,
                            confidence = it.confidence
                        )
                    },
                    noteheadCount = noteheads.size,
                    noteheadDetections = noteheads.map {
                        OmrLocatedDetection(
                            x = (it.boundingBox.left + it.boundingBox.right) / 2,
                            y = (it.boundingBox.top + it.boundingBox.bottom) / 2,
                            label = it.type.name,
                            group = it.staffAssignment.group,
                            track = it.staffAssignment.track
                        )
                    },
                    groupedNoteChordCount = chords.size,
                    clefCounts = symbolResult.clefs.groupingBy { it.label.name }.eachCount().toSortedMap(),
                    accidentalCounts = symbolResult.accidentals.groupingBy { it.label.name }.eachCount().toSortedMap(),
                    restCounts = symbolResult.rests.groupingBy { it.label.name }.eachCount().toSortedMap(),
                    restDetections = symbolResult.rests.map {
                        OmrLocatedDetection(
                            x = (it.boundingBox.left + it.boundingBox.right) / 2,
                            y = (it.boundingBox.top + it.boundingBox.bottom) / 2,
                            label = when (it.wholeHalfPlacement) {
                                RestWholeHalfPlacement.WHOLE -> "WHOLE"
                                RestWholeHalfPlacement.HALF -> "HALF"
                                else -> it.label.name
                            },
                            group = it.assignment.group,
                            track = it.assignment.track,
                            confidence = it.placementConfidence
                                ?: it.classificationMargin?.toDouble()
                        )
                    },
                    restCandidateFilterCounts = restCandidateFilterCounts(symbolResult.restDiagnostics),
                    restRejectedDetections = symbolResult.restDiagnostics?.rejectedReasons?.map { (box, reason) ->
                        val x = (box.left + box.right) / 2
                        val y = (box.top + box.bottom) / 2
                        val assignment = StaffGeometryResolver.assignNote(
                            gridResult.validatedGrid,
                            x,
                            y
                        ).staff
                        OmrLocatedDetection(
                            x = x,
                            y = y,
                            label = reason,
                            group = assignment.group,
                            track = assignment.track
                        )
                    }.orEmpty(),
                    correctlyTypedRestCount = rhythmResult.rests.count { it.baseDuration != null },
                    interpretedEvents = interpretedNoteEvents + interpretedRestEvents,
                    rhythmResolvedCount = resolvedRhythmCount,
                    rhythmUnresolvedCount = unresolvedRhythmCount,
                    semanticNoteCount = semanticSummary.notes,
                    semanticMeasuresBySystem = semanticMeasuresBySystem,
                    unresolvedSemanticEventCount = semanticSummary.unresolvedEvents,
                    omittedUnresolvedEventCount = export.omittedUnresolvedEventCount,
                    musicXmlMeasureCount = export.exportedMeasureCount,
                    musicXmlBarlineCount = export.exportedBarlineCount,
                    musicXmlBarlineLocations = export.exportedBarlineLocations,
                    editorParsedMeasureCount = editorRoundTrip?.parsedMeasures ?: 0,
                    editorRenderedMeasureCount = editorRoundTrip?.renderedMeasures ?: 0,
                    rhythmicallyValidMeasureCount = null,
                    musicXmlExportWarnings = export.warnings.map { "${it.code}: ${it.message}" }
                )
                Log.d(TAG, "[OMR_ACCURACY] $accuracyReport")
                lastCompletedStage = SmokeTestStage.MUSICXML_EXPORT
                diagnosticResult(
                    lastCompletedStage,
                    timings,
                    previews,
                    details,
                    errorMessage = null,
                    musicXmlOutputPath = export.outputFilePath,
                    accuracyReport = accuracyReport
                )
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

    private fun rhythmSummary(result: RhythmExtractionResult): List<String> {
        val unresolvedNotes = result.noteGroups.filterNot { it.isResolved() }
        val unresolvedRests = result.rests.filterNot { it.isResolved() }
        val assignedStems = result.noteGroups.count {
            it.stemAssociation.status == StemAssociationStatus.ASSIGNED
        }
        return listOf(
            "total note groups=${result.noteGroups.size}",
            "groups with assigned stems=$assignedStems",
            "beam-count distribution=${distribution(result.noteGroups) { it.beamCount?.toString() }}",
            "flag-count distribution=${distribution(result.noteGroups) { it.flagCount?.toString() }}",
            "dotted groups=${result.noteGroups.count { (it.dotCount ?: 0) > 0 }}",
            "inferred-duration distribution=${distribution(result.noteGroups) { it.baseDuration?.name }}",
            "unresolved rhythm count=${unresolvedNotes.size}",
            "unresolved rhythm reasons=${reasonDistribution(unresolvedNotes.flatMap { it.unresolvedReasons })}",
            "classified rests=${result.rests.size}",
            "rest-duration distribution=${distribution(result.rests) { it.baseDuration?.name }}",
            "dotted rests=${result.rests.count { it.dotCount == 1 }}",
            "unresolved rest rhythm count=${unresolvedRests.size}",
            "unresolved rest rhythm reasons=${reasonDistribution(unresolvedRests.flatMap { it.unresolvedReasons })}"
        )
    }

    private fun <T> distribution(
        values: List<T>,
        key: (T) -> String?
    ): Map<String, Int> = values
        .groupingBy { key(it) ?: UNRESOLVED_SUMMARY_KEY }
        .eachCount()
        .toSortedMap()

    private fun reasonDistribution(
        reasons: List<RhythmUnresolvedReason>
    ): Map<String, Int> = reasons
        .groupingBy { it.name }
        .eachCount()
        .toSortedMap()

    private fun RhythmCandidate.isResolved(): Boolean =
        resolutionState == RhythmResolutionState.RESOLVED

    private fun RestRhythmResult.isResolved(): Boolean =
        resolutionState == RhythmResolutionState.RESOLVED

    /** Number of tiles [spec] will require for a `canonicalWidth`x`canonicalHeight` page — pure math, no allocation. */
    private fun tileCountFor(
        spec: OmrModelSpec,
        canonicalWidth: Int,
        canonicalHeight: Int,
        stepSize: Int
    ): Int {
        val paddedWidth = maxOf(canonicalWidth, spec.windowSize)
        val paddedHeight = maxOf(canonicalHeight, spec.windowSize)
        return SlidingWindowTiler.computeOrigins(
            paddedWidth, paddedHeight, spec.windowSize, stepSize
        ).size
    }

    /** Byte size of the largest single output tensor [spec] will ever produce in one batch (FLOAT32). */
    private fun largestBatchOutputTensorBytes(spec: OmrModelSpec): Long =
        OmrRuntimeTuning.inferenceBatchSize.toLong() *
                spec.windowSize.toLong() * spec.windowSize.toLong() *
                spec.outputChannels.toLong() * Float.SIZE_BYTES

    private fun diagnosticResult(
        lastCompletedStage: SmokeTestStage?,
        timings: List<OmrSmokeTestStageTiming>,
        previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>,
        details: Map<SmokeTestStage, List<String>>,
        errorMessage: String?,
        musicXmlOutputPath: String? = null,
        accuracyReport: OmrAccuracyDiagnosticReport? = null
    ): OmrSmokeTestDiagnosticResult {
        val peakUsedMb = timings.maxOfOrNull { it.memoryAfter.javaUsedMb + it.memoryAfter.nativeUsedMb } ?: 0L
        Log.d(
            TAG,
            "[OMR_SMOKE] PEAK (Java+Native) usedMB=$peakUsedMb across ${timings.size} completed stage(s); " +
                    "lastCompletedStage=$lastCompletedStage"
        )
        val result = OmrSmokeTestDiagnosticResult(
            lastCompletedStage = lastCompletedStage,
            stageDurations = timings,
            previews = previews,
            stageDetails = details,
            errorMessage = errorMessage,
            musicXmlOutputPath = musicXmlOutputPath,
            accuracyReport = accuracyReport
        )
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!isDebuggable) return result
        return try {
            val bundle = OmrDebugBundleWriter.write(context, result)
            Log.d(TAG, "[OMR_SMOKE] Debug bundle=${bundle.absolutePath}")
            result.copy(debugBundlePath = bundle.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "[OMR_SMOKE] Could not persist debug bundle", t)
            result
        }
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

    private fun reusedDewarpedImagePreview(
        previews: Map<SmokeTestStage, List<OmrSmokeTestPreview>>
    ): OmrSmokeTestPreview? =
        previews[SmokeTestStage.DEWARPING]?.firstOrNull { it.label == "dewarpedImage" }

    private fun musicalBarlineFilterCounts(
        diagnostics: MusicalBarlineDiagnostics?
    ): Map<String, Int> = linkedMapOf(
        "overlapPixels" to (diagnostics?.selectedOverlapPixelCount ?: 0),
        "rawHough" to diagnostics?.rawHoughLines.orEmpty().size,
        "horizontal" to diagnostics?.horizontallyAcceptedBoxes.orEmpty().size,
        "merged" to diagnostics?.mergedBoxes.orEmpty().size,
        "angle" to diagnostics?.angleAcceptedBoxes.orEmpty().size,
        "consolidated" to diagnostics?.consolidatedBoxes.orEmpty().size,
        "minHeight" to diagnostics?.heightAcceptedBoxes.orEmpty().size,
        "structural" to diagnostics?.structuralBoxes.orEmpty().size,
        "accepted" to diagnostics?.acceptedBoxes.orEmpty().size
    )

    private fun restCandidateFilterCounts(
        diagnostics: RestExtractionDiagnostics?
    ): Map<String, Int> {
        val counts = linkedMapOf(
            "initial" to diagnostics?.initialBoxes.orEmpty().size,
            "nearbyMerged" to diagnostics?.nearbyMergedBoxes.orEmpty().size,
            "overlapFiltered" to diagnostics?.overlapFilteredBoxes.orEmpty().size,
            "accepted" to diagnostics?.acceptedBoxes.orEmpty().size
        )
        diagnostics?.rejectedReasons?.values
            ?.groupingBy { it }
            ?.eachCount()
            ?.toSortedMap()
            ?.forEach { (reason, count) -> counts["rejected:$reason"] = count }
        return counts
    }

    private fun durationMs(
        timings: List<OmrSmokeTestStageTiming>,
        stage: SmokeTestStage
    ): Long = timings.firstOrNull { it.stage == stage }?.durationMs ?: 0L

    private fun describeExifOrientation(imagePath: String): String = runCatching {
        when (ExifInterface(imagePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )) {
            ExifInterface.ORIENTATION_NORMAL -> "normal"
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "flip-horizontal"
            ExifInterface.ORIENTATION_ROTATE_180 -> "rotate-180"
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> "flip-vertical"
            ExifInterface.ORIENTATION_TRANSPOSE -> "transpose"
            ExifInterface.ORIENTATION_ROTATE_90 -> "rotate-90"
            ExifInterface.ORIENTATION_TRANSVERSE -> "transverse"
            ExifInterface.ORIENTATION_ROTATE_270 -> "rotate-270"
            else -> "undefined"
        }
    }.getOrElse { "unreadable:${it::class.java.simpleName}" }

    private data class EditorRoundTripCounts(
        val parsedMeasures: Int,
        val parsedBarlines: Int,
        val parsedBarlineLocations: List<String>,
        val renderedMeasures: Int
    )

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
