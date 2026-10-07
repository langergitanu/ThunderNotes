package com.thundernotes.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [EditorPalette] — the 3 spec palettes (§6.10 Row 3c). */
class EditorPaletteTest {

    @Test fun `three palettes are defined`() {
        assertEquals(3, EditorPalette.PALETTES.size)
        assertEquals(3, EditorPalette.PALETTE_NAMES.size)
    }

    @Test fun `each palette has exactly 8 colors`() {
        EditorPalette.PALETTES.forEach { pal ->
            assertEquals("expected 8 colors per palette, got ${pal.size}", 8, pal.size)
        }
    }

    @Test fun `palette names match the spec`() {
        assertEquals(listOf("ThunderDark", "ThunderLight", "Sunflower"), EditorPalette.PALETTE_NAMES)
    }

    @Test fun `all colors are opaque (alpha FF)`() {
        EditorPalette.PALETTES.flatten().forEach { argb ->
            assertEquals(0xFF.toLong(), ((argb ushr 24) and 0xFF).toLong())
        }
    }

    @Test fun `ThunderLight is lighter than ThunderDark for parallel slots`() {
        // The spec: ThunderLight is the light variant of ThunderDark. Per-slot,
        // ThunderLight's value should be ≥ ThunderDark's (lighter = higher RGB).
        repeat(8) { i ->
            val dark = EditorPalette.THUNDER_DARK[i]
            val light = EditorPalette.THUNDER_LIGHT[i]
            val darkLum = (dark and 0xFF) + ((dark ushr 8) and 0xFF) + ((dark ushr 16) and 0xFF)
            val lightLum = (light and 0xFF) + ((light ushr 8) and 0xFF) + ((light ushr 16) and 0xFF)
            assertTrue(
                "slot $i: ThunderLight (lum=$lightLum) should be lighter than ThunderDark (lum=$darkLum)",
                lightLum >= darkLum,
            )
        }
    }
}
