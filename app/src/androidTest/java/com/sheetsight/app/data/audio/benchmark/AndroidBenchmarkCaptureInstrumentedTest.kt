package com.sheetsight.app.data.audio.benchmark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit developer-only acoustic fixture capture. This test never runs as part of Practice Mode
 * and refuses to persist PCM unless the caller supplies the confirmation instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBenchmarkCaptureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun captureExplicitDeveloperFixture() {
        assertEquals(
            "Pass -e confirm_developer_capture I_UNDERSTAND_PCM_IS_PERSISTED to enable explicit fixture capture.",
            CONFIRMATION,
            arguments.getString("confirm_developer_capture")
        )
        val fixtureId = requireArgument("fixture_id").also {
            require(it.matches(Regex("[a-z0-9][a-z0-9-]{2,63}"))) { "fixture_id must be lowercase kebab-case" }
        }
        val piano = requireArgument("piano")
        val placement = requireArgument("phone_placement")
        val roomCondition = requireArgument("room_condition")
        val requestedSampleRate = arguments.getString("sample_rate_hz")?.toIntOrNull() ?: 22_050
        val durationMillis = (arguments.getString("duration_ms")?.toLongOrNull() ?: 4_000L).also {
            require(it in 500L..30_000L)
        }
        val leadInMillis = (arguments.getString("lead_in_ms")?.toLongOrNull() ?: 3_000L).also {
            require(it in 0L..10_000L)
        }
        val requestedSourceName = arguments.getString("audio_source")?.uppercase(Locale.US) ?: "UNPROCESSED"

        grantMicrophonePermission()
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        )

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedSupported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val requestedSource = when (requestedSourceName) {
            "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
            "DEFAULT" -> MediaRecorder.AudioSource.DEFAULT
            else -> error("audio_source must be UNPROCESSED or DEFAULT")
        }
        val configuredSource = if (requestedSource == MediaRecorder.AudioSource.UNPROCESSED && !unprocessedSupported) {
            MediaRecorder.AudioSource.DEFAULT
        } else requestedSource
        val minBufferBytes = AudioRecord.getMinBufferSize(
            requestedSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferBytes > 0) { "Requested capture format is unavailable: $minBufferBytes" }
        val bufferBytes = maxOf(minBufferBytes, 8_192 * Short.SIZE_BYTES)
        val recorder = AudioRecord.Builder()
            .setAudioSource(configuredSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(requestedSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }

        val actualSampleRate = recorder.sampleRate
        val actualSource = recorder.audioSource
        val agcStatus = effectStatus(
            available = AutomaticGainControl.isAvailable(),
            create = { AutomaticGainControl.create(recorder.audioSessionId) }
        )
        val noiseSuppressorStatus = effectStatus(
            available = NoiseSuppressor.isAvailable(),
            create = { NoiseSuppressor.create(recorder.audioSessionId) }
        )
        val captured = ShortArray((durationMillis * actualSampleRate / 1_000L).toInt())
        val readBuffer = ShortArray(2_048)
        var written = 0

        try {
            if (leadInMillis > 0) Thread.sleep(leadInMillis)
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
            while (written < captured.size) {
                val requested = minOf(readBuffer.size, captured.size - written)
                val read = recorder.read(readBuffer, 0, requested, AudioRecord.READ_BLOCKING)
                require(read > 0) { "AudioRecord read failed: $read" }
                readBuffer.copyInto(captured, destinationOffset = written, endIndex = read)
                written += read
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }

        val routedDevice = recorder.routedDevice?.describe() ?: "UNKNOWN"
        val bufferFrames = recorder.bufferSizeInFrames
        recorder.release()

        val root = requireNotNull(context.getExternalFilesDir("phase75b/captures"))
        root.mkdirs()
        val wav = File(root, "$fixtureId.wav")
        BufferedOutputStream(FileOutputStream(wav)).use { output ->
            writePcm16Wave(output, captured, actualSampleRate)
        }
        val provenance = File(root, "$fixtureId.capture.tsv")
        provenance.writeText(
            provenanceTsv(
                fixtureId = fixtureId,
                wavName = wav.name,
                requestedSampleRate = requestedSampleRate,
                actualSampleRate = actualSampleRate,
                requestedSource = sourceName(requestedSource),
                actualSource = sourceName(actualSource),
                unprocessedSupported = unprocessedSupported,
                agcStatus = agcStatus,
                noiseSuppressorStatus = noiseSuppressorStatus,
                piano = piano,
                placement = placement,
                roomCondition = roomCondition,
                routedDevice = routedDevice,
                bufferFrames = bufferFrames
            )
        )

        assertTrue(wav.length() > 44L)
        assertTrue(provenance.length() > 0L)
        println("PHASE75B_CAPTURE_WAV=${wav.absolutePath}")
        println("PHASE75B_CAPTURE_PROVENANCE=${provenance.absolutePath}")
    }

    private fun grantMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requireArgument(name: String): String =
        requireNotNull(arguments.getString(name)?.trim()?.takeIf { it.isNotEmpty() }) { "Missing -e $name value" }

    private fun effectStatus(available: Boolean, create: () -> android.media.audiofx.AudioEffect?): String {
        if (!available) return "UNAVAILABLE"
        val effect = runCatching(create).getOrNull() ?: return "AVAILABLE_CREATE_FAILED"
        return try {
            if (effect.enabled) "AVAILABLE_ENABLED" else "AVAILABLE_DISABLED"
        } finally {
            effect.release()
        }
    }

    private fun AudioDeviceInfo.describe(): String =
        "type=$type;product=${productName};source=$isSource"

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> "ANDROID_SOURCE_$source"
    }

    private fun provenanceTsv(
        fixtureId: String,
        wavName: String,
        requestedSampleRate: Int,
        actualSampleRate: Int,
        requestedSource: String,
        actualSource: String,
        unprocessedSupported: Boolean,
        agcStatus: String,
        noiseSuppressorStatus: String,
        piano: String,
        placement: String,
        roomCondition: String,
        routedDevice: String,
        bufferFrames: Int
    ): String {
        val recordedAtUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val header = listOf(
            "id", "wav", "device_model", "android_version", "requested_sample_rate_hz",
            "actual_sample_rate_hz", "requested_audio_source", "actual_audio_source",
            "unprocessed_supported", "agc_status", "noise_suppressor_status", "piano",
            "phone_placement", "room_condition", "recorded_at_utc", "routed_audio_device",
            "buffer_size_frames"
        )
        val values = listOf(
            fixtureId,
            wavName,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            requestedSampleRate.toString(),
            actualSampleRate.toString(),
            requestedSource,
            actualSource,
            unprocessedSupported.toString(),
            agcStatus,
            noiseSuppressorStatus,
            piano,
            placement,
            roomCondition,
            recordedAtUtc,
            routedDevice,
            bufferFrames.toString()
        )
        return header.joinToString("\t") + "\n" + values.joinToString("\t") { it.replace('\t', ' ') } + "\n"
    }

    private fun writePcm16Wave(output: BufferedOutputStream, samples: ShortArray, sampleRate: Int) {
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
        little32(sampleRate)
        little32(sampleRate * Short.SIZE_BYTES)
        little16(Short.SIZE_BYTES)
        little16(16)
        ascii("data")
        little32(dataSize)
        samples.forEach { sample -> little16(sample.toInt() and 0xffff) }
    }

    private companion object {
        const val CONFIRMATION = "I_UNDERSTAND_PCM_IS_PERSISTED"
    }
}
