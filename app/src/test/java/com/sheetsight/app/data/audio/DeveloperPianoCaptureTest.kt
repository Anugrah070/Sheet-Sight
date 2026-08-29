package com.sheetsight.app.data.audio

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperPianoCaptureTest {
    @Test
    fun `full plan has three placements for every required base case`() {
        val plan = DeveloperPianoCapturePlans.full

        assertEquals(69, plan.size)
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        DeveloperPhonePlacement.entries.forEach { placement ->
            assertEquals(23, plan.count { it.placement == placement })
        }
        assertTrue(plan.any { it.attack == "VERY_SOFT" && it.register == "LOW" })
        assertTrue(plan.any { it.attack == "SOFT" && it.register == "HIGH" })
        assertTrue(plan.any { it.attack == "STRONG" && it.register == "MID" })
        assertTrue(plan.any { it.scenario == "NEIGHBOR_SEMITONE" })
        assertTrue(plan.any { it.scenario == "OCTAVE_ERROR" })
        assertTrue(plan.any { it.scenario == "REPEATED_RESTRIKES" })
        assertTrue(plan.any { it.scenario == "REPEATED_SUSTAIN" })
        assertTrue(plan.any { it.scenario == "LEGATO_TRANSITION" })
        assertTrue(plan.any { it.scenario == "SUSTAIN_RESIDUAL" })
        assertTrue(plan.any { it.scenario == "BACKGROUND_NOISE" })
        assertTrue(plan.any { it.scenario == "SILENCE" })
    }

    @Test
    fun `pilot is short but retains core correctness and safety cases`() {
        assertEquals(7, DeveloperPianoCapturePlans.pilot.size)
        assertTrue(DeveloperPianoCapturePlans.pilot.any { it.scenario == "VERY_SOFT_EXPECTED" })
        assertTrue(DeveloperPianoCapturePlans.pilot.any { it.scenario == "WRONG_ISOLATED" })
        assertTrue(DeveloperPianoCapturePlans.pilot.any { it.scenario == "REPEATED_RESTRIKES" })
        assertTrue(DeveloperPianoCapturePlans.pilot.any { it.scenario == "REPEATED_SUSTAIN" })
    }

    @Test
    fun `export is one zip with wav files and an explicitly unverified manifest`() {
        val provenance = DeveloperAudioCaptureProvenance(
            deviceModel = "Test Phone",
            androidVersion = "16 (API 36)",
            requestedSampleRateHz = 22_050,
            actualSampleRateHz = 22_050,
            requestedAudioSource = "UNPROCESSED_PREFERRED",
            actualAudioSource = "UNPROCESSED",
            unprocessedSupported = true,
            agcStatus = "UNAVAILABLE",
            noiseSuppressorStatus = "UNAVAILABLE",
            routedAudioDevice = "builtin mic",
            bufferSizeFrames = 4_096
        )
        val takes = DeveloperPianoCapturePlans.pilot.take(2).map { prompt ->
            DeveloperCapturedPianoTake(
                prompt = prompt,
                sampleRateHz = 22_050,
                samples = ShortArray(256) { it.toShort() },
                provenance = provenance,
                recordedAtUtc = "2026-08-11T00:00:00Z"
            )
        }
        val output = ByteArrayOutputStream()

        DeveloperPianoCaptureBundleExporter.export(output, "Test upright", "Quiet room", takes)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(4, entries.size)
        assertTrue(entries.containsKey("README.txt"))
        takes.forEach { take ->
            val wav = requireNotNull(entries["local-recordings/${take.prompt.id}.wav"])
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        }
        val manifest = requireNotNull(entries["manifest.tsv"]).toString(Charsets.UTF_8)
        assertEquals(3, manifest.lineSequence().count { it.isNotBlank() })
        assertTrue(manifest.contains("\tfalse\t\t"))
        assertTrue(manifest.contains("Test Phone"))
        assertTrue(manifest.contains("routed_audio_device\tbuffer_size_frames"))
        assertTrue(manifest.contains("builtin mic\t4096"))
        val manifestLines = manifest.lineSequence().filter { it.isNotBlank() }.toList()
        val header = manifestLines.first().split('\t')
        val manualVerifiedIndex = header.indexOf("manual_verified")
        assertTrue(manifestLines.drop(1).all { it.split('\t')[manualVerifiedIndex] == "false" })
        assertThrows(IllegalArgumentException::class.java) {
            com.sheetsight.app.data.audio.benchmark.BenchmarkManifestReader.read(
                ByteArrayInputStream(manifest.toByteArray())
            )
        }

        val zipFile = File.createTempFile("sheetsight-capture-", ".zip").apply {
            deleteOnExit()
            writeBytes(output.toByteArray())
        }
        val report = com.sheetsight.app.data.audio.benchmark.BenchmarkDatasetBundleValidator.validate(
            zipFile,
            expectedCaptureCount = 2
        )
        assertEquals(2, report.recordingCount)
        assertTrue(report.wavs.all { it.sampleRateHz == 22_050 && it.clippedSampleCount == 0 })
    }

    @Test
    fun `bundle validator rejects a truncated zip and an escaping entry`() {
        val valid = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.tsv"))
                zip.write("not-a-valid-manifest".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val truncated = File.createTempFile("sheetsight-truncated-", ".zip").apply {
            deleteOnExit()
            writeBytes(valid.copyOf(valid.size - 22))
        }
        assertThrows(Exception::class.java) {
            com.sheetsight.app.data.audio.benchmark.BenchmarkDatasetBundleValidator.validate(truncated)
        }

        val escaping = File.createTempFile("sheetsight-escaping-", ".zip").apply {
            deleteOnExit()
            outputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("../escape.wav"))
                    zip.write(byteArrayOf(0))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("manifest.tsv"))
                    zip.write("not-a-valid-manifest".toByteArray())
                    zip.closeEntry()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.sheetsight.app.data.audio.benchmark.BenchmarkDatasetBundleValidator.validate(escaping)
        }
    }
}
