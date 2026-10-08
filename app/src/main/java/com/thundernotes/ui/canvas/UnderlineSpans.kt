package com.thundernotes.ui.canvas

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.text.style.LineBackgroundSpan

/**
 * Spec §7.1 underline styles — thin / thick / dashed / wavy.
 *
 * `thin` is the baseline `Paint.UNDERLINE_TEXT_FLAG` (handled inline by the
 * textbox renderer). The other three are [LineBackgroundSpan]s applied to the
 * whole text, so they work per visual line (multiline-safe) and don't require
 * a custom TextView.
 *
 * The span draws UNDER the line's descent, at a depth proportional to the
 * text size, using the TextView's current paint color.
 */
internal abstract class BaseUnderlineSpan(
    private val thicknessFactor: Float,
    private val pathEffect: PathEffect? = null,
) : LineBackgroundSpan {

    protected val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        line: Int,
        lnum: Int,
        lineHeight: Int,
    ) {
        val textSize = paint.textSize
        val thickness = (textSize * thicknessFactor).coerceAtLeast(1.5f)
        val y = baseline + textSize * 0.18f
        strokePaint.color = paint.color
        strokePaint.strokeWidth = thickness
        strokePaint.pathEffect = pathEffect
        onDraw(canvas, left.toFloat(), right.toFloat(), y, thickness, textSize)
    }

    protected abstract fun onDraw(
        canvas: Canvas,
        left: Float, right: Float, y: Float,
        thickness: Float, textSize: Float,
    )
}

/** §7.1 underline: THICK — a solid bar ~2x the thin underline. */
internal class ThickUnderlineSpan : BaseUnderlineSpan(thicknessFactor = 0.11f) {
    override fun onDraw(
        canvas: Canvas, left: Float, right: Float, y: Float,
        thickness: Float, textSize: Float,
    ) {
        strokePaint.style = Paint.Style.FILL
        canvas.drawRect(left, y - thickness / 2f, right, y + thickness / 2f, strokePaint)
        strokePaint.style = Paint.Style.STROKE
    }
}

/** §7.1 underline: DASHED — dotted/dashed rule line. */
internal class DashedUnderlineSpan : BaseUnderlineSpan(
    thicknessFactor = 0.06f,
    pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f),
) {
    override fun onDraw(
        canvas: Canvas, left: Float, right: Float, y: Float,
        thickness: Float, textSize: Float,
    ) {
        canvas.drawLine(left, y, right, y, strokePaint)
    }
}

/** §7.1 underline: WAVY — a sine-wave rule under the line (spell-check style). */
internal class WavyUnderlineSpan : BaseUnderlineSpan(thicknessFactor = 0.05f) {
    override fun onDraw(
        canvas: Canvas, left: Float, right: Float, y: Float,
        thickness: Float, textSize: Float,
    ) {
        val amplitude = (textSize * 0.07f).coerceAtLeast(1.5f)
        val wavelength = (textSize * 0.5f).coerceAtLeast(6f)
        val path = Path()
        var x = left
        var goingUp = true
        path.moveTo(x, y)
        while (x < right) {
            val nextX = (x + wavelength).coerceAtMost(right)
            val midX = (x + nextX) / 2f
            path.quadTo(midX, y + if (goingUp) -amplitude else amplitude, nextX, y)
            x = nextX
            goingUp = !goingUp
        }
        canvas.drawPath(path, strokePaint)
    }
}
