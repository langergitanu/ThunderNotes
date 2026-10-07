package com.thundernotes.canvas.inject

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThunderClipboardTest {

    private val strokeGroup = ClipboardItem.StrokeGroup(
        strokes = emptyList(),
        bbox = floatArrayOf(0f, 0f, 10f, 10f),
    )

    @Before fun setUp() { ThunderClipboard.clear() }
    @After fun tearDown() { ThunderClipboard.clear() }

    @Test fun `starts empty`() = runTest {
        assertNull(ThunderClipboard.item.value)
        assertFalse(ThunderClipboard.hasItem)
    }

    @Test fun `put makes hasItem true + item observable`() = runTest {
        ThunderClipboard.put(strokeGroup)
        assertTrue(ThunderClipboard.hasItem)
        assertEquals(strokeGroup, ThunderClipboard.item.value)
    }

    @Test fun `put overwrites a previous item (single-item per spec)`() = runTest {
        val first = strokeGroup
        val second = strokeGroup.copy(bbox = floatArrayOf(5f, 5f, 15f, 15f))
        ThunderClipboard.put(first)
        ThunderClipboard.put(second)
        assertEquals(second, ThunderClipboard.item.value)
        // Still exactly one item — not two.
        assertTrue(ThunderClipboard.hasItem)
    }

    @Test fun `take returns the item and clears the glow`() = runTest {
        ThunderClipboard.put(strokeGroup)
        val taken = ThunderClipboard.take()
        assertEquals(strokeGroup, taken)
        assertFalse(ThunderClipboard.hasItem)
        assertNull(ThunderClipboard.item.value)
    }

    @Test fun `take on empty clipboard returns null`() = runTest {
        assertNull(ThunderClipboard.take())
    }

    @Test fun `clear empties the clipboard`() = runTest {
        ThunderClipboard.put(strokeGroup)
        ThunderClipboard.clear()
        assertFalse(ThunderClipboard.hasItem)
    }

    @Test fun `item StateFlow reflects put + take reactively`() =
        runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<ClipboardItem?>()
        val job = launch {
            ThunderClipboard.item.collect { collected.add(it) }
        }
        ThunderClipboard.put(strokeGroup)
        ThunderClipboard.take()
        runCurrent()
        job.cancel()
        // The flow emitted: null (initial), strokeGroup, null.
        assertTrue("expected ≥ 3 emissions, got ${collected.size}", collected.size >= 3)
        assertNotNull(collected[1])
        assertNull(collected.last())
    }
}
