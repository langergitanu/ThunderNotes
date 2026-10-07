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
            ShapeType.LINE -> listOf(lineStroke(x1, y1, x2, y2, cfg))
            ShapeType.RECTANGLE -> listOf(rectStroke(left, top, right, bottom, cfg))
            ShapeType.ROUNDED_RECTANGLE -> listOf(rectStroke(left, top, right, bottom, cfg))
            ShapeType.ELLIPSE -> listOf(circleStroke(left, top, right, bottom, cfg, circleSegments))
            ShapeType.TRIANGLE -> listOf(triangleStroke(left, top, right, bottom, cfg))
            ShapeType.ARROW -> listOf(arrowStroke(x1, y1, x2, y2, cfg))
            ShapeType.POLYGON -> listOf(polygonStroke(left, top, right, bottom, cfg, sides = 6))
            ShapeType.STAR -> listOf(starStroke(left, top, right, bottom, cfg, points = 5))
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

    /** Equilateral triangle inscribed in the box, apex at the top. */
    private fun triangleStroke(l: Float, t: Float, r: Float, b: Float, c: BrushCfg): StrokeRecord {
        val cx = (l + r) / 2f
        val xy = listOf(cx, t, r, b, l, b, cx, t)
        return stroke(xy, c)
    }

    /** Arrow from (x1,y1) → (x2,y2) with two head barbs. */
    private fun arrowStroke(x1: Float, y1: Float, x2: Float, y2: Float, c: BrushCfg): StrokeRecord {
        // The arrow = shaft + two head barbs (single polyline: barb1 → tip → barb2 → tip → shaft start → tip).
        // Keep it simple: one polyline along the shaft + the barbs.
        val dx = x2 - x1; val dy = y2 - y1
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1f) return stroke(listOf(x1, y1, x2, y2), c)
        val ux = dx / len; val uy = dy / len  // unit shaft direction
        val headLen = minOf(len * 0.3f, 40f)
        val ang = Math.PI / 6.0  // 30° head angle
        val cosA = Math.cos(ang).toFloat(); val sinA = Math.sin(ang).toFloat()
        // Rotate the unit shaft by ±30° to get the two barb directions.
        val barb1x = x2 - headLen * (ux * cosA + uy * sinA)
        val barb1y = y2 - headLen * (-ux * sinA + uy * cosA)
        val barb2x = x2 - headLen * (ux * cosA - uy * sinA)
        val barb2y = y2 - headLen * (ux * sinA + uy * cosA)
        // Polyline: shaft start → tip → barb1 → tip → barb2 → tip.
        return stroke(listOf(x1, y1, x2, y2, barb1x, barb1y, x2, y2, barb2x, barb2y, x2, y2), c)
    }

    /** Regular polygon with [sides] vertices inscribed in the box. */
    private fun polygonStroke(l: Float, t: Float, r: Float, b: Float, c: BrushCfg, sides: Int): StrokeRecord {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        val xy = ArrayList<Float>((sides + 1) * 2)
        for (i in 0..sides) {
            val ang = (2.0 * Math.PI * i / sides - Math.PI / 2).toFloat()  // start at top
            xy.add((cx + rx * cos(ang)).toFloat())
            xy.add((cy + ry * sin(ang)).toFloat())
        }
        return stroke(xy, c)
    }

    /** Star with [points] outer vertices (alternating inner vertices at 0.4× radius). */
    private fun starStroke(l: Float, t: Float, r: Float, b: Float, c: BrushCfg, points: Int): StrokeRecord {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        val innerRx = rx * 0.4f; val innerRy = ry * 0.4f
        val verts = points * 2
        val xy = ArrayList<Float>((verts + 1) * 2)
        for (i in 0..verts) {
            val ang = (Math.PI * i / points - Math.PI / 2).toFloat()
            val isOuter = (i % 2 == 0)
            val xr = if (isOuter) rx else innerRx
            val yr = if (isOuter) ry else innerRy
            xy.add((cx + xr * cos(ang)).toFloat())
            xy.add((cy + yr * sin(ang)).toFloat())
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
