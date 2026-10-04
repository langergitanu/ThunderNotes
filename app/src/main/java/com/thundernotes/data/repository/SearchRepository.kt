package com.thundernotes.data.repository

import com.thundernotes.data.dao.FolderDao
import com.thundernotes.data.dao.NoteDao
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Business-logic layer for title-only search (spec §6.2 + §6.3: "the search
 * field searches titles only of files and folders").
 *
 * Combines note-search + folder-search into a single observable [SearchResults]
 * so the search UI can render both in one list.
 *
 * **Future scope (NOT implemented now):** the spec is title-only, but the
 * SamsungNotes reference (see `docs/SamsungNotes-README.md` §4) shows how to
 * add content+OCR search later — separate search-tables with raw queries.
 * The NoteEntity schema already has the [NoteEntity.displayName] column
 * indexed, so the title search scales fine; for content search we'd add a
 * `NotesStrokeSearchEntity` table mirroring SamsungNotes' pattern.
 */
class SearchRepository(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
) {

    /**
     * Combined search across notes + folders by display name.
     *
     * Uses SQLite `LIKE '%query%'` matching — case-insensitive by default
     * for ASCII; for full Unicode case-insensitivity we'd need a custom
     * collation (deferred — LIKE is sufficient for personal use).
     */
    fun searchByTitle(query: String): Flow<SearchResults> = combine(
        noteDao.searchByTitle(query),
        folderDao.searchByTitle(query)
    ) { notes, folders ->
        SearchResults(notes = notes, folders = folders, query = query)
    }
}

/** Combined search results for the search UI. */
data class SearchResults(
    val query: String,
    val notes: List<NoteEntity>,
    val folders: List<FolderEntity>
) {
    val totalCount: Int get() = notes.size + folders.size
    val isEmpty: Boolean get() = notes.isEmpty() && folders.isEmpty()
}
