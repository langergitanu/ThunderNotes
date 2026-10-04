package com.thundernotes.data.repository

import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Business-logic layer for the recycle bin.
 *
 * Per spec §6.5: "It displays all trashed files and folders with basic
 * functions such as Restore, Delete, Empty Trash, Restore All, etc."
 *
 * This repository combines notes and folders trashed in the app-global DB
 * and applies operations across both. Permanent deletes also remove the
 * .thunder file for trashed notes (via [NotesRepository.deleteNotePermanently]).
 */
class TrashRepository(
    private val notesRepo: NotesRepository,
    private val foldersRepo: FoldersRepository,
    private val appDatabase: AppDatabase,
) {

    /** Combined view of trashed notes + folders for the TrashPage UI. */
    fun observeTrashed(): Flow<TrashedItems> = combine(
        appDatabase.noteDao().observeTrashed(),
        appDatabase.folderDao().observeTrashed()
    ) { notes, folders ->
        TrashedItems(notes = notes, folders = folders)
    }

    /** Restore all trashed notes + folders in one operation. */
    suspend fun restoreAll() {
        appDatabase.noteDao().restoreAllFromRecycleBin()
        appDatabase.folderDao().restoreAllFromRecycleBin()
    }

    /**
     * Empty the trash — permanently deletes all trashed notes + folders.
     * For notes, this also removes the .thunder file on disk.
     */
    suspend fun emptyTrash() {
        // Read the current trashed lists BEFORE deleting them.
        // Room Flows always emit the current DB state on first collect, so
        // `first()` returns the current trashed list synchronously.
        val trashedNotes = appDatabase.noteDao().observeTrashed().first()
        val trashedFolders = appDatabase.folderDao().observeTrashed().first()

        // Permanently delete each note (also deletes its .thunder file).
        trashedNotes.forEach { notesRepo.deleteNotePermanently(it.noteId) }

        // Permanently delete each folder + its closure-table subtree.
        trashedFolders.forEach { foldersRepo.deleteFolderPermanently(it.folderId) }

        // Fallback: bulk-DELETE any rows that the per-id deletes missed
        // (shouldn't be necessary but cheap to call).
        appDatabase.noteDao().emptyRecycleBin()
        appDatabase.folderDao().emptyRecycleBin()
    }

    /** Per-item restore (used by the per-row "Restore" button). */
    suspend fun restoreNote(noteId: String) = notesRepo.restoreNote(noteId)
    suspend fun restoreFolder(folderId: String) = foldersRepo.restoreFolder(folderId)

    /** Per-item permanent delete (used by the per-row "Delete" button). */
    suspend fun deleteNotePermanently(noteId: String) = notesRepo.deleteNotePermanently(noteId)
    suspend fun deleteFolderPermanently(folderId: String) = foldersRepo.deleteFolderPermanently(folderId)
}

/** Combined trashed items for the TrashPage UI. */
data class TrashedItems(
    val notes: List<NoteEntity>,
    val folders: List<FolderEntity>
) {
    val isEmpty: Boolean get() = notes.isEmpty() && folders.isEmpty()
    val totalCount: Int get() = notes.size + folders.size
}
