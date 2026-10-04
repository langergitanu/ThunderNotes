package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A PDF imported as a page background (per spec mentions PDF info in the
 * .thunder format; per Notein's `pdfInfoId` reference on `NoteContentEntity`).
 *
 * When the user imports a PDF, each PDF page becomes a "page" in the note
 * (rendered as a background image of the PDF page + strokes/textboxes
 * layered on top). The PDF file itself is embedded in the .thunder ZIP's
 * `assets/` directory; [filePath] is relative to that root.
 *
 * The canvas renders the PDF page at [scale] × [rotation] with an
 * ([offsetX], [offsetY]) translation, then draws strokes/textboxes/etc.
 * on top using the same page-local coordinate convention.
 */
@Entity(
    tableName = "pdf_info",
    indices = [
        Index(value = ["file_path"])
    ]
)
data class PdfInfoEntity(
    @PrimaryKey
    @ColumnInfo(name = "pdf_info_id")
    val pdfInfoId: String = UUID.randomUUID().toString(),

    /** Path relative to the .thunder ZIP's `assets/` root, e.g. `pdfs/source.pdf`. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "page_count")
    val pageCount: Int = 0,

    /** Currently-rendered page (resumed on re-open). */
    @ColumnInfo(name = "current_page")
    val currentPage: Int = 0,

    /** Render scale (1.0 = 100%). */
    @ColumnInfo(name = "scale")
    val scale: Float = 1.0f,

    /** Rotation in degrees (0 / 90 / 180 / 270). */
    @ColumnInfo(name = "rotation")
    val rotation: Int = 0,

    @ColumnInfo(name = "offset_x")
    val offsetX: Float = 0.0f,

    @ColumnInfo(name = "offset_y")
    val offsetY: Float = 0.0f
)
