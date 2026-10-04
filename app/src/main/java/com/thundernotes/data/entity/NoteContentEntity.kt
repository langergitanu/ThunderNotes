package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-note top-level metadata, stored INSIDE the `.thunder` ZIP's `note.sqlite`.
 *
 * Pattern adopted from Notein's `NoteContentEntity` (readable in
 * `notein/data/p602db/entity/`) — see `docs/Notein-README.md` §3.
 *
 * Notein stores `pageListJson`, `pageLayerListJson`, `outlines`, `defaultPageId`,
 * `pdfInfoId`, `savedPageIndex`, `activatedPageLayerId`, `zoom`, `pageOffSet`,
 * `unboundedNote`, `extrasJson` in a single row. We mirror that exactly, plus
 * we add explicit `page_offset_x/y` (per-page offset tracking for the Add
 * Writing Space feature; see `SpacerEntity` + `data/spacer/SpacerManager.kt`).
 *
 * There is exactly ONE row per `.thunder` file. The primary key is the noteId
 * itself (mirrors the parent `NoteEntity.noteId`).
 */
@Entity(tableName = "note_content")
data class NoteContentEntity(
    @PrimaryKey
    @ColumnInfo(name = "note_id")
    val noteId: String,

    /** The page that opens when the user re-opens this note. */
    @ColumnInfo(name = "default_page_id")
    val defaultPageId: String? = null,

    /** Last-viewed page index (resumed on next open). */
    @ColumnInfo(name = "saved_page_index")
    val savedPageIndex: Int = 0,

    /** Currently-active layer id (for the layer switcher). */
    @ColumnInfo(name = "activated_page_layer_id")
    val activatedPageLayerId: String? = null,

    /** Last-applied zoom level (0..N where 1.0 = 100%). */
    @ColumnInfo(name = "zoom")
    val zoom: Float = 1.0f,

    /** Note-level X offset (Notein's `unbounded_page_offset_x`). Combined
     * with per-page offsets to compute the global render X of any stroke. */
    @ColumnInfo(name = "page_offset_x")
    val pageOffsetX: Float = 0.0f,

    @ColumnInfo(name = "page_offset_y")
    val pageOffsetY: Float = 0.0f,

    /** Infinite-canvas feel: when true, pages render with no gap between them
     * (only a dotted separator, per spec §6.10 Canvas Area). */
    @ColumnInfo(name = "unbounded_note")
    val unboundedNote: Boolean = true,

    /** PDF imported as page background (nullable). When set, [pdfInfoId]
     * references the `pdf_info` row with the file path + page count. */
    @ColumnInfo(name = "pdf_info_id")
    val pdfInfoId: String? = null,

    /** Free-form extras (JSON) for forward-compat — anything not yet
     * promoted to a typed column lands here. */
    @ColumnInfo(name = "extras_json")
    val extrasJson: String = "{}"
)
