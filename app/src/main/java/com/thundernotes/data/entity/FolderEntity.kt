package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A user-created folder.
 *
 * Pattern adopted from SamsungNotes' `NotesCategoryTreeEntity` with the
 * recycle-bin + sync-timestamp columns from `NotesRecycleBinDocumentEntity`.
 *
 * **Folder hierarchy:** the parent-child relationships are ALSO stored in
 * [FolderClosureEntity] (the closure-table pattern) for O(1) subtree queries.
 * The [parentFolderId] column here is the direct parent only — it's the
 * authoritative source for "who is my parent"; the closure table is derived.
 *
 * **Why both?** The closure table makes "list all notes under folder X including
 * its subfolders" a single SELECT join; the [parentFolderId] column makes
 * "list children of folder X" a single WHERE clause (no join needed). Having
 * both is the standard pattern.
 */
@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["parent_folder_id"]),
        Index(value = ["display_name"]),
        Index(value = ["recycle_bin_time_moved"]),
        Index(value = ["is_bookmarked"]),
        Index(value = ["sync_timestamp"])
    ]
)
data class FolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String = UUID.randomUUID().toString(),

    /** Parent folder. NULL = root level. */
    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String? = null,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Index into [com.thundernotes.theme.BrandPalette] for the folder accent. */
    @ColumnInfo(name = "color")
    val color: Int = 0,

    /** Sort order under the parent folder. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** Soft-delete: when set (epoch ms), the folder is in the recycle bin. */
    @ColumnInfo(name = "recycle_bin_time_moved")
    val recycleBinTimeMoved: Long? = null,

    @ColumnInfo(name = "recycle_bin_expires_at")
    val recycleBinExpiresAt: Long? = null,

    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,

    @ColumnInfo(name = "bookmark_time")
    val bookmarkTime: Long? = null,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
)
