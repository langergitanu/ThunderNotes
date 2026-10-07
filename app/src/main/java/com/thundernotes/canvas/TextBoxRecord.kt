package com.thundernotes.canvas

import java.util.UUID

/**
 * A textbox on a canvas page (spec §7.1) — typed content placed on the canvas
 * (the snip output target for text/code per §7.3.4: "text & code should appear
 * as textboxes"). Pure data, no Android/Ink dependency — rendered by the
 * page's textbox overlay layer as a positioned [android.widget.TextView].
 *
 * Coordinates are page-local (same model as strokes — thunder-format-proposal
 * Part C). Font family index maps to [com.thundernotes.data.entity.FontFamily].
 *
 * @param underline one of [com.thundernotes.data.entity.UnderlineType] (none/thin/thick/dashed/wavy).
 */
data class TextBoxRecord(
    val id: String = UUID.randomUUID().toString(),
    val pageId: String,
    val text: String,
    val x: Float,
    val y: Float,
    val fontFamily: Int = 0,
    val fontSizeSp: Float = 16f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Int = 0,
    val colorArgb: Int = 0xFF1A1A1A.toInt(),
)
