package com.thundernotes.canvas

import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import com.thundernotes.ui.canvas.EditorTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [BrushRegistry]'s tool→brush mapping (spec §6.10 Row-3 penset). */
class BrushRegistryTest {

    @Test fun `pen maps to ballpoint family with the chosen color`() {
        val cfg = BrushRegistry.configFor(EditorTool.PEN, 0xFFE53935.toInt(), 2.0f)
        assertEquals(BrushFamily.THUNDER_BALLPOINT_V1, cfg.familyId)
        assertEquals(2.0f, cfg.sizeDp)
        assertEquals(0xFFE53935.toInt(), cfg.colorArgb)
        assertEquals(ToolType.STYLUS.rawValue, cfg.toolType)
        assertFalse(cfg.isEraser)
    }

    @Test fun `highlighter doubles the width and uses highlighter family`() {
        val cfg = BrushRegistry.configFor(EditorTool.HIGHLIGHTER, 0xFF1E88E5.toInt(), 3.0f)
        assertEquals(BrushFamily.THUNDER_HIGHLIGHTER_V1, cfg.familyId)
        assertEquals(6.0f, cfg.sizeDp)
        assertFalse(cfg.isEraser)
    }

    @Test fun `eraser is flagged and uses no color`() {
        val cfg = BrushRegistry.configFor(EditorTool.ERASER, 0xFF000000.toInt(), 2.0f)
        assertTrue(cfg.isEraser)
        assertEquals(0, cfg.colorArgb)
        // 4× the chosen width = eraser hit radius
        assertEquals(8.0f, cfg.sizeDp)
    }

    @Test fun `lasso shape text are placeholder mappings not erasers`() {
        for (t in listOf(EditorTool.LASSO, EditorTool.SHAPE, EditorTool.TEXT)) {
            val cfg = BrushRegistry.configFor(t, 0xFF0000FF.toInt(), 2.0f)
            assertFalse(cfg.isEraser)
        }
    }
}
