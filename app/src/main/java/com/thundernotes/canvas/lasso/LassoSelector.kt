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
}
