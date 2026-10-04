package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.thundernotes.data.entity.FolderClosureEntity
import kotlinx.coroutines.flow.Flow

/**
 * Closure-table DAO for the folder hierarchy — adopted directly from
 * SamsungNotes' `NotesCategoryTreeClosureDao` pattern (see
 * `docs/SamsungNotes-README.md` §3).
 *
 * The closure table is maintained transactionally whenever a folder is created,
 * moved, or deleted. The [rebuildClosureFor] method is the workhorse:
 *
 *   - For a newly-created folder under parent P, we insert:
 *       (F, F, 0)                 — self
 *       (parent(P), F, 1)         — direct parent (P itself at depth 0 in its own chain)
 *       (grandparent(P), F, 2)
 *       ... up to the root
 *
 *   - For a folder being moved to a new parent, we first DELETE all rows
 *     where the folder is a descendant (via [deleteForDescendant]), then
 *     re-insert using the new parent's ancestor chain.
 *
 * This makes "list all notes under folder X including subfolders" a single
 * SELECT JOIN notes ON notes.parent_folder_id = closure.descendant_id
 * WHERE closure.ancestor_id = :X — no recursive CTE.
 *
 * See also: `data/repository/FoldersRepository.kt` (phase 4) for the higher-
 * level createFolder/moveFolder/deleteFolder operations that pair these closure
 * mutations with the [com.thundernotes.data.dao.FolderDao] writes inside a
 * single transaction.
 */
@Dao
interface FolderClosureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(closure: FolderClosureEntity)

    @Query("DELETE FROM folder_closure WHERE ancestor_id = :folderId OR descendant_id = :folderId")
    suspend fun deleteSubtree(folderId: String)

    @Query("DELETE FROM folder_closure WHERE descendant_id = :folderId")
    suspend fun deleteForDescendant(folderId: String)

    @Query("DELETE FROM folder_closure WHERE ancestor_id = :folderId AND descendant_id = :folderId")
    suspend fun deleteSelf(folderId: String)

    // ─── reads ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM folder_closure WHERE ancestor_id = :folderId AND depth = 1")
    fun observeDirectChildren(folderId: String): Flow<List<FolderClosureEntity>>

    @Query("""
        SELECT * FROM folder_closure
        WHERE descendant_id = :folderId
        ORDER BY depth DESC
    """)
    suspend fun getAncestors(folderId: String): List<FolderClosureEntity>

    @Query("""
        SELECT * FROM folder_closure
        WHERE ancestor_id = :folderId AND depth > 0
        ORDER BY depth ASC
    """)
    suspend fun getDescendants(folderId: String): List<FolderClosureEntity>

    @Query("""
        SELECT depth FROM folder_closure
        WHERE ancestor_id = :ancestorId AND descendant_id = :descendantId
    """)
    suspend fun getDepth(ancestorId: String, descendantId: String): Int?

    @Query("""
        SELECT COUNT(*) FROM folder_closure
        WHERE ancestor_id = :folderId AND depth = 1
    """)
    suspend fun getDirectChildrenCount(folderId: String): Int

    // ─── transactional compound operations ────────────────────────────────────

    /**
     * Atomically rebuild the closure rows for [folderId] as if it were newly
     * placed under [parentFolderId]. Use this both on folder creation AND on
     * folder move (move = delete old closure rows + rebuild under new parent).
     *
     * If [parentFolderId] is null, the folder lives at the root — only the
     * self-closure row (F, F, 0) is inserted.
     */
    @Transaction
    suspend fun rebuildClosureFor(folderId: String, parentFolderId: String?) {
        // Wipe any existing closure rows where this folder is a descendant.
        deleteForDescendant(folderId)
        // Self-closure at depth 0.
        insert(FolderClosureEntity(ancestorId = folderId, descendantId = folderId, depth = 0))
        // For each ancestor of the parent (including the parent itself at depth 0),
        // insert (ancestor, newFolder, ancestorDepth + 1).
        if (parentFolderId != null) {
            val parentChain = getAncestors(parentFolderId)
            parentChain.forEach { ancestorRow ->
                insert(
                    FolderClosureEntity(
                        ancestorId = ancestorRow.ancestorId,
                        descendantId = folderId,
                        depth = ancestorRow.depth + 1
                    )
                )
            }
        }
    }
}
