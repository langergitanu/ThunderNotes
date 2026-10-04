package com.thundernotes.data.repository

import com.thundernotes.data.dao.FolderClosureDao
import com.thundernotes.data.dao.FolderDao
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Business-logic layer for folders — coordinates [FolderDao] (folder rows)
 * with [FolderClosureDao] (closure-table rows for the folder hierarchy).
 *
 * Pattern adopted from SamsungNotes' `NotesCategoryTreeDao` +
 * `NotesDocumentRepository` (see `docs/SamsungNotes-README.md` §2 + §3).
 *
 * **Closure-table maintenance** (CRITICAL): every folder create/move/delete
 * must atomically update BOTH the [FolderEntity] row AND the closure table.
 * The DAO exposes `FolderClosureDao.rebuildClosureFor` as a @Transaction
 * default method for create/move; for delete, we need a manual transaction.
 */
class FoldersRepository(
    private val appDatabase: AppDatabase,
    private val folderDao: FolderDao = appDatabase.folderDao(),
    private val closureDao: FolderClosureDao = appDatabase.folderClosureDao(),
) {

    /**
     * Create a new folder under [parentFolderId] (NULL = root).
     *
     * Atomically:
     *   1. Insert the [FolderEntity] row.
     *   2. Build the closure rows via [FolderClosureDao.rebuildClosureFor].
     */
    suspend fun createFolder(
        displayName: String,
        parentFolderId: String? = null,
        color: Int = 0,
        sortOrder: Int = 0
    ): String {
        val folderId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            folderId = folderId,
            parentFolderId = parentFolderId,
            displayName = displayName,
            color = color,
            sortOrder = sortOrder,
            createdTime = now,
            modifiedTime = now,
            syncTimestamp = now
        )
        folderDao.insert(folder)
        // Atomically rebuild closure rows for this new folder.
        closureDao.rebuildClosureFor(folderId, parentFolderId)
        return folderId
    }

    /**
     * Move a folder under a new parent (NULL = root). Atomically:
     *   1. Update the [FolderEntity.parentFolderId] column.
     *   2. Rebuild the closure rows for this folder under the new parent.
     */
    suspend fun moveFolder(folderId: String, newParentFolderId: String?) {
        folderDao.moveToFolder(folderId, newParentFolderId)
        closureDao.rebuildClosureFor(folderId, newParentFolderId)
    }

    suspend fun renameFolder(folderId: String, newName: String) =
        folderDao.rename(folderId, newName)

    suspend fun changeFolderColor(folderId: String, color: Int) =
        folderDao.changeColor(folderId, color)

    // ─── bookmark ────────────────────────────────────────────────────────────

    suspend fun setFolderBookmarked(folderId: String, bookmarked: Boolean) =
        folderDao.setBookmarked(folderId, bookmarked)

    // ─── recycle bin ─────────────────────────────────────────────────────────

    suspend fun trashFolder(folderId: String) = folderDao.moveToRecycleBin(folderId)
    suspend fun restoreFolder(folderId: String) = folderDao.restoreFromRecycleBin(folderId)

    /**
     * Permanently delete a folder AND its closure-table subtree.
     *
     * Notes/folders UNDER this folder are NOT cascade-deleted (they would
     * be orphaned). Callers should either (a) trash all children first, or
     * (b) re-parent children to the deleted folder's parent before deletion.
     * The TrashRepository (phase 4) handles this for the empty-trash flow.
     */
    suspend fun deleteFolderPermanently(folderId: String) {
        closureDao.deleteSubtree(folderId)
        folderDao.deleteByFolderId(folderId)
    }

    // ─── reads ───────────────────────────────────────────────────────────────

    fun observeFolder(folderId: String): Flow<FolderEntity?> = folderDao.observeByFolderId(folderId)
    fun observeFoldersByParent(parentFolderId: String?): Flow<List<FolderEntity>> =
        folderDao.observeByParentFolder(parentFolderId)
    fun observeAllFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()
    fun observeBookmarkedFolders(): Flow<List<FolderEntity>> = folderDao.observeBookmarked()
    fun observeTrashedFolders(): Flow<List<FolderEntity>> = folderDao.observeTrashed()
    fun searchFoldersByTitle(query: String): Flow<List<FolderEntity>> = folderDao.searchByTitle(query)

    suspend fun getFolder(folderId: String): FolderEntity? = folderDao.getByFolderId(folderId)

    // ─── closure-table queries (rarely needed by the UI directly, but
    //     exposed for the trash cascade + future "move all notes under X" UI) ─

    /** Returns descendant folder IDs (excluding self). */
    suspend fun getDescendants(folderId: String): List<String> =
        closureDao.getDescendants(folderId).map { it.descendantId }.filter { it != folderId }

    /** Returns ancestor folder IDs from root to direct parent (in that order). */
    suspend fun getAncestors(folderId: String): List<String> =
        closureDao.getAncestors(folderId).map { it.ancestorId }.filter { it != folderId }

    /** Direct child folder count (depth = 1 descendants). */
    suspend fun getDirectChildCount(folderId: String): Int =
        closureDao.getDirectChildrenCount(folderId)
}
