package com.thundernotes.canvas.lasso

import com.thundernotes.ui.canvas.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [ShapeGeometry] — rect/circle/line → StrokeRecords. */
class ShapeGeometryTest {

    private val color = 0xFF1A1A1A.toInt()

    @Test fun `rectangle produces a closed 5-point polyline (4 corners + close)`() {
        val strokes = ShapeGeometry.buildShape(ShapeType.RECTANGLE, 0f, 0f, 10f, 10f, color, 2f)
        assertEquals(1, strokes.size)
        // 5 points (10 floats): (0,0)(10,0)(10,10)(0,10)(0,0)
        assertEquals(10, strokes[0].inputXy.size)
        assertEquals(listOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f), strokes[0].inputXy)
    }

    @Test fun `rectangle is drag-direction-agnostic (normalizes the box)`() {
        val a = ShapeGeometry.buildShape(ShapeType.RECTANGLE, 10f, 10f, 0f, 0f, color, 2f)[0]
        val b = ShapeGeometry.buildShape(ShapeType.RECTANGLE, 0f, 0f, 10f, 10f, color, 2f)[0]
        assertEquals(a.inputXy, b.inputXy)
    }

    @Test fun `line produces a 2-point polyline from p1 to p2 (no normalization)`() {
        val strokes = ShapeGeometry.buildShape(ShapeType.LINE, 1f, 2f, 5f, 6f, color, 2f)
        assertEquals(1, strokes.size)
        assertEquals(listOf(1f, 2f, 5f, 6f), strokes[0].inputXy)
    }

    @Test fun `circle produces a closed polyline with segments+1 points`() {
        val strokes = ShapeGeometry.buildShape(ShapeType.CIRCLE, 0f, 0f, 10f, 10f, color, 2f, circleSegments = 48)
        assertEquals(1, strokes.size)
        // 48 segments → 49 points → 98 floats.
        assertEquals(98, strokes[0].inputXy.size)
        // First + last point coincide (closed).
        val xy = strokes[0].inputXy
        assertEquals(xy[0], xy[xy.size - 2], 0.001f)
        assertEquals(xy[1], xy[xy.size - 1], 0.001f)
    }

    @Test fun `circle center is the box centroid`() {
        val strokes = ShapeGeometry.buildShape(ShapeType.CIRCLE, 0f, 0f, 10f, 10f, color, 2f, circleSegments = 4)
        // 4 segments → 5 points; the points are at 0°, 90°, 180°, 270°, 360° around (5,5).
        val xy = strokes[0].inputXy
        // point at 0°: (cx+rx, cy) = (5+5, 5) = (10, 5)
        assertEquals(10f, xy[0], 0.001f)
        assertEquals(5f, xy[1], 0.001f)
    }

    @Test fun `every shape stroke has the chosen color + size + ballpoint family`() {
        for (shape in ShapeType.entries) {
            val s = ShapeGeometry.buildShape(shape, 0f, 0f, 10f, 10f, color, 2.5f)[0]
            assertEquals(color, s.brushColorArgb)
            assertEquals(2.5f, s.brushSize, 0.001f)
            assertEquals("thunder-ballpoint-v1", s.brushFamilyId)
            assertTrue("attrs must be 5 per point", s.inputAttrs.size == s.pointCount * 5)
        }
    }
}
