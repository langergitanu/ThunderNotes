package com.thundernotes.ui.canvas.tabs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for [CanvasTabs] — the multi-file tab system (max 10). */
class CanvasTabsTest {

    @Before fun setUp() { CanvasTabs.clear() }
    @After fun tearDown() { CanvasTabs.clear() }

    @Test fun `starts empty`() {
        assertEquals(0, CanvasTabs.tabCount)
        assertNull(CanvasTabs.activeNoteId)
    }

    @Test fun `open adds a tab + sets active`() {
        CanvasTabs.open("n1", "Math")
        assertEquals(1, CanvasTabs.tabCount)
        assertEquals("n1", CanvasTabs.activeNoteId)
    }

    @Test fun `open same note twice doesn't duplicate — just activates`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.open("n1", "Math")
        assertEquals(1, CanvasTabs.tabCount)
    }

    @Test fun `switch activates an existing tab`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.open("n2", "Physics")
        CanvasTabs.switch("n1")
        assertEquals("n1", CanvasTabs.activeNoteId)
    }

    @Test fun `switch to non-open note is a no-op`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.switch("nope")
        assertEquals("n1", CanvasTabs.activeNoteId)
    }

    @Test fun `close the active tab switches to the next`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.open("n2", "Physics")
        CanvasTabs.close("n2")  // close the active (n2)
        assertEquals(1, CanvasTabs.tabCount)
        assertEquals("n1", CanvasTabs.activeNoteId)
    }

    @Test fun `close the last tab leaves empty`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.close("n1")
        assertEquals(0, CanvasTabs.tabCount)
        assertNull(CanvasTabs.activeNoteId)
    }

    @Test fun `max 10 tabs — oldest evicted on 11th open`() {
        for (i in 1..10) CanvasTabs.open("n$i", "Note $i")
        assertEquals(10, CanvasTabs.tabCount)
        CanvasTabs.open("n11", "Note 11")
        assertEquals(10, CanvasTabs.tabCount)  // still 10
        // The oldest (n1) should be evicted.
        assertTrue("n1 should be evicted", CanvasTabs.tabs.value.none { it.noteId == "n1" })
        assertTrue("n11 should be present", CanvasTabs.tabs.value.any { it.noteId == "n11" })
    }

    @Test fun `secondTab returns the most-recent non-active tab`() {
        CanvasTabs.open("n1", "Math")
        CanvasTabs.open("n2", "Physics")
        CanvasTabs.switch("n1")  // n1 active; n2 is the second
        val second = CanvasTabs.secondTab()
        assertEquals("n2", second?.noteId)
    }

    @Test fun `secondTab null when only one tab open`() {
        CanvasTabs.open("n1", "Math")
        assertNull(CanvasTabs.secondTab())
    }
}
