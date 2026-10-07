package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import java.util.UUID

/**
 * Generates the grid-line [StrokeRecord] polylines for a table drawn by the
 * Table Maker (spec §6.10 Row 3g: "Border Visibility, Border Thickness, Border
 * Color, Header Color, Rows, Columns, Alternative Row Color"). The table is
 * a grid of (rows × cols) cells inscribed in a box (x, y, w, h); each grid line
 * is an ordinary 2-point [StrokeRecord] polyline → renders + erases + saves
 * through the existing paths with zero special-casing.
 *
 * Pure + unit-testable (no Android/Ink dependency). Mirrors [ShapeGeometry]'s
 * pattern (shape → StrokeRecords).
 */
object TableGeometry {

    /** Build the grid-line strokes for a table of [rows] × [cols] cells inscribed
     *  in the box ([x], [y], [x]+[w], [y]+[h]). Returns (rows+1)+(cols+1) 2-point
     *  polylines (the horizontal + vertical grid lines). */
    fun buildTable(
        x: Float, y: Float, w: Float, h: Float,
        rows: Int, cols: Int,
        colorArgb: Int,
        brushSize: Float,
        epsilon: Float = 0.1f,
    ): List<StrokeRecord> {
        val r = rows.coerceAtLeast(1)
        val c = cols.coerceAtLeast(1)
        val out = mutableListOf<StrokeRecord>()
        val dx = w / c
        val dy = h / r
        // Horizontal grid lines (r+1 of them across the height).
        for (i in 0..r) {
            val yy = y + i * dy
            out.add(stroke(listOf(x, yy, x + w, yy), colorArgb, brushSize, epsilon))
        }
        // Vertical grid lines (c+1 of them across the width).
        for (j in 0..c) {
            val xx = x + j * dx
            out.add(stroke(listOf(xx, y, xx, y + h), colorArgb, brushSize, epsilon))
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
