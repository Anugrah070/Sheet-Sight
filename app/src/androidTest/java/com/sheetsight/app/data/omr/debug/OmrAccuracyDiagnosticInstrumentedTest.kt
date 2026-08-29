package com.sheetsight.app.data.omr.debug

import ai.onnxruntime.OrtEnvironment
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheetsight.app.data.local.ScoreFileStorage
import com.sheetsight.app.data.omr.inference.OmrExecutionProfile
import com.sheetsight.app.data.omr.inference.OmrRuntimeTuning
import com.sheetsight.app.data.omr.inference.OrtSessionProvider
import com.sheetsight.app.data.omr.inference.TileInferenceRunner
import com.sheetsight.app.data.omr.musicxml.MusicXmlExporter
import com.sheetsight.app.data.omr.preprocessing.OmrTensorFactory
import com.sheetsight.app.data.omr.symbol.ClefAccidentalExtractor
import com.sheetsight.app.data.omr.symbol.OnnxSvmClassifierBackend
import com.sheetsight.app.data.omr.symbol.RestExtractor
import com.sheetsight.app.data.omr.symbol.SymbolClassifierLoader
import com.sheetsight.app.data.omr.symbol.SymbolExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs a small fixed list of app-private representative pages. An instrumentation
 * argument can replace the defaults when a developer deliberately changes the corpus.
 *
 * Example instrumentation argument:
 * `-e omrAccuracyPages page-a.png,page-b.jpg,page-c.png`.
 * Ordinary CI runs skip this device-data test when the argument is absent.
 */
@RunWith(AndroidJUnit4::class)
class OmrAccuracyDiagnosticInstrumentedTest {

    @Test
    fun fixedRepresentativePagesReportCompleteMeasureRoundTrip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requestedNames = InstrumentationRegistry.getArguments().getString(ARGUMENT_PAGES)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf(List<String>::isNotEmpty)
            ?: DEFAULT_REPRESENTATIVE_PAGES
        val inferenceStepSize = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_STEP_SIZE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val targetPixels = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_TARGET_PIXELS)
            ?.toIntOrNull()
            ?: com.sheetsight.app.data.omr.preprocessing.CanonicalImageResizer.DEFAULT_TARGET_PIXELS
        val legacyNonOverlap = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_LEGACY_NON_OVERLAP)
            ?.toBooleanStrictOrNull()
            ?: false
        val exportDebugBundles = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_EXPORT_DEBUG_BUNDLES)
            ?.toBooleanStrictOrNull()
            ?: false
        val executionProfile = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_EXECUTION_PROFILE)
            ?.let { value ->
                OmrExecutionProfile.entries.firstOrNull {
                    it.name.equals(value, ignoreCase = true)
                }
            }
            ?: OmrRuntimeTuning.executionProfile
        OmrRuntimeTuning.selectExecutionProfile(executionProfile)
        val inferenceBatchSize = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_BATCH_SIZE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: OmrRuntimeTuning.inferenceBatchSize
        OmrRuntimeTuning.selectInferenceBatchSize(inferenceBatchSize)
        val context = instrumentation.targetContext.applicationContext
        val pageFiles = requestedNames.map { File(context.filesDir, "scores/$it") }
        pageFiles.forEach { page ->
            assumeTrue("Representative page is missing: ${page.absolutePath}", page.isFile)
        }

        val environment = OrtEnvironment.getEnvironment()
        val sessions = OrtSessionProvider(environment, context)
        val classifierLoader = SymbolClassifierLoader(OnnxSvmClassifierBackend(environment, sessions))
        val runner = OmrSmokeTestRunner(
            tileInferenceRunner = TileInferenceRunner(sessions, OmrTensorFactory(environment)),
            symbolExtractor = SymbolExtractor(
                ClefAccidentalExtractor(classifierLoader),
                RestExtractor(classifierLoader)
            ),
            musicXmlExporter = MusicXmlExporter(ScoreFileStorage(context)),
            context = context,
            defaultDispatcher = Dispatchers.Default
        )

        pageFiles.forEach { page ->
            instrumentation.sendStatus(2, Bundle().apply {
                putString(
                    "stream",
                    "OMR_ACCURACY starting page=${page.name} profile=$executionProfile " +
                        "batchSize=$inferenceBatchSize\n"
                )
            })
            val diagnostic = runner.run(
                page.absolutePath,
                SmokeTestStage.MUSICXML_EXPORT,
                inferenceStepSize = inferenceStepSize,
                legacyNonOverlappingStride = legacyNonOverlap,
                inferenceTargetPixels = targetPixels
            )
            try {
                assertNull(diagnostic.errorMessage)
                val report = requireNotNull(diagnostic.accuracyReport)
                val semanticMeasureCount = report.semanticMeasuresBySystem.values.sumOf { it.size }
                assertEquals(semanticMeasureCount, report.musicXmlMeasureCount)
                assertEquals(report.musicXmlMeasureCount, report.editorParsedMeasureCount)
                assertEquals(report.musicXmlMeasureCount, report.editorRenderedMeasureCount)
                assertEquals(report.musicXmlBarlineCount, report.musicXmlBarlineLocations.size)
                val summary = report.compactSummary()
                Log.i(TAG, summary)
                val peakUsedMb = diagnostic.stageDurations.maxOfOrNull {
                    it.memoryAfter.javaUsedMb + it.memoryAfter.nativeUsedMb
                } ?: 0L
                val exportedBundle = if (exportDebugBundles) {
                    diagnostic.debugBundlePath?.let { sourcePath ->
                        val exportDirectory = requireNotNull(context.getExternalFilesDir("omr-debug"))
                            .apply { mkdirs() }
                        File(sourcePath).copyTo(
                            File(
                                exportDirectory,
                                "${page.nameWithoutExtension}-${targetPixels}-${System.currentTimeMillis()}.zip"
                            ),
                            overwrite = true
                        )
                    }
                } else {
                    null
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString(
                        "stream",
                        "OMR_ACCURACY completed $summary peakUsedMb=$peakUsedMb " +
                            "bundle=${exportedBundle?.absolutePath ?: "not-exported"}\n"
                    )
                })
            } finally {
                diagnostic.previews.values.flatten().forEach { it.bitmap.recycle() }
            }
        }
    }

    private companion object {
        const val TAG = "OmrAccuracySuite"
        const val ARGUMENT_PAGES = "omrAccuracyPages"
        const val ARGUMENT_STEP_SIZE = "omrAccuracyStepSize"
        const val ARGUMENT_TARGET_PIXELS = "omrAccuracyTargetPixels"
        const val ARGUMENT_LEGACY_NON_OVERLAP = "omrAccuracyLegacyNoOverlap"
        const val ARGUMENT_EXPORT_DEBUG_BUNDLES = "omrAccuracyExportBundles"
        const val ARGUMENT_EXECUTION_PROFILE = "omrAccuracyExecutionProfile"
        const val ARGUMENT_BATCH_SIZE = "omrAccuracyBatchSize"
        val DEFAULT_REPRESENTATIVE_PAGES = listOf(
            "IMG_20260804_050838.png",
            "IMG_20260801_215451.jpg",
            "Screenshot_2026-03-31-15-45-23-66_439a3fec0400f8974d35eed09a31f914.jpg"
        )
    }
}

private fun OmrAccuracyDiagnosticReport.compactSummary(): String =
    "page=$pageName input=$inputResolution canonical=$canonicalResolution " +
        "profile=$executionProfile batch=$inferenceBatchSize tiles=$tileCounts modelMs=$modelInferenceTimingsMs " +
        "systems=$staffSystemCount staffs=$staffCount bars=$musicalBarlineCount " +
        "noteheads=$noteheadCount grouped=$groupedNoteChordCount rests=$restCounts " +
        "resolved=$rhythmResolvedCount unresolved=$rhythmUnresolvedCount " +
        "measures=$musicXmlMeasureCount rhythmicallyValid=$rhythmicallyValidMeasureCount " +
        "warnings=${musicXmlExportWarnings.size}"
