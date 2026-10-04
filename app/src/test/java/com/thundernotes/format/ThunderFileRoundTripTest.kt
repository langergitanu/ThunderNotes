package com.thundernotes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-trip test for the `.thunder` ZIP container.
 *
 * Verifies that [ThunderFile.write] then [ThunderFile.extract] gives back
 * byte-identical `note.sqlite`, `preview.png`, and `assets/` files, plus a
 * manifest that round-trips with the right noteId / displayName / checksum.
 *
 * Run with: `./gradlew :app:testDebugUnitTest --tests *.ThunderFileRoundTripTest`
 */
class ThunderFileRoundTripTest {

    @Test
    fun `round-trip preserves sqlite bytes and manifest fields`() {
        val tmpRoot = createTempDirectory(prefix = "thunder_rt_")
        try {
            // ── setup: write a fake "note.sqlite" with deterministic bytes ──
            val sqliteFile = tmpRoot.resolve("note.sqlite").apply {
                writeBytes(ByteArray(8192) { ((it * 31) and 0xFF).toByte() })
            }
            val expectedChecksum = ThunderFile.sha256(sqliteFile)

            val previewFile = tmpRoot.resolve("preview.png").apply {
                writeBytes(ByteArray(2048) { ((it * 7) and 0xFF).toByte() })
            }
            val assetsDir = tmpRoot.resolve("assets").apply { mkdirs() }
            assetsDir.resolve("images/img1.png").apply {
                parentFile?.mkdirs(); writeBytes(ByteArray(512) { 0x10 })
            }
            assetsDir.resolve("pdfs/source.pdf").apply {
                parentFile?.mkdirs(); writeBytes(ByteArray(1024) { 0x20 })
            }

            // ── build the manifest stub + write the .thunder ───────────────
            val manifestStub = ThunderManifest(
                noteId = "test-note-id",
                displayName = "Round-Trip Test Note",
                pageCount = 3,
                fileSizeBytes = 0L,        // overwritten by ThunderFile.write
                checksum = "placeholder", // overwritten by ThunderFile.write
                createdAt = 1_700_000_000_000L,
                modifiedAt = 1_700_000_500_000L,
                extras = mapOf("source" to "unit-test")
            )
            val thunderFile = tmpRoot.resolve("test.thunder")
            val writtenManifest = ThunderFile.write(
                outputFile = thunderFile,
                manifestStub = manifestStub,
                sqliteFile = sqliteFile,
                previewFile = previewFile,
                assetsDir = assetsDir
            )

            // ── verify the returned manifest ───────────────────────────────
            assertEquals("test-note-id", writtenManifest.noteId)
            assertEquals("Round-Trip Test Note", writtenManifest.displayName)
            assertEquals(3, writtenManifest.pageCount)
            assertEquals(expectedChecksum, writtenManifest.checksum)
            assertEquals(sqliteFile.length(), writtenManifest.fileSizeBytes)
            assertEquals(ThunderManifest.FORMAT_VERSION_CURRENT, writtenManifest.formatVersion)
            assertEquals(ThunderManifest.SCHEMA_VERSION_CURRENT, writtenManifest.schemaVersion)

            // ── validate without extracting ────────────────────────────────
            val validated = ThunderFile.validate(thunderFile)
            assertEquals(writtenManifest, validated)

            // ── extract + verify byte-identical files ──────────────────────
            val stagingDir = tmpRoot.resolve("staging").apply { mkdirs() }
            val extractedManifest = ThunderFile.extract(thunderFile, stagingDir)
            assertEquals(writtenManifest, extractedManifest)

            val extractedSqlite = stagingDir.resolve("note.sqlite")
            assertTrue("extracted note.sqlite should exist", extractedSqlite.exists())
            assertEquals(sqliteFile.length(), extractedSqlite.length())
            sqliteFile.inputStream().use { a ->
                extractedSqlite.inputStream().use { b ->
                    var i = 0
                    while (true) {
                        val ba = a.read()
                        val bb = b.read()
                        assertEquals("byte $i mismatch", ba, bb)
                        if (ba == -1) break
                        i++
                    }
                }
            }

            val extractedPreview = stagingDir.resolve("preview.png")
            assertTrue(extractedPreview.exists())
            assertEquals(previewFile.length(), extractedPreview.length())

            val extractedImg1 = stagingDir.resolve("assets/images/img1.png")
            assertTrue(extractedImg1.exists())
            val extractedPdf = stagingDir.resolve("assets/pdfs/source.pdf")
            assertTrue(extractedPdf.exists())
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    @Test
    fun `validate rejects missing manifest`() {
        val tmpRoot = createTempDirectory(prefix = "thunder_bad_")
        try {
            val fakeThunder = tmpRoot.resolve("bad.thunder")
            // Build a ZIP with only note.sqlite (no manifest).
            java.util.zip.ZipOutputStream(fakeThunder.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("note.sqlite"))
                zos.write(ByteArray(100) { 0 })
                zos.closeEntry()
            }
            assertThrows(IllegalStateException::class.java) {
                ThunderFile.validate(fakeThunder)
            }
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    @Test
    fun `extract refuses path traversal entries`() {
        val tmpRoot = createTempDirectory(prefix = "thunder_traversal_")
        try {
            val evilThunder = tmpRoot.resolve("evil.thunder")
            // Build a ZIP with an entry that tries to escape via ../.
            java.util.zip.ZipOutputStream(evilThunder.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }
            val stagingDir = tmpRoot.resolve("staging").apply { mkdirs() }
            assertThrows(SecurityException::class.java) {
                ThunderFile.extract(evilThunder, stagingDir)
            }
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    @Test
    fun `sha256 is deterministic`() {
        val tmpRoot = createTempDirectory(prefix = "thunder_sha_")
        try {
            val f = tmpRoot.resolve("data.bin")
            f.writeBytes(ByteArray(1024) { (it and 0xFF).toByte() })
            val h1 = ThunderFile.sha256(f)
            val h2 = ThunderFile.sha256(f)
            assertEquals(h1, h2)
            // 64 hex chars (256 bits / 4 bits per hex char).
            assertEquals(64, h1.length)
            assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun createTempDirectory(prefix: String): File {
        val tmp = File(System.getProperty("java.io.tmpdir"), "$prefix${System.nanoTime()}")
        tmp.mkdirs()
        return tmp
    }
}
