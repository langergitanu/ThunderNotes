package com.thundernotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CanvasSpacerManager] — the in-memory Add Writing Space data layer
 * (spec §7.4: <1000-page optimization via page-local immutable coords + spacers).
 */
class CanvasSpacerManagerTest {

    private lateinit var mgr: CanvasSpacerManager

    @Before fun setUp() { mgr = CanvasSpacerManager() }

    @Test fun `no spacers → cumulative offset is zero`() {
        assertEquals(0f, mgr.cumulativeOffsetAbove("page-0", 100f))
        assertEquals(0f, mgr.totalHeightOnPage("page-0"))
    }

    @Test fun `insert + cumulative offset above the spacer`() {
        mgr.insertSpacer("p0", offsetInPage = 100f, height = 50f)
        // Content at y=50 (above the spacer at 100) → no offset.
        assertEquals(0f, mgr.cumulativeOffsetAbove("p0", 50f))
        // Content at y=150 (below the spacer) → offset = 50.
        assertEquals(50f, mgr.cumulativeOffsetAbove("p0", 150f))
        // Content exactly at the spacer's offset → offset = 0 (strictly above).
        assertEquals(0f, mgr.cumulativeOffsetAbove("p0", 100f))
    }

    @Test fun `multiple spacers on a page accumulate`() {
        mgr.insertSpacer("p0", offsetInPage = 50f, height = 30f)
        mgr.insertSpacer("p0", offsetInPage = 200f, height = 70f)
        // At y=250: both spacers are above → offset = 30 + 70 = 100.
        assertEquals(100f, mgr.cumulativeOffsetAbove("p0", 250f))
        assertEquals(100f, mgr.totalHeightOnPage("p0"))
    }

    @Test fun `spacers on different pages are independent (page-local, 1000-page guarantee)`() {
        mgr.insertSpacer("p0", 100f, 50f)
        mgr.insertSpacer("p1", 200f, 70f)
        // p0 at y=150 → 50; p1 at y=250 → 70. Cross-page independence.
        assertEquals(50f, mgr.cumulativeOffsetAbove("p0", 150f))
        assertEquals(70f, mgr.cumulativeOffsetAbove("p1", 250f))
        assertEquals(50f, mgr.totalHeightOnPage("p0"))
        assertEquals(70f, mgr.totalHeightOnPage("p1"))
    }

    @Test fun `remove a spacer by id`() {
        val id = mgr.insertSpacer("p0", 100f, 50f)
        assertTrue(mgr.removeSpacer(id))
        assertEquals(0f, mgr.cumulativeOffsetAbove("p0", 150f))
        assertEquals(0, mgr.spacerCount("p0"))
    }

    @Test fun `remove unknown id returns false`() {
        assertFalse(mgr.removeSpacer("nope"))
    }

    @Test fun `resize a spacer`() {
        val id = mgr.insertSpacer("p0", 100f, 50f)
        assertTrue(mgr.resizeSpacer(id, 100f))
        assertEquals(100f, mgr.cumulativeOffsetAbove("p0", 150f))
    }

    @Test fun `insert keeps the list sorted (binary search invariant)`() {
        // Insert out of order → the cumulative query still works.
        mgr.insertSpacer("p0", 300f, 10f)
        mgr.insertSpacer("p0", 100f, 20f)
        mgr.insertSpacer("p0", 200f, 30f)
        // At y=400: all 3 are above → 60.
        assertEquals(60f, mgr.cumulativeOffsetAbove("p0", 400f))
        // At y=150: only the 100-offset spacer → 20.
        assertEquals(20f, mgr.cumulativeOffsetAbove("p0", 150f))
    }

    @Test fun `negative height is clamped to zero`() {
        val id = mgr.insertSpacer("p0", 100f, -50f)
        assertEquals(0f, mgr.totalHeightOnPage("p0"))
        mgr.resizeSpacer(id, -10f)
        assertEquals(0f, mgr.totalHeightOnPage("p0"))
    }

    @Test fun `invalidate drops a page's spacers`() {
        mgr.insertSpacer("p0", 100f, 50f)
        mgr.invalidate("p0")
        assertEquals(0f, mgr.totalHeightOnPage("p0"))
    }
}
