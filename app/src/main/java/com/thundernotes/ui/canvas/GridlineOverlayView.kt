package com.thundernotes.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The m×n gridline overlay (spec §6.10 Row 2c Gridline) — a bold grid drawn over
 * the page surface. The "magnetic" snap-to-grid is a refinement (the grid lines
 * are the visual base). The activity calls [show] with the active rows/cols +
 * toggles visibility via [setVisibility]; [clear] hides it.
 *
 * Pattern adapted from Notein's `PageGrid` model (pageId + bounds + index) —
 * here it's a single per-page overlay sized to the page card.
 */
class GridlineOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var rows: Int = 8
    private var cols: Int = 4

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 220, 220, 200)  // soft warm grid line on the light page
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    fun show(rows: Int, cols: Int) {
        this.rows = rows.coerceAtLeast(1)
        this.cols = cols.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Vertical lines (cols+1 of them across the width).
        val dx = w / cols
        for (i in 0..cols) {
            val x = i * dx
            canvas.drawLine(x, 0f, x, h, linePaint)
        }
        // Horizontal lines (rows+1 of them down the height).
        val dy = h / rows
        for (i in 0..rows) {
            val y = i * dy
            canvas.drawLine(0f, y, w, y, linePaint)
        }
    }
}
