package com.thundernotes.ui.canvas

/**
 * The six authoring tools available on the canvas pen tray.
 *
 * Mirrors the tool set in the Frontend `canvasLayoutPage` mock:
 * Pen · Highlighter · Eraser · Lasso · Shape · Text.
 *
 * Each tool declares whether it shows the color picker and the stroke-width
 * selector in the secondary tray row. (Eraser + Lasso ignore color; Text
 * ignores stroke width.) This drives [NoteEditorViewModel]'s UI state and
 * keeps the tray logic testable without a View.
 */
enum class EditorTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    TEXT;

    /** Whether the color swatch row is relevant for this tool. */
    val showsColorPicker: Boolean
        get() = this != ERASER && this != LASSO

    /** Whether the stroke-width selector is relevant for this tool. */
    val showsStrokeWidth: Boolean
        get() = this == PEN || this == HIGHLIGHTER || this == SHAPE
}

/**
 * Fixed palette offered in the color swatch row (matches the mock's swatch set:
 * black, red, blue, green, amber, magenta + an eraser-white).
 *
 * Colors are ARGB ints so they can be applied directly to a View background /
 * Paint.color once AndroidX Ink lands in Phase 7.
 */
object EditorPalette {
    val COLORS: List<Int> = listOf(
        0xFF1A1A1A.toInt(), // obsidian ink (near-black)
        0xFFE53935.toInt(), // red
        0xFF1E88E5.toInt(), // blue
        0xFF43A047.toInt(), // green
        0xFFFB8C00.toInt(), // amber
        0xFF8E24AA.toInt(), // magenta
    )
    val DEFAULT_COLOR_INDEX = 0
}

/**
 * The three stroke widths offered in the width selector (thin / medium / thick).
 * Values are dp-equivalent stroke widths; the real Ink brush will be configured
 * from these in Phase 7.
 */
object EditorStrokeWidths {
    val WIDTHS_DP: List<Float> = listOf(1.5f, 3.0f, 6.0f)
    const val DEFAULT_WIDTH_INDEX = 1 // medium
}

/** Zoom bounds for the page surface, as percentages. */
object EditorZoom {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val STEP_PERCENT = 10
    const val DEFAULT_PERCENT = 100
}
