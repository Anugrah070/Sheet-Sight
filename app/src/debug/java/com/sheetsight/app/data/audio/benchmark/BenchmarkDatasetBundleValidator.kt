package com.sheetsight.app.data.audio.benchmark

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

data class BenchmarkBundleWavIntegrity(
    val id: String,
    val path: String,
    val sampleRateHz: Int,
    val sampleCount: Int,
    val clippedSampleCount: Int
)

data class BenchmarkBundleValidationReport(
    val recordingCount: Int,
    val wavs: List<BenchmarkBundleWavIntegrity>
)

/** Strict validator for a raw guided-capture ZIP before any row can be reviewed. */
object BenchmarkDatasetBundleValidator {
    private val provenanceColumns = setOf(
        "device_model",
        "android_version",
        "requested_sample_rate_hz",
        "actual_sample_rate_hz",
        "requested_audio_source",
        "actual_audio_source",
        "unprocessed_supported",
        "agc_status",
        "noise_suppressor_status",
        "piano",
        "phone_placement",
        "room_condition",
        "recorded_at_utc",
        "routed_audio_device",
        "buffer_size_frames"
    )

    fun validate(zipFile: File, expectedCaptureCount: Int? = null): BenchmarkBundleValidationReport {
        require(zipFile.isFile && zipFile.length() > 0L) { "Capture ZIP is missing or empty" }
        return ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            require(entries.isNotEmpty()) { "Capture ZIP has no files" }
            val normalizedNames = entries.map { safeRelativePath(it.name) }
            require(normalizedNames.map { it.lowercase(Locale.US) }.distinct().size == normalizedNames.size) {
                "Capture ZIP contains duplicate entry paths"
            }
            require(normalizedNames.count { it == MANIFEST_PATH } == 1) { "Capture ZIP must contain manifest.tsv" }
            require(normalizedNames.all { it == MANIFEST_PATH || it == README_PATH || it.isWavPath() }) {
                "Capture ZIP contains an unexpected file"
            }

            val manifestEntry = entries.single { safeRelativePath(it.name) == MANIFEST_PATH }
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val rawRows = rawManifestRows(manifestBytes)
            expectedCaptureCount?.let { expected ->
                require(rawRows.size == expected) {
                    "Capture count mismatch: expected $expected, found ${rawRows.size}"
                }
            }
            require(rawRows.map { it.getValue("id") }.distinct().size == rawRows.size) {
                "Capture manifest contains duplicate recording IDs"
            }
            require(rawRows.map { it.getValue("wav").lowercase(Locale.US) }.distinct().size == rawRows.size) {
                "Capture manifest contains duplicate WAV paths"
            }
            rawRows.forEach { row ->
                val id = row.getValue("id")
                require(row.getValue("manual_verified").equals("false", true)) {
                    "$id: an exported raw capture must remain manual_verified=false"
                }
                require(row.getValue("reviewer").isBlank()) {
                    "$id: an exported raw capture must not pre-populate a reviewer"
                }
                provenanceColumns.forEach { column ->
                    require(row.getValue(column).isNotBlank()) { "$id: missing provenance field $column" }
                }
                require(row.getValue("requested_sample_rate_hz").toInt() > 0)
                require(row.getValue("actual_sample_rate_hz").toInt() > 0)
                require(row.getValue("buffer_size_frames").toInt() > 0)
            }

            val parsed = BenchmarkManifestReader.read(
                ByteArrayInputStream(manifestBytes),
                requireManualVerification = false
            )
            val zipWavNames = normalizedNames.filter { it.isWavPath() }.toSet()
            val manifestWavNames = parsed.map { safeRelativePath(it.wavRelativePath) }.toSet()
            require(zipWavNames == manifestWavNames) {
                "Capture ZIP WAV entries do not exactly match manifest.tsv"
            }
            require(parsed.size == rawRows.size)

            val entryByName = entries.associateBy { safeRelativePath(it.name) }
            val integrity = parsed.map { manifest ->
                val wavEntry = requireNotNull(entryByName[safeRelativePath(manifest.wavRelativePath)])
                val clip = zip.getInputStream(wavEntry).use { BenchmarkWaveReader.read(it, manifest.label) }
                val raw = rawRows.single { it.getValue("id") == manifest.label.id }
                require(clip.sampleRateHz == raw.getValue("actual_sample_rate_hz").toInt()) {
                    "${manifest.label.id}: WAV sample rate disagrees with capture provenance"
                }
                val clipped = clip.samples.count { sample -> sample <= -1.0f || sample >= MAX_PCM16_FLOAT }
                require(clipped == 0) { "${manifest.label.id}: WAV contains clipped PCM samples" }
                BenchmarkBundleWavIntegrity(
                    id = manifest.label.id,
                    path = manifest.wavRelativePath,
                    sampleRateHz = clip.sampleRateHz,
                    sampleCount = clip.samples.size,
                    clippedSampleCount = clipped
                )
            }
            BenchmarkBundleValidationReport(integrity.size, integrity)
        }
    }

    private fun rawManifestRows(bytes: ByteArray): List<Map<String, String>> {
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }.toList()
        require(lines.size >= 2) { "Benchmark manifest has no data rows" }
        val header = lines.first().split('\t')
        val required = setOf(
            "id", "wav", "manual_verified", "reviewer", "scenario", "register", "attack",
            "expected_midi_sequence", "expected_onsets_ms", "performed_midi", "notes"
        ) + provenanceColumns
        require(header.toSet().containsAll(required)) { "Benchmark manifest is missing bundle columns" }
        return lines.drop(1).map { line ->
            val values = line.split('\t')
            header.withIndex().associate { (index, name) -> name to values.getOrElse(index) { "" }.trim() }
        }
    }

    private fun safeRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !DRIVE_PATH.containsMatchIn(normalized)) {
            "ZIP entry path must be relative: $path"
        }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "ZIP entry path escapes or is malformed: $path"
        }
        return normalized
    }

    private fun String.isWavPath(): Boolean = startsWith("local-recordings/") && endsWith(".wav", true)

    private const val MANIFEST_PATH = "manifest.tsv"
    private const val README_PATH = "README.txt"
    private const val MAX_PCM16_FLOAT = 32_767f / 32_768f
    private val DRIVE_PATH = Regex("^[A-Za-z]:")
}
