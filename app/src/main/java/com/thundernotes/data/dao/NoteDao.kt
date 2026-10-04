package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [NoteEntity] — the global notes list.
 *
 * Mirrors SamsungNotes' `NotesDocumentDao` patterns (see
 * `docs/SamsungNotes-README.md` §2b):
 *   - title-only LIKE search
 *   - bookmark flag + bookmark_time
 *   - recycle_bin_time_moved + recycle_bin_expires_at (soft-delete)
 *   - sync_timestamp bump on every mutation
 *
 * All write methods are `suspend` because we use Room's coroutine support
 * (`room-ktx`). Read methods return [Flow] so the UI observes live updates
 * (insert a note → the library grid updates without manual refresh).
 */
@Dao
interface NoteDao {

    // ─── inserts / updates / deletes ──────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE note_id = :noteId")
    suspend fun deleteByNoteId(noteId: String)

    // ─── single-row reads ────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE note_id = :noteId")
    suspend fun getByNoteId(noteId: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE note_id = :noteId")
    fun observeByNoteId(noteId: String): Flow<NoteEntity?>

    // ─── library listings (Flow) ───────────────────────────────────────────────

    /** All active notes under [parentFolderId] (NULL = root level). */
    @Query("""
        SELECT * FROM notes
        WHERE parent_folder_id IS :parentFolderId
          AND recycle_bin_time_moved IS NULL
        ORDER BY modified_time DESC
    """)
    fun observeByParentFolder(parentFolderId: String?): Flow<List<NoteEntity>>

    /** All active notes anywhere in the tree (root + every subfolder). */
    @Query("""
        SELECT * FROM notes
        WHERE recycle_bin_time_moved IS NULL
        ORDER BY modified_time DESC
    """)
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("""
        SELECT * FROM notes
        WHERE is_bookmarked = 1
          AND recycle_bin_time_moved IS NULL
        ORDER BY bookmark_time DESC
    """)
    fun observeBookmarked(): Flow<List<NoteEntity>>

    @Query("""
        SELECT * FROM notes
        WHERE recycle_bin_time_moved IS NOT NULL
        ORDER BY recycle_bin_time_moved DESC
    """)
    fun observeTrashed(): Flow<List<NoteEntity>>

    /** Title-only search per spec §6.2 ("the search field searches titles only"). */
    @Query("""
        SELECT * FROM notes
        WHERE recycle_bin_time_moved IS NULL
          AND display_name LIKE '%' || :query || '%'
        ORDER BY modified_time DESC
    """)
    fun searchByTitle(query: String): Flow<List<NoteEntity>>

    // ─── three-dot overflow operations (spec §6.2: Rename, Change Cover,
    //     Move, Export, Bookmark, Information, Trash) ─────────────────────────

    /** Rename — bumps modified_time + sync_timestamp. */
    @Query("""
        UPDATE notes
        SET display_name = :newName,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun rename(noteId: String, newName: String, now: Long = System.currentTimeMillis())

    /** Change cover image. */
    @Query("""
        UPDATE notes
        SET cover_path = :coverPath,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun changeCover(noteId: String, coverPath: String, now: Long = System.currentTimeMillis())

    /** Move to a different parent folder (NULL = root). */
    @Query("""
        UPDATE notes
        SET parent_folder_id = :newParentFolderId,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun moveToFolder(
        noteId: String,
        newParentFolderId: String?,
        now: Long = System.currentTimeMillis()
    )

    /** Toggle bookmark. */
    @Query("""
        UPDATE notes
        SET is_bookmarked = :bookmarked,
            bookmark_time = CASE WHEN :bookmarked THEN :now ELSE NULL END,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun setBookmarked(
        noteId: String,
        bookmarked: Boolean,
        now: Long = System.currentTimeMillis()
    )

    // ─── recycle bin ──────────────────────────────────────────────────────────

    /** Move to recycle bin. Sets both recycleBinTimeMoved and recycleBinExpiresAt. */
    @Query("""
        UPDATE notes
        SET recycle_bin_time_moved = :now,
            recycle_bin_expires_at = :expiresAt,
            is_bookmarked = 0,
            bookmark_time = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun moveToRecycleBin(
        noteId: String,
        now: Long = System.currentTimeMillis(),
        expiresAt: Long = now + RECYLE_BIN_TTL_MS
    )

    /** Restore from recycle bin. */
    @Query("""
        UPDATE notes
        SET recycle_bin_time_moved = NULL,
            recycle_bin_expires_at = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun restoreFromRecycleBin(
        noteId: String,
        now: Long = System.currentTimeMillis()
    )

    /** Auto-purge: delete notes whose recycle_bin_expires_at has passed. */
    @Query("""
        DELETE FROM notes
        WHERE recycle_bin_expires_at IS NOT NULL
          AND recycle_bin_expires_at < :now
    """)
    suspend fun deleteExpiredRecycleBinEntries(now: Long = System.currentTimeMillis()): Int

    /** Batch-restore all trashed notes. */
    @Query("""
        UPDATE notes
        SET recycle_bin_time_moved = NULL,
            recycle_bin_expires_at = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE recycle_bin_time_moved IS NOT NULL
    """)
    suspend fun restoreAllFromRecycleBin(now: Long = System.currentTimeMillis()): Int

    /** Empty the recycle bin — permanent delete. */
    @Query("DELETE FROM notes WHERE recycle_bin_time_moved IS NOT NULL")
    suspend fun emptyRecycleBin(): Int

    // ─── mirror from per-note DB on save ──────────────────────────────────────

    @Query("""
        UPDATE notes
        SET page_count = :pageCount,
            file_size_bytes = :fileSizeBytes,
            modified_time = :now,
            sync_timestamp = :now
        WHERE note_id = :noteId
    """)
    suspend fun mirrorSaveFromPerNoteDb(
        noteId: String,
        pageCount: Int,
        fileSizeBytes: Long,
        now: Long = System.currentTimeMillis()
    )

    companion object {
        /** 30-day recycle-bin TTL (matches most notes apps). */
        const val RECYLE_BIN_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
