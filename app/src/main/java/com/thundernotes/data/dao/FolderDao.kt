package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [FolderEntity] — the global folders list.
 *
 * Mirrors SamsungNotes' `NotesCategoryTreeDao` patterns (see
 * `docs/SamsungNotes-README.md` §3).
 *
 * **Closure table:** the parent-child relationships here are ALSO stored in
 * [FolderClosureDao] for O(1) subtree queries. The [moveToFolder] operation
 * must be paired with a [FolderClosureDao.rebuildClosureFor] call inside a
 * single transaction; see `FoldersRepository` (phase 4).
 *
 * The [delete] operation should be paired with
 * [FolderClosureDao.deleteSubtree] inside a transaction; see
 * `FoldersRepository`.
 */
@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE folder_id = :folderId")
    suspend fun deleteByFolderId(folderId: String)

    @Query("SELECT * FROM folders WHERE folder_id = :folderId")
    suspend fun getByFolderId(folderId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE folder_id = :folderId")
    fun observeByFolderId(folderId: String): Flow<FolderEntity?>

    /** All active sub-folders of [parentFolderId] (NULL = root). */
    @Query("""
        SELECT * FROM folders
        WHERE parent_folder_id IS :parentFolderId
          AND recycle_bin_time_moved IS NULL
        ORDER BY sort_order ASC, display_name ASC
    """)
    fun observeByParentFolder(parentFolderId: String?): Flow<List<FolderEntity>>

    @Query("""
        SELECT * FROM folders
        WHERE recycle_bin_time_moved IS NULL
        ORDER BY display_name ASC
    """)
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("""
        SELECT * FROM folders
        WHERE is_bookmarked = 1
          AND recycle_bin_time_moved IS NULL
        ORDER BY bookmark_time DESC
    """)
    fun observeBookmarked(): Flow<List<FolderEntity>>

    @Query("""
        SELECT * FROM folders
        WHERE recycle_bin_time_moved IS NOT NULL
        ORDER BY recycle_bin_time_moved DESC
    """)
    fun observeTrashed(): Flow<List<FolderEntity>>

    /** Title-only search per spec §6.3. */
    @Query("""
        SELECT * FROM folders
        WHERE recycle_bin_time_moved IS NULL
          AND display_name LIKE '%' || :query || '%'
        ORDER BY display_name ASC
    """)
    fun searchByTitle(query: String): Flow<List<FolderEntity>>

    // ─── three-dot overflow ops (same shape as NoteDao) ───────────────────────

    @Query("""
        UPDATE folders
        SET display_name = :newName,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun rename(folderId: String, newName: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE folders
        SET color = :color,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun changeColor(folderId: String, color: Int, now: Long = System.currentTimeMillis())

    /** Move under a new parent. Pair with FolderClosureDao.rebuildClosureFor. */
    @Query("""
        UPDATE folders
        SET parent_folder_id = :newParentFolderId,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun moveToFolder(
        folderId: String,
        newParentFolderId: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE folders
        SET is_bookmarked = :bookmarked,
            bookmark_time = CASE WHEN :bookmarked THEN :now ELSE NULL END,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun setBookmarked(
        folderId: String,
        bookmarked: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE folders
        SET recycle_bin_time_moved = :now,
            recycle_bin_expires_at = :expiresAt,
            is_bookmarked = 0,
            bookmark_time = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun moveToRecycleBin(
        folderId: String,
        now: Long = System.currentTimeMillis(),
        expiresAt: Long = now + RECYLE_BIN_TTL_MS
    )

    @Query("""
        UPDATE folders
        SET recycle_bin_time_moved = NULL,
            recycle_bin_expires_at = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE folder_id = :folderId
    """)
    suspend fun restoreFromRecycleBin(
        folderId: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        DELETE FROM folders
        WHERE recycle_bin_expires_at IS NOT NULL
          AND recycle_bin_expires_at < :now
    """)
    suspend fun deleteExpiredRecycleBinEntries(now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE folders
        SET recycle_bin_time_moved = NULL,
            recycle_bin_expires_at = NULL,
            modified_time = :now,
            sync_timestamp = :now
        WHERE recycle_bin_time_moved IS NOT NULL
    """)
    suspend fun restoreAllFromRecycleBin(now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM folders WHERE recycle_bin_time_moved IS NOT NULL")
    suspend fun emptyRecycleBin(): Int

    companion object {
        const val RECYLE_BIN_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
