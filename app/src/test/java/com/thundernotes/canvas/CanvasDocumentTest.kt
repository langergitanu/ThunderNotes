package com.thundernotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for [CanvasDocument] — pages, undo/redo, navigation, zoom. */
class CanvasDocumentTest {

    private lateinit var doc: CanvasDocument

    private fun stroke(id: String, pageId: String = "p0") =
        StrokeRecord(
            id = id,
            layerId = "l0",
            pageId = pageId,
            brushSize = 2f,
            brushColorArgb = 0xFF000000.toInt(),
            brushEpsilon = 0.1f,
            brushFamilyId = "thunder-ballpoint-v1",
            toolType = 1,
            inputXy = listOf(0f, 0f, 10f, 10f),
            inputAttrs = listOf(0f, 1f, 0f, 0f, 0f, 10f, 1f, 0f, 0f, 0f),
        )

    @Before fun setUp() {
        doc = CanvasDocument()
    }

    @Test fun `document starts with one empty page at index 0`() {
        assertEquals(1, doc.totalPages)
        assertEquals(0, doc.currentPageIndex)
        assertEquals(0, doc.currentStrokes.size)
        assertFalse(doc.canUndo)
        assertFalse(doc.canRedo)
    }

    @Test fun `addStroke appends to current page and enables undo`() {
        doc.addStroke(stroke("s1"))
        doc.addStroke(stroke("s2"))
        assertEquals(2, doc.currentStrokes.size)
        assertEquals("s1", doc.currentStrokes[0].id)
        assertTrue(doc.canUndo)
        assertFalse(doc.canRedo)
    }

    @Test fun `undo reverses last add and moves it to redo`() {
        doc.addStroke(stroke("s1"))
        assertTrue(doc.undo())
        assertEquals(0, doc.currentStrokes.size)
        assertTrue(doc.canRedo)
        assertFalse(doc.canUndo)
        assertTrue(doc.redo())
        assertEquals(1, doc.currentStrokes.size)
    }

    @Test fun `undo with empty stack is a no-op returning false`() {
        assertFalse(doc.undo())
        assertFalse(doc.redo())
    }

    @Test fun `new mutation clears the redo stack`() {
        doc.addStroke(stroke("s1"))
        doc.undo()                  // redo has s1
        assertTrue(doc.canRedo)
        doc.addStroke(stroke("s2")) // should clear redo
        assertFalse(doc.canRedo)
        assertEquals(1, doc.currentStrokes.size)
        assertEquals("s2", doc.currentStrokes[0].id)
    }

    @Test fun `removeStroke deletes by id and is undoable`() {
        doc.addStroke(stroke("s1"))
        val removed = doc.removeStroke("s1")
        assertNotNull(removed)
        assertEquals(0, doc.currentStrokes.size)
        assertTrue(doc.undo())     // restore s1
        assertEquals(1, doc.currentStrokes.size)
    }

    @Test fun `removeStroke with unknown id returns null`() {
        doc.addStroke(stroke("s1"))
        assertNull(doc.removeStroke("nope"))
    }

    @Test fun `addPage appends and jumps current index`() {
        assertEquals(0, doc.currentPageIndex)
        val newIdx = doc.addPage()
        assertEquals(1, newIdx)
        assertEquals(1, doc.currentPageIndex)
        assertEquals(2, doc.totalPages)
    }

    @Test fun `undo of addPage removes the new page when it is last and empty`() {
        doc.addPage()
        assertEquals(2, doc.totalPages)
        assertTrue(doc.undo())
        assertEquals(1, doc.totalPages)
        assertEquals(0, doc.currentPageIndex)
    }

    @Test fun `undo of addPage does NOT remove a page that has strokes`() {
        doc.addPage()
        doc.addStroke(stroke("s1", pageId = doc.currentPage!!.id))
        assertTrue(doc.undo())  // undoes the stroke add, not the page
        assertEquals(0, doc.currentStrokes.size)
        assertEquals(2, doc.totalPages) // page still there
    }

    @Test fun `page navigation bounds`() {
        doc.addPage()  // 2 pages, current=1
        assertFalse(doc.nextPage())     // already last
        assertTrue(doc.previousPage())  // back to 0
        assertFalse(doc.previousPage()) // already first
        doc.goToPage(5)
        assertEquals(1, doc.currentPageIndex) // clamped to lastIndex
    }

    @Test fun `zoom clamps to 50-200`() {
        doc.setZoomPercent(150); assertEquals(150, doc.zoomPercent)
        doc.setZoomPercent(500); assertEquals(200, doc.zoomPercent)
        doc.setZoomPercent(0); assertEquals(50, doc.zoomPercent)
    }

    @Test fun `allStrokes flattens across pages`() {
        doc.addStroke(stroke("s1"))
        doc.addPage()
        doc.addStroke(stroke("s2"))
        assertEquals(2, doc.allStrokes().size)
    }

    @Test fun `clearHistory empties undo and redo without touching strokes`() {
        doc.addStroke(stroke("s1"))
        doc.undo()
        doc.clearHistory()
        assertFalse(doc.canUndo)
        assertFalse(doc.canRedo)
    }
}
