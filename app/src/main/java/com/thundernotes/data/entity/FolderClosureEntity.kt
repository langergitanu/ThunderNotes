package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Closure table for the folder hierarchy — the standard efficient pattern for
 * arbitrary-depth trees, adopted directly from SamsungNotes'
 * `NotesCategoryTreeClosureEntity`.
 *
 * For each folder F, we store rows:
 *   - (F, F, 0)              — self
 *   - (parent(F), F, 1)      — direct parent
 *   - (grandparent(F), F, 2)
 *   - ... up to the root
 *
 * **Why this matters:** it lets us answer "all notes under folder X including
 * every subfolder" with a single SELECT join (notes JOIN folder_closure ON
 * notes.parent_folder_id = descendant_id WHERE ancestor_id = X) — no recursive
 * CTEs, no per-row parent climbing.
 *
 * The closure table is maintained transactionally whenever a folder is created,
 * moved, or deleted — see [com.thundernotes.data.dao.FolderClosureDao].
 *
 * The table uses a composite primary key (ancestor_id, descendant_id) because
 * each pair appears exactly once. Depth is included in the index because the
 * "direct children" query filters on depth = 1.
 */
@Entity(
    tableName = "folder_closure",
    primaryKeys = ["ancestor_id", "descendant_id"],
    indices = [
        Index(value = ["descendant_id"]),
        Index(value = ["depth"])
    ]
)
data class FolderClosureEntity(
    @ColumnInfo(name = "ancestor_id")
    val ancestorId: String,

    @ColumnInfo(name = "descendant_id")
    val descendantId: String,

    /** 0 = self, 1 = direct child, 2 = grandchild, ... */
    @ColumnInfo(name = "depth")
    val depth: Int
)
