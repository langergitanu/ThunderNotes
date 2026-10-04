package com.thundernotes.data.repository

import com.thundernotes.data.dao.FolderDao
import com.thundernotes.data.dao.NoteDao
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Business-logic layer for bookmarks — combines bookmarked notes + folders
 * for the BookmarksPage UI (spec §6.4: "displays all bookmarked files and
 * folders with some basic functions").
 *
 * Per spec §6.2, bookmarking is one of the 7 three-dot overflow functions
 * on a note card. For folders, the same pattern applies.
 */
class BookmarksRepository(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
) {

    /** Combined view of all bookmarked notes + folders. */
    fun observeBookmarked(): Flow<BookmarkedItems> = combine(
        noteDao.observeBookmarked(),
        folderDao.observeBookmarked()
    ) { notes, folders ->
        BookmarkedItems(notes = notes, folders = folders)
    }

    /** Toggle the bookmark flag on a note. */
    suspend fun toggleNoteBookmark(noteId: String) {
        val note = noteDao.getByNoteId(noteId) ?: return
        noteDao.setBookmarked(noteId, !note.isBookmarked)
    }

    /** Toggle the bookmark flag on a folder. */
    suspend fun toggleFolderBookmark(folderId: String) {
        val folder = folderDao.getByFolderId(folderId) ?: return
        folderDao.setBookmarked(folderId, !folder.isBookmarked)
    }

    /** Explicit set (used by the three-dot overflow "Bookmark" action). */
    suspend fun setNoteBookmarked(noteId: String, bookmarked: Boolean) =
        noteDao.setBookmarked(noteId, bookmarked)

    suspend fun setFolderBookmarked(folderId: String, bookmarked: Boolean) =
        folderDao.setBookmarked(folderId, bookmarked)
}

/** Combined bookmarked items for the BookmarksPage UI. */
data class BookmarkedItems(
    val notes: List<NoteEntity>,
    val folders: List<FolderEntity>
) {
    val isEmpty: Boolean get() = notes.isEmpty() && folders.isEmpty()
    val totalCount: Int get() = notes.size + folders.size
}
