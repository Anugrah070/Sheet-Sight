@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.sheetsight.app.ui.editor

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import androidx.test.platform.app.InstrumentationRegistry
import com.sheetsight.app.data.omr.musicxml.MusicXmlParser
import com.sheetsight.app.ui.editor.identity.AlphaTabIdentityMapper
import com.sheetsight.app.ui.editor.identity.MusicXmlIdentityBuilder
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Device-only reproduction against an existing private score; never copies or mutates it. */
class EditorPhase86DeviceReproductionTest {
    @Test
    fun existingRealScoreMapsEverySafelyRenderedNote() {
        val scores = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "scores"
        )
        val fixture = scores.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("musicxml", "xml") }
            .filter { it.length() > 10_000L }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.length() })
        assumeTrue("No existing real MusicXML score is installed on this device.", fixture != null)

        val bytes = requireNotNull(fixture).readBytes()
        val identityIndex = MusicXmlIdentityBuilder.build(
            scoreId = DEVICE_SCORE_NAMESPACE,
            document = MusicXmlParser.parseBytes(bytes)
        )
        val score = ScoreLoader.loadScoreFromBytes(
            Uint8Array(bytes.asUByteArray()),
            Settings()
        )
        val mapping = AlphaTabIdentityMapper.map(identityIndex, score)

        println(
            "EDITOR_REAL_SCORE_REPRO file=${fixture.name} bytes=${bytes.size} " +
                "sourceNotes=${identityIndex.notes.size} mappedNotes=${mapping.noteRefs.size} " +
                "issues=${mapping.issues.size}"
        )
        mapping.issues.forEach { issue ->
            println("EDITOR_REAL_SCORE_REPRO ${issue.code} ${issue.stableIdentity}: ${issue.detail}")
        }
        assertTrue(mapping.issues.joinToString("\n"), mapping.isComplete)
    }

    private companion object {
        const val DEVICE_SCORE_NAMESPACE = 86L
    }
}
