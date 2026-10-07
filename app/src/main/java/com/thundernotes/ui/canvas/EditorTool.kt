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
 * The three color palettes offered in the swatch row (spec §6.10 Row 3c):
 *  - **ThunderDark** — the default pen palette (8 dark, high-chroma colors).
 *  - **ThunderLight** — the default highlighter palette (8 light colors).
 *  - **Sunflower** — a warm user-created palette (8 yellows/oranges/reds).
 *
 * Each palette has 8 colors. Colors are ARGB ints so they apply directly to a
 * View background / Paint color. The editor's [NoteEditorViewModel] tracks the
 * active palette + color index; the activity renders the active palette's
 * swatches + a palette-switcher (canvasCustomizationPage mock).
 */
object EditorPalette {

    val THUNDER_DARK: List<Int> = listOf(
        0xFF1A1A1A.toInt(), // obsidian ink (near-black)
        0xFFE53935.toInt(), // crimson red
        0xFF1E88E5.toInt(), // cobalt blue
        0xFF43A047.toInt(), // forest green
        0xFFFB8C00.toInt(), // amber
        0xFF8E24AA.toInt(), // magenta
        0xFF00ACC1.toInt(), // teal
        0xFF6D4C41.toInt(), // walnut brown
    )

    val THUNDER_LIGHT: List<Int> = listOf(
        0xFFFFFFFF.toInt(), // white
        0xFFFFCDD2.toInt(), // light red
        0xFFBBDEFB.toInt(), // light blue
        0xFFC8E6C9.toInt(), // light green
        0xFFFFE082.toInt(), // light amber
        0xFFE1BEE7.toInt(), // light magenta
        0xFFB2EBF2.toInt(), // light teal
        0xFFD7CCC8.toInt(), // light brown
    )

    val SUNFLOWER: List<Int> = listOf(
        0xFFFFF176.toInt(), // sunflower yellow
        0xFFFFD54F.toInt(), // mango
        0xFFFFB74D.toInt(), // orange
        0xFFFF8A65.toInt(), // coral
        0xFFEF5350.toInt(), // poppy red
        0xFFAB47BC.toInt(), // plum
        0xFFEC407A.toInt(), // pink
        0xFF827717.toInt(), // olive
    )

    /** The three named palettes, in the order the palette-switcher shows them. */
    val PALETTES: List<List<Int>> = listOf(THUNDER_DARK, THUNDER_LIGHT, SUNFLOWER)
    val PALETTE_NAMES: List<String> = listOf("ThunderDark", "ThunderLight", "Sunflower")

    /** Default palette per the spec: ThunderDark for pens, ThunderLight for highlighter. */
    const val DEFAULT_PALETTE_INDEX = 0
    const val DEFAULT_COLOR_INDEX = 0

    /** Back-compat: the single-palette API used before the 3-palette refactor
     *  (canvas colors + the editor VM's default). Aliases ThunderDark. */
    val COLORS: List<Int> get() = THUNDER_DARK
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

/**
 * Pen line type (spec §6.10 Row 3a: "Line Type (straight, dotted, dashed)").
 * Drives the AndroidX Ink brush family — STRAIGHT → `StockBrushes.pressurePen()`;
 * DOTTED/DASHED → `StockBrushes.dashedLine()` (the public built-in dashed family,
 * since Notein's `.brushfamily` assets are proprietary).
 */
enum class LineType {
    STRAIGHT,
    DOTTED,
    DASHED;
    companion object {
        const val DEFAULT_ORDINAL = 0
    }
}

/** Zoom bounds for the page surface, as percentages. */
object EditorZoom {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val STEP_PERCENT = 10
    const val DEFAULT_PERCENT = 100
}
