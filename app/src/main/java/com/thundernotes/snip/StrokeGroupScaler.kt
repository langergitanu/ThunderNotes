package com.thundernotes.snip

import com.thundernotes.canvas.StrokeRecord

/**
 * Uniformly rescales a traced stroke group (Equation- or Diagram-snip output)
 * from its **source-image pixel space** into **canvas-page pixel space** so the
 * pasted group lands at a sensible size on the page.
 *
 * Why this is needed (spec §7.3.4d + §7.8): the centerline tracer produces
 * strokes whose coordinates are pixels of the *rendered/traced bitmap* — a
 * full-screen phone/tablet capture can be 3000×2000 px while a note page is
 * only ~1840 px wide, so pasted diagram strokes would otherwise overflow far
 * beyond the page edges. The KaTeX render is a fixed 1080-px-wide bitmap, so
 * equations pasted at 1:1 would look inconsistent (sometimes huge, sometimes
 * tiny relative to the page).
 *
 * What it does:
 *  1. Computes the true bounding box of the stroke group (in source px).
 *  2. Picks a uniform scale so the box fits inside (targetW, targetH) —
 *     capped at [maxScale] (tiny crops shouldn't blow up to full page) and
 *     floored at [minScale] (huge captures don't shrink to nothing).
 *  3. Multiplies every point coordinate by the scale + every stroke's brush
 *     size by the same scale (so line thicknesses keep their visual ratio),
 *     clamped to a sane on-page range.
 *
 * Pure + unit-testable (no Android dependency — operates on [StrokeRecord]s).
 */
object StrokeGroupScaler {

    /** Default cap on upscaling — a small 200-px trace shouldn't become full-page. */
    const val DEFAULT_MAX_SCALE = 2.5f

    /** Floor on downscaling — guards against division-by-tiny-bbox explosions. */
    const val DEFAULT_MIN_SCALE = 0.05f

    /** Brush-size clamp after scaling (dp-ish page px — matches editor widths). */
    private const val BRUSH_MIN = 0.75f
    private const val BRUSH_MAX = 12f

    /**
     * Bounding box of a stroke group: `floatArrayOf(minX, minY, maxX, maxY)`.
     * Empty strokes → all zeros. Only geometry is used (brush width is added
     * by the caller when it needs a padded box).
     */
    fun bboxOf(strokes: List<StrokeRecord>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) {
            val xy = s.inputXy
            var i = 0
            while (i + 1 < xy.size) {
                val x = xy[i]; val y = xy[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        if (minX > maxX || minY > maxY) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Scale [strokes] so the group fits inside ([targetW], [targetH]) page px.
     *
     * @param targetW max width on the page (px) — e.g. 60% of the page width.
     * @param targetH max height on the page (px) — e.g. 45% of the page height.
     * @return the scaled stroke list + its real post-scale bbox
     *   `[minX, minY, maxX, maxY]` (origin stays at 0,0 — translation to the
     *   drop point happens later in [com.thundernotes.canvas.inject.InkInjector]).
     */
    fun fit(
        strokes: List<StrokeRecord>,
        targetW: Float,
        targetH: Float,
        maxScale: Float = DEFAULT_MAX_SCALE,
        minScale: Float = DEFAULT_MIN_SCALE,
    ): Pair<List<StrokeRecord>, FloatArray> {
        if (strokes.isEmpty()) return emptyList<StrokeRecord>() to floatArrayOf(0f, 0f, 0f, 0f)
        if (targetW <= 0f || targetH <= 0f) return strokes to bboxOf(strokes)

        val bbox = bboxOf(strokes)
        val boxW = bbox[2] - bbox[0]
        val boxH = bbox[3] - bbox[1]
        if (boxW <= 0f && boxH <= 0f) return strokes to bbox  // degenerate (dots)

        var scale = minOf(
            if (boxW > 0f) targetW / boxW else Float.MAX_VALUE,
            if (boxH > 0f) targetH / boxH else Float.MAX_VALUE,
        )
        if (scale.isNaN() || scale.isInfinite() || scale <= 0f) scale = 1f
        scale = scale.coerceIn(minScale, maxScale)

        val scaled = strokes.map { s ->
            s.copy(
                inputXy = s.inputXy.map { v -> v * scale },
                brushSize = (s.brushSize * scale).coerceIn(BRUSH_MIN, BRUSH_MAX),
            )
        }
        return scaled to bboxOf(scaled)
    }

    /**
     * Plain uniform scale (no fit computation) — used when a caller already
     * knows the exact scale factor (e.g. the diagram path computed one from
     * the downscaled-capture ratio). Brush size scales with it, clamped.
     */
    fun scale(strokes: List<StrokeRecord>, factor: Float): List<StrokeRecord> {
        if (factor == 1f || strokes.isEmpty()) return strokes
        return strokes.map { s ->
            s.copy(
                inputXy = s.inputXy.map { v -> v * factor },
                brushSize = (s.brushSize * factor).coerceIn(BRUSH_MIN, BRUSH_MAX),
            )
        }
    }
}
