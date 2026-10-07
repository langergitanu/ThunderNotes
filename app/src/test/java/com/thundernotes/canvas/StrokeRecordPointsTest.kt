package com.thundernotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [StrokeRecordPoints] — the per-point extraction used by
 *  [StrokeRecordConverter] to build a live androidx.ink Stroke from a
 *  [StrokeRecord] (for rendering injected/pasted/loaded strokes on the
 *  CompletedStrokesView). */
class StrokeRecordPointsTest {

    private fun record(xy: List<Float>, attrs: List<Float> = emptyList()) = StrokeRecord(
        id = "s", layerId = "l", pageId = "p",
        brushSize = 1f, brushColorArgb = 0, brushEpsilon = 0.1f,
        brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
        inputXy = xy, inputAttrs = attrs,
    )

    @Test fun `extracts each point as x_y_elapsed_pressure_tilt_orientation`() {
        val r = record(
            xy = listOf(1f, 2f, 3f, 4f, 5f, 6f),  // 3 points
            attrs = listOf(
                10f, 0.5f, 0.1f, 0.2f, 0f,   // p0
                20f, 0.6f, 0.0f, 0.3f, 0f,   // p1 (tilt=0 → NO_TILT sentinel downstream)
                30f, 0.7f, 0.2f, 0.4f, 0f,   // p2
            ),
        )
        val pts = StrokeRecordPoints.extract(r)
        assertEquals(3, pts!!.size)
        assertEquals(listOf(1f, 2f, 10f, 0.5f, 0.1f, 0.2f), pts[0].toList())
        assertEquals(listOf(3f, 4f, 20f, 0.6f, 0.0f, 0.3f), pts[1].toList())
        assertEquals(listOf(5f, 6f, 30f, 0.7f, 0.2f, 0.4f), pts[2].toList())
    }

    @Test fun `extracts with zero attrs (default pressure 1f, rest zero)`() {
        val r = record(xy = listOf(1f, 2f, 3f, 4f))  // 2 points, no attrs
        val pts = StrokeRecordPoints.extract(r)
        assertEquals(2, pts!!.size)
        // No-attrs path: elapsed/tilt/orientation default to 0; pressure defaults
        // to 1f (a visible stroke) — see StrokeRecordPoints.extract.
        assertEquals(listOf(1f, 2f, 0f, 1f, 0f, 0f), pts[0].toList())
        assertEquals(listOf(3f, 4f, 0f, 1f, 0f, 0f), pts[1].toList())
    }

    @Test fun `returns null for odd-length xy (malformed)`() {
        assertNull(StrokeRecordPoints.extract(record(xy = listOf(1f, 2f, 3f))))
    }

    @Test fun `returns null for empty xy (no points)`() {
        assertNull(StrokeRecordPoints.extract(record(xy = emptyList())))
    }

    @Test fun `returns null when attrs length mismatches point count`() {
        // 2 points (4 xy floats) but only 5 attrs (should be 10)
        assertNull(StrokeRecordPoints.extract(record(
            xy = listOf(1f, 2f, 3f, 4f),
            attrs = listOf(0f, 1f, 2f, 3f, 4f),
        )))
    }
}
