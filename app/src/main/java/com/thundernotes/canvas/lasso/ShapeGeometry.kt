package com.thundernotes.canvas.lasso

import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.ui.canvas.ShapeType
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates the [StrokeRecord] polylines for a shape drawn by the SHAPE tool
 * (spec §6.10 Row 3g Shape Picker) — a rectangle/circle/line inscribed in the
 * drag box (x1,y1)-(x2,y2). The output is ordinary [StrokeRecord]s (page-local
 * coords, the active brush family/color/size), so they render + erase + save
 * through the existing paths with **zero special-casing** (no new tables).
 *
 * Pure + unit-testable (no Android/Ink dependency).
 */
object ShapeGeometry {

    /** Build the stroke(s) for [shape] inscribed in the drag box.
     *  @param colorArgb the active pen color.
     *  @param brushSize the active brush size.
     *  @param epsilon the brush epsilon. */
    fun buildShape(
        shape: ShapeType,
        x1: Float, y1: Float, x2: Float, y2: Float,
        colorArgb: Int,
        brushSize: Float,
        epsilon: Float = 0.1f,
        circleSegments: Int = 48,
    ): List<StrokeRecord> {
        val left = minOf(x1, x2); val right = maxOf(x1, x2)
        val top = minOf(y1, y2); val bottom = maxOf(y1, y2)
        val cfg = BrushCfg(colorArgb, brushSize, epsilon)
        return when (shape) {
            ShapeType.RECTANGLE -> listOf(rectStroke(left, top, right, bottom, cfg))
            ShapeType.LINE -> listOf(lineStroke(x1, y1, x2, y2, cfg))
            ShapeType.CIRCLE -> listOf(circleStroke(left, top, right, bottom, cfg, circleSegments))
        }
    }

    private data class BrushCfg(val colorArgb: Int, val brushSize: Float, val epsilon: Float)

    private fun rectStroke(l: Float, t: Float, r: Float, b: Float, c: BrushCfg): StrokeRecord {
        // 4 corners as a closed polyline.
        val xy = listOf(l, t, r, t, r, b, l, b, l, t)
        return stroke(xy, c)
    }

    private fun lineStroke(x1: Float, y1: Float, x2: Float, y2: Float, c: BrushCfg): StrokeRecord {
        return stroke(listOf(x1, y1, x2, y2), c)
    }

    private fun circleStroke(l: Float, t: Float, r: Float, b: Float, c: BrushCfg, segments: Int): StrokeRecord {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        val xy = ArrayList<Float>((segments + 1) * 2)
        for (i in 0..segments) {
            val ang = (2.0 * Math.PI * i / segments).toFloat()
            xy.add((cx + rx * cos(ang)).toFloat())
            xy.add((cy + ry * sin(ang)).toFloat())
        }
        return stroke(xy, c)
    }

    private fun stroke(xy: List<Float>, c: BrushCfg): StrokeRecord {
        val n = xy.size / 2
        val attrs = FloatArray(n * 5)  // 5 attrs/point (elapsed, pressure, tilt, orient, 0)
        var i = 0
        while (i < n) {
            attrs[i * 5 + 1] = 1f  // pressure = 1
            i++
        }
        return StrokeRecord(
            id = UUID.randomUUID().toString(),
            layerId = "layer-0",
            pageId = "shape",
            brushSize = c.brushSize,
            brushColorArgb = c.colorArgb,
            brushEpsilon = c.epsilon,
            brushFamilyId = BrushFamily.THUNDER_BALLPOINT_V1,
            toolType = ToolType.STYLUS.rawValue,
            inputXy = xy,
            inputAttrs = attrs.toList(),
        )
    }
}
