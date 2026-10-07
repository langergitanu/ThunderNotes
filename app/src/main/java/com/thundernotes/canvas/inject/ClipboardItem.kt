package com.thundernotes.canvas.inject

import com.thundernotes.canvas.StrokeRecord

/**
 * A single item held by [ThunderClipboard] — the "glowing paste button" payload
 * (spec §6.10 Row 2 + §7.3: the app clipboard holds exactly one copied item;
 * the paste button glows while it's held; on paste it appears as a floating
 * lasso/textbox the user positions + drops).
 *
 * Two variants, matching the snip output contract (spec §7.3.4):
 *  - [StrokeGroup]: equation/diagram snip output → native pen strokes (erasable).
 *  - [TextBox]: text/code snip output → a textbox.
 *
 * Coordinates are **relative** (the group's bbox is normalized to 0,0 at the
 * top-left) so a paste can drop the group at any (dropX, dropY) on the canvas
 * — the [InkInjector] translates by the drop point. Mirrors Notein's
 * `ClipboardData { List<selectedItems>, RectF border, Long timestamp }`
 * structure (Notein README §3) + the `LassoParams { selectedItems, border,
 * polygonBorder }` shape — adapted into our Kotlin model (Notein's field types
 * are obfuscated `InterfaceC#####OooO0O0`, so we don't copy them verbatim).
 */
sealed class ClipboardItem {
    /** The bounding box of the group, in the group's local (pre-drop) coords. */
    abstract val bbox: FloatArray

    /** A group of finished pen strokes (relative coords). */
    data class StrokeGroup(
        val strokes: List<StrokeRecord>,
        override val bbox: FloatArray,
    ) : ClipboardItem()

    /** A textbox (snipped text/code → appears as a textbox on paste). */
    data class TextBox(
        val text: String,
        val fontFamily: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Int,
        val x: Float,
        val y: Float,
        override val bbox: FloatArray,
    ) : ClipboardItem()
}
