package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One page of a note — the unit of vertical scroll in the canvas.
 *
 * Pattern adopted from SamsungNotes' `NotesDocumentPageEntity` (readable in
 * `senl/p148nt/data/database/core/document/entity/`) — see
 * `docs/SamsungNotes-README.md` §2a.
 *
 * **Page-local coordinates (CRITICAL):** every stroke's stored x/y is relative
 * to the page's top-left corner, NOT a global canvas coordinate. When the user
 * adds writing space above this page, the strokes DON'T move — only the page's
 * render offset changes (computed via `data/spacer/SpacerManager.kt` using
 * a Fenwick prefix sum). This is the design that makes "Add Writing Space"
 * O(log N) instead of O(strokes-below-insertion-point).
 *
 * Per spec §6.6: page types are Blank and Lined; orientations are Portrait
 * and Landscape. Infinite Canvas is deferred.
 */
@Entity(
    tableName = "pages",
    indices = [
        Index(value = ["page_index"])
    ]
)
data class NotePageEntity(
    @PrimaryKey
    @ColumnInfo(name = "page_id")
    val pageId: String = UUID.randomUUID().toString(),

    /** 0-indexed position of this page in the note. */
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    /** Page background: see [PageType]. */
    @ColumnInfo(name = "page_type")
    val pageType: Int = PageType.BLANK.rawValue,

    /** Page orientation: see [PageOrientation]. */
    @ColumnInfo(name = "orientation")
    val orientation: Int = PageOrientation.PORTRAIT.rawValue,

    /** Width-to-height ratio (e.g. A4 portrait = 210/297 ≈ 0.707;
     * A4 landscape = 297/210 ≈ 1.414). */
    @ColumnInfo(name = "page_ratio")
    val pageRatio: Float = 0.707f,

    /** Rendered page height in pixels at 100% zoom (width = height × ratio).
     * This is the value the canvas uses to lay out pages. */
    @ColumnInfo(name = "page_height_px")
    val pageHeightPx: Int = 3508,  // A4 at 300 DPI ≈ 3508px tall

    /** Background color (ARGB int). Default = pure white for canvas-light,
     * or surface_base for canvas-dark. The theme toggle re-renders pages
     * with the inverted color (see `SmartThemeInverter.kt`, phase 4). */
    @ColumnInfo(name = "page_background_color")
    val pageBackgroundColor: Int = 0xFFFFFFFF.toInt(),

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis()
)
