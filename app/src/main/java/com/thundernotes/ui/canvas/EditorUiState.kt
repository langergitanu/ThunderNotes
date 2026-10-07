package com.thundernotes.ui.canvas

/**
 * Immutable snapshot of the Canvas editor UI state.
 *
 * Driven by [NoteEditorViewModel]. The Activity observes this and re-renders
 * the toolbar / pen tray / color swatches / zoom controls.
 *
 * Phase 6 holds the editor *shell*: tool selection, color, stroke width, page
 * navigation, zoom, undo/redo flags, and the note's display name. The actual
 * AndroidX Ink stroke rendering lands in Phase 7 — the page surface is a
 * placeholder until then.
 */
data class EditorUiState(
    val noteId: String,
    val noteTitle: String,
    val selectedTool: EditorTool = EditorTool.PEN,
    val selectedPaletteIndex: Int = EditorPalette.DEFAULT_PALETTE_INDEX,
    val selectedColorIndex: Int = EditorPalette.DEFAULT_COLOR_INDEX,
    val strokeWidthIndex: Int = EditorStrokeWidths.DEFAULT_WIDTH_INDEX,
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    val zoomPercent: Int = EditorZoom.DEFAULT_PERCENT,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /** Convenience: the ARGB color currently selected (or null if N/A for tool).
     *  Looks up [selectedPaletteIndex] × [selectedColorIndex] in [EditorPalette.PALETTES]. */
    val selectedColorArgb: Int?
        get() = if (selectedTool.showsColorPicker)
            EditorPalette.PALETTES.getOrNull(selectedPaletteIndex)
                ?.getOrNull(selectedColorIndex) else null

    /** Convenience: the stroke width in dp currently selected (or null if N/A). */
    val selectedStrokeWidthDp: Float?
        get() = if (selectedTool.showsStrokeWidth)
            EditorStrokeWidths.WIDTHS_DP.getOrNull(strokeWidthIndex) else null

    /** True when the color swatch row should be visible. */
    val showsColorPicker: Boolean get() = selectedTool.showsColorPicker

    /** True when the stroke-width selector should be visible. */
    val showsStrokeWidth: Boolean get() = selectedTool.showsStrokeWidth
}
