package com.thundernotes.canvas.inject

import com.thundernotes.canvas.CanvasDocument
import com.thundernotes.canvas.StrokeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for [InkInjector] — the internal paste path. */
class InkInjectorTest {

    private lateinit var doc: CanvasDocument
    private lateinit var injector: InkInjector

    private fun stroke(id: String, xy: List<Float>) = StrokeRecord(
        id = id, layerId = "l0", pageId = "p0",
        brushSize = 2f, brushColorArgb = 0xFF000000.toInt(),
        brushEpsilon = 0.1f, brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
        inputXy = xy,
        inputAttrs = xy.map { 0f }, // 5 attrs per point won't match; but inject doesn't validate
    )

    @Before fun setUp() {
        doc = CanvasDocument()
        injector = InkInjector(doc)
    }

    @Test fun `inject stroke group translates every point by the drop offset`() {
        val group = ClipboardItem.StrokeGroup(
            strokes = listOf(
                stroke("a", listOf(0f, 0f, 10f, 10f)),
                stroke("b", listOf(5f, 5f, 15f, 15f)),
            ),
            bbox = floatArrayOf(0f, 0f, 15f, 15f),
        )
        val ids = injector.inject(group, dropX = 100f, dropY = 50f)
        assertEquals(2, ids.size)
        val strokes = doc.currentStrokes
        assertEquals(2, strokes.size)
        // First stroke's points translated by (100, 50).
        assertEquals(listOf(100f, 50f, 110f, 60f), strokes[0].inputXy)
        // Second stroke translated too.
        assertEquals(listOf(105f, 55f, 115f, 65f), strokes[1].inputXy)
    }

    @Test fun `inject mints fresh stroke ids (paste of same group is a distinct set)`() {
        val group = ClipboardItem.StrokeGroup(
            strokes = listOf(stroke("orig", listOf(0f, 0f, 1f, 1f))),
            bbox = floatArrayOf(0f, 0f, 1f, 1f),
        )
        val ids1 = injector.inject(group, 0f, 0f)
        val ids2 = injector.inject(group, 10f, 10f)
        assertEquals(1, ids1.size)
        assertEquals(1, ids2.size)
        assertTrue("pasted strokes must have distinct ids", ids1[0] != ids2[0])
        assertEquals(2, doc.currentStrokes.size)
    }

    @Test fun `inject on empty document writes to the default first page`() {
        val group = ClipboardItem.StrokeGroup(
            strokes = listOf(stroke("a", listOf(0f, 0f, 2f, 2f))),
            bbox = floatArrayOf(0f, 0f, 2f, 2f),
        )
        injector.inject(group, 0f, 0f)
        assertEquals(1, doc.currentStrokes.size)
        assertEquals(1, doc.totalPages)
    }

    @Test fun `translate helper shifts a flat xy list`() {
        assertEquals(
            listOf(10f, 20f, 30f, 40f),
            InkInjector.translate(listOf(0f, 10f, 20f, 30f), 10f, 10f),
        )
    }
}
