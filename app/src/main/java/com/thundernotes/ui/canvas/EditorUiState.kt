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
    val selectedLineType: LineType = LineType.entries[LineType.DEFAULT_ORDINAL],
    /** Spec §7.5: if constant scaling is ON, a lasso Enlarge/Reduce does NOT change
     *  stroke thickness; if OFF, thickness scales proportionally with the resize. */
    val constantScaling: Boolean = true,
    /** Spec §6.1.9: palm rejection is on by default — the host rejects FINGER
     *  touches, accepting only STYLUS/ERASER (Notein's pattern: getToolType(0)==STYLUS). */
    val palmRejection: Boolean = true,
    /** Spec §6.10 Row 2c Gridline: an m×n grid overlay on the canvas. */
    val gridVisible: Boolean = false,
    val gridRows: Int = 8,
    val gridCols: Int = 4,
    /** Spec §6.10 Row 3g Shape Picker: the active shape type (Line/Rect/etc.). */
    val shapeType: ShapeType = ShapeType.entries[ShapeType.DEFAULT_ORDINAL],
    /** Spec §6.10.6d Eraser: AREA = drag-rect-remove-all; SHAPE = tap-remove-one. */
    val eraserType: EraserType = EraserType.entries[EraserType.DEFAULT_ORDINAL],
    /** Spec §6.10.6d Eraser size index (reuses EditorStrokeWidths for the slider). */
    val eraserSizeIndex: Int = EditorStrokeWidths.DEFAULT_WIDTH_INDEX,
    /** Spec §6.10.6e Lasso: RECT = drag-rectangle; FREEFORM = trace polygon. */
    val lassoMode: LassoMode = LassoMode.entries[LassoMode.DEFAULT_ORDINAL],
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

    /** True when the line-type selector should be visible (pens + highlighter). */
    val showsLineType: Boolean get() = selectedTool.showsLineType

    /** Convenience: the eraser hit radius in dp (reuses the stroke-width slider). */
    val eraserSizeDp: Float
        get() = EditorStrokeWidths.WIDTHS_DP.getOrElse(eraserSizeIndex) { 6.0f } * 4f
}
