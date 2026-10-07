package com.thundernotes.snip

import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-Kotlin centerline tracer (spec §7.8 / thunder-format-proposal Part D):
 * renders a binarized glyph bitmap → skeleton (1px-wide centerline) → polylines
 * → [StrokeRecord]s. **No OpenCV dependency** — uses the Zhang-Suen thinning
 * algorithm (pure pixel ops) + a graph-traversal polyline tracer.
 *
 * This is the **shared tracer** between the Equation Snip (LaTeX → KaTeX
 * render → bitmap → here) and the Diagram Snip (image → here directly).
 * One code path (spec §7.8: "the tracer is shared with the Diagram Snip").
 *
 * Algorithm:
 * 1. **Skeletonize** (Zhang-Suen): iteratively remove border pixels until the
 *    stroke is 1px thick. Preserves the glyph's topology.
 * 2. **Trace polylines**: on the skeleton, find endpoints (1 neighbor) + junctions
 *    (3+ neighbors), follow paths → ordered polylines. Downsample (~1 pt / 3px).
 * 3. **Build [StrokeRecord]s**: each polyline → a StrokeRecord with the points
 *    (x, y) + 5 zero attrs + a fixed brush size (medium). The distance-transform
 *    width estimation is a refinement (the KaTeX render has a uniform stroke).
 *
 * **Deterministic** (row-major endpoint discovery, stable for tests — spec §7.8).
 * Pure + unit-testable (operates on [SnipImage], no Android/OpenCV dependency).
 */
object CenterlineTracer {

    // ─── 1. Zhang-Suen skeletonize ────────────────────────────────────────

    /**
     * Thin a binarized image (black ink on white) to a 1px-wide skeleton
     * using the Zhang-Suen algorithm (two sub-iterations per pass).
     */
    fun skeletonize(img: SnipImage): SnipImage {
        val w = img.width; val h = img.height
        // Working grid: true = ink (black), false = background.
        val grid = BooleanArray(w * h) { i -> img.pixels[i] == 0xFF000000.toInt() }
        var changed = true
        while (changed) {
            changed = false
            // Sub-iteration 1.
            val toRemove1 = mutableListOf<Int>()
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    if (!grid[idx]) continue
                    val n = neighbors(grid, x, y, w)
                    val b = n.count { it }
                    val a = transitions(n)
                    // Zhang-Suen sub-iteration 1: B(P) in 2..6, A(P) = 1,
                    // P2×P4×P6 = 0, P4×P6×P8 = 0.
                    if (b in 2..6 && a == 1) {
                        if (!(n[0] && n[2] && n[4]) && !(n[2] && n[4] && n[6])) {
                            toRemove1.add(idx)
                        }
                    }
                }
            }
            for (idx in toRemove1) { grid[idx] = false; changed = true }

            // Sub-iteration 2.
            val toRemove2 = mutableListOf<Int>()
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    if (!grid[idx]) continue
                    val n = neighbors(grid, x, y, w)
                    val b = n.count { it }
                    val a = transitions(n)
                    if (b in 2..6 && a == 1) {
                        // P2×P4×P8 = 0 AND P2×P6×P8 = 0
                        if (!(n[0] && n[2] && n[6]) && !(n[0] && n[4] && n[6])) {
                            toRemove2.add(idx)
                        }
                    }
                }
            }
            for (idx in toRemove2) { grid[idx] = false; changed = true }
        }
        // Convert back to SnipImage (black skeleton on white).
        val out = IntArray(w * h) { i -> if (grid[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return SnipImage(w, h, out)
    }

    /** The 8 neighbors in clockwise order: P2, P3, P4, P5, P6, P7, P8, P9. */
    private fun neighbors(grid: BooleanArray, x: Int, y: Int, w: Int): BooleanArray {
        val idx = { dx: Int, dy: Int -> (y + dy) * w + (x + dx) }
        return booleanArrayOf(
            grid[idx(0, -1)],  // P2 (above)
            grid[idx(1, -1)],  // P3 (top-right)
            grid[idx(1, 0)],   // P4 (right)
            grid[idx(1, 1)],   // P5 (bottom-right)
            grid[idx(0, 1)],   // P6 (below)
            grid[idx(-1, 1)],  // P7 (bottom-left)
            grid[idx(-1, 0)],  // P8 (left)
            grid[idx(-1, -1)], // P9 (top-left)
        )
    }

    /** Count 01 transitions in the clockwise neighbor sequence (cyclic). */
    private fun transitions(n: BooleanArray): Int {
        var count = 0
        for (i in n.indices) {
            if (!n[i] && n[(i + 1) % n.size]) count++
        }
        return count
    }

    // ─── 2. Trace polylines ────────────────────────────────────────────────

    /**
     * Trace the skeleton into ordered polylines. Finds endpoints (1 neighbor) +
     * follows paths (2-neighbor chains) until reaching another endpoint or
     * junction. Returns a list of polylines, each a list of (x, y) pairs.
     *
     * Deterministic: endpoints discovered row-major (top-to-bottom, left-to-right).
     */
    fun tracePolylines(skeleton: SnipImage): List<List<FloatArray>> {
        val w = skeleton.width; val h = skeleton.height
        val ink = BooleanArray(w * h) { i -> skeleton.pixels[i] == 0xFF000000.toInt() }
        val visited = BooleanArray(w * h)
        val polylines = mutableListOf<List<FloatArray>>()

        // Count neighbors for each ink pixel.
        fun neighborCount(x: Int, y: Int): Int {
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && ink[ny * w + nx]) c++
            }
            return c
        }

        // Follow a path from (x, y) until endpoint/junction/dead-end.
        fun followPath(sx: Int, sy: Int): List<FloatArray> {
            val path = mutableListOf<FloatArray>()
            var x = sx; var y = sy
            while (true) {
                val idx = y * w + x
                visited[idx] = true
                path.add(floatArrayOf(x.toFloat(), y.toFloat()))
                // Find next unvisited ink neighbor.
                var nx = -1; var ny = -1
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val cx = x + dx; val cy = y + dy
                    if (cx in 0 until w && cy in 0 until h) {
                        val ci = cy * w + cx
                        if (ink[ci] && !visited[ci]) { nx = cx; ny = cy }
                    }
                }
                if (nx < 0) break  // dead-end or all visited
                val nCount = neighborCount(nx, ny)
                if (nCount != 2) {
                    // Reached an endpoint (1) or junction (3+) → include it + stop.
                    visited[ny * w + nx] = true
                    path.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
                    break
                }
                x = nx; y = ny
            }
            return path
        }

        // Find endpoints (1 neighbor) first → start traces from them.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!ink[idx] || visited[idx]) continue
                val nc = neighborCount(x, y)
                if (nc == 1) {
                    val path = followPath(x, y)
                    if (path.size >= 2) polylines.add(path)
                }
            }
        }
        // Trace remaining unvisited paths (closed loops or isolated segments).
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!ink[idx] || visited[idx]) continue
                val path = followPath(x, y)
                if (path.size >= 2) polylines.add(path)
            }
        }
        return polylines
    }

    // ─── 3. Build StrokeRecords ────────────────────────────────────────────

    /**
     * Convert the polylines to [StrokeRecord]s. Downsamples to ~1 point per 3px
     * (spec §7.8). Uses a fixed medium brush size (3dp) — the distance-transform
     * width estimation is a refinement. Emits row-major (deterministic).
     */
    fun toStrokeRecords(polylines: List<List<FloatArray>>, pageId: String = "latex"): List<StrokeRecord> {
        return polylines.map { polyline ->
            val downsampled = downsamplePolyline(polyline, minGap = 3f)
            val n = downsampled.size
            val xy = downsampled.flatMap { it.toList() }  // [x0,y0,x1,y1,...]
            val attrs = FloatArray(n * 5).also { arr ->
                for (i in 0 until n) {
                    arr[i * 5 + 1] = 1f  // pressure = 1
                }
            }
            StrokeRecord(
                id = UUID.randomUUID().toString(),
                layerId = "layer-0",
                pageId = pageId,
                brushSize = 3f,        // medium (refinement: distance-transform width)
                brushColorArgb = 0xFF1A1A1A.toInt(),  // base color black (spec §7.8)
                brushEpsilon = 0.1f,
                brushFamilyId = BrushFamily.THUNDER_BALLPOINT_V1,
                toolType = ToolType.STYLUS.rawValue,
                inputXy = xy,
                inputAttrs = attrs.toList(),
            )
        }
    }

    /** Downsample: keep only points ≥ [minGap] apart from the last kept. */
    private fun downsamplePolyline(polyline: List<FloatArray>, minGap: Float): List<FloatArray> {
        if (polyline.size <= 2) return polyline
        val out = mutableListOf(polyline[0])
        for (i in 1 until polyline.size - 1) {
            val last = out.last()
            val p = polyline[i]
            val dx = (p[0] - last[0]).toDouble()
            val dy = (p[1] - last[1]).toDouble()
            val d = sqrt(dx * dx + dy * dy)
            if (d >= minGap.toDouble()) out.add(p)
        }
        out.add(polyline.last())  // always keep the endpoint
        return out
    }

    // ─── Full pipeline: bitmap → skeleton → polylines → strokes ────────────

    /**
     * The full centerline-trace pipeline: binarize → skeletonize → trace
     * polylines → build StrokeRecords. This is the entry point for both the
     * Equation Snip (LaTeX → KaTeX → bitmap → here) and the Diagram Snip
     * (image → here directly).
     */
    fun trace(img: SnipImage): List<StrokeRecord> {
        val bw = SnipPreprocessor.binarize(img)  // ensure pure B/W
        val skeleton = skeletonize(bw)
        val polylines = tracePolylines(skeleton)
        return toStrokeRecords(polylines)
    }
}
