package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import java.util.UUID

/**
 * Generates the grid-line [StrokeRecord] polylines for a table drawn by the
 * Table Maker (spec §6.10.9b: "Border Visibility, Border Thickness, Border
 * Color, Header Color, Rows, Columns, Alternative Row Color"). The table is
 * a grid of (rows × cols) cells inscribed in a box (x, y, w, h); each grid line
 * is an ordinary 2-point [StrokeRecord] polyline → renders + erases + saves
 * through the existing paths with zero special-casing.
 *
 * **Phase 9i:** the full 7-option set is now supported — border visibility,
 * border thickness (separate from inner-grid thickness), border color, header
 * color (the top + first-row-bottom lines), rows, cols, + alt-row color (the
 * separator line between odd/even rows). All optional with sensible defaults
 * so existing callers + tests keep working.
 *
 * Pure + unit-testable (no Android/Ink dependency). Mirrors [ShapeGeometry]'s
 * pattern (shape → StrokeRecords).
 */
object TableGeometry {

    /** Build the grid-line strokes for a table of [rows] × [cols] cells inscribed
     *  in the box ([x], [y], [x]+[w], [y]+[h]). Returns (rows+1)+(cols+1) 2-point
     *  polylines (the horizontal + vertical grid lines).
     *
     *  @param borderVisible if false, the outer border is skipped (only inner
     *    grid lines + the header + alt-row separators are drawn).
     *  @param borderThickness the brush size for the outer border (when visible).
     *  @param borderColor the border color.
     *  @param innerThickness the brush size for inner grid lines.
     *  @param innerColor the inner grid line color.
     *  @param headerColor if non-null, the top line + the first-row bottom line
     *    use this color + thickness (the "header row" emphasis).
     *  @param altRowColor if non-null, the horizontal separator line between
     *    odd/even rows (every 2nd row boundary) uses this color (the "zebra"
     *    alt-row stripe, spec §6.10.9b "Alternative Row Color").
     */
    fun buildTable(
        x: Float, y: Float, w: Float, h: Float,
        rows: Int, cols: Int,
        colorArgb: Int,
        brushSize: Float,
        epsilon: Float = 0.1f,
        // §6.10.9b options (defaults preserve the original behaviour):
        borderVisible: Boolean = true,
        borderThickness: Float = brushSize,
        borderColor: Int = colorArgb,
        innerThickness: Float = brushSize,
        innerColor: Int = colorArgb,
        headerColor: Int? = null,
        altRowColor: Int? = null,
    ): List<StrokeRecord> {
        val r = rows.coerceAtLeast(1)
        val c = cols.coerceAtLeast(1)
        val out = mutableListOf<StrokeRecord>()
        val dx = w / c
        val dy = h / r

        // Horizontal grid lines (r+1 of them across the height).
        for (i in 0..r) {
            val yy = y + i * dy
            val isTop = (i == 0)
            val isBottom = (i == r)
            val isHeaderBottom = (i == 1 && headerColor != null)
            val isAltRowBoundary = (i > 0 && i < r && i % 2 == 0 && altRowColor != null)
            val isOuterH = (isTop || isBottom)
            when {
                // Header row: top line or first-row-bottom → header colour + thickness.
                headerColor != null && (isTop || isHeaderBottom) ->
                    out.add(stroke(listOf(x, yy, x + w, yy), headerColor,
                        borderThickness * 1.3f, epsilon))
                // Alt-row separator → alt-row colour.
                isAltRowBoundary ->
                    out.add(stroke(listOf(x, yy, x + w, yy), altRowColor!!,
                        innerThickness, epsilon))
                // Outer border (top/bottom) → border colour + thickness (only if visible).
                isOuterH -> {
                    if (borderVisible) {
                        out.add(stroke(listOf(x, yy, x + w, yy), borderColor,
                            borderThickness, epsilon))
                    }
                    // else: skip the outer border entirely.
                }
                // Inner grid line.
                else ->
                    out.add(stroke(listOf(x, yy, x + w, yy), innerColor,
                        innerThickness, epsilon))
            }
        }
        // Vertical grid lines (c+1 of them across the width).
        for (j in 0..c) {
            val xx = x + j * dx
            val isOuter = (j == 0 || j == c)
            if (borderVisible && isOuter) {
                out.add(stroke(listOf(xx, y, xx, y + h), borderColor,
                    borderThickness, epsilon))
            } else if (!isOuter) {
                // Inner vertical line — always drawn.
                out.add(stroke(listOf(xx, y, xx, y + h), innerColor,
                    innerThickness, epsilon))
            }
            // else: outer + borderVisible=false → skip.
        }
        return out
    }

    private fun stroke(xy: List<Float>, color: Int, size: Float, eps: Float): StrokeRecord {
        val n = xy.size / 2
        val attrs = FloatArray(n * 5).also { it[1] = 1f }  // pressure = 1 at index 1
        return StrokeRecord(
            id = UUID.randomUUID().toString(),
            layerId = "layer-0",
            pageId = "table",
            brushSize = size,
            brushColorArgb = color,
            brushEpsilon = eps,
            brushFamilyId = BrushFamily.THUNDER_BALLPOINT_V1,
            toolType = ToolType.STYLUS.rawValue,
            inputXy = xy,
            inputAttrs = attrs.toList(),
        )
    }
}
