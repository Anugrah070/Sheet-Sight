package com.sheetsight.app.data.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRecordPitchSourceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun requireMicrophonePermission() {
        assumeTrue(
            "Grant RECORD_AUDIO to the test target before running this hardware lifecycle test.",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    @Test
    fun captureReleasesAfterCancellationAndRestartsCleanly() = runBlocking {
        val source = AudioRecordPitchSource(context, Dispatchers.IO)
        val first = withTimeout(8_000) { source.frames().first() }
        val second = withTimeout(8_000) { source.frames().first() }
        assertTrue(first.timestampMillis > 0L)
        assertTrue(second.timestampMillis >= first.timestampMillis)
    }
}
