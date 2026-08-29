package com.sheetsight.app.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.sheetsight.app.data.audio.recognition.MpmPitchConfig
import com.sheetsight.app.data.audio.recognition.MpmPitchDetector
import android.os.Build
import androidx.core.content.ContextCompat
import com.sheetsight.app.di.IoDispatcher
import com.sheetsight.app.domain.practice.DetectedPitch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class DeveloperCaptureAudioSource(val displayName: String) {
    UNPROCESSED_PREFERRED("UNPROCESSED when available"),
    DEFAULT("Android DEFAULT")
}

data class DeveloperAudioCaptureProvenance(
    val deviceModel: String,
    val androidVersion: String,
    val requestedSampleRateHz: Int,
    val actualSampleRateHz: Int,
    val requestedAudioSource: String,
    val actualAudioSource: String,
    val unprocessedSupported: Boolean,
    val agcStatus: String,
    val noiseSuppressorStatus: String,
    val routedAudioDevice: String,
    val bufferSizeFrames: Int
)

data class DeveloperPianoCaptureFrame(
    val pcm: ShortArray,
    val rms: Double,
    val detectedPitch: DetectedPitch?,
    val timestampMillis: Long,
    val provenance: DeveloperAudioCaptureProvenance
)

/**
 * Developer-only raw microphone seam for the explicit guided benchmark recorder.
 * Merely listening emits PCM to memory; persistence occurs only after the developer records a
 * labeled take and explicitly chooses an export document.
 */
@Singleton
class DeveloperPianoCaptureRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val config = PitchDetectionConfig()
    private val collecting = AtomicBoolean(false)

    fun frames(source: DeveloperCaptureAudioSource): Flow<DeveloperPianoCaptureFrame> = flow {
        check(collecting.compareAndSet(false, true)) { "Developer microphone capture is already active." }
        var recorder: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Microphone permission is not granted.")
            }
            val minBufferBytes = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBufferBytes > 0) { "This device does not support the benchmark audio configuration." }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessedSupported =
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val requestedAndroidSource = when (source) {
                DeveloperCaptureAudioSource.UNPROCESSED_PREFERRED -> MediaRecorder.AudioSource.UNPROCESSED
                DeveloperCaptureAudioSource.DEFAULT -> MediaRecorder.AudioSource.DEFAULT
            }
            val configuredSource = if (
                requestedAndroidSource == MediaRecorder.AudioSource.UNPROCESSED && !unprocessedSupported
            ) MediaRecorder.AudioSource.DEFAULT else requestedAndroidSource
            val adaptiveFrameSize = 8_192
            val bufferBytes = maxOf(minBufferBytes, adaptiveFrameSize * Short.SIZE_BYTES * 2)
            val activeRecorder = AudioRecord.Builder()
                .setAudioSource(configuredSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
            recorder = activeRecorder
            check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }

            val agcStatus = effectStatus(
                AutomaticGainControl.isAvailable(),
                create = { AutomaticGainControl.create(activeRecorder.audioSessionId) }
            )
            val noiseSuppressorStatus = effectStatus(
                NoiseSuppressor.isAvailable(),
                create = { NoiseSuppressor.create(activeRecorder.audioSessionId) }
            )
            val detector = MpmPitchDetector(MpmPitchConfig(sampleRateHz = config.sampleRateHz))
            val hop = ShortArray(config.hopSize)
            val analysisFrame = FloatArray(adaptiveFrameSize)
            var samplesAvailable = 0
            activeRecorder.startRecording()
            check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
            val provenance = DeveloperAudioCaptureProvenance(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                requestedSampleRateHz = config.sampleRateHz,
                actualSampleRateHz = activeRecorder.sampleRate,
                requestedAudioSource = source.name,
                actualAudioSource = audioSourceName(activeRecorder.audioSource),
                unprocessedSupported = unprocessedSupported,
                agcStatus = agcStatus,
                noiseSuppressorStatus = noiseSuppressorStatus,
                routedAudioDevice = activeRecorder.routedDevice?.describe() ?: "UNKNOWN",
                bufferSizeFrames = activeRecorder.bufferSizeInFrames
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = activeRecorder.read(hop, 0, hop.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("Microphone read failed with AudioRecord code $read.")
                if (read == 0) continue
                val pcm = hop.copyOf(read)
                analysisFrame.copyInto(analysisFrame, destinationOffset = 0, startIndex = read)
                for (index in 0 until read) {
                    analysisFrame[analysisFrame.size - read + index] = pcm[index] / 32768f
                }
                samplesAvailable = (samplesAvailable + read).coerceAtMost(analysisFrame.size)
                val timestamp = System.nanoTime() / 1_000_000L
                val rms = sqrt(pcm.fold(0.0) { total, sample ->
                    val normalized = sample / 32768.0
                    total + normalized * normalized
                } / pcm.size)
                val pitch = if (samplesAvailable == analysisFrame.size) {
                    detector.analyze(analysisFrame, timestamp).detectedPitch
                } else null
                emit(DeveloperPianoCaptureFrame(pcm, rms, pitch, timestamp, provenance))
            }
        } finally {
            recorder?.let { active ->
                runCatching { if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop() }
                active.release()
            }
            collecting.set(false)
        }
    }.flowOn(ioDispatcher)

    private fun effectStatus(available: Boolean, create: () -> AudioEffect?): String {
        if (!available) return "UNAVAILABLE"
        val effect = runCatching(create).getOrNull() ?: return "AVAILABLE_CREATE_FAILED"
        return try {
            if (effect.enabled) "AVAILABLE_ENABLED" else "AVAILABLE_DISABLED"
        } finally {
            effect.release()
        }
    }

    private fun AudioDeviceInfo.describe(): String = "type=$type;product=$productName;source=$isSource"

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> "ANDROID_SOURCE_$source"
    }
}

enum class DeveloperCapturePlanType(val displayName: String) {
    PILOT("Pilot — 7 cases"),
    FULL("Full matrix — 69 takes")
}

enum class DeveloperPhonePlacement(val displayName: String) {
    NORMAL("Normal / music stand"),
    NEAR_KEYS("Near the keys"),
    OPEN_LID("Near the open lid")
}

data class DeveloperPianoCapturePrompt(
    val id: String,
    val scenario: String,
    val register: String,
    val attack: String,
    val expectedMidiSequence: List<Int>,
    val intendedPerformedMidi: List<Int>,
    val placement: DeveloperPhonePlacement,
    val durationMillis: Long,
    val title: String,
    val instruction: String
)

object DeveloperPianoCapturePlans {
    val pilot: List<DeveloperPianoCapturePrompt> = listOf(
        base("pilot-normal-c4", "CORRECT_ISOLATED", "MID", "NORMAL", listOf(60), listOf(60), "Normal C4", "Play one normal-strength middle C."),
        base("pilot-very-soft-c4", "VERY_SOFT_EXPECTED", "MID", "VERY_SOFT", listOf(60), listOf(60), "Very-soft C4", "Play one very-soft middle C."),
        base("pilot-wrong-d4", "WRONG_ISOLATED", "MID", "NORMAL", listOf(60), listOf(62), "Wrong note D4", "The score expects C4. Intentionally play D4 once."),
        base("pilot-restrikes-c4", "REPEATED_RESTRIKES", "MID", "NORMAL", listOf(60, 60, 60), listOf(60, 60, 60), "Three C4 restrikes", "Strike middle C three separate times, about 600 ms apart.", 5_000),
        base("pilot-sustain-c4", "REPEATED_SUSTAIN", "MID", "NORMAL", listOf(60, 60, 60), listOf(60), "One sustained C4", "Strike middle C once and hold it. Do not restrike.", 5_000),
        base("pilot-room-noise", "BACKGROUND_NOISE", "MID", "NONE", listOf(60), emptyList(), "Room noise", "Do not play. Leave normal room noise present."),
        base("pilot-silence", "SILENCE", "MID", "NONE", listOf(60), emptyList(), "Silence", "Do not play. Keep the room as quiet as practical.")
    )

    val full: List<DeveloperPianoCapturePrompt> by lazy {
        val bases = fullBaseCases()
        DeveloperPhonePlacement.entries.flatMapIndexed { takeIndex, placement ->
            bases.map { prompt ->
                prompt.copy(
                    id = "${prompt.id}-${takeIndex + 1}".replace("--", "-"),
                    placement = placement,
                    title = "${prompt.title} — ${placement.displayName}"
                )
            }
        }
    }

    fun forType(type: DeveloperCapturePlanType): List<DeveloperPianoCapturePrompt> = when (type) {
        DeveloperCapturePlanType.PILOT -> pilot
        DeveloperCapturePlanType.FULL -> full
    }

    private fun fullBaseCases(): List<DeveloperPianoCapturePrompt> = buildList {
        val registers = listOf(Triple("low", "LOW", 36), Triple("mid", "MID", 60), Triple("high", "HIGH", 84))
        val dynamics = listOf(
            Triple("very-soft", "VERY_SOFT", "VERY_SOFT_EXPECTED"),
            Triple("soft", "SOFT", "CORRECT_ISOLATED"),
            Triple("normal", "NORMAL", "CORRECT_ISOLATED"),
            Triple("strong", "STRONG", "STRONG_EXPECTED")
        )
        dynamics.forEach { (dynamicId, attack, scenario) ->
            registers.forEach { (registerId, register, midi) ->
                add(base("$dynamicId-$registerId", scenario, register, attack, listOf(midi), listOf(midi), "$attack $register note", "Play MIDI $midi once at $attack strength."))
            }
        }
        add(base("wrong-isolated", "WRONG_ISOLATED", "MID", "NORMAL", listOf(60), listOf(62), "Wrong D4 for expected C4", "Intentionally play D4 once while C4 is expected."))
        add(base("neighbor-semitone", "NEIGHBOR_SEMITONE", "MID", "NORMAL", listOf(60), listOf(61), "C♯4 for expected C4", "Intentionally play C♯4 once while C4 is expected."))
        add(base("octave-below", "OCTAVE_ERROR", "MID", "NORMAL", listOf(60), listOf(48), "C3 for expected C4", "Intentionally play C3 once while C4 is expected."))
        add(base("octave-above", "OCTAVE_ERROR", "MID", "NORMAL", listOf(60), listOf(72), "C5 for expected C4", "Intentionally play C5 once while C4 is expected."))
        add(base("repeated-restrikes", "REPEATED_RESTRIKES", "MID", "NORMAL", listOf(60, 60, 60), listOf(60, 60, 60), "Three C4 restrikes", "Strike C4 three separate times, about 600 ms apart.", 5_000))
        add(base("repeated-sustain", "REPEATED_SUSTAIN", "MID", "NORMAL", listOf(60, 60, 60), listOf(60), "One sustained C4", "Strike C4 once and hold it without restriking.", 5_000))
        add(base("legato-mid", "LEGATO_TRANSITION", "MID", "SOFT", listOf(60, 64, 67), listOf(60, 64, 67), "Legato C4–E4–G4", "Play C4, E4, G4 softly and legato.", 5_000))
        add(base("sustain-residual", "SUSTAIN_RESIDUAL", "MID", "NORMAL", listOf(60, 72), listOf(60), "C4 residual for expected C5", "Play and sustain C4. Do not play C5.", 5_000))
        add(base("after-silence", "NOTE_AFTER_SILENCE", "MID", "SOFT", listOf(64), listOf(64), "E4 after silence", "Wait about two seconds, then play soft E4.", 5_000))
        add(base("background-noise", "BACKGROUND_NOISE", "MID", "NONE", listOf(60), emptyList(), "Room noise", "Do not play; leave ordinary room noise present."))
        add(base("silence", "SILENCE", "MID", "NONE", listOf(60), emptyList(), "Silence", "Do not play; keep the room as quiet as practical."))
    }

    private fun base(
        id: String,
        scenario: String,
        register: String,
        attack: String,
        expected: List<Int>,
        performed: List<Int>,
        title: String,
        instruction: String,
        durationMillis: Long = 4_000L
    ) = DeveloperPianoCapturePrompt(
        id = id,
        scenario = scenario,
        register = register,
        attack = attack,
        expectedMidiSequence = expected,
        intendedPerformedMidi = performed,
        placement = DeveloperPhonePlacement.NORMAL,
        durationMillis = durationMillis,
        title = title,
        instruction = instruction
    )
}

data class DeveloperCapturedPianoTake(
    val prompt: DeveloperPianoCapturePrompt,
    val sampleRateHz: Int,
    val samples: ShortArray,
    val provenance: DeveloperAudioCaptureProvenance,
    val recordedAtUtc: String = utcNow()
)

/** Creates one portable ZIP. All manifest rows remain unverified until a human reviews the WAVs. */
object DeveloperPianoCaptureBundleExporter {
    fun export(
        output: OutputStream,
        piano: String,
        roomCondition: String,
        takes: List<DeveloperCapturedPianoTake>
    ) {
        require(piano.isNotBlank() && roomCondition.isNotBlank())
        require(takes.isNotEmpty())
        ZipOutputStream(output.buffered()).use { zip ->
            takes.forEach { take ->
                zip.putNextEntry(ZipEntry("local-recordings/${take.prompt.id}.wav"))
                writeWave(zip, take.samples, take.sampleRateHz)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.tsv"))
            zip.write(manifest(piano, roomCondition, takes).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                (
                    "SheetSight explicit developer piano captures.\n" +
                        "Every manifest row is manual_verified=false. Listen to each complete WAV, " +
                        "correct performed pitches and onset times, then name the reviewer before benchmarking.\n" +
                        "These files are not ordinary Practice Mode audio.\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    internal fun manifest(
        piano: String,
        roomCondition: String,
        takes: List<DeveloperCapturedPianoTake>
    ): String = buildString {
        append(MANIFEST_HEADER).append('\n')
        takes.forEach { take ->
            val prompt = take.prompt
            val provenance = take.provenance
            append(
                listOf(
                    prompt.id,
                    "local-recordings/${prompt.id}.wav",
                    prompt.scenario,
                    prompt.register,
                    prompt.attack,
                    prompt.expectedMidiSequence.joinToString(","),
                    prompt.expectedMidiSequence.joinToString(",") { "-" },
                    prompt.intendedPerformedMidi.joinToString(",").ifBlank { "-" },
                    prompt.placement.name,
                    clean(roomCondition),
                    "false",
                    "",
                    clean(prompt.instruction),
                    clean(provenance.deviceModel),
                    clean(provenance.androidVersion),
                    provenance.requestedSampleRateHz,
                    provenance.actualSampleRateHz,
                    provenance.requestedAudioSource,
                    provenance.actualAudioSource,
                    provenance.unprocessedSupported,
                    provenance.agcStatus,
                    provenance.noiseSuppressorStatus,
                    clean(piano),
                    take.recordedAtUtc,
                    clean(provenance.routedAudioDevice),
                    provenance.bufferSizeFrames
                ).joinToString("\t")
            ).append('\n')
        }
    }

    private fun writeWave(output: OutputStream, samples: ShortArray, sampleRateHz: Int) {
        val dataSize = samples.size * Short.SIZE_BYTES
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
        little32(sampleRateHz)
        little32(sampleRateHz * Short.SIZE_BYTES)
        little16(Short.SIZE_BYTES)
        little16(16)
        ascii("data")
        little32(dataSize)
        samples.forEach { little16(it.toInt() and 0xffff) }
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private const val MANIFEST_HEADER =
        "id\twav\tscenario\tregister\tattack\texpected_midi_sequence\texpected_onsets_ms\t" +
            "performed_midi\tphone_placement\troom_condition\tmanual_verified\treviewer\tnotes\t" +
            "device_model\tandroid_version\trequested_sample_rate_hz\tactual_sample_rate_hz\t" +
            "requested_audio_source\tactual_audio_source\tunprocessed_supported\tagc_status\t" +
            "noise_suppressor_status\tpiano\trecorded_at_utc\trouted_audio_device\tbuffer_size_frames"
}

private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())
