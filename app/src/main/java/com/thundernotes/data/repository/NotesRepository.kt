package com.thundernotes.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.thundernotes.data.dao.NoteDao
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.db.NoteDatabase
import com.thundernotes.data.entity.NoteContentEntity
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.entity.NotePageEntity
import com.thundernotes.data.entity.PageLayerEntity
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.format.ThunderFile
import com.thundernotes.format.ThunderManifest
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * Business-logic layer for notes — coordinates:
 *   - the app-global [AppDatabase] (note metadata + folders + recycle bin)
 *   - the per-note [NoteDatabase] (page/stroke/textbox content, opened from
 *     the .thunder ZIP)
 *   - the .thunder ZIP file itself (via [ThunderFile])
 *
 * **Three-dot overflow operations** (spec §6.2): Rename, Change Cover, Move,
 * Export, Bookmark, Information, Trash — all implemented here.
 *
 * The "Information" popup data comes straight from the [NoteEntity] row
 * (no separate query needed — file name, file size, date created are all
 * columns on the entity).
 */
class NotesRepository(
    private val appContext: Context,
    private val appDatabase: AppDatabase,
    private val noteDao: NoteDao = appDatabase.noteDao(),
) {

    // ─── directory layout ───────────────────────────────────────────────────

    /** App-external storage root for all ThunderNotes files. */
    private val notesRootDir: File by lazy {
        File(appContext.getExternalFilesDir(null), "notes").apply { mkdirs() }
    }

    /** Path where the .thunder file for [noteId] lives. */
    fun thunderFilePathFor(noteId: String): File =
        File(notesRootDir, "$noteId.thunder")

    /** Staging dir for an open editing session. */
    private fun stagingDirFor(noteId: String): File =
        File(appContext.getExternalFilesDir(null), "staging/$noteId").apply { mkdirs() }

    // ─── create + open + save ───────────────────────────────────────────────

    /**
     * Create a brand-new note with the metadata specified.
     *
     * Flow:
     *   1. Generate a fresh [noteId] (UUID).
     *   2. Create the staging dir + open a fresh NoteDatabase there.
     *   3. Bootstrap the per-note DB: insert [com.thundernotes.data.entity.NoteContentEntity]
     *      (1 row) + the first [com.thundernotes.data.entity.NotePageEntity]
     *      (pageIndex = 0) + the bottom-most [com.thundernotes.data.entity.PageLayerEntity]
     *      (sortOrder = 0). Wire NoteContent.activatedPageLayerId to the new layer.
     *   4. Close the NoteDatabase (TRUNCATE journal = single file).
     *   5. Write the .thunder ZIP via [ThunderFile.write] (no preview.png yet —
     *      the canvas phase will render one on first save).
     *   6. Insert the [NoteEntity] row in the app-global DB.
     *   7. Return the [noteId].
     */
    suspend fun createNote(
        displayName: String,
        parentFolderId: String? = null,
        pageType: PageType = PageType.BLANK,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
        color: Int = 0
    ): String {
        val noteId = UUID.randomUUID().toString()
        val stagingDir = stagingDirFor(noteId)
        val sqliteFile = File(stagingDir, "note.sqlite")
        val thunderFile = thunderFilePathFor(noteId)

        // If a staging dir already exists (e.g., from a previous crashed
        // session), wipe it to start fresh.
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        // Open a fresh NoteDatabase and bootstrap the per-note rows.
        // Using Room's `withTransaction` from room-ktx so the three
        // inserts (NoteContentEntity + NotePageEntity + PageLayerEntity)
        // are atomic — a mid-bootstrap crash leaves the staging DB clean
        // rather than half-bootstrapped.
        val noteDb = NoteDatabase.open(appContext, sqliteFile)
        try {
            noteDb.withTransaction {
                val now = System.currentTimeMillis()
                val firstPageId = UUID.randomUUID().toString()
                val firstLayerId = UUID.randomUUID().toString()

                noteDb.noteContentDao().upsert(
                    NoteContentEntity(
                        noteId = noteId,
                        defaultPageId = firstPageId,
                        savedPageIndex = 0,
                        activatedPageLayerId = firstLayerId,
                        zoom = 1.0f,
                        pageOffsetX = 0.0f,
                        pageOffsetY = 0.0f,
                        unboundedNote = true,
                        pdfInfoId = null,
                        extrasJson = "{}"
                    )
                )
                noteDb.notePageDao().insert(
                    NotePageEntity(
                        pageId = firstPageId,
                        pageIndex = 0,
                        pageType = pageType.rawValue,
                        orientation = orientation.rawValue,
                        pageRatio = if (orientation == PageOrientation.PORTRAIT) 0.707f else 1.414f,
                        pageHeightPx = 3508,
                        pageBackgroundColor = 0xFFFFFFFF.toInt(),
                        createdTime = now,
                        modifiedTime = now
                    )
                )
                noteDb.layerDao().insert(
                    PageLayerEntity(
                        layerId = firstLayerId,
                        pageId = firstPageId,
                        layerName = "Layer 1",
                        isVisible = true,
                        isLocked = false,
                        opacity = 1.0f,
                        sortOrder = 0,
                        createdTime = now,
                        modifiedTime = now
                    )
                )
            }
        } finally {
            // Close + checkpoint the WAL (TRUNCATE journal = single file).
            noteDb.close()
        }

        // Write the .thunder ZIP (no preview.png yet — canvas phase will
        // generate one on first save once we have a real cover renderer).
        val manifestStub = ThunderManifest(
            noteId = noteId,
            displayName = displayName,
            pageCount = 1,
            fileSizeBytes = 0L,
            checksum = "",
            createdAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis(),
            extras = emptyMap()
        )
        val writtenManifest = ThunderFile.write(
            outputFile = thunderFile,
            manifestStub = manifestStub,
            sqliteFile = sqliteFile,
            previewFile = null,
            assetsDir = null
        )

        // Insert the NoteEntity row in the app-global DB.
        noteDao.insert(
            NoteEntity(
                noteId = noteId,
                parentFolderId = parentFolderId,
                displayName = displayName,
                filePath = thunderFile.absolutePath,
                coverPath = null,
                color = color,
                pageCount = 1,
                fileSizeBytes = writtenManifest.fileSizeBytes,
                defaultPageType = pageType.rawValue,
                defaultOrientation = orientation.rawValue,
                recycleBinTimeMoved = null,
                recycleBinExpiresAt = null,
                isBookmarked = false,
                bookmarkTime = null
            )
        )

        // Clean up the staging dir — next open re-extracts from .thunder.
        stagingDir.deleteRecursively()

        return noteId
    }

    /**
     * Open an existing note for editing. Extracts the .thunder to staging
     * and opens the NoteDatabase. Returns a [NoteEditingSession] which the
     * caller MUST close (typically via `.use { ... }`).
     */
    suspend fun openNote(noteId: String): NoteEditingSession {
        val noteEntity = noteDao.getByNoteId(noteId)
            ?: throw IllegalArgumentException("Note not found: $noteId")
        val thunderFile = File(noteEntity.filePath)
        val stagingDir = stagingDirFor(noteId)
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val manifest = ThunderFile.extract(thunderFile, stagingDir)
        val sqliteFile = File(stagingDir, "note.sqlite")
        val noteDb = NoteDatabase.open(appContext, sqliteFile)

        return NoteEditingSession(
            noteId = noteId,
            stagingDir = stagingDir,
            noteDatabase = noteDb,
            thunderFile = thunderFile,
            currentManifest = manifest
        )
    }

    /**
     * Save a note's changes back to the .thunder file. Closes the
     * NoteDatabase, re-zips the staging dir, and updates the NoteEntity row
     * in the app-global DB.
     *
     * Returns the new [ThunderManifest] written to the .thunder file.
     */
    suspend fun saveNote(session: NoteEditingSession): ThunderManifest {
        require(session.isOpen) {
            "Cannot save a closed session — openNote must be called before saveNote."
        }
        // Close the NoteDatabase (TRUNCATE journal = single file, no WAL to checkpoint).
        session.noteDatabase.close()

        val sqliteFile = File(session.stagingDir, "note.sqlite")
        val previewFile = File(session.stagingDir, "preview.png").takeIf { it.exists() }
        val assetsDir = File(session.stagingDir, "assets").takeIf { it.exists() }

        val newManifest = ThunderFile.write(
            outputFile = session.thunderFile,
            manifestStub = session.manifest.copy(
                displayName = session.manifest.displayName,  // unchanged unless renamed
                modifiedAt = System.currentTimeMillis()
            ),
            sqliteFile = sqliteFile,
            previewFile = previewFile,
            assetsDir = assetsDir
        )
        session.currentManifest = newManifest

        // Mirror the new pageCount + fileSize + modifiedTime into the
        // app-global NoteEntity row.
        noteDao.mirrorSaveFromPerNoteDb(
            noteId = session.noteId,
            pageCount = newManifest.pageCount,
            fileSizeBytes = newManifest.fileSizeBytes
        )

        return newManifest
    }

    // ─── three-dot overflow operations (spec §6.2) ──────────────────────────

    suspend fun renameNote(noteId: String, newName: String) =
        noteDao.rename(noteId, newName)

    suspend fun changeCover(noteId: String, coverPath: String) =
        noteDao.changeCover(noteId, coverPath)

    suspend fun moveNote(noteId: String, newParentFolderId: String?) =
        noteDao.moveToFolder(noteId, newParentFolderId)

    suspend fun changeColor(noteId: String, color: Int) {
        // We don't have a direct changeColor on NoteDao — use update.
        val note = noteDao.getByNoteId(noteId) ?: return
        noteDao.update(note.copy(color = color))
    }

    // ─── bookmark ────────────────────────────────────────────────────────────

    suspend fun setNoteBookmarked(noteId: String, bookmarked: Boolean) =
        noteDao.setBookmarked(noteId, bookmarked)

    // ─── recycle bin ─────────────────────────────────────────────────────────

    suspend fun trashNote(noteId: String) = noteDao.moveToRecycleBin(noteId)
    suspend fun restoreNote(noteId: String) = noteDao.restoreFromRecycleBin(noteId)

    /** Permanently delete a note: removes the .thunder file AND the NoteEntity
     *  row. Use after the note has been moved to recycle bin (or directly to
     *  bypass the recycle bin — used by emptyTrash). */
    suspend fun deleteNotePermanently(noteId: String) {
        val note = noteDao.getByNoteId(noteId)
        if (note != null) {
            val thunderFile = File(note.filePath)
            if (thunderFile.exists()) thunderFile.delete()
            // Also delete the staging dir if there's one.
            stagingDirFor(noteId).deleteRecursively()
            noteDao.deleteByNoteId(noteId)
        }
    }

    // ─── reads (Flow — observable from ViewModel) ───────────────────────────

    fun observeNote(noteId: String): Flow<NoteEntity?> = noteDao.observeByNoteId(noteId)
    fun observeNotesByFolder(parentFolderId: String?): Flow<List<NoteEntity>> =
        noteDao.observeByParentFolder(parentFolderId)
    fun observeAllNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()
    fun observeBookmarkedNotes(): Flow<List<NoteEntity>> = noteDao.observeBookmarked()
    fun observeTrashedNotes(): Flow<List<NoteEntity>> = noteDao.observeTrashed()
    fun searchNotesByTitle(query: String): Flow<List<NoteEntity>> = noteDao.searchByTitle(query)

    suspend fun getNote(noteId: String): NoteEntity? = noteDao.getByNoteId(noteId)
}
