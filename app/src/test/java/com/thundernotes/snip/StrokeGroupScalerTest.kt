package com.thundernotes.snip

import com.thundernotes.canvas.StrokeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the snip-pipeline fixes:
 *  - [StrokeGroupScaler] — traced stroke groups scale from image-pixel space
 *    to page-pixel space (fixes "diagram pastes at full-screen scale").
 *  - [SnipPreprocessor.downscale] / [downscaleFactorFor] — the pre-trace
 *    downscale (fixes "diagram tracing takes tens of seconds on a screen
 *    capture").
 *  - [SnipTextCleaner] — strips the markdown fences / math dollars vision
 *    models wrap their answers in.
 */
class StrokeGroupScalerTest {

    // ─── helpers ────────────────────────────────────────────────────────────

    /** A single straight horizontal stroke from (x0,y) to (x1,y) — brush 3f. */
    private fun hStroke(x0: Float, x1: Float, y: Float, brush: Float = 3f) =
        StrokeRecord(
            id = "s-$x0-$x1", layerId = "layer-0", pageId = "p",
            brushSize = brush, brushColorArgb = 0xFF000000.toInt(),
            brushEpsilon = 0.1f, brushFamilyId = "ballpoint", toolType = 0,
            inputXy = listOf(x0, y, x1, y),
            inputAttrs = listOf(0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f),
        )

    // ─── bboxOf ─────────────────────────────────────────────────────────────

    @Test fun `bboxOf spans all strokes`() {
        val strokes = listOf(hStroke(0f, 100f, 0f), hStroke(50f, 250f, 70f))
        val bbox = StrokeGroupScaler.bboxOf(strokes)
        assertEquals(0f, bbox[0], 0.01f)
        assertEquals(0f, bbox[1], 0.01f)
        assertEquals(250f, bbox[2], 0.01f)
        assertEquals(70f, bbox[3], 0.01f)
    }

    @Test fun `bboxOf of empty list is zeros`() {
        val bbox = StrokeGroupScaler.bboxOf(emptyList())
        assertTrue(bbox.contentEquals(floatArrayOf(0f, 0f, 0f, 0f)))
    }

    // ─── fit ────────────────────────────────────────────────────────────────

    @Test fun `fit downscales a group wider than the target`() {
        // A 3000×1000 "screen capture" traced group → target 900×800.
        val strokes = listOf(hStroke(0f, 3000f, 500f))
        val (scaled, bbox) = StrokeGroupScaler.fit(strokes, 900f, 800f)
        val w = bbox[2] - bbox[0]
        assertTrue("width should fit 900 (got $w)", w <= 900f + 1f)
        assertTrue("should actually shrink", w < 3000f)
        // Coordinates scaled by the same factor.
        assertEquals(w, scaled[0].inputXy[2] - scaled[0].inputXy[0], 0.5f)
    }

    @Test fun `fit does not upscale small groups beyond maxScale`() {
        // A tiny 100×40 group + a huge target → capped at 2.5×.
        val strokes = listOf(hStroke(0f, 100f, 20f))
        val (_, bbox) = StrokeGroupScaler.fit(strokes, 2000f, 2000f)
        assertEquals(100f * 2.5f, bbox[2] - bbox[0], 0.5f)
    }

    @Test fun `fit scales brush size proportionally`() {
        val strokes = listOf(hStroke(0f, 3000f, 500f, brush = 6f))
        val (scaled, _) = StrokeGroupScaler.fit(strokes, 900f, 800f)
        // 900/3000 = 0.3 → brush 6×0.3 = 1.8.
        assertEquals(1.8f, scaled[0].brushSize, 0.05f)
    }

    @Test fun `fit keeps degenerate dot groups unchanged`() {
        val dots = listOf(StrokeRecord(
            id = "d", layerId = "l", pageId = "p",
            brushSize = 3f, brushColorArgb = 0, brushEpsilon = 0.1f,
            brushFamilyId = "b", toolType = 0,
            inputXy = listOf(5f, 5f), inputAttrs = listOf(0f, 1f, 0f, 0f, 0f),
        ))
        val (scaled, _) = StrokeGroupScaler.fit(dots, 100f, 100f)
        assertEquals(dots, scaled)
    }

    @Test fun `fit with non-positive target is a no-op`() {
        val strokes = listOf(hStroke(0f, 100f, 0f))
        val (scaled, _) = StrokeGroupScaler.fit(strokes, 0f, 0f)
        assertEquals(strokes, scaled)
    }

    @Test fun `plain scale multiplies every coordinate`() {
        val strokes = listOf(hStroke(10f, 110f, 40f, brush = 2f))
        val scaled = StrokeGroupScaler.scale(strokes, 0.5f)
        assertEquals(5f, scaled[0].inputXy[0], 0.01f)
        assertEquals(55f, scaled[0].inputXy[2], 0.01f)
        assertEquals(20f, scaled[0].inputXy[3], 0.01f)
        assertEquals(1f, scaled[0].brushSize, 0.01f)
    }

    // ─── SnipPreprocessor.downscale / downscaleFactorFor ────────────────────

    @Test fun `downscale halves dimensions with area averaging`() {
        // 2×2 image: one black + three white → downscaled 2× → one pixel whose
        // channels are (0 + 255×3) / 4 = 191 (a point-sample would be 0 or 255).
        val px = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val img = SnipImage(2, 2, px)
        val small = SnipPreprocessor.downscale(img, 2)
        assertEquals(1, small.width)
        assertEquals(1, small.height)
        val p = small.pixels[0]
        val r = (p shr 16) and 0xFF
        assertEquals(191, r)
    }

    @Test fun `downscale by 1 is a no-op`() {
        val img = SnipImage(2, 2, IntArray(4) { 0xFF808080.toInt() })
        assertEquals(img, SnipPreprocessor.downscale(img, 1))
    }

    @Test fun `downscale keeps thin ink visible (area average)`() {
        // A 1-px-wide black column in a 2×8 image survives a 2× downscale as
        // a dark column (a point-sample would drop it half the time).
        val px = IntArray(16) { 0xFFFFFFFF.toInt() }
        for (y in 0 until 8) px[y * 2 + 0] = 0xFF000000.toInt()  // x=0 black
        val img = SnipImage(2, 8, px)
        val small = SnipPreprocessor.downscale(img, 2)
        val r = (small.pixels[0] shr 16) and 0xFF
        assertTrue("ink column should stay dark (got r=$r)", r < 160)
    }

    @Test fun `downscaleFactorFor picks the smallest factor that fits`() {
        assertEquals(1, SnipPreprocessor.downscaleFactorFor(800, 600, 1600))
        assertEquals(2, SnipPreprocessor.downscaleFactorFor(3000, 2000, 1600))
        assertEquals(2, SnipPreprocessor.downscaleFactorFor(2000, 1600, 1600))
        assertEquals(3, SnipPreprocessor.downscaleFactorFor(4000, 3000, 1600))
    }

    @Test fun `downscaleFactorFor never shrinks below minDim`() {
        // 4600×100 strip: shrinking 2× would make the height 50 < 400 → the
        // guard holds the factor at 1 (a 4600×100 image is only 460K px —
        // tracing it directly is fine).
        assertEquals(1, SnipPreprocessor.downscaleFactorFor(4600, 100, 1600))
    }

    // ─── SnipTextCleaner ────────────────────────────────────────────────────

    @Test fun `stripCodeFences removes a python fence pair`() {
        val raw = "```python\ndef f():\n    return 1\n```"
        assertEquals("def f():\n    return 1", SnipTextCleaner.stripCodeFences(raw))
    }

    @Test fun `stripCodeFences removes a bare fence pair`() {
        val raw = "```\nint x = 5;\n```"
        assertEquals("int x = 5;", SnipTextCleaner.stripCodeFences(raw))
    }

    @Test fun `stripCodeFences leaves unfenced code alone`() {
        val raw = "int x = 5;"
        assertEquals(raw, SnipTextCleaner.stripCodeFences(raw))
    }

    @Test fun `stripCodeFences keeps interior fences`() {
        // A markdown string INSIDE the code must survive.
        val raw = "s = \"```\""
        assertEquals(raw, SnipTextCleaner.stripCodeFences(raw))
    }

    @Test fun `stripMathDollars removes display math wrappers`() {
        // "\$\$…\$\$" written with escaped dollars so Kotlin doesn't treat
        // them as string templates.
        assertEquals("x^2", SnipTextCleaner.stripMathDollars("\$\$x^2\$\$"))
    }

    @Test fun `stripMathDollars removes a single inline pair`() {
        assertEquals("x^2", SnipTextCleaner.stripMathDollars("\$x^2\$"))
    }

    @Test fun `stripMathDollars keeps bare latex`() {
        assertEquals("\\frac{a}{b}", SnipTextCleaner.stripMathDollars("\\frac{a}{b}"))
    }
}
