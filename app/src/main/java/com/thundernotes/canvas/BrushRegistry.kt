package com.thundernotes.canvas

import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType

/**
 * Maps an [EditorTool][com.thundernotes.ui.canvas.EditorTool] + the user's
 * selected color + stroke-width to a concrete [EditorBrushConfig] that the
 * Ink host uses to build an `androidx.ink.brush.Brush` + `BrushFamily`
 * (via `StockBrushes`) for the active stroke.
 *
 * Pure + unit-testable (no Android/Ink dependency). The mapping encodes the
 * spec §6.10 Row-3 penset rules (PEN → ballpoint, HIGHLIGHTER → wide semi-
 * transparent, ERASER → remove-on-hit; LASSO/SHAPE/TEXT are not brushes).
 *
 * Brush-family IDs come from [BrushFamily] (our own `thunder-*-v1` IDs,
 * mirroring Notein's `notein-*-v1` naming per the Notein README §2).
 */
data class EditorBrushConfig(
    val familyId: String,
    val sizeDp: Float,
    val epsilon: Float,
    val colorArgb: Int,
    val isEraser: Boolean,
    val toolType: Int = ToolType.STYLUS.rawValue,
)

object BrushRegistry {

    /** Map a UI tool + color + width to a brush config. */
    fun configFor(
        tool: com.thundernotes.ui.canvas.EditorTool,
        colorArgb: Int,
        widthDp: Float,
    ): EditorBrushConfig = when (tool) {
        // Fountain-style pen — variable width. We use the ballpoint family for
        // now (PHASE 8 core); fountain family is a brush-asset concern (Phase 8b).
        com.thundernotes.ui.canvas.EditorTool.PEN ->
            EditorBrushConfig(BrushFamily.THUNDER_BALLPOINT_V1, widthDp, 0.10f, colorArgb, false)

        com.thundernotes.ui.canvas.EditorTool.HIGHLIGHTER ->
            // Highlighter: ~2× width, looser epsilon, same color (the family itself
            // is semi-transparent — StockBrushes.marker/highlighter in the host).
            EditorBrushConfig(BrushFamily.THUNDER_HIGHLIGHTER_V1, widthDp * 2.0f, 0.05f, colorArgb, false)

        com.thundernotes.ui.canvas.EditorTool.ERASER ->
            // Eraser isn't a real brush — the host hit-tests finished strokes and
            // calls CanvasDocument.removeStroke. sizeDp is the eraser's hit radius.
            EditorBrushConfig(BrushFamily.THUNDER_BALLPOINT_V1, widthDp * 4.0f, 0.10f, 0, isEraser = true)

        com.thundernotes.ui.canvas.EditorTool.LASSO ->
            // Lasso selects, doesn't draw — the host uses a selection path, not a brush.
            // Returned config is a no-op placeholder so the registry total-maps the enum.
            EditorBrushConfig(BrushFamily.THUNDER_BALLPOINT_V1, widthDp, 0.10f, colorArgb, false)

        com.thundernotes.ui.canvas.EditorTool.SHAPE ->
            // Shape tool (rectangle/circle/line) — Phase 8b; placeholder config.
            EditorBrushConfig(BrushFamily.THUNDER_BALLPOINT_V1, widthDp, 0.10f, colorArgb, false)

        com.thundernotes.ui.canvas.EditorTool.TEXT ->
            // Text tool — Phase 8b; placeholder.
            EditorBrushConfig(BrushFamily.THUNDER_BALLPOINT_V1, widthDp, 0.10f, colorArgb, false)
    }
}
