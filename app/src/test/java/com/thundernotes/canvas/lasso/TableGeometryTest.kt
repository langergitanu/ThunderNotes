package com.thundernotes.canvas.lasso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TableGeometry] — the Table Maker's grid-line generator. */
class TableGeometryTest {

    private val color = 0xFF1A1A1A.toInt()

    @Test fun `2x3 table produces 3 horizontal + 4 vertical = 7 grid lines`() {
        val strokes = TableGeometry.buildTable(0f, 0f, 300f, 200f, rows = 2, cols = 3, color, 2f)
        assertEquals(7, strokes.size)  // (2+1) horizontal + (3+1) vertical
        // Every stroke is a 2-point polyline (4 floats).
        strokes.forEach { assertEquals(4, it.inputXy.size) }
    }

    @Test fun `grid lines span the full width and height`() {
        val strokes = TableGeometry.buildTable(10f, 20f, 100f, 50f, rows = 1, cols = 1, color, 2f)
        assertEquals(4, strokes.size)  // 2 horizontal + 2 vertical
        // First horizontal line: (10,20) → (110,20) — full width.
        assertEquals(listOf(10f, 20f, 110f, 20f), strokes[0].inputXy)
        // Second horizontal: (10,70) → (110,70) — y=20+50=70.
        assertEquals(listOf(10f, 70f, 110f, 70f), strokes[1].inputXy)
        // First vertical: (10,20) → (10,70).
        assertEquals(listOf(10f, 20f, 10f, 70f), strokes[2].inputXy)
        // Second vertical: (110,20) → (110,70).
        assertEquals(listOf(110f, 20f, 110f, 70f), strokes[3].inputXy)
    }

    @Test fun `rows=0 or cols=0 coerced to 1 (single cell)`() {
        val strokes = TableGeometry.buildTable(0f, 0f, 100f, 100f, rows = 0, cols = 0, color, 2f)
        assertEquals(4, strokes.size)  // 2+2
    }

    @Test fun `every stroke has the chosen color + ballpoint family`() {
        val strokes = TableGeometry.buildTable(0f, 0f, 100f, 100f, 1, 1, color, 2.5f)
        strokes.forEach {
            assertEquals(color, it.brushColorArgb)
            assertEquals(2.5f, it.brushSize, 0.001f)
            assertEquals("thunder-ballpoint-v1", it.brushFamilyId)
            assertTrue(it.inputAttrs.size == it.pointCount * 5)
        }
    }
}
