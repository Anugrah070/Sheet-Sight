package com.sheetsight.app.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
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
    /** A cold, local-only PCM analysis stream. Cancellation stops and releases AudioRecord. */
    fun frames(): Flow<PitchFrame>
}

@Singleton
class AudioRecordPitchSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AudioPitchSource {
    private val config = PitchDetectionConfig()
    private val collecting = AtomicBoolean(false)

    override fun frames(): Flow<PitchFrame> = flow {
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
            val bufferBytes = maxOf(minBufferBytes, config.frameSize * Short.SIZE_BYTES * 2)
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

            val detector = YinPitchDetector(config)
            val hop = ShortArray(config.hopSize)
            val frame = FloatArray(config.frameSize)
            var samplesAvailable = 0
            activeRecorder.startRecording()
            check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = activeRecorder.read(hop, 0, hop.size, AudioRecord.READ_BLOCKING)
                if (read < 0) error("Microphone read failed with AudioRecord code $read.")
                if (read == 0) continue
                if (read < frame.size) frame.copyInto(frame, destinationOffset = 0, startIndex = read)
                for (index in 0 until read) {
                    frame[frame.size - read + index] = hop[index] / 32768f
                }
                samplesAvailable = (samplesAvailable + read).coerceAtMost(frame.size)
                if (samplesAvailable == frame.size) {
                    emit(detector.analyze(frame, System.nanoTime() / 1_000_000L))
                }
            }
        } finally {
            recorder?.let { active ->
                runCatching { if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop() }
                active.release()
            }
            collecting.set(false)
        }
    }.flowOn(ioDispatcher)
}
