package com.sheetsight.app.data.omr.debug

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheetsight.app.data.local.ScoreFileStorage
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
            val diagnostic = runner.run(
                page.absolutePath,
                SmokeTestStage.MUSICXML_EXPORT,
                inferenceStepSize = inferenceStepSize
            )
            try {
                assertNull(diagnostic.errorMessage)
                val report = requireNotNull(diagnostic.accuracyReport)
                val semanticMeasureCount = report.semanticMeasuresBySystem.values.sumOf { it.size }
                assertEquals(semanticMeasureCount, report.musicXmlMeasureCount)
                assertEquals(report.musicXmlMeasureCount, report.editorParsedMeasureCount)
                assertEquals(report.musicXmlMeasureCount, report.editorRenderedMeasureCount)
                assertEquals(report.musicXmlBarlineCount, report.musicXmlBarlineLocations.size)
                Log.i(TAG, "page=${page.name} report=$report")
            } finally {
                diagnostic.previews.values.flatten().forEach { it.bitmap.recycle() }
            }
        }
    }

    private companion object {
        const val TAG = "OmrAccuracySuite"
        const val ARGUMENT_PAGES = "omrAccuracyPages"
        const val ARGUMENT_STEP_SIZE = "omrAccuracyStepSize"
        val DEFAULT_REPRESENTATIVE_PAGES = listOf(
            "IMG_20260804_050838.png",
            "IMG_20260801_215451.jpg",
            "Screenshot_2026-03-31-15-45-23-66_439a3fec0400f8974d35eed09a31f914.jpg"
        )
    }
}
