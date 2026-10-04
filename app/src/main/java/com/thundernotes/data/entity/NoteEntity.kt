package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A ThunderNotes document (one `.thunder` file on disk).
 *
 * Pattern adopted from SamsungNotes' `NotesDocumentEntity` (readable in
 * `senl/p148nt/data/database/core/document/entity/`) with the addition of
 * Samsung's recycle-bin + sync-timestamp columns.
 *
 * **Where the page/stroke content lives:** in a SEPARATE per-note SQLite DB
 * INSIDE the `.thunder` ZIP (see `NoteDatabase` + the per-note entities).
 * This row only holds the metadata needed to display the note in the library,
 * trash, bookmarks, and search — never the strokes themselves.
 *
 * **Soft-delete pattern:** "trashing" a note sets [recycleBinTimeMoved] and
 * [recycleBinExpiresAt]; the row stays. Permanent delete removes the row AND
 * deletes the `.thunder` file.
 *
 * **Sync pattern:** every mutation bumps [syncTimestamp] so a future cloud sync
 * can reconcile by last-write-wins (Samsung's `deleteByUuidWithTimestampIncrease`
 * pattern).
 *
 * **Bookmarking:** uses the [isBookmarked] flag rather than a separate bookmarks
 * table — simpler for the spec's title-only / single-user scenario. We can split
 * a separate `BookmarkEntity` table later if multi-device sync needs it.
 */
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["parent_folder_id"]),
        Index(value = ["display_name"]),
        Index(value = ["recycle_bin_time_moved"]),
        Index(value = ["is_bookmarked"]),
        Index(value = ["sync_timestamp"])
    ]
)
data class NoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "note_id")
    val noteId: String = UUID.randomUUID().toString(),

    /** Folder containing this note. NULL = root level (no folder). */
    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String? = null,

    /** User-visible title (the spec's "search by title" target). */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Path to the `.thunder` file under app-external storage
     * (e.g. `files/notes/<noteId>.thunder`). Relative to the app's
     * `getExternalFilesDir(null)` so it survives app upgrades. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** Path to the cover preview PNG (extracted from the `.thunder` ZIP's
     * `preview.png` on first import, for fast library-grid rendering). */
    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,

    /** Index into [com.thundernotes.theme.BrandPalette] for the note card accent. */
    @ColumnInfo(name = "color")
    val color: Int = 0,

    /** Total page count. Mirrored from the per-note DB on save. */
    @ColumnInfo(name = "page_count")
    val pageCount: Int = 1,

    /** File size of the `.thunder` in bytes (for the "Information" popup). */
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    /** Page type at creation: see [com.thundernotes.data.entity.PageType]. */
    @ColumnInfo(name = "default_page_type")
    val defaultPageType: Int = PageType.BLANK.rawValue,

    /** Page orientation at creation: see [com.thundernotes.data.entity.PageOrientation]. */
    @ColumnInfo(name = "default_orientation")
    val defaultOrientation: Int = PageOrientation.PORTRAIT.rawValue,

    /** Soft-delete: when set (epoch ms), the note is in the recycle bin. Null = active. */
    @ColumnInfo(name = "recycle_bin_time_moved")
    val recycleBinTimeMoved: Long? = null,

    /** Auto-purge time. The recycle bin empties entries past this time (epoch ms). */
    @ColumnInfo(name = "recycle_bin_expires_at")
    val recycleBinExpiresAt: Long? = null,

    /** Bookmark toggle. */
    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,

    @ColumnInfo(name = "bookmark_time")
    val bookmarkTime: Long? = null,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    /** Bumped on every mutation; for future cloud sync reconciliation. */
    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
)
