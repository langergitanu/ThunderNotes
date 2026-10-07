package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.StrokeRecord
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 7 geometric/brush transforms a lasso selection can apply (spec §7.2 —
 * the lasso's 9 functions; Cut/Copy/Delete are lifecycle ops in [LassoOps],
 * the other 6 are here + Rotate; Enlarge + Reduce share [scale]). Each
 * transform returns a new [StrokeRecord] list with the SAME ids (so the
 * document can replace the old strokes in place by id) but transformed
 * points/brush. Pure + unit-testable.
 *
 * All transforms operate around the **selection bbox center** so the group
 * stays visually coherent (rotate/flip/scale around the group's centroid).
 */
object StrokeTransforms {

    /** The bounding box of a group of strokes = the union of each stroke's bbox. */
    fun groupBounds(strokes: List<StrokeRecord>): FloatArray {
        if (strokes.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            val bb = LassoSelector.strokeBounds(s)
            if (bb[0] < minX) minX = bb[0]
            if (bb[1] < minY) minY = bb[1]
            if (bb[2] > maxX) maxX = bb[2]
            if (bb[3] > maxY) maxY = bb[3]
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Rotate every point by [degrees] around the group bbox center. */
    fun rotate(strokes: List<StrokeRecord>, degrees: Float): List<StrokeRecord> {
        val bb = groupBounds(strokes)
        val cx = (bb[0] + bb[2]) / 2f
        val cy = (bb[1] + bb[3]) / 2f
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val cosA = cos(rad); val sinA = sin(rad)
        return strokes.map { s ->
            s.copy(inputXy = mapPoints(s.inputXy) { x, y ->
                val dx = x - cx; val dy = y - cy
                cx + dx * cosA - dy * sinA to cy + dx * sinA + dy * cosA
            })
        }
    }

    /** Scale every point by [factor] around the group bbox center (Enlarge/Reduce).
     *  @param scaleBrushSize when true (constant-scaling OFF, spec §7.5), also scale
     *    each stroke's [StrokeRecord.brushSize] by [factor] so the thickness grows/
     *    shrinks proportionally. Default false (constant scaling ON). */
    fun scale(strokes: List<StrokeRecord>, factor: Float, scaleBrushSize: Boolean = false): List<StrokeRecord> {
        val bb = groupBounds(strokes)
        val cx = (bb[0] + bb[2]) / 2f
        val cy = (bb[1] + bb[3]) / 2f
        return strokes.map { s ->
            val scaledPts = mapPoints(s.inputXy) { x, y ->
                (cx + (x - cx) * factor) to (cy + (y - cy) * factor)
            }
            if (scaleBrushSize) s.copy(inputXy = scaledPts, brushSize = s.brushSize * factor)
            else s.copy(inputXy = scaledPts)
        }
    }

    /** Mirror every point's x around the group bbox center (Horizontal Flip). */
    fun flipHorizontal(strokes: List<StrokeRecord>): List<StrokeRecord> {
        val bb = groupBounds(strokes)
        val cx = (bb[0] + bb[2]) / 2f
        return strokes.map { s ->
            s.copy(inputXy = mapPoints(s.inputXy) { x, y -> (2 * cx - x) to y })
        }
    }

    /** Mirror every point's y around the group bbox center (Vertical Flip). */
    fun flipVertical(strokes: List<StrokeRecord>): List<StrokeRecord> {
        val bb = groupBounds(strokes)
        val cy = (bb[1] + bb[3]) / 2f
        return strokes.map { s ->
            s.copy(inputXy = mapPoints(s.inputXy) { x, y -> x to (2 * cy - y) })
        }
    }

    /** Set the brush color of every selected stroke (Change Color). */
    fun changeColor(strokes: List<StrokeRecord>, colorArgb: Int): List<StrokeRecord> =
        strokes.map { it.copy(brushColorArgb = colorArgb) }

    /** Scale the brush size of every selected stroke by [factor]
     *  (Change Stroke Thickness — spec §7.2 note: this sets the thickness of the
     *  ENTIRE lasso-selected area). */
    fun changeStrokeThickness(strokes: List<StrokeRecord>, factor: Float): List<StrokeRecord> =
        strokes.map { it.copy(brushSize = it.brushSize * factor) }

    // ─── helper: apply a transform to each (x,y) pair in a flat list ─────────

    private inline fun mapPoints(
        xy: List<Float>,
        transform: (x: Float, y: Float) -> Pair<Float, Float>,
    ): List<Float> {
        val out = ArrayList<Float>(xy.size)
        var i = 0
        while (i + 1 < xy.size) {
            val (tx, ty) = transform(xy[i], xy[i + 1])
            out.add(tx); out.add(ty)
            i += 2
        }
        return out
    }
}
