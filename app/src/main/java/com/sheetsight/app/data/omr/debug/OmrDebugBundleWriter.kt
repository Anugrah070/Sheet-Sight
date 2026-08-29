package com.sheetsight.app.data.omr.debug

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persists one developer diagnostic as a portable, machine-comparable ZIP.
 * The live inspector keeps only small bitmaps in memory; this writer records
 * those previews alongside tabular timings, stage facts, detections, and the
 * generated MusicXML without changing the production recognition path.
 */
object OmrDebugBundleWriter {
    private const val DIRECTORY = "omr-debug"

    fun write(context: Context, result: OmrSmokeTestDiagnosticResult): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val stage = result.lastCompletedStage?.stageNumber ?: 0
        val output = File(directory, "omr-debug-${System.currentTimeMillis()}-stage-$stage.zip")
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.writeText("manifest.txt", manifest(result))
            zip.writeText("timings.tsv", timings(result))
            zip.writeText("stage-details.tsv", stageDetails(result))
            result.accuracyReport?.let { report ->
                zip.writeText("accuracy-report.txt", report.toString())
                zip.writeText("detections/barlines.tsv", detections(report.barlineDetections))
                zip.writeText("detections/noteheads.tsv", detections(report.noteheadDetections))
                zip.writeText("detections/rests.tsv", detections(report.restDetections))
                zip.writeText("detections/rest-rejections.tsv", detections(report.restRejectedDetections))
                zip.writeText("detections/interpreted-events.tsv", interpretedEvents(report.interpretedEvents))
            }
            result.previews.toSortedMap(compareBy(SmokeTestStage::stageNumber))
                .forEach { (previewStage, previews) ->
                    previews.forEachIndexed { index, preview ->
                        val name = "previews/${previewStage.stageNumber.toString().padStart(2, '0')}-" +
                            "${safeName(previewStage.label)}-${index.toString().padStart(2, '0')}-" +
                            "${safeName(preview.label)}.png"
                        zip.putNextEntry(ZipEntry(name))
                        check(preview.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) {
                            "Could not encode OMR debug preview '$name'"
                        }
                        zip.closeEntry()
                    }
                }
            result.musicXmlOutputPath?.let(::File)
                ?.takeIf { it.isFile }
                ?.let { musicXml ->
                    zip.putNextEntry(ZipEntry("recognized.musicxml"))
                    musicXml.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return output
    }

    private fun manifest(result: OmrSmokeTestDiagnosticResult): String = buildString {
        appendLine("formatVersion=1")
        appendLine("lastCompletedStage=${result.lastCompletedStage?.name.orEmpty()}")
        appendLine("error=${result.errorMessage.orEmpty().replace('\n', ' ')}")
        appendLine("hasAccuracyReport=${result.accuracyReport != null}")
        appendLine("hasMusicXml=${result.musicXmlOutputPath != null}")
        appendLine("previewCount=${result.previews.values.sumOf { it.size }}")
    }

    private fun timings(result: OmrSmokeTestDiagnosticResult): String = buildString {
        appendLine("stage\tdurationMs\tjavaUsedMb\tjavaTotalMb\tjavaMaxMb\tnativeUsedMb")
        result.stageDurations.forEach { timing ->
            val memory = timing.memoryAfter
            append(timing.stage.name).append('\t')
            append(timing.durationMs).append('\t')
            append(memory.javaUsedMb).append('\t')
            append(memory.javaTotalMb).append('\t')
            append(memory.javaMaxMb).append('\t')
            append(memory.nativeUsedMb).appendLine()
        }
    }

    private fun stageDetails(result: OmrSmokeTestDiagnosticResult): String = buildString {
        appendLine("stage\tdetailIndex\tdetail")
        result.stageDetails.toSortedMap(compareBy(SmokeTestStage::stageNumber))
            .forEach { (stage, details) ->
                details.forEachIndexed { index, detail ->
                    append(stage.name).append('\t')
                    append(index).append('\t')
                    append(detail.replace('\t', ' ').replace('\n', ' ')).appendLine()
                }
            }
    }

    private fun detections(detections: List<OmrLocatedDetection>): String = buildString {
        appendLine("x\ty\tlabel\tgroup\ttrack\tconfidence")
        detections.forEach { detection ->
            append(detection.x).append('\t')
            append(detection.y).append('\t')
            append(detection.label.replace('\t', ' ')).append('\t')
            append(detection.group ?: "").append('\t')
            append(detection.track ?: "").append('\t')
            append(detection.confidence ?: "").appendLine()
        }
    }

    private fun interpretedEvents(events: List<OmrInterpretedEventDetection>): String = buildString {
        appendLine(
            "eventId\townerEventId\tmeasureIndex\tkind\tx\ty\tgroup\ttrack\tstaffStep\tclef\taccidental\tfinalPitch\t" +
                "durationNumerator\tdurationDenominator\trhythmState\tconfidence\tevidence"
        )
        events.forEach { event ->
            append(event.eventId).append('\t')
            append(event.ownerEventId.orEmpty()).append('\t')
            append(event.measureIndex ?: "").append('\t')
            append(event.kind).append('\t')
            append(event.x).append('\t')
            append(event.y).append('\t')
            append(event.group).append('\t')
            append(event.track).append('\t')
            append(event.staffStep ?: "").append('\t')
            append(event.clef.orEmpty()).append('\t')
            append(event.accidental.orEmpty()).append('\t')
            append(event.finalPitch.orEmpty()).append('\t')
            append(event.durationNumerator ?: "").append('\t')
            append(event.durationDenominator ?: "").append('\t')
            append(event.rhythmState).append('\t')
            append(event.confidence ?: "").append('\t')
            append(event.evidence.replace('\t', ' ').replace('\n', ' ')).appendLine()
        }
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun safeName(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "artifact" }
}
