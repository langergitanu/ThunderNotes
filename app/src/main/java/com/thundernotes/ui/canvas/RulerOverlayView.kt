package com.thundernotes.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Scale ruler (spec §6.10 Row 2c: "Opens a long marked ruler on the canvas
 * for drawing quick lines; the ruler can be rotated").
 *
 * Pattern adapted from Notein's `RulerOverlayView` (a View with onDraw + a
 * transform gesture + an angle-dial click listener — Notein's body is obfuscated,
 * so this is a clean re-implementation of the same structure): draws a long
 * rounded ruler with tick marks + numbers, **draggable** (single-touch moves the
 * ruler's centre) and **rotatable** (long-press → preset-angle PopupMenu via
 * the activity; or [setAngle] directly). "Draw quick lines along the ruler"
 * (snap-to-edge) is a refinement — the ruler renders + moves + rotates now.
 *
 * The activity owns the instance per page (added to the page item), toggles
 * visibility via [setVisibility], + calls [setAngle] from the long-press menu.
 */
class RulerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Ruler centre, in view-local px. */
    private var cx: Float = 0f
    private var cy: Float = 0f
    /** Rotation in degrees (0 = horizontal). */
    private var angleDeg: Float = 0f

    private val rulerLenPx: Float get() = width * 0.8f
    private val rulerHPx: Float get() = dp(28).toFloat()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 250, 220, 90)  // translucent amber ruler body
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 90, 60, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 60, 40, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 40, 20, 0)
        textSize = 9f
        textAlign = Paint.Align.CENTER
    }

    /** Position the ruler at the view centre on first show. */
    fun initAtCentre() {
        cx = width / 2f
        cy = height / 2f
        invalidate()
    }

    /** Rotate the ruler (called from the long-press preset-angle menu). */
    fun setAngle(deg: Float) {
        angleDeg = deg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (cx == 0f && cy == 0f) initAtCentre()
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        // Ruler body (a rounded rect centred at cx,cy).
        val halfLen = rulerLenPx / 2
        val halfH = rulerHPx / 2
        val rect = RectF(cx - halfLen, cy - halfH, cx + halfLen, cy + halfH)
        canvas.drawRoundRect(rect, 8f, 8f, bodyPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
        // Tick marks + numbers along the length (every ~20dp).
        val step = dp(20)
        var n = 0
        var x = cx - halfLen + step
        while (x < cx + halfLen - step / 2) {
            val major = n % 5 == 0
            val tickH = if (major) halfH else halfH * 0.5f
            canvas.drawLine(x, cy - halfH, x, cy - halfH + tickH, tickPaint)
            if (major) canvas.drawText((n / 5).toString(), x, cy - halfH + tickH + 9, numPaint)
            x += step
            n++
        }
        canvas.restore()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Drag the ruler (single-touch). The angle is set via the activity's
        // long-press menu (setAngle) — a two-finger rotate is a refinement.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { /* begin drag */ }
            MotionEvent.ACTION_MOVE -> {
                cx = ev.x
                cy = ev.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> { /* end drag */ }
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
