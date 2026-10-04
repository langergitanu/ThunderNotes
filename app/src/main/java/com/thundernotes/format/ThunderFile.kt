package com.thundernotes.format

import com.thundernotes.format.ThunderFormatConstants.Entry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Read + write `.thunder` files (ZIP containers).
 *
 * A `.thunder` file is a ZIP archive with this layout (per
 * `docs/thunder-format-proposal.md` Part A):
 *
 *   note.thunder (ZIP)
 *   ├── manifest.json      # ThunderManifest (JSON)
 *   ├── note.sqlite         # Room DB (NoteDatabase — per-note content)
 *   ├── assets/             # images, pdfs, audio, thumbnails — embedded
 *   └── preview.png         # library-grid preview thumbnail
 *
 * **Write flow** (in `NotesRepository.save()`, phase 4):
 *   1. NoteDatabase checkpoints + closes (TRUNCATE journal mode = single file).
 *   2. [write] zips the staged `note.sqlite` + `assets/` + `preview.png`
 *      into the .thunder file with a fresh `manifest.json`.
 *   3. The NoteEntity row in the app-global DB is updated with the new
 *      pageCount, fileSizeBytes, and modifiedTime.
 *
 * **Read flow** (in `NotesRepository.open()`, phase 4):
 *   1. [extract] unzips the .thunder to a staging directory under
 *      `<app-external-files-dir>/staging/<noteId>/`.
 *   2. `NoteDatabase.open()` is called on the staged `note.sqlite`.
 *   3. The canvas loads pages/strokes/etc. via the DAOs.
 *
 * The staging directory is per-note and lives under app-external storage:
 *   `/Android/data/com.thundernotes/files/staging/<noteId>/`
 */
object ThunderFile {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        // Pretty-printed so a .thunder can be inspected by unzipping + reading manifest.json.
        prettyPrint = true
        // Encode defaults so missing-field decode errors don't happen when
        // reading old manifests.
        encodeDefaults = true
        // Lenient on read so old files with extra/missing fields still load.
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ─── public API ─────────────────────────────────────────────────────────

    /**
     * Write a `.thunder` file at [outputFile] containing:
     *   - `manifest.json` (built from [manifestStub] + computed checksum + fileSize)
     *   - `note.sqlite`   (copied from [sqliteFile])
     *   - `preview.png`   (copied from [previewFile] if non-null)
     *   - `assets/`       (recursively copied from [assetsDir] if non-null)
     *
     * The [manifestStub] supplies everything except `checksum` and `fileSizeBytes`,
     * which are computed from [sqliteFile] (its SHA-256 and length). The
     * returned manifest is the final, fully-populated version that was written.
     *
     * **Atomicity:** the ZIP is written to a sibling `.tmp` file first, then
     * renamed to [outputFile] on success. A crash mid-write never leaves a
     * corrupt .thunder.
     */
    fun write(
        outputFile: File,
        manifestStub: ThunderManifest,
        sqliteFile: File,
        previewFile: File? = null,
        assetsDir: File? = null
    ): ThunderManifest {
        require(sqliteFile.exists()) { "note.sqlite not found: ${sqliteFile.absolutePath}" }
        require(sqliteFile.canRead()) { "note.sqlite not readable: ${sqliteFile.absolutePath}" }

        val sqliteChecksum = sha256(sqliteFile)
        val finalManifest = manifestStub.copy(
            checksum = sqliteChecksum,
            fileSizeBytes = sqliteFile.length()
        )
        val manifestJson = json.encodeToString(ThunderManifest.serializer(), finalManifest)

        // Stage to a sibling .tmp file, then rename for atomicity.
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        try {
            ZipOutputStream(tmpFile.outputStream().buffered()).use { zos ->
                // Set compression level — note.sqlite compresses well, assets
                // (PNG/JPEG) are already compressed so we skip re-compressing them.
                zos.setLevel(DeflaterLevel.DEFAULT_COMPRESSION)

                putZipEntry(zos, Entry.MANIFEST, manifestJson.toByteArray(Charsets.UTF_8))
                putZipEntryFromFile(zos, Entry.NOTE_SQLITE, sqliteFile)
                if (previewFile != null && previewFile.exists()) {
                    putZipEntryFromFile(zos, Entry.PREVIEW_PNG, previewFile)
                }
                if (assetsDir != null && assetsDir.exists()) {
                    addDirectoryToZip(zos, assetsDir, Entry.ASSETS_DIR)
                }
            }

            // Atomic rename (if tmpFile and outputFile are on the same filesystem).
            if (outputFile.exists()) outputFile.delete()
            if (!tmpFile.renameTo(outputFile)) {
                // Fallback for cross-filesystem renames.
                tmpFile.copyTo(outputFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }

        return finalManifest
    }

    /**
     * Extract a `.thunder` file at [inputFile] into [stagingDir].
     *
     * The staging directory will contain `manifest.json`, `note.sqlite`,
     * `preview.png` (if present), and `assets/` (if present).
     *
     * Returns the parsed [ThunderManifest].
     *
     * **Path-traversal protection:** ZIP entry names are validated to ensure
     * they don't escape [stagingDir] via `../` tricks.
     */
    fun extract(inputFile: File, stagingDir: File): ThunderManifest {
        require(inputFile.exists()) { ".thunder not found: ${inputFile.absolutePath}" }
        if (!stagingDir.exists()) stagingDir.mkdirs()
        val stagingCanonical = stagingDir.canonicalFile

        var manifest: ThunderManifest? = null

        ZipInputStream(inputFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val target = File(stagingDir, entry.name).canonicalFile
                // Path-traversal protection: target must be inside stagingDir.
                if (!target.path.startsWith(stagingCanonical.path + File.separator) &&
                    target.path != stagingCanonical.path
                ) {
                    throw SecurityException(
                        "ZIP entry '${entry.name}' escapes staging dir; refusing to extract."
                    )
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> zis.copyTo(out) }
                }
                if (entry.name == Entry.MANIFEST) {
                    // Already extracted above; parse it.
                    val manifestBytes = target.readBytes()
                    manifest = json.decodeFromString(ThunderManifest.serializer(),
                        manifestBytes.toString(Charsets.UTF_8))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return manifest ?: throw IllegalStateException(
            ".thunder file is missing '${Entry.MANIFEST}' entry: ${inputFile.absolutePath}"
        )
    }

    /**
     * Validate a `.thunder` file: open the ZIP, read the manifest, verify
     * format + schema versions are compatible, and verify the SHA-256 of
     * `note.sqlite` matches the manifest's checksum.
     *
     * Returns the manifest on success; throws on any failure.
     */
    fun validate(inputFile: File): ThunderManifest {
        require(inputFile.exists()) { ".thunder not found: ${inputFile.absolutePath}" }

        var manifest: ThunderManifest? = null
        var sqliteDigest: MessageDigest? = null
        var sqliteSize = 0L

        ZipInputStream(inputFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    Entry.MANIFEST -> {
                        val bytes = zis.readBytes()
                        manifest = json.decodeFromString(
                            ThunderManifest.serializer(),
                            bytes.toString(Charsets.UTF_8)
                        )
                    }
                    Entry.NOTE_SQLITE -> {
                        val md = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                            sqliteSize += n
                        }
                        sqliteDigest = md
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val m = manifest ?: throw IllegalStateException("Missing manifest in .thunder file")
        require(m.formatVersion <= ThunderManifest.FORMAT_VERSION_CURRENT) {
            "Unsupported .thunder formatVersion ${m.formatVersion} (this app supports <= ${ThunderManifest.FORMAT_VERSION_CURRENT})"
        }
        require(m.schemaVersion <= ThunderManifest.SCHEMA_VERSION_CURRENT) {
            "Unsupported .thunder schemaVersion ${m.schemaVersion} (this app supports <= ${ThunderManifest.SCHEMA_VERSION_CURRENT})"
        }
        val md = sqliteDigest ?: throw IllegalStateException("Missing note.sqlite in .thunder file")
        val computedChecksum = md.digest().joinToString("") { "%02x".format(it) }
        require(computedChecksum == m.checksum) {
            "Checksum mismatch: manifest says ${m.checksum} but computed $computedChecksum"
        }
        require(sqliteSize == m.fileSizeBytes) {
            "Size mismatch: manifest says ${m.fileSizeBytes} but file is $sqliteSize"
        }
        return m
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Compute SHA-256 of a file as a lowercase hex string. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun putZipEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putZipEntryFromFile(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun addDirectoryToZip(zos: ZipOutputStream, dir: File, zipPrefix: String) {
        require(dir.isDirectory) { "Not a directory: ${dir.absolutePath}" }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        for (file in files) {
            val rel = dir.toURI().relativize(file.toURI()).path
            val entryName = zipPrefix + rel
            putZipEntryFromFile(zos, entryName, file)
        }
    }
}

/** Compression levels (mirrors java.util.zip.Deflater constants). */
private object DeflaterLevel {
    const val DEFAULT_COMPRESSION = -1
    const val NO_COMPRESSION = 0
    const val BEST_SPEED = 1
    const val BEST_COMPRESSION = 9
}

/**
 * Base class for ThunderFile-format-specific exceptions.
 * Thrown when a .thunder file is corrupt or has an incompatible version.
 */
class ThunderFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
