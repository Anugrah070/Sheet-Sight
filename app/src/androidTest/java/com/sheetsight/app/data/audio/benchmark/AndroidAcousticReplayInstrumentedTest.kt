package com.sheetsight.app.data.audio.benchmark

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Replays one manually verified acoustic corpus through A/B/C/D/F on an Android device. */
@RunWith(AndroidJUnit4::class)
class AndroidAcousticReplayInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun replayVerifiedAcousticCorpusAndWriteDeviceReport() {
        val root = requireNotNull(context.getExternalFilesDir("phase75b/replay")).canonicalFile
        val relativeManifest = requireNotNull(arguments.getString("manifest")) {
            "Pass -e manifest manifest.tsv; the path is relative to the app's phase75b/replay directory."
        }
        val manifest = File(root, relativeManifest).canonicalFile
        require(manifest.toPath().startsWith(root.toPath())) { "Manifest path escapes phase75b/replay" }
        require(manifest.isFile) { "Missing acoustic manifest: $manifest" }
        val clips = manifest.inputStream().use { LocalBenchmarkDatasetLoader.load(root, it) }
        require(clips.all { it.label.provenance == BenchmarkProvenance.MANUALLY_VERIFIED_RECORDING })
        require(clips.all { it.label.recordingProvenance != null })

        val candidates = listOf(
            BenchmarkCandidate(LegacyYinBenchmarkDetector.ID, ::LegacyYinBenchmarkDetector),
            BenchmarkCandidate(AdaptiveYinBenchmarkDetector.ID, ::AdaptiveYinBenchmarkDetector),
            BenchmarkCandidate(HarmonicBenchmarkDetector.ID, ::HarmonicBenchmarkDetector),
            BenchmarkCandidate(LogFrequencyBenchmarkDetector.ID, ::LogFrequencyBenchmarkDetector),
            BenchmarkCandidate(HybridBenchmarkDetector.ID, ::HybridBenchmarkDetector)
        )
        val thermalStart = thermalStatus()
        val batteryStart = batterySnapshot()
        val deviceReports = candidates.map { candidate ->
            val pssBefore = Debug.getPss()
            val cpuStart = android.os.Process.getElapsedCpuTime()
            val report = PracticeRecognitionBenchmarkSuite().run(candidate, clips)
            DeviceCandidateReport(
                report = report,
                cpuMillis = android.os.Process.getElapsedCpuTime() - cpuStart,
                pssBeforeKb = pssBefore,
                pssAfterKb = Debug.getPss()
            )
        }
        val reportFile = File(root, "android-benchmark-report.json")
        reportFile.writeText(
            jsonReport(
                clips = clips,
                reports = deviceReports,
                thermalStart = thermalStart,
                thermalEnd = thermalStatus(),
                batteryStart = batteryStart,
                batteryEnd = batterySnapshot()
            )
        )
        assertTrue(reportFile.length() > 0L)
        println("PHASE75B_ANDROID_REPORT=${reportFile.absolutePath}")
    }

    private data class DeviceCandidateReport(
        val report: CandidateBenchmarkReport,
        val cpuMillis: Long,
        val pssBeforeKb: Long,
        val pssAfterKb: Long
    )

    private data class BatterySnapshot(val percent: Int, val chargeCounterMicroAh: Int, val currentMicroA: Int)

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    } else null

    private fun batterySnapshot(): BatterySnapshot {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return BatterySnapshot(
            percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            chargeCounterMicroAh = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentMicroA = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        )
    }

    private fun jsonReport(
        clips: List<BenchmarkClip>,
        reports: List<DeviceCandidateReport>,
        thermalStart: Int?,
        thermalEnd: Int?,
        batteryStart: BatterySnapshot,
        batteryEnd: BatterySnapshot
    ): String = buildString {
        append("{\n")
        append("  \"evidence\": \"MANUALLY_VERIFIED_ACOUSTIC_RECORDINGS\",\n")
        append("  \"device\": \"").append(escape("${Build.MANUFACTURER} ${Build.MODEL}")).append("\",\n")
        append("  \"android\": \"").append(escape("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")).append("\",\n")
        append("  \"fixture_count\": ").append(clips.size).append(",\n")
        append("  \"audio_seconds\": ").append(format(clips.sumOf { it.durationSeconds })).append(",\n")
        append("  \"apk_bytes\": ").append(File(context.applicationInfo.sourceDir).length()).append(",\n")
        append("  \"thermal_status_start\": ").append(thermalStart ?: "null").append(",\n")
        append("  \"thermal_status_end\": ").append(thermalEnd ?: "null").append(",\n")
        append("  \"battery_start\": ").append(batteryStart.json()).append(",\n")
        append("  \"battery_end\": ").append(batteryEnd.json()).append(",\n")
        append("  \"candidates\": [\n")
        reports.forEachIndexed { index, device ->
            val report = device.report
            append("    {\n")
            append("      \"id\": \"").append(escape(report.detectorId)).append("\",\n")
            append("      \"expected_recall\": ").append(metric(report.overall.expectedRecall)).append(",\n")
            append("      \"wrong_note_rejection\": ").append(metric(report.overall.wrongNoteRejection)).append(",\n")
            append("      \"false_advance_rate\": ").append(metric(report.overall.falsePositiveRate)).append(",\n")
            append("      \"very_soft_recall\": ").append(metric(report.verySoftNoteRecall)).append(",\n")
            append("      \"soft_recall\": ").append(metric(report.softNoteRecall)).append(",\n")
            append("      \"octave_confusion_rate\": ").append(metric(report.octaveConfusionRate)).append(",\n")
            append("      \"repeated_note_error_rate\": ").append(metric(report.repeatedNoteErrorRate)).append(",\n")
            append("      \"residual_false_advance_rate\": ").append(metric(report.residualNoteFalseAdvanceRate)).append(",\n")
            append("      \"silence_false_advance_rate\": ").append(metric(report.silenceFalsePositiveRate)).append(",\n")
            append("      \"median_latency_ms\": ").append(report.overall.medianLatencyMillis ?: "null").append(",\n")
            append("      \"p95_latency_ms\": ").append(report.overall.p95LatencyMillis ?: "null").append(",\n")
            append("      \"cold_initialization_ms\": ").append(format(report.coldInitializationNanos / 1e6)).append(",\n")
            append("      \"processing_ms_per_1024_samples\": ").append(format(report.sustainedProcessingMillisPerBlock)).append(",\n")
            append("      \"real_time_factor\": ").append(format(report.realTimeFactor)).append(",\n")
            append("      \"cpu_ms\": ").append(device.cpuMillis).append(",\n")
            append("      \"pss_before_kb\": ").append(device.pssBeforeKb).append(",\n")
            append("      \"pss_after_kb\": ").append(device.pssAfterKb).append(",\n")
            append("      \"register_recall\": ").append(
                BenchmarkRegister.entries.joinToString(prefix = "{", postfix = "}") { register ->
                    "\"${register.name}\":${metric(report.byRegister.getValue(register).expectedRecall)}"
                }
            ).append(",\n")
            append("      \"dynamic_recall\": ").append(
                BenchmarkAttack.entries.joinToString(prefix = "{", postfix = "}") { attack ->
                    "\"${attack.name}\":${metric(report.byAttack.getValue(attack).expectedRecall)}"
                }
            ).append("\n")
            append("    }")
            if (index != reports.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private fun BatterySnapshot.json(): String =
        "{\"percent\":$percent,\"charge_counter_micro_ah\":$chargeCounterMicroAh,\"current_micro_a\":$currentMicroA}"

    private fun metric(value: Double?): String = value?.let(::format) ?: "null"
    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
