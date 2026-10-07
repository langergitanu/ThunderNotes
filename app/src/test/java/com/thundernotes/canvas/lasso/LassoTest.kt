package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.inject.ThunderClipboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for [LassoSelector] + [StrokeTransforms] + [LassoOps]. */
class LassoTest {

    private fun stroke(id: String, xy: List<Float>) = StrokeRecord(
        id = id, layerId = "l", pageId = "p",
        brushSize = 2f, brushColorArgb = 0xFF000000.toInt(),
        brushEpsilon = 0.1f, brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
        inputXy = xy, inputAttrs = xy.map { 0f },
    )

    @Before fun setUp() { ThunderClipboard.clear() }
    @After fun tearDown() { ThunderClipboard.clear() }

    // ─── LassoSelector ─────────────────────────────────────────────────────

    @Test fun `selects strokes whose bbox intersects the lasso rect`() {
        val strokes = listOf(
            stroke("a", listOf(0f, 0f, 10f, 10f)),    // bbox (0,0,10,10)
            stroke("b", listOf(20f, 20f, 30f, 30f)),  // bbox (20,20,30,30)
            stroke("c", listOf(100f, 100f, 110f, 110f)),  // bbox (100,100,110,110)
        )
        // Lasso rect (5,5,25,25) — intersects a + b, not c.
        val sel = LassoSelector.selectByRect(strokes, 5f, 5f, 25f, 25f)
        assertEquals(setOf("a", "b"), sel.strokeIds.toSet())
        assertFalse(sel.isEmpty)
    }

    @Test fun `drag direction normalized (rect can go any direction)`() {
        val strokes = listOf(stroke("a", listOf(0f, 0f, 10f, 10f)))
        // Drag down-right vs up-left → same selection.
        val s1 = LassoSelector.selectByRect(strokes, 0f, 0f, 10f, 10f)
        val s2 = LassoSelector.selectByRect(strokes, 10f, 10f, 0f, 0f)
        assertEquals(s1.strokeIds, s2.strokeIds)
    }

    @Test fun `no intersection yields empty selection`() {
        val sel = LassoSelector.selectByRect(
            listOf(stroke("a", listOf(0f, 0f, 5f, 5f))), 100f, 100f, 200f, 200f,
        )
        assertTrue(sel.isEmpty)
    }

    // ─── StrokeTransforms ──────────────────────────────────────────────────

    @Test fun `scale enlarges points around the group center`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f))  // center (5,5)
        val scaled = StrokeTransforms.scale(listOf(s), 2f)
        // (0,0) → (-5,-5); (10,10) → (15,15) — both 2× from center.
        assertEquals(listOf(-5f, -5f, 15f, 15f), scaled[0].inputXy)
    }

    @Test fun `scale with scaleBrushSize also scales the brush (constant-scaling OFF, §7_5)`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f)).copy(brushSize = 2f)
        val scaled = StrokeTransforms.scale(listOf(s), 2f, scaleBrushSize = true)
        assertEquals(4f, scaled[0].brushSize, 0.001f)  // thickness doubled
    }

    @Test fun `scale without scaleBrushSize keeps brush (constant-scaling ON, §7_5)`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f)).copy(brushSize = 2f)
        val scaled = StrokeTransforms.scale(listOf(s), 2f, scaleBrushSize = false)
        assertEquals(2f, scaled[0].brushSize, 0.001f)  // thickness unchanged
    }

    @Test fun `flipHorizontal mirrors x around center`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f))  // center x = 5
        val flipped = StrokeTransforms.flipHorizontal(listOf(s))
        // (0,_) → (10,_); (10,_) → (0,_) — x' = 2*5 - x.
        assertEquals(listOf(10f, 0f, 0f, 10f), flipped[0].inputXy)
    }

    @Test fun `flipVertical mirrors y around center`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f))  // center y = 5
        val flipped = StrokeTransforms.flipVertical(listOf(s))
        assertEquals(listOf(0f, 10f, 10f, 0f), flipped[0].inputXy)
    }

    @Test fun `changeColor sets the brush color of every selected stroke`() {
        val strokes = listOf(stroke("a", listOf(0f, 0f)), stroke("b", listOf(1f, 1f)))
        val colored = StrokeTransforms.changeColor(strokes, 0xFFFF0000.toInt())
        colored.forEach { assertEquals(0xFFFF0000.toInt(), it.brushColorArgb) }
    }

    @Test fun `changeStrokeThickness scales brushSize`() {
        val s = stroke("a", listOf(0f, 0f)).copy(brushSize = 2f)
        val thicker = StrokeTransforms.changeStrokeThickness(listOf(s), 2f)
        assertEquals(4f, thicker[0].brushSize, 0.001f)
    }

    @Test fun `rotate 180 swaps corners around center`() {
        val s = stroke("a", listOf(0f, 0f, 10f, 10f))  // center (5,5)
        val rotated = StrokeTransforms.rotate(listOf(s), 180f)
        // (0,0) → (10,10); (10,10) → (0,0) — 180° around (5,5).
        assertEquals(10f, rotated[0].inputXy[0], 0.001f)
        assertEquals(10f, rotated[0].inputXy[1], 0.001f)
        assertEquals(0f, rotated[0].inputXy[2], 0.001f)
        assertEquals(0f, rotated[0].inputXy[3], 0.001f)
    }

    @Test fun `transforms preserve stroke ids (in-place replace by id)`() {
        val s = stroke("orig", listOf(0f, 0f, 10f, 10f))
        val scaled = StrokeTransforms.scale(listOf(s), 2f)
        assertEquals("orig", scaled[0].id)
    }

    // ─── LassoOps (cut/copy/delete) ────────────────────────────────────────

    @Test fun `copy builds a relative-coords clipboard item + puts it`() {
        val strokes = listOf(stroke("a", listOf(10f, 10f, 30f, 30f)))  // bbox (10,10,30,30)
        LassoOps.copy(strokes)
        val item = ThunderClipboard.item.value
        assertTrue(item is com.thundernotes.canvas.inject.ClipboardItem.StrokeGroup)
        val group = item as com.thundernotes.canvas.inject.ClipboardItem.StrokeGroup
        // Points translated by (-10, -10) → normalized to (0,0) origin.
        assertEquals(listOf(0f, 0f, 20f, 20f), group.strokes[0].inputXy)
        assertEquals(0f, group.bbox[0], 0f)
        assertEquals(20f, group.bbox[2], 0f)
    }

    @Test fun `cut removes from document + populates clipboard`() {
        val doc = com.thundernotes.canvas.CanvasDocument()
        doc.addStroke(stroke("a", listOf(0f, 0f, 5f, 5f)))
        doc.addStroke(stroke("b", listOf(0f, 0f, 5f, 5f)))
        val selected = listOf(doc.currentStrokes[0])
        val removed = LassoOps.cut(doc, selected)
        assertEquals(listOf("a"), removed)
        assertEquals(1, doc.currentStrokes.size)
        assertTrue(ThunderClipboard.hasItem)
    }

    @Test fun `delete removes from document without touching clipboard`() {
        val doc = com.thundernotes.canvas.CanvasDocument()
        doc.addStroke(stroke("a", listOf(0f, 0f, 5f, 5f)))
        val removed = LassoOps.delete(doc, doc.currentStrokes)
        assertEquals(listOf("a"), removed)
        assertEquals(0, doc.currentStrokes.size)
        assertFalse(ThunderClipboard.hasItem)
    }
}
