package com.thundernotes.snip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [DiagramTracer] — the diagram-specific tracer with
 * fill exclusion + sharp-point/colour-change splitting.
 */
class DiagramTracerTest {

    private fun blankImg(w: Int, h: Int) =
        SnipImage(w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })

    private fun imgWithInk(w: Int, h: Int, inkPixels: Set<Pair<Int, Int>>): SnipImage {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for ((x, y) in inkPixels) px[y * w + x] = 0xFF000000.toInt()
        return SnipImage(w, h, px)
    }

    @Test fun `trace a simple line produces strokes`() {
        // 1px-wide horizontal line (cols 1-10, row 5) in a 12×12 image.
        val ink = (1..10).map { it to 5 }.toSet()
        val img = imgWithInk(12, 12, ink)
        val strokes = DiagramTracer.trace(img)
        assertTrue("should produce at least one stroke", strokes.isNotEmpty())
    }

    @Test fun `trace a blank image returns empty`() {
        assertTrue(DiagramTracer.trace(blankImg(5, 5)).isEmpty())
    }

    @Test fun `trace a filled rectangle excludes the interior (outline only)`() {
        // A 4×4 filled black square (rows 2-5, cols 2-5) in a 10×10 image.
        val ink = (2..5).flatMap { x -> (2..5).map { y -> x to y } }.toSet()
        val img = imgWithInk(10, 10, ink)
        val strokes = DiagramTracer.trace(img)
        // The tracer should produce strokes (the outline), NOT noise from the
        // filled interior. The edge-detection step removes the interior →
        // only the border is skeletonized + traced.
        assertTrue("should produce outline strokes", strokes.isNotEmpty())
        // The number of strokes should be small (the outline, not many interior fragments).
        assertTrue("should not have too many strokes (interior excluded)", strokes.size <= 8)
    }

    @Test fun `trace two separate lines produces two strokes`() {
        // Two horizontal lines (row 2 + row 8, cols 1-6) in a 10×10 image.
        val ink = ((1..6).map { it to 2 } + (1..6).map { it to 8 }).toSet()
        val img = imgWithInk(10, 10, ink)
        val strokes = DiagramTracer.trace(img)
        assertEquals(2, strokes.size)
    }

    @Test fun `trace produces deterministic output (same input, same count)`() {
        val ink = (1..8).map { it to 4 }.toSet()
        val img = imgWithInk(10, 10, ink)
        val a = DiagramTracer.trace(img)
        val b = DiagramTracer.trace(img)
        assertEquals(a.size, b.size)
    }

    @Test fun `trace strokes have the ballpoint family + black color`() {
        val ink = (1..5).map { it to 3 }.toSet()
        val img = imgWithInk(8, 8, ink)
        val strokes = DiagramTracer.trace(img)
        strokes.forEach {
            assertEquals("thunder-ballpoint-v1", it.brushFamilyId)
            assertEquals(0xFF1A1A1A.toInt(), it.brushColorArgb)
            assertTrue(it.inputAttrs.size == it.pointCount * 5)
        }
    }
}
