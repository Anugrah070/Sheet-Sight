package com.sheetsight.app.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.sheetsight.app.data.audio.dsp.AudioAnalysisConfig
import com.sheetsight.app.data.audio.recognition.MpmPitchConfig
import com.sheetsight.app.data.audio.recognition.MpmPitchDetector
import com.sheetsight.app.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

interface AudioPitchSource {
    /** Raw normalized PCM chunks for score-aware practice analysis. */
    fun pcmChunks(): Flow<PcmAudioChunk>

    /** A cold, local-only PCM analysis stream. Cancellation stops and releases AudioRecord. */
    fun frames(): Flow<PitchFrame>
}

data class PcmAudioChunk(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val timestampMillis: Long
)

@Singleton
class AudioRecordPitchSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AudioPitchSource {
    private val config = PitchDetectionConfig()
    private val analysisConfig = AudioAnalysisConfig(sampleRateHz = config.sampleRateHz)
    private val collecting = AtomicBoolean(false)

    override fun pcmChunks(): Flow<PcmAudioChunk> = flow {
        check(collecting.compareAndSet(false, true)) { "Microphone capture is already active." }
        var recorder: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is not granted.")
            }
            val minBufferBytes = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBufferBytes > 0) { "This device does not support the practice audio configuration." }
            val bufferBytes = maxOf(minBufferBytes, analysisConfig.maximumFrameSize * Short.SIZE_BYTES * 2)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val supportsUnprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val activeRecorder = AudioRecord.Builder()
                .setAudioSource(
                    if (supportsUnprocessed) MediaRecorder.AudioSource.UNPROCESSED
                    else MediaRecorder.AudioSource.DEFAULT
                )
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

            val hop = ShortArray(analysisConfig.captureHopSize)
            activeRecorder.startRecording()
            check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = activeRecorder.read(hop, 0, hop.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("Microphone read failed with AudioRecord code $read.")
                if (read == 0) continue
                val samples = FloatArray(read) { index -> hop[index] / 32768f }
                emit(PcmAudioChunk(samples, config.sampleRateHz, System.nanoTime() / 1_000_000L))
            }
        } finally {
            recorder?.let { active ->
                runCatching { if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop() }
                active.release()
            }
            collecting.set(false)
        }
    }.flowOn(ioDispatcher)

    override fun frames(): Flow<PitchFrame> = flow {
        val detector = MpmPitchDetector(MpmPitchConfig(sampleRateHz = config.sampleRateHz))
        val ring = FloatArray(analysisConfig.maximumFrameSize)
        var writeIndex = 0
        var available = 0
        var sinceAnalysis = 0
        pcmChunks().collect { chunk ->
            for (sample in chunk.samples) {
                ring[writeIndex] = sample
                writeIndex = (writeIndex + 1) % ring.size
                available = (available + 1).coerceAtMost(ring.size)
            }
            sinceAnalysis += chunk.samples.size
            if (sinceAnalysis >= config.hopSize) {
                sinceAnalysis %= config.hopSize
                val ordered = FloatArray(ring.size)
                val present = minOf(available, ring.size)
                val start = (writeIndex - present + ring.size) % ring.size
                val offset = ring.size - present
                for (index in 0 until present) ordered[offset + index] = ring[(start + index) % ring.size]
                emit(detector.analyze(ordered, chunk.timestampMillis))
            }
        }
    }.flowOn(ioDispatcher)
}
