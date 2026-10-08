package com.thundernotes.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws the rectangular lasso drag (spec §6.10 Row 3e Rectangular Lasso) as a
 * translucent fill + dashed border, on top of the ink surface. The activity
 * calls [setRect] during a LASSO-tool drag + [clear] on release.
 *
 * A minimal example of the custom-View "state + invalidate" loop: the only
 * state is the current [rect]; [setRect] stores it + calls `invalidate()`,
 * which asks Android to call [onDraw] on the next frame; onDraw paints the
 * rect with [fillPaint]/[borderPaint] (the border dashes come from
 * [DashPathEffect]). Cheap enough to run at drag rate (no allocations in
 * onDraw — the Paints are pre-built exactly for that reason).
 */
class LassoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var rect: FloatArray? = null  // [left, top, right, bottom]

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 78, 222, 163)   // emerald 15% — matches the paste-glow accent
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 78, 222, 163)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    fun setRect(left: Float, top: Float, right: Float, bottom: Float) {
        rect = floatArrayOf(left, top, right, bottom)
        invalidate()
    }

    fun clear() {
        rect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = rect ?: return
        canvas.drawRect(r[0], r[1], r[2], r[3], fillPaint)
        canvas.drawRect(r[0], r[1], r[2], r[3], borderPaint)
    }
}
