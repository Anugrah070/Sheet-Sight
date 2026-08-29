package com.sheetsight.app.data.audio.benchmark

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeRecognitionBenchmarkTest {
    private val benchmark = PracticeRecognitionBenchmark()

    @Test
    fun `synthetic labels cover every required benchmark family and all strata`() {
        val clips = SyntheticPracticeBenchmarkFixtures.clips
        assertEquals(BenchmarkScenario.entries.toSet(), clips.map { it.label.scenario }.toSet())
        assertEquals(BenchmarkRegister.entries.toSet(), clips.map { it.label.register }.toSet())
        assertTrue(clips.any { it.label.attack == BenchmarkAttack.VERY_SOFT })
        assertTrue(clips.any { it.label.attack == BenchmarkAttack.STRONG })
        assertTrue(clips.all { it.label.provenance == BenchmarkProvenance.AUTHOR_DEFINED_SYNTHETIC })
    }

    @Test
    fun `all non-neural candidates run over exactly the same fixtures and expose required slices`() {
        val candidates = listOf(
            BenchmarkCandidate(LegacyYinBenchmarkDetector.ID, ::LegacyYinBenchmarkDetector),
            BenchmarkCandidate(AdaptiveYinBenchmarkDetector.ID, ::AdaptiveYinBenchmarkDetector),
            BenchmarkCandidate(HarmonicBenchmarkDetector.ID, ::HarmonicBenchmarkDetector),
            BenchmarkCandidate(LogFrequencyBenchmarkDetector.ID, ::LogFrequencyBenchmarkDetector),
            BenchmarkCandidate(HybridBenchmarkDetector.ID, ::HybridBenchmarkDetector)
        )
        val reports = candidates.map { PracticeRecognitionBenchmarkSuite().run(it, SyntheticPracticeBenchmarkFixtures.clips) }

        reports.forEach { report ->
            assertEquals(SyntheticPracticeBenchmarkFixtures.clips.size, report.results.size)
            assertEquals(BenchmarkRegister.entries.toSet(), report.byRegister.keys)
            assertEquals(BenchmarkAttack.entries.toSet(), report.byAttack.keys)
            assertTrue(report.realTimeFactor >= 0.0)
            println(report.compactSyntheticLine())
        }
    }

    @Test
    fun `score constrained harmonic prototype rejects semitone and octave competitors`() {
        val detector = HarmonicBenchmarkDetector()
        val fixtures = SyntheticPracticeBenchmarkFixtures.clips.filter {
            it.label.scenario in setOf(BenchmarkScenario.NEIGHBOR_SEMITONE, BenchmarkScenario.OCTAVE_ERROR)
        }
        fixtures.forEach { fixture ->
            val result = benchmark.run(detector, fixture)
            assertTrue("${fixture.label.id} advanced: ${result.advances}", result.advances.isEmpty())
        }
    }

    @Test
    fun `spectral prototypes accept every restrike but one sustain advances only once`() {
        val restrikes = SyntheticPracticeBenchmarkFixtures.clips.single {
            it.label.scenario == BenchmarkScenario.REPEATED_RESTRIKES
        }
        val sustain = SyntheticPracticeBenchmarkFixtures.clips.single {
            it.label.scenario == BenchmarkScenario.REPEATED_SUSTAIN
        }
        listOf(HarmonicBenchmarkDetector(), LogFrequencyBenchmarkDetector(), HybridBenchmarkDetector()).forEach { detector ->
            val restrikeResult = benchmark.run(detector, restrikes)
            assertEquals("${detector.id} missed a genuine restrike", 3, restrikeResult.matchedExpectedCount)
            assertEquals("${detector.id} falsely advanced the sustained repeat", 1, benchmark.run(detector, sustain).advances.size)
        }
    }

    @Test
    fun `silence and deterministic room noise never advance score constrained prototypes`() {
        val quiet = SyntheticPracticeBenchmarkFixtures.clips.filter {
            it.label.scenario in setOf(BenchmarkScenario.SILENCE, BenchmarkScenario.BACKGROUND_NOISE)
        }
        listOf(HarmonicBenchmarkDetector(), LogFrequencyBenchmarkDetector(), HybridBenchmarkDetector()).forEach { detector ->
            quiet.forEach { fixture ->
                assertTrue("${detector.id} advanced ${fixture.label.id}", benchmark.run(detector, fixture).advances.isEmpty())
            }
        }
    }

    @Test
    fun `wave loader accepts mono pcm16 and retains explicit sample rate`() {
        val pcm = shortArrayOf(0, 16_384, -16_384, 32_767)
        val bytes = wav(pcm, 22_050)
        val label = BenchmarkLabel(
            id = "wav-reader",
            scenario = BenchmarkScenario.CORRECT_ISOLATED,
            register = BenchmarkRegister.MID,
            attack = BenchmarkAttack.NORMAL,
            expectedMidiSequence = listOf(60),
            expectedOnsetsMillis = listOf(0L),
            performedMidi = listOf(60),
            provenance = BenchmarkProvenance.MANUALLY_VERIFIED_RECORDING
        )
        val clip = BenchmarkWaveReader.read(ByteArrayInputStream(bytes), label)

        assertEquals(22_050, clip.sampleRateHz)
        assertEquals(4, clip.samples.size)
        assertTrue(clip.samples[1] in 0.49f..0.51f)
        assertTrue(clip.samples[2] in -0.51f..-0.49f)
        assertFalse(clip.samples.any { it.isNaN() })
    }

    @Test
    fun `acoustic manifest requires human verification and reviewer`() {
        val header = "id\twav\tscenario\tregister\tattack\texpected_midi_sequence\t" +
            "expected_onsets_ms\tperformed_midi\tmanual_verified\treviewer\tnotes\tdevice_model\t" +
            "android_version\trequested_sample_rate_hz\tactual_sample_rate_hz\trequested_audio_source\t" +
            "actual_audio_source\tunprocessed_supported\tagc_status\tnoise_suppressor_status\tpiano\t" +
            "phone_placement\troom_condition\trecorded_at_utc\trouted_audio_device\tbuffer_size_frames\n"
        val unverified = header +
            "soft-c4\tlocal-recordings/soft-c4.wav\tVERY_SOFT_EXPECTED\tMID\tVERY_SOFT\t" +
            "60\t500\t60\tfalse\t\tpp\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n"
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkManifestReader.read(ByteArrayInputStream(unverified.toByteArray()))
        }

        val verified = header +
            "soft-c4\tlocal-recordings/soft-c4.wav\tVERY_SOFT_EXPECTED\tMID\tVERY_SOFT\t" +
            "60\t500\t60\ttrue\tdeveloper-a\tpp\tOnePlus CPH2707\t16\t22050\t22050\t" +
            "UNPROCESSED\tUNPROCESSED\ttrue\tUNAVAILABLE\tUNAVAILABLE\tUpright A\tNORMAL\tQUIET\t" +
            "2026-08-11T00:00:00Z\tbuiltin-mic\t4096\n"
        val entry = BenchmarkManifestReader.read(ByteArrayInputStream(verified.toByteArray())).single()
        assertEquals("local-recordings/soft-c4.wav", entry.wavRelativePath)
        assertEquals(BenchmarkProvenance.MANUALLY_VERIFIED_RECORDING, entry.label.provenance)
        assertEquals(listOf(60), entry.label.expectedMidiSequence)
        assertEquals(listOf(500L), entry.label.expectedOnsetsMillis)
        assertEquals(22_050, entry.label.recordingProvenance?.actualSampleRateHz)
        assertEquals("builtin-mic", entry.label.recordingProvenance?.routedAudioDevice)
        assertEquals(4_096, entry.label.recordingProvenance?.bufferSizeFrames)
    }

    @Test
    fun `manifest rejects duplicate ids and wave reader rejects truncated data`() {
        val header = "id\twav\tscenario\tregister\tattack\texpected_midi_sequence\t" +
            "expected_onsets_ms\tperformed_midi\tmanual_verified\treviewer\tnotes\tdevice_model\t" +
            "android_version\trequested_sample_rate_hz\tactual_sample_rate_hz\trequested_audio_source\t" +
            "actual_audio_source\tunprocessed_supported\tagc_status\tnoise_suppressor_status\tpiano\t" +
            "phone_placement\troom_condition\trecorded_at_utc\trouted_audio_device\tbuffer_size_frames\n"
        val row = "duplicate\tlocal-recordings/%s.wav\tCORRECT_ISOLATED\tMID\tNORMAL\t60\t500\t60\t" +
            "true\treviewer\tnote\tPhone\t16\t22050\t22050\tDEFAULT\tDEFAULT\ttrue\tUNAVAILABLE\t" +
            "UNAVAILABLE\tPiano\tNORMAL\tQUIET\t2026-08-11T00:00:00Z\tbuiltin-mic\t4096\n"
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkManifestReader.read(
                ByteArrayInputStream((header + row.format("one") + row.format("two")).toByteArray())
            )
        }

        val label = BenchmarkLabel(
            id = "truncated",
            scenario = BenchmarkScenario.CORRECT_ISOLATED,
            register = BenchmarkRegister.MID,
            attack = BenchmarkAttack.NORMAL,
            expectedMidiSequence = listOf(60),
            expectedOnsetsMillis = listOf(0L),
            performedMidi = listOf(60),
            provenance = BenchmarkProvenance.MANUALLY_VERIFIED_RECORDING
        )
        val complete = wav(shortArrayOf(1, 2, 3, 4), 22_050)
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkWaveReader.read(ByteArrayInputStream(complete.copyOf(complete.size - 1)), label)
        }
    }

    @Test
    fun `export identical synthetic fixtures for external debug candidates`() {
        val directory = File("build/phase75a-synthetic").apply { mkdirs() }
        val manifest = StringBuilder("id\twav\tscenario\tregister\tattack\texpected_midi_sequence\texpected_onsets_ms\tperformed_midi\n")
        SyntheticPracticeBenchmarkFixtures.clips.forEach { clip ->
            val wav = File(directory, "${clip.label.id}.wav")
            FileOutputStream(wav).use { BenchmarkWaveWriter.write(it, clip) }
            manifest.append(clip.label.id).append('\t')
                .append(wav.name).append('\t')
                .append(clip.label.scenario).append('\t')
                .append(clip.label.register).append('\t')
                .append(clip.label.attack).append('\t')
                .append(clip.label.expectedMidiSequence.joinToString(",")).append('\t')
                .append(clip.label.expectedOnsetsMillis.joinToString(",") { it?.toString() ?: "-" }).append('\t')
                .append(clip.label.performedMidi.joinToString(",")).append('\n')
        }
        File(directory, "manifest.tsv").writeText(manifest.toString())
        assertEquals(SyntheticPracticeBenchmarkFixtures.clips.size, directory.listFiles { file -> file.extension == "wav" }?.size)
    }

    private fun CandidateBenchmarkReport.compactSyntheticLine(): String = buildString {
        append(detectorId)
        append(" recall=")
        append(overall.expectedRecall)
        append(" wrongReject=")
        append(overall.wrongNoteRejection)
        append(" fp=")
        append(overall.falsePositiveRate)
        append(" softRecall=")
        append(combinedSoftNoteRecall)
        append(" octave=")
        append(octaveConfusionRate)
        append(" repeated=")
        append(repeatedNoteErrorRate)
        append(" medianMs=")
        append(overall.medianLatencyMillis)
        append(" p95Ms=")
        append(overall.p95LatencyMillis)
        append(" coldMs=")
        append(String.format(java.util.Locale.US, "%.3f", coldInitializationNanos / 1e6))
        append(" rtf=")
        append(String.format(java.util.Locale.US, "%.4f", realTimeFactor))
        append(" blockMs=")
        append(String.format(java.util.Locale.US, "%.3f", sustainedProcessingMillisPerBlock))
        append(" registerRecall=")
        append(
            BenchmarkRegister.entries.joinToString(",") { register ->
                "${register.name}:${formatMetric(byRegister.getValue(register).expectedRecall)}"
            }
        )
        append(" attackRecall=")
        append(
            BenchmarkAttack.entries.joinToString(",") { attack ->
                "${attack.name}:${formatMetric(byAttack.getValue(attack).expectedRecall)}"
            }
        )
        append(" missed=")
        append(results.filter { it.matchedExpectedCount != it.expectedEventCount }.joinToString(",") { it.label.id })
        assertNotNull(overall)
    }

    private fun formatMetric(value: Double?): String =
        value?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "n/a"

    private fun wav(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}
