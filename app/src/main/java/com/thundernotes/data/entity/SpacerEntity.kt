package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * "Add Extra Writing Space" metadata — per spec §7.4 and
 * `docs/thunder-format-proposal.md` Part C.
 *
 * The reference implementations (Notein + Samsung) DO NOT shift strokes
 * when space is added. Notein keeps an `unbounded_page_offset_x/y` on
 * `NoteContentEntity` and a per-page `PageOffSet{offset_x, offset_y}`;
 * Samsung offers `SpenWNote.PageMode.SINGLE` and `insertPage(index)` —
 * neither moves existing strokes.
 *
 * Our model goes further: instead of one offset per page (Notein), we
 * support arbitrary gap insertions within a page via the [SpacerEntity].
 * The page's render Y for any point is computed as:
 *
 *   globalY = pageLocalY
 *           + cumulativePageOffsetUpTo(page)
 *           + Σ(spacer heights above this point on this page)
 *
 * The Σ is computed in O(log N) via a Fenwick prefix sum maintained in
 * `data/spacer/SpacerManager.kt` — that's the data structure that
 * keeps the canvas snappy on 1000-page documents.
 *
 * **CRITICAL:** strokes/textboxes/shapes have page-local immutable
 * coordinates. Adding/removing a spacer DOES NOT touch the strokes —
 * it only changes the offset metadata used by the renderer.
 */
@Entity(
    tableName = "spacers",
    indices = [
        Index(value = ["anchor_page_id"]),
        Index(value = ["offset_in_page"])
    ]
)
data class SpacerEntity(
    @PrimaryKey
    @ColumnInfo(name = "spacer_id")
    val spacerId: String = UUID.randomUUID().toString(),

    /** Page on which this spacer lives. */
    @ColumnInfo(name = "anchor_page_id")
    val anchorPageId: String,

    /** Y offset within the page (in canvas units at 100% zoom) where the
     *  spacer is inserted. Spacers are ordered by this value within a page. */
    @ColumnInfo(name = "offset_in_page")
    val offsetInPage: Float,

    /** Vertical height of the spacer (in canvas units at 100% zoom). */
    @ColumnInfo(name = "height")
    val height: Float,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis()
)
