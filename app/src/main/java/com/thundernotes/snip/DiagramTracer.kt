package com.thundernotes.snip

import com.thundernotes.canvas.StrokeRecord
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Diagram-specific tracer (spec §7.3.4d + the user's rules):
 *
 * 1. **No fill/gradient** — only lines (straight or curved) are traced. Filled
 *    regions (solid color areas) are excluded: an edge-detection step keeps
 *    only border ink pixels (those with at least one non-ink neighbour) →
 *    filled interiors become outlines.
 *
 * 2. **Separate at sharp points** — if a skeleton polyline has a sharp angle
 *    (deviation > [SHARP_ANGLE_DEGREES]), it's split into two separate
 *    StrokeRecords at that point. This separates, e.g., a rectangle's sides
 *    into 4 distinct strokes.
 *
 * 3. **Separate at color changes** — using the ORIGINAL (pre-binarized) image,
 *    if two adjacent skeleton pixels have a significant hue difference
 *    (> [COLOR_CHANGE_THRESHOLD]), the polyline splits there. This separates
 *    strokes of different colours (spec §7.3.4d: "separate strokes iff sharp
 *    point OR different color").
 *
 * Shares the [CenterlineTracer]'s skeletonize + trace code. Pure + unit-testable.
 */
object DiagramTracer {

    /** A polyline point with angle > this (degrees) is a "sharp point" → split. */
    private const val SHARP_ANGLE_DEGREES = 60.0

    /** Hue difference (0-360) above which two pixels are "different colour". */
    private const val COLOR_CHANGE_THRESHOLD = 30

    /**
     * Trace a diagram image → [StrokeRecord]s.
     * @param original the ORIGINAL colour image (for colour-change detection).
     *   If null, colour-change splitting is skipped (only sharp-point splitting).
     */
    fun trace(original: SnipImage): List<StrokeRecord> {
        // 1. Binarize (auto-polarity + Otsu).
        val bw = SnipPreprocessor.binarize(original)

        // 2. Edge detection: keep only border ink pixels (remove filled interiors).
        val edges = detectEdges(bw)

        // 3. Skeletonize (Zhang-Suen — thins to 1px centerlines).
        val skeleton = CenterlineTracer.skeletonize(edges)

        // 4. Trace polylines on the skeleton.
        val polylines = CenterlineTracer.tracePolylines(skeleton)

        // 5. Split polylines at sharp points + colour changes.
        val splitPolylines = polylines.flatMap { splitAtBreaks(it, original) }

        // 6. Drop polylines shorter than 3px (spur cleanup, spec §7.8).
        val cleaned = splitPolylines.filter { polylineLength(it) >= 3f }

        // 7. Build StrokeRecords.
        return CenterlineTracer.toStrokeRecords(cleaned, pageId = "diagram")
    }

    // ─── Edge detection (remove filled interiors) ──────────────────────────

    /**
     * Keep only border ink pixels: a black pixel is kept iff at least one of
     * its 8 neighbours is white (non-ink). Interior pixels (all neighbours
     * black) are removed → filled regions become outlines only.
     */
    private fun detectEdges(bw: SnipImage): SnipImage {
        val w = bw.width; val h = bw.height
        val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (bw.pixels[idx] != 0xFF000000.toInt()) continue  // not ink → skip
                // Check if any neighbour is non-ink (border pixel).
                var isBorder = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) { isBorder = true; continue }
                        if (bw.pixels[ny * w + nx] != 0xFF000000.toInt()) { isBorder = true }
                    }
                }
                if (isBorder) out[idx] = 0xFF000000.toInt()
            }
        }
        return SnipImage(w, h, out)
    }

    // ─── Split at sharp points + colour changes ─────────────────────────────

    /**
     * Split a polyline at sharp points (angle > threshold) + colour changes.
     * Returns a list of sub-polylines (each a separate stroke).
     */
    private fun splitAtBreaks(polyline: List<FloatArray>, original: SnipImage): List<List<FloatArray>> {
        if (polyline.size < 3) return listOf(polyline)
        val segments = mutableListOf<MutableList<FloatArray>>()
        var current = mutableListOf(polyline[0], polyline[1])
        segments.add(current)
        for (i in 1 until polyline.size - 1) {
            val prev = polyline[i - 1]; val curr = polyline[i]; val next = polyline[i + 1]
            // Check sharp angle.
            val angle = angleBetween(prev, curr, next)
            val isSharp = angle > SHARP_ANGLE_DEGREES
            // Check colour change.
            val isColorChange = hasColorChange(original, curr, next)
            if (isSharp || isColorChange) {
                // End the current segment + start a new one.
                current.add(curr)
                current = mutableListOf(curr, next)
                segments.add(current)
            } else {
                current.add(curr)
            }
        }
        // Add the last point.
        current.add(polyline.last())
        // Filter out tiny segments (< 2 points).
        return segments.filter { it.size >= 2 }
    }

    /** Compute the turn angle (degrees) at point B formed by A→B→C.
     *  0° = straight, 90° = right angle, 180° = U-turn. */
    private fun angleBetween(a: FloatArray, b: FloatArray, c: FloatArray): Double {
        val dir1 = Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble()))
        val dir2 = Math.toDegrees(atan2((c[1] - b[1]).toDouble(), (c[0] - b[0]).toDouble()))
        var turn = abs(dir2 - dir1)
        if (turn > 180) turn = 360 - turn
        return turn
    }

    /** Check if two pixels in the original image have a significant hue difference. */
    private fun hasColorChange(img: SnipImage, p1: FloatArray, p2: FloatArray): Boolean {
        val x1 = p1[0].toInt().coerceIn(0, img.width - 1)
        val y1 = p1[1].toInt().coerceIn(0, img.height - 1)
        val x2 = p2[0].toInt().coerceIn(0, img.width - 1)
        val y2 = p2[1].toInt().coerceIn(0, img.height - 1)
        val h1 = hue(img.pixels[y1 * img.width + x1])
        val h2 = hue(img.pixels[y2 * img.width + x2])
        val diff = kotlin.math.min(abs(h1 - h2), 360 - abs(h1 - h2))
        return diff > COLOR_CHANGE_THRESHOLD
    }

    /** Compute the hue (0-360) of an ARGB pixel. */
    private fun hue(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max == min) return 0.0
        val d = max - min
        val h = when (max) {
            r -> ((g - b) / d) % 6.0
            g -> ((b - r) / d) + 2.0
            else -> ((r - g) / d) + 4.0
        }
        return (h * 60.0 + 360.0) % 360.0
    }

    /** Compute the total length of a polyline (Euclidean). */
    private fun polylineLength(polyline: List<FloatArray>): Float {
        var len = 0f
        for (i in 1 until polyline.size) {
            val dx = polyline[i][0] - polyline[i - 1][0]
            val dy = polyline[i][1] - polyline[i - 1][1]
            len += sqrt(dx * dx + dy * dy)
        }
        return len
    }
}
