package com.thundernotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ColorInverter] — smart theme inversion (spec §6.10 Row 1
 * right): invert lightness, preserve hue + saturation.
 */
class ColorInverterTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val RED   = 0xFFFF0000.toInt()
    private val CYAN  = 0xFF00FFFF.toInt()
    private val DARK_RED  = 0xFF8B0000.toInt()
    private val MID_GRAY = 0xFF808080.toInt()

    @Test fun `black inverts to white`() = assertEquals(WHITE, ColorInverter.invert(BLACK))

    @Test fun `white inverts to black`() = assertEquals(BLACK, ColorInverter.invert(WHITE))

    @Test fun `red stays red NOT cyan (hue preserved)`() {
        val inverted = ColorInverter.invert(RED)
        assertNotEquals("red must NOT become cyan ($CYAN)", CYAN, inverted)
        // Red is (H=0, S=1, L=0.5); inverting L → 0.5 → same red.
        assertEquals(RED, inverted)
    }

    @Test fun `dark red becomes a lighter red (lightness increased, hue preserved)`() {
        val inverted = ColorInverter.invert(DARK_RED)
        // Inverted L must be higher → the value is brighter (closer to white).
        val origL = lightness(DARK_RED)
        val newL = lightness(inverted)
        assertTrue("expected lighter, origL=$origL newL=$newL", newL > origL)
        // Hue preserved (still red-ish, not green/blue).
        val h = hue(inverted)
        assertTrue("hue should be near 0 (red), got $h", h < 10f || h > 350f)
    }

    @Test fun `mid-gray inverts to itself (L=0_5 is the fixed point)`() {
        // 0x808080 ≈ L=0.5 → inverts to itself (within rounding).
        val inverted = ColorInverter.invert(MID_GRAY)
        val origL = lightness(MID_GRAY)
        val newL = lightness(inverted)
        assertTrue("|newL - 0.5| should be small, newL=$newL", kotlin.math.abs(newL - 0.5f) < 0.02f)
        assertTrue("|origL - newL| should be ~0", kotlin.math.abs(origL - newL) < 0.02f)
    }

    @Test fun `inversion is its own inverse for pure colors`() {
        // Pure red (H=0, S=1, L=0.5) round-trips exactly through the
        // RGB↔HSL→invert-L→RGB path. Non-pure colors may drift a few LSB per
        // channel due to float→int rounding in the conversion (a known property
        // of the W3C HSL algorithm, not a bug in the inverter's logic).
        assertEquals(RED, ColorInverter.invert(ColorInverter.invert(RED)))
        assertEquals(BLACK, ColorInverter.invert(ColorInverter.invert(BLACK)))
        assertEquals(WHITE, ColorInverter.invert(ColorInverter.invert(WHITE)))
    }

    @Test fun `alpha is preserved`() {
        val semi = 0x80FF0000.toInt()  // 50% transparent red
        val inverted = ColorInverter.invert(semi)
        val a = (inverted ushr 24) and 0xFF
        assertEquals(0x80, a)
    }

    // ─── helpers (mirror ColorInverter's internal HSL) ───────────────────────

    private fun lightness(argb: Int): Float {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return (mx + mn) / 2f
    }

    private fun hue(argb: Int): Float {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx == mn) return 0f
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0))
            g -> ((b - r) / d + 2)
            else -> ((r - g) / d + 4)
        }
        return h * 60f
    }
}
