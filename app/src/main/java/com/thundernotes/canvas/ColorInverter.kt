package com.thundernotes.canvas

import kotlin.math.max
import kotlin.math.min

/**
 * Smart theme inversion (spec §6.10 Row 1 right + §6.10 Canvas Area note):
 * "Toggling changes the canvas area from dark to light and vice-versa while
 * preserving base colors — e.g., a black pen stroke becomes white, dark red
 * becomes light red, dark blue becomes light blue. Smart color inversion
 * must preserve hue — red `#FF0000` must not become cyan `#00FFFF`."
 *
 * Implementation: convert ARGB → HSL, **invert L** (lightness), keep H + S,
 * convert back. Black (L=0)↔white (L=1); red (H=0, L=0.5)→red (L=0.5, NOT
 * cyan which would need H inverted); dark red (low L)→light red (high L).
 *
 * Pure Kotlin RGB↔HSL (no android.graphics dependency) → unit-testable on
 * the JVM. The standard W3C algorithm (matches CSS `hsl()`).
 */
object ColorInverter {

    /** Invert the lightness of an ARGB color, preserving alpha + hue + saturation. */
    fun invert(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hsl = FloatArray(3)
        rgbToHsl(r, g, b, hsl)
        hsl[2] = 1f - hsl[2]  // invert lightness only
        val rgb = hslToRgb(hsl[0], hsl[1], hsl[2])
        return (a shl 24) or
            ((rgb[0] and 0xFF) shl 16) or
            ((rgb[1] and 0xFF) shl 8) or
            (rgb[2] and 0xFF)
    }

    // ─── RGB ↔ HSL (W3C / CSS reference algorithm) ──────────────────────────

    private fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val l = (max + min) / 2f
        var h = 0f
        var s = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6 else 0))
                g -> ((b - r) / d + 2)
                else -> ((r - g) / d + 4)
            }
            h *= 60f
        }
        out[0] = h
        out[1] = s
        out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
        val r: Float
        val g: Float
        val b: Float
        if (s == 0f) {
            r = l; g = l; b = l
        } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            val hk = (h % 360f) / 360f
            r = hueToRgb(p, q, hk + 1f / 3f)
            g = hueToRgb(p, q, hk)
            b = hueToRgb(p, q, hk - 1f / 3f)
        }
        return intArrayOf((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0) t += 1
        if (t > 1) t -= 1
        if (t < 1f / 6) return p + (q - p) * 6 * t
        if (t < 1f / 2) return q
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6
        return p
    }
}
