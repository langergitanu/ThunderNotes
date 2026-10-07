package com.thundernotes.snip

import android.content.Context
import android.graphics.Bitmap
import com.thundernotes.canvas.StrokeRecord

/**
 * The LaTeX → stroke pipeline (spec §7.8 / thunder-format-proposal Part D):
 *
 * LaTeX → [KatexRenderer] (offscreen WebView, black-on-white, high-DPI) →
 * bitmap → [CenterlineTracer] (binarize → Zhang-Suen skeletonize → polylines
 * → [StrokeRecord]s) → return the strokes.
 *
 * The output is ordinary [StrokeRecord]s (ballpoint brush, base color black) →
 * they render, erase, lasso, theme-invert, save to `.thunder`, + paste
 * through the existing paths with **zero special-casing** (spec §7.8: "no
 * schema change: plain StrokeEntity rows + InkStrokeProto blobs").
 *
 * Android-only (uses [KatexRenderer]'s WebView). The [CenterlineTracer] is
 * pure + unit-tested; this pipeline is build-verifiable.
 */
object LatexToStrokes {

    /**
     * Convert [latex] to a list of [StrokeRecord]s via the KaTeX → centerline
     * trace pipeline. Returns null on failure (KaTeX render error, tracer
     * failure — the caller should surface an error, never silently drop
     * content per spec §7.8).
     */
    suspend fun convert(latex: String, context: Context): List<StrokeRecord>? {
        // 1. Render LaTeX → high-DPI bitmap (black-on-white).
        val bmp = KatexRenderer.render(latex, context) ?: return null

        // 2. Bitmap → SnipImage (pure pixel array).
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val img = SnipImage(w, h, pixels)

        // 3. Centerline trace → StrokeRecords.
        val strokes = CenterlineTracer.trace(img)
        return strokes.ifEmpty { null }
    }
}
