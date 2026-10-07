package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.StrokeRecord

/**
 * A rectangular lasso selection (spec §6.10 Row 3e: "Lasso: 2 types — Random
 * Lasso, Rectangular Lasso"). The rectangular variant is implemented here;
 * the free-form (random) lasso is a refinement that swaps the rect for a
 * polygon + a point-in-polygon test.
 *
 * Pure + unit-testable (no Android/Ink dependency).
 *
 * @property left top right bottom the lasso rectangle, in page-local coords.
 * @property strokeIds the ids of the strokes whose bounding boxes intersect
 * the rect (the [LassoSelector] computes this). The strokes themselves are
 * looked up by the caller from the [com.thundernotes.canvas.CanvasDocument].
 */
data class LassoSelection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val strokeIds: List<String>,
) {
    val isEmpty: Boolean get() = strokeIds.isEmpty()
}

/**
 * Selects strokes whose bounding box intersects a lasso rectangle.
 *
 * The bounding box of a [StrokeRecord] is the min/max of its [StrokeRecord.inputXy]
 * (the flat x,y list). A stroke is selected iff its bbox intersects the lasso rect
 * (inclusive) — a permissive hit-test so a lasso that grazes a stroke selects it.
 */
object LassoSelector {

    /** Select the strokes whose bbox intersects ([left],[top],[right],[bottom]). */
    fun selectByRect(
        strokes: List<StrokeRecord>,
        left: Float, top: Float, right: Float, bottom: Float,
    ): LassoSelection {
        // Normalize the rect (drag can go up-left → down-right or any direction).
        val l = minOf(left, right)
        val t = minOf(top, bottom)
        val r = maxOf(left, right)
        val b = maxOf(top, bottom)
        val ids = strokes.filter { s -> bboxIntersects(s, l, t, r, b) }.map { it.id }
        return LassoSelection(l, t, r, b, ids)
    }

    /** A stroke's bbox = (minX, minY, maxX, maxY) over its inputXy. */
    fun strokeBounds(s: StrokeRecord): FloatArray {
        val xy = s.inputXy
        if (xy.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < xy.size) {
            val x = xy[i]; val y = xy[i + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
            i += 2
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun bboxIntersects(s: StrokeRecord, l: Float, t: Float, r: Float, b: Float): Boolean {
        val bb = strokeBounds(s)
        // No overlap iff one rect is strictly left/right/above/below the other.
        return !(bb[2] < l || bb[0] > r || bb[3] < t || bb[1] > b)
    }

    /**
     * Select strokes whose bbox intersects the free-form polygon's bbox AND
     * whose bbox has at least one corner inside the polygon (spec §6.10.6e
     * "Random Lasso"). A point-in-polygon ray-cast test (even-odd rule) is
     * used — this is the standard algorithm (W. Randolph Franklin).
     *
     * The [polygon] is a flat x,y list (≥6 floats = 3 points). The polygon
     * doesn't need to be closed (the test wraps around). Returns a
     * [LassoSelection] whose rect is the polygon's bbox + whose strokeIds are
     * the selected strokes.
     */
    fun selectByPolygon(strokes: List<StrokeRecord>, polygon: List<Float>): LassoSelection {
        if (polygon.size < 6) return LassoSelection(0f, 0f, 0f, 0f, emptyList())
        // Compute the polygon's bbox.
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < polygon.size) {
            val x = polygon[i]; val y = polygon[i + 1]
            if (x < l) l = x; if (y < t) t = y
            if (x > r) r = x; if (y > b) b = y
            i += 2
        }
        // First filter by bbox-intersection (cheap), then confirm by
        // point-in-polygon on at least one bbox corner (the stroke is selected
        // if any of its 4 bbox corners is inside the polygon — permissive).
        val ids = strokes.filter { s -> bboxIntersects(s, l, t, r, b) }
            .filter { s -> bboxCornerInPolygon(s, polygon) }
            .map { it.id }
        return LassoSelection(l, t, r, b, ids)
    }

    /** True iff any of the stroke's 4 bbox corners lies inside [polygon]. */
    private fun bboxCornerInPolygon(s: StrokeRecord, polygon: List<Float>): Boolean {
        val bb = strokeBounds(s)
        val corners = arrayOf(
            bb[0] to bb[1], bb[2] to bb[1], bb[0] to bb[3], bb[2] to bb[3],
        )
        return corners.any { (cx, cy) -> pointInPolygon(cx, cy, polygon) }
    }

    /** Even-odd ray-cast point-in-polygon test (Franklin's algorithm). */
    fun pointInPolygon(px: Float, py: Float, polygon: List<Float>): Boolean {
        val n = polygon.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[i * 2]; val yi = polygon[i * 2 + 1]
            val xj = polygon[j * 2]; val yj = polygon[j * 2 + 1]
            val intersects = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / ((yj - yi).coerceAtLeast(Float.MIN_VALUE)) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
