package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A textbox on the canvas — typed content with rich formatting.
 *
 * Per spec §7.1, a textbox supports:
 *   Bold, Italic, Underline (thin, thick, dashed, wavy), Font Size,
 *   Font Family (10 predefined — 2 serif, 2 sans-serif, 6 handwriting),
 *   Fill Color (background of the textbox).
 *
 * Per spec §7.1, the 10 fonts are:
 *   - Sans-serif: Noto Sans (owner-chosen), Inter (developer-chosen)
 *   - Serif:      STIX Two Text (owner-chosen), Noto Serif (developer-chosen)
 *   - Handwriting: Patrick Hand, Short Stack, Comic Neue (owner-chosen),
 *                  Caveat, Kalam, Edu AU VIC WA NT Hand (developer-chosen)
 *
 * The [fontFamilyId] is an index into [FontFamily] constants — the actual
 * TTFs ship under `assets/fonts/` and are loaded by the textbox renderer.
 *
 * Textbox theme: per spec §7.1, the textbox has BOTH a global setting (in
 * canvasSettingsPage) AND a local change (in canvasUtilityPage). The global
 * setting is the default for new textboxes; the local setting is the
 * textbox's own override (stored here as columns).
 *
 * Page-local bounding box, same convention as strokes/shapes.
 */
@Entity(
    tableName = "textboxes",
    indices = [
        Index(value = ["page_id", "layer_id"]),
        Index(value = ["layer_id"]),
        Index(value = ["font_family_id"]),
        Index(value = ["page_id", "left", "right", "top", "bottom"])
    ]
)
data class TextBoxEntity(
    @PrimaryKey
    @ColumnInfo(name = "textbox_id")
    val textboxId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "layer_id")
    val layerId: String,

    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** The text content (may include newlines). */
    @ColumnInfo(name = "text")
    val text: String = "",

    /** Index into [FontFamily] constants. */
    @ColumnInfo(name = "font_family_id")
    val fontFamilyId: Int = FontFamily.NOTO_SANS,

    /** Font size in sp-equivalent canvas units. */
    @ColumnInfo(name = "font_size")
    val fontSize: Float = 16.0f,

    @ColumnInfo(name = "is_bold")
    val isBold: Boolean = false,

    @ColumnInfo(name = "is_italic")
    val isItalic: Boolean = false,

    /** See [UnderlineType]. */
    @ColumnInfo(name = "underline_type")
    val underlineType: Int = UnderlineType.NONE.rawValue,

    /** ARGB int background color, or null for transparent background. */
    @ColumnInfo(name = "fill_color")
    val fillColor: Int? = null,

    /** ARGB int text color. */
    @ColumnInfo(name = "text_color")
    val textColor: Int = 0xFF000000.toInt(),

    @ColumnInfo(name = "left")
    val left: Float,
    @ColumnInfo(name = "top")
    val top: Float,
    @ColumnInfo(name = "right")
    val right: Float,
    @ColumnInfo(name = "bottom")
    val bottom: Float,

    /** Rotation in degrees (0..360). */
    @ColumnInfo(name = "rotation")
    val rotation: Float = 0.0f,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
)
