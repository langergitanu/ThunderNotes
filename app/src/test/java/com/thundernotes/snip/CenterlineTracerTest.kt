package com.thundernotes.snip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CenterlineTracer] — the Zhang-Suen skeletonize +
 * polyline tracer + StrokeRecord builder. No OpenCV/Android dependency.
 */
class CenterlineTracerTest {

    private fun blankImg(w: Int, h: Int): SnipImage =
        SnipImage(w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })

    private fun imgWithInk(w: Int, h: Int, inkPixels: Set<Pair<Int, Int>>): SnipImage {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for ((x, y) in inkPixels) px[y * w + x] = 0xFF000000.toInt()
        return SnipImage(w, h, px)
    }

    // ─── skeletonize ───────────────────────────────────────────────────────

    @Test fun `skeletonize thins a 3px-thick line to 1px`() {
        // 3px-thick, 10px-wide horizontal line (rows 4-6, cols 1-10) in a 12×12
        // image (1px white border so Zhang-Suen can process all ink pixels).
        val ink = (1..10).flatMap { x -> (4..6).map { y -> x to y } }.toSet()
        val img = imgWithInk(12, 12, ink)
        val skel = CenterlineTracer.skeletonize(img)
        // The skeleton should be 1px thick → only one row of ink in the middle.
        val inkRows = (0 until 12).filter { y -> (0 until 12).any { x -> skel.pixels[y * 12 + x] == 0xFF000000.toInt() } }
        assertEquals("skeleton should be 1 row thick", 1, inkRows.size)
    }

    @Test fun `skeletonize of a blank image is blank`() {
        val skel = CenterlineTracer.skeletonize(blankImg(5, 5))
        // No ink pixels.
        assertTrue(skel.pixels.none { it == 0xFF000000.toInt() })
    }

    @Test fun `skeletonize preserves a 1px line as-is`() {
        // A single 1px-wide line → already 1px → skeletonize is a no-op.
        val img = imgWithInk(10, 5, (0..9).map { it to 2 }.toSet())
        val skel = CenterlineTracer.skeletonize(img)
        // The ink row should still be there.
        assertTrue(skel.pixels.any { it == 0xFF000000.toInt() })
    }

    // ─── tracePolylines ─────────────────────────────────────────────────────

    @Test fun `tracePolylines on a 1px horizontal line gives one polyline`() {
        val img = imgWithInk(10, 5, (0..9).map { it to 2 }.toSet())
        val skel = CenterlineTracer.skeletonize(img)
        val polys = CenterlineTracer.tracePolylines(skel)
        assertEquals(1, polys.size)
        assertTrue("polyline should have multiple points", polys[0].size >= 2)
        // First point should be near the left end (row-major: leftmost endpoint).
        assertEquals(0f, polys[0][0][0], 1f)
    }

    @Test fun `tracePolylines on a blank image returns empty`() {
        val polys = CenterlineTracer.tracePolylines(blankImg(5, 5))
        assertTrue(polys.isEmpty())
    }

    @Test fun `tracePolylines on two separate lines gives two polylines`() {
        // Two horizontal lines: row 0 + row 4, cols 0-3.
        val ink = ((0..3).map { it to 0 } + (0..3).map { it to 4 }).toSet()
        val img = imgWithInk(5, 5, ink)
        val skel = CenterlineTracer.skeletonize(img)
        val polys = CenterlineTracer.tracePolylines(skel)
        assertEquals(2, polys.size)
    }

    // ─── toStrokeRecords ────────────────────────────────────────────────────

    @Test fun `toStrokeRecords builds a StrokeRecord per polyline`() {
        val polys = listOf(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(5f, 5f), floatArrayOf(10f, 10f)),
        )
        val strokes = CenterlineTracer.toStrokeRecords(polys)
        assertEquals(1, strokes.size)
        val s = strokes[0]
        assertEquals("thunder-ballpoint-v1", s.brushFamilyId)
        assertEquals(0xFF1A1A1A.toInt(), s.brushColorArgb)  // base color black
        assertTrue(s.inputAttrs.size == s.pointCount * 5)
    }

    @Test fun `toStrokeRecords with no polylines returns empty`() {
        assertTrue(CenterlineTracer.toStrokeRecords(emptyList()).isEmpty())
    }

    // ─── full trace pipeline ────────────────────────────────────────────────

    @Test fun `trace on a simple line produces StrokeRecords`() {
        val img = imgWithInk(10, 5, (0..9).map { it to 2 }.toSet())
        val strokes = CenterlineTracer.trace(img)
        assertTrue("should produce at least one stroke", strokes.isNotEmpty())
        val s = strokes[0]
        assertTrue(s.pointCount >= 2)
        assertEquals("thunder-ballpoint-v1", s.brushFamilyId)
    }

    @Test fun `trace on a blank image returns empty`() {
        assertTrue(CenterlineTracer.trace(blankImg(5, 5)).isEmpty())
    }

    @Test fun `trace is deterministic (same input → same stroke count)`() {
        val img = imgWithInk(10, 5, (0..9).map { it to 2 }.toSet())
        val a = CenterlineTracer.trace(img)
        val b = CenterlineTracer.trace(img)
        assertEquals(a.size, b.size)
    }
}
