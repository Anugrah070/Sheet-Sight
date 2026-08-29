package com.sheetsight.app.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicXmlArtifactStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `eligibility requires managed readable non-empty current MusicXML`() {
        val root = temporaryFolder.newFolder("scores")
        val store = FileMusicXmlArtifactStore(root)
        val valid = File(root, "valid.musicxml").apply { writeText("<score-partwise/>") }
        val empty = File(root, "empty.musicxml").apply { createNewFile() }
        val outside = temporaryFolder.newFile("outside.musicxml").apply { writeText("<score-partwise/>") }

        assertNotNull(store.inspect(valid.path))
        assertEquals(valid.length(), store.inspect(valid.path)?.sizeBytes)
        assertNull(store.inspect(null))
        assertNull(store.inspect("  "))
        assertNull(store.inspect(empty.path))
        assertNull(store.inspect(File(root, "missing.musicxml").path))
        assertNull(store.inspect(outside.path))
        assertNull(store.inspect(File(root, "source.pdf").apply { writeText("source") }.path))
    }

    @Test
    fun `staged deletion removes only the requested managed MusicXML`() {
        val root = temporaryFolder.newFolder("scores")
        val store = FileMusicXmlArtifactStore(root)
        val scoreA = File(root, "a.musicxml").apply { writeText("A") }
        val scoreB = File(root, "b.musicxml").apply { writeText("B") }

        val staged = store.stageDeletion(scoreA.path) as ArtifactDeletionStage.Staged
        assertFalse(scoreA.exists())
        assertTrue(scoreB.exists())
        assertTrue(store.commitDeletion(staged.token))

        assertFalse(scoreA.exists())
        assertTrue(scoreB.exists())
    }

    @Test
    fun `failed transaction can restore staged artifact`() {
        val root = temporaryFolder.newFolder("scores")
        val store = FileMusicXmlArtifactStore(root)
        val current = File(root, "current.musicxml").apply { writeText("current") }

        val staged = store.stageDeletion(current.path) as ArtifactDeletionStage.Staged
        assertTrue(store.rollbackDeletion(staged.token))

        assertTrue(current.isFile)
        assertEquals("current", current.readText())
    }

    @Test
    fun `already missing managed path is a cleanup outcome and outside path is rejected`() {
        val root = temporaryFolder.newFolder("scores")
        val store = FileMusicXmlArtifactStore(root)

        assertTrue(store.stageDeletion(File(root, "missing.musicxml").path) is ArtifactDeletionStage.AlreadyMissing)
        assertTrue(
            store.stageDeletion(File(temporaryFolder.root, "outside.musicxml").path) is ArtifactDeletionStage.Failure
        )
    }

    @Test
    fun `edited version is written non-empty under scores and can be discarded safely`() {
        val root = temporaryFolder.newFolder("scores")
        val store = FileMusicXmlArtifactStore(root)

        val artifact = store.writeVersion(9L, "<score-partwise/>".toByteArray())

        val file = File(artifact.canonicalPath)
        assertTrue(file.isFile)
        assertTrue(file.length() > 0L)
        assertEquals(root.canonicalFile, requireNotNull(file.parentFile).canonicalFile)
        assertFalse(file.name.startsWith("."))
        assertTrue(store.discard(file.path))
        assertFalse(file.exists())
    }
}
