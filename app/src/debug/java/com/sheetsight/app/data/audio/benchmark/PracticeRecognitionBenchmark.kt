package com.sheetsight.app.data.audio.benchmark

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/** Human-facing dimensions required by the Phase 7.5A report. */
enum class BenchmarkRegister { LOW, MID, HIGH }
enum class BenchmarkAttack { NONE, VERY_SOFT, SOFT, NORMAL, STRONG }
enum class BenchmarkScenario {
    CORRECT_ISOLATED,
    WRONG_ISOLATED,
    NEIGHBOR_SEMITONE,
    OCTAVE_ERROR,
    VERY_SOFT_EXPECTED,
    STRONG_EXPECTED,
    REPEATED_RESTRIKES,
    REPEATED_SUSTAIN,
    LEGATO_TRANSITION,
    SUSTAIN_RESIDUAL,
    NOTE_AFTER_SILENCE,
    BACKGROUND_NOISE,
    SILENCE
}

enum class BenchmarkProvenance { MANUALLY_VERIFIED_RECORDING, AUTHOR_DEFINED_SYNTHETIC }

/** Capture-path facts retained with a manually verified local recording, never ordinary practice audio. */
data class BenchmarkRecordingProvenance(
    val deviceModel: String,
    val androidVersion: String,
    val requestedSampleRateHz: Int,
    val actualSampleRateHz: Int,
    val requestedAudioSource: String,
    val actualAudioSource: String,
    val unprocessedSupported: Boolean,
    val agcStatus: String,
    val noiseSuppressorStatus: String,
    val piano: String,
    val phonePlacement: String,
    val roomCondition: String,
    val recordedAtUtc: String,
    val routedAudioDevice: String,
    val bufferSizeFrames: Int
) {
    init {
        require(deviceModel.isNotBlank() && androidVersion.isNotBlank())
        require(requestedSampleRateHz > 0 && actualSampleRateHz > 0)
        require(requestedAudioSource.isNotBlank() && actualAudioSource.isNotBlank())
        require(agcStatus.isNotBlank() && noiseSuppressorStatus.isNotBlank())
        require(piano.isNotBlank() && phonePlacement.isNotBlank() && roomCondition.isNotBlank())
        require(recordedAtUtc.isNotBlank())
        require(routedAudioDevice.isNotBlank() && bufferSizeFrames > 0)
    }
}

data class BenchmarkLabel(
    val id: String,
    val scenario: BenchmarkScenario,
    val register: BenchmarkRegister,
    val attack: BenchmarkAttack,
    val expectedMidiSequence: List<Int>,
    /** One entry per expected score step; null means the step was intentionally not played. */
    val expectedOnsetsMillis: List<Long?>,
    val performedMidi: List<Int>,
    val provenance: BenchmarkProvenance,
    val recordingProvenance: BenchmarkRecordingProvenance? = null,
    val notes: String = ""
) {
    init {
        require(id.isNotBlank())
        require(expectedMidiSequence.isNotEmpty())
        require(expectedMidiSequence.size == expectedOnsetsMillis.size)
        require((expectedMidiSequence + performedMidi).all { it in 21..108 })
    }
}

data class BenchmarkClip(
    val label: BenchmarkLabel,
    val sampleRateHz: Int,
    val samples: FloatArray
) {
    init {
        require(sampleRateHz > 0 && samples.isNotEmpty())
    }

    val durationSeconds: Double get() = samples.size.toDouble() / sampleRateHz
}

data class BenchmarkScoreContext(
    val expectedMidi: Int,
    val previousMidi: Int?,
    val nextMidi: List<Int>
)

sealed interface BenchmarkDecision {
    data object NoEvidence : BenchmarkDecision
    data class Ambiguous(val expectedConfidence: Double, val competingConfidence: Double) : BenchmarkDecision
    data class AcceptedExpectedNote(val confidence: Double) : BenchmarkDecision
    data class WrongNote(val midi: Int, val confidence: Double) : BenchmarkDecision
}

/** Debug-only recognition seam. Production AudioPitchSource and PracticeEngine are untouched. */
interface ScoreConstrainedBenchmarkDetector {
    val id: String
    val frameSize: Int
    val hopSize: Int
    fun reset()
    fun analyze(frame: FloatArray, frameEndMillis: Long, context: BenchmarkScoreContext): BenchmarkDecision
}

data class BenchmarkAdvance(
    val stepIndex: Int,
    val midi: Int,
    val acceptedAtMillis: Long,
    val confidence: Double
)

data class ClipBenchmarkResult(
    val label: BenchmarkLabel,
    val advances: List<BenchmarkAdvance>,
    val wrongNotes: List<Pair<Long, Int>>,
    val ambiguousFrameCount: Int,
    val processingNanos: Long,
    val audioDurationSeconds: Double
) {
    val expectedEventCount: Int get() = label.expectedOnsetsMillis.count { it != null }
    val matchedExpectedCount: Int get() = advances.count { advance ->
        label.expectedOnsetsMillis.getOrNull(advance.stepIndex)?.let { onset ->
            advance.acceptedAtMillis >= onset - EARLY_ACCEPTANCE_TOLERANCE_MILLIS
        } == true
    }
    val falseAdvanceCount: Int get() = advances.size - matchedExpectedCount
    val latenciesMillis: List<Long> get() = advances.mapNotNull { advance ->
        label.expectedOnsetsMillis.getOrNull(advance.stepIndex)?.let { onset ->
            (advance.acceptedAtMillis - onset).takeIf { it >= -EARLY_ACCEPTANCE_TOLERANCE_MILLIS }
        }
    }
    val realTimeFactor: Double
        get() = if (audioDurationSeconds == 0.0) 0.0 else processingNanos / 1e9 / audioDurationSeconds

    private companion object {
        const val EARLY_ACCEPTANCE_TOLERANCE_MILLIS = 40L
    }
}

class PracticeRecognitionBenchmark {
    fun run(detector: ScoreConstrainedBenchmarkDetector, clip: BenchmarkClip): ClipBenchmarkResult {
        require(detector.frameSize <= clip.samples.size)
        require(clip.sampleRateHz == 22_050) {
            "Benchmark detectors currently require explicit 22,050 Hz fixtures; resample before loading."
        }
        detector.reset()
        val advances = mutableListOf<BenchmarkAdvance>()
        val wrong = mutableListOf<Pair<Long, Int>>()
        var ambiguous = 0
        var stepIndex = 0
        var start = 0
        val started = System.nanoTime()
        while (start + detector.frameSize <= clip.samples.size) {
            val frame = clip.samples.copyOfRange(start, start + detector.frameSize)
            if (stepIndex < clip.label.expectedMidiSequence.size) {
                val context = BenchmarkScoreContext(
                    expectedMidi = clip.label.expectedMidiSequence[stepIndex],
                    previousMidi = clip.label.expectedMidiSequence.getOrNull(stepIndex - 1),
                    nextMidi = clip.label.expectedMidiSequence.drop(stepIndex + 1).take(3)
                )
                val frameEndMillis = ((start + detector.frameSize) * 1_000L) / clip.sampleRateHz
                when (val decision = detector.analyze(frame, frameEndMillis, context)) {
                    is BenchmarkDecision.AcceptedExpectedNote -> {
                        advances += BenchmarkAdvance(
                            stepIndex = stepIndex,
                            midi = context.expectedMidi,
                            acceptedAtMillis = frameEndMillis,
                            confidence = decision.confidence
                        )
                        stepIndex++
                    }
                    is BenchmarkDecision.WrongNote -> wrong += frameEndMillis to decision.midi
                    is BenchmarkDecision.Ambiguous -> ambiguous++
                    BenchmarkDecision.NoEvidence -> Unit
                }
            }
            start += detector.hopSize
        }
        return ClipBenchmarkResult(
            label = clip.label,
            advances = advances,
            wrongNotes = wrong.distinct(),
            ambiguousFrameCount = ambiguous,
            processingNanos = System.nanoTime() - started,
            audioDurationSeconds = clip.durationSeconds
        )
    }
}

data class MetricSlice(
    val expectedRecall: Double?,
    val wrongNoteRejection: Double?,
    val falsePositiveRate: Double?,
    val medianLatencyMillis: Long?,
    val p95LatencyMillis: Long?
)

data class CandidateBenchmarkReport(
    val detectorId: String,
    val coldInitializationNanos: Long,
    val results: List<ClipBenchmarkResult>
) {
    val overall: MetricSlice get() = slice(results)
    val verySoftNoteRecall: Double? get() = recall(results.filter { it.label.attack == BenchmarkAttack.VERY_SOFT })
    val softNoteRecall: Double? get() = recall(results.filter { it.label.attack == BenchmarkAttack.SOFT })
    val combinedSoftNoteRecall: Double? get() = recall(
        results.filter { it.label.attack in setOf(BenchmarkAttack.VERY_SOFT, BenchmarkAttack.SOFT) }
    )
    val octaveConfusionRate: Double? get() = negativeFailureRate(
        results.filter { it.label.scenario == BenchmarkScenario.OCTAVE_ERROR }
    )
    val repeatedNoteErrorRate: Double? get() {
        val repeated = results.filter {
            it.label.scenario in setOf(BenchmarkScenario.REPEATED_RESTRIKES, BenchmarkScenario.REPEATED_SUSTAIN)
        }
        if (repeated.isEmpty()) return null
        return repeated.count { it.falseAdvanceCount > 0 || it.matchedExpectedCount != it.expectedEventCount }
            .toDouble() / repeated.size
    }
    val silenceFalsePositiveRate: Double? get() = negativeFailureRate(
        results.filter { it.label.scenario in setOf(BenchmarkScenario.SILENCE, BenchmarkScenario.BACKGROUND_NOISE) }
    )
    val residualNoteFalseAdvanceRate: Double? get() = negativeFailureRate(
        results.filter { it.label.scenario == BenchmarkScenario.SUSTAIN_RESIDUAL }
    )
    val sustainedProcessingMillisPerBlock: Double
        get() {
            val blocks = results.sumOf { result ->
                ((result.audioDurationSeconds * SAMPLE_RATE_FOR_BLOCK_ESTIMATE).roundToInt() / 1_024).coerceAtLeast(1)
            }
            return if (blocks == 0) 0.0 else results.sumOf { it.processingNanos }.toDouble() / blocks / 1e6
        }
    val realTimeFactor: Double
        get() = results.sumOf { it.processingNanos }.toDouble() / 1e9 /
            results.sumOf { it.audioDurationSeconds }.coerceAtLeast(1e-9)
    val byRegister: Map<BenchmarkRegister, MetricSlice>
        get() = BenchmarkRegister.entries.associateWith { register -> slice(results.filter { it.label.register == register }) }
    val byAttack: Map<BenchmarkAttack, MetricSlice>
        get() = BenchmarkAttack.entries.associateWith { attack -> slice(results.filter { it.label.attack == attack }) }

    private fun slice(selected: List<ClipBenchmarkResult>): MetricSlice = MetricSlice(
        expectedRecall = recall(selected),
        wrongNoteRejection = rejection(selected.filter { it.label.performedMidi.isNotEmpty() && it.expectedEventCount == 0 }),
        falsePositiveRate = negativeFailureRate(selected),
        medianLatencyMillis = percentile(selected.flatMap { it.latenciesMillis }, 0.50),
        p95LatencyMillis = percentile(selected.flatMap { it.latenciesMillis }, 0.95)
    )

    private fun recall(selected: List<ClipBenchmarkResult>): Double? {
        val total = selected.sumOf { it.expectedEventCount }
        return if (total == 0) null else selected.sumOf { it.matchedExpectedCount }.toDouble() / total
    }

    private fun rejection(selected: List<ClipBenchmarkResult>): Double? =
        if (selected.isEmpty()) null else selected.count { it.advances.isEmpty() }.toDouble() / selected.size

    private fun negativeFailureRate(selected: List<ClipBenchmarkResult>): Double? {
        val negatives = selected.sumOf { result -> result.label.expectedOnsetsMillis.count { it == null } }
        return if (negatives == 0) null else selected.sumOf { it.falseAdvanceCount }.toDouble() / negatives
    }

    private fun percentile(values: List<Long>, quantile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.lastIndex * quantile).roundToInt()).coerceIn(0, sorted.lastIndex)]
    }

    private companion object {
        const val SAMPLE_RATE_FOR_BLOCK_ESTIMATE = 22_050
    }
}

data class BenchmarkCandidate(
    val id: String,
    val factory: () -> ScoreConstrainedBenchmarkDetector
)

class PracticeRecognitionBenchmarkSuite(
    private val benchmark: PracticeRecognitionBenchmark = PracticeRecognitionBenchmark()
) {
    fun run(candidate: BenchmarkCandidate, clips: List<BenchmarkClip>): CandidateBenchmarkReport {
        require(clips.isNotEmpty())
        val start = System.nanoTime()
        val detector = candidate.factory()
        val cold = System.nanoTime() - start
        require(detector.id == candidate.id)
        return CandidateBenchmarkReport(candidate.id, cold, clips.map { benchmark.run(detector, it) })
    }
}

data class BenchmarkManifestEntry(
    val wavRelativePath: String,
    val label: BenchmarkLabel
)

/**
 * Reads the tracked TSV contract for explicit local recordings. Acoustic rows are rejected unless
 * a human has verified them and identified the reviewer; this prevents placeholders from becoming
 * reported recognition evidence by accident.
 */
object BenchmarkManifestReader {
    fun read(input: InputStream, requireManualVerification: Boolean = true): List<BenchmarkManifestEntry> {
        val lines = input.bufferedReader().readLines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "Benchmark manifest has no data rows" }
        val header = lines.first().trimEnd('\r').split('\t')
        val required = setOf(
            "id", "wav", "scenario", "register", "attack", "expected_midi_sequence",
            "expected_onsets_ms", "performed_midi", "manual_verified", "reviewer", "notes",
            "device_model", "android_version", "requested_sample_rate_hz", "actual_sample_rate_hz",
            "requested_audio_source", "actual_audio_source", "unprocessed_supported", "agc_status",
            "noise_suppressor_status", "piano", "phone_placement", "room_condition", "recorded_at_utc",
            "routed_audio_device", "buffer_size_frames"
        )
        require(header.toSet().containsAll(required)) { "Benchmark manifest is missing required columns" }
        val index = header.withIndex().associate { it.value to it.index }
        fun List<String>.column(name: String): String = getOrElse(index.getValue(name)) { "" }.trim()
        fun midiList(value: String): List<Int> = value.split(',').mapNotNull { token ->
            token.trim().takeUnless { it.isEmpty() || it == "-" }?.toInt()
        }
        fun onsetList(value: String): List<Long?> = value.split(',').map { token ->
            token.trim().takeUnless { it.isEmpty() || it == "-" }?.toLong()
        }

        val entries = lines.drop(1).map { line ->
            val cells = line.trimEnd('\r').split('\t')
            val verificationValue = cells.column("manual_verified")
            require(verificationValue.equals("true", true) || verificationValue.equals("false", true)) {
                "${cells.column("id")}: manual_verified must be true or false"
            }
            val verified = verificationValue.toBoolean()
            val reviewer = cells.column("reviewer")
            if (requireManualVerification) {
                require(verified) { "${cells.column("id")}: acoustic benchmark row is not manually verified" }
                require(reviewer.isNotBlank()) { "${cells.column("id")}: verified row has no reviewer" }
            }
            val recordingProvenance = if (verified) {
                BenchmarkRecordingProvenance(
                    deviceModel = cells.column("device_model"),
                    androidVersion = cells.column("android_version"),
                    requestedSampleRateHz = cells.column("requested_sample_rate_hz").toInt(),
                    actualSampleRateHz = cells.column("actual_sample_rate_hz").toInt(),
                    requestedAudioSource = cells.column("requested_audio_source"),
                    actualAudioSource = cells.column("actual_audio_source"),
                    unprocessedSupported = cells.column("unprocessed_supported").let { value ->
                        require(value.equals("true", true) || value.equals("false", true)) {
                            "${cells.column("id")}: unprocessed_supported must be true or false"
                        }
                        value.toBoolean()
                    },
                    agcStatus = cells.column("agc_status"),
                    noiseSuppressorStatus = cells.column("noise_suppressor_status"),
                    piano = cells.column("piano"),
                    phonePlacement = cells.column("phone_placement"),
                    roomCondition = cells.column("room_condition"),
                    recordedAtUtc = cells.column("recorded_at_utc"),
                    routedAudioDevice = cells.column("routed_audio_device"),
                    bufferSizeFrames = cells.column("buffer_size_frames").toInt()
                )
            } else null
            BenchmarkManifestEntry(
                wavRelativePath = cells.column("wav").also { require(it.isNotBlank()) },
                label = BenchmarkLabel(
                    id = cells.column("id"),
                    scenario = BenchmarkScenario.valueOf(cells.column("scenario")),
                    register = BenchmarkRegister.valueOf(cells.column("register")),
                    attack = BenchmarkAttack.valueOf(cells.column("attack")),
                    expectedMidiSequence = midiList(cells.column("expected_midi_sequence")),
                    expectedOnsetsMillis = onsetList(cells.column("expected_onsets_ms")),
                    performedMidi = midiList(cells.column("performed_midi")),
                    provenance = BenchmarkProvenance.MANUALLY_VERIFIED_RECORDING,
                    recordingProvenance = recordingProvenance,
                    notes = cells.column("notes")
                )
            )
        }
        require(entries.map { it.label.id }.distinct().size == entries.size) {
            "Benchmark manifest contains duplicate recording IDs"
        }
        require(entries.map { it.wavRelativePath.lowercase() }.distinct().size == entries.size) {
            "Benchmark manifest contains duplicate WAV paths"
        }
        return entries
    }
}

object LocalBenchmarkDatasetLoader {
    fun load(directory: File, manifest: InputStream): List<BenchmarkClip> {
        val root = directory.canonicalFile
        return BenchmarkManifestReader.read(manifest).map { entry ->
            val wav = File(root, entry.wavRelativePath).canonicalFile
            require(wav.toPath().startsWith(root.toPath())) { "Fixture path escapes benchmark directory" }
            wav.inputStream().use { BenchmarkWaveReader.read(it, entry.label) }
        }
    }
}

/** Minimal local-fixture loader: PCM, mono, 16-bit little-endian WAV only. */
object BenchmarkWaveReader {
    fun read(input: InputStream, label: BenchmarkLabel): BenchmarkClip {
        val stream = BufferedInputStream(input)
        fun bytes(count: Int): ByteArray = ByteArray(count).also { target ->
            var offset = 0
            while (offset < count) {
                val read = stream.read(target, offset, count - offset)
                require(read > 0) { "Unexpected end of WAV" }
                offset += read
            }
        }
        fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
        fun i32(data: ByteArray, at: Int) = (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

        val header = bytes(12)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
        var sampleRate = 0
        var formatVerified = false
        var pcm: ByteArray? = null
        while (pcm == null) {
            val chunk = bytes(8)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = i32(chunk, 4)
            require(size >= 0)
            val payload = bytes(size)
            if (size % 2 != 0) bytes(1)
            when (id) {
                "fmt " -> {
                    require(size >= 16)
                    require(u16(payload, 0) == 1) { "Only PCM WAV is supported" }
                    require(u16(payload, 2) == 1) { "Only mono WAV is supported" }
                    sampleRate = i32(payload, 4)
                    require(u16(payload, 14) == 16) { "Only 16-bit PCM WAV is supported" }
                    formatVerified = true
                }
                "data" -> pcm = payload
            }
        }
        require(formatVerified && sampleRate > 0)
        val data = requireNotNull(pcm)
        require(data.size % 2 == 0)
        val samples = FloatArray(data.size / 2) { index ->
            val low = data[index * 2].toInt() and 0xff
            val high = data[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768f
        }
        return BenchmarkClip(label, sampleRate, samples)
    }
}

/** Writes deterministic debug fixtures only; production microphone audio never reaches this API. */
object BenchmarkWaveWriter {
    fun write(output: OutputStream, clip: BenchmarkClip) {
        val dataSize = clip.samples.size * 2
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun little16(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }
        fun little32(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            output.write((value ushr 16) and 0xff)
            output.write((value ushr 24) and 0xff)
        }
        ascii("RIFF")
        little32(36 + dataSize)
        ascii("WAVEfmt ")
        little32(16)
        little16(1)
        little16(1)
        little32(clip.sampleRateHz)
        little32(clip.sampleRateHz * 2)
        little16(2)
        little16(16)
        ascii("data")
        little32(dataSize)
        clip.samples.forEach { sample ->
            val encoded = (sample.coerceIn(-1f, 0.999969f) * 32_768f).roundToInt().coerceIn(-32_768, 32_767)
            little16(encoded and 0xffff)
        }
    }
}
