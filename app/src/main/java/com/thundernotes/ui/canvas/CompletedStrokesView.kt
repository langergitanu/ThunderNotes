package com.thundernotes.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.ink.brush.Brush
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import com.thundernotes.canvas.ColorInverter
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.StrokeRecordConverter

/**
 * Renders the **finished** strokes on a canvas page — the completed-strokes
 * layer that sits *under* the [InProgressStrokesView] (which only renders the
 * in-progress stroke). Per the standard AndroidX Ink pattern (Notein README
 * §2: "Low-latency front-buffered rendering helpers are provided by the
 * library"): InProgressStrokesView hands off finished strokes via its
 * `addFinishedStrokesListener`; this view renders them so they persist after
 * handoff, can be re-added on redo, and can appear when **injected/pasted**
 * from the live-injection clipboard.
 *
 * Operations: [add] (a finished Stroke, keyed by our record id), [remove]
 * (undo — keeps the Stroke for redo), [redo] (re-add the last-removed),
 * [addFromRecord] (inject/paste/load — convert a StrokeRecord → Stroke → add),
 * [setColorInverted] (theme toggle — redraw with each stroke's brush color
 * passed through [ColorInverter], preserving hue per spec §6.10 Row 1 right).
 *
 * Defensive: if [ViewStrokeRenderer] / native init fails, the view is a
 * no-op surface (no crash); the document model is independent.
 */
class CompletedStrokesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Entry(val recordId: String, val stroke: Stroke)

    private val strokes = mutableListOf<Entry>()
    private val removedForRedo = ArrayDeque<Entry>()  // undo buffer (LIFO)
    private var colorInverted = false

    private val renderer: ViewStrokeRenderer? = try {
        val csr = androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create()
        ViewStrokeRenderer(csr, this)
    } catch (t: Throwable) {
        null
    }

    /** Add a finished stroke (from the InProgressStrokesView finished listener). */
    fun add(recordId: String, stroke: Stroke) {
        strokes.add(Entry(recordId, stroke))
        invalidate()
    }

    /** Inject/paste/load — convert a [StrokeRecord] → Stroke → add. */
    fun addFromRecord(record: StrokeRecord) {
        val stroke = StrokeRecordConverter.toInkStroke(record) ?: return
        add(record.id, stroke)
    }

    /** Undo — remove a stroke by record id; keep it for redo. Returns true if found. */
    fun remove(recordId: String): Boolean {
        val idx = strokes.indexOfFirst { it.recordId == recordId }
        if (idx < 0) return false
        val entry = strokes.removeAt(idx)
        removedForRedo.addLast(entry)
        invalidate()
        return true
    }

    /** Redo — re-add the most-recently-removed stroke. Returns true if one was buffered. */
    fun redo(): Boolean {
        val entry = removedForRedo.removeLastOrNull() ?: return false
        strokes.add(entry)
        invalidate()
        return true
    }

    /** Replace a finished stroke (matched by record id) with a new live Stroke —
     *  used by lasso transforms (the model's [com.thundernotes.canvas.CanvasDocument.replaceStroke]
     *  produces a new StrokeRecord; the host converts it → a new Stroke + swaps here).
     *  No redo buffering (transforms are immediate). Returns true if a stroke was swapped. */
    fun replace(recordId: String, newStroke: Stroke): Boolean {
        val idx = strokes.indexOfFirst { it.recordId == recordId }
        if (idx < 0) return false
        strokes[idx] = Entry(recordId, newStroke)
        invalidate()
        return true
    }

    /** Theme toggle — when true, each stroke is drawn with its brush color
     *  passed through [ColorInverter] (preserve hue, invert lightness). */
    fun setColorInverted(inverted: Boolean) {
        if (colorInverted == inverted) return
        colorInverted = inverted
        invalidate()
    }

    /** Clear all (page teardown). */
    fun clear() {
        strokes.clear()
        removedForRedo.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = renderer ?: return
        if (strokes.isEmpty()) return
        r.drawWithStrokes(canvas) { scope ->
            for (e in strokes) {
                val s = if (colorInverted) invertStroke(e.stroke) else e.stroke
                scope.drawStroke(s)
            }
        }
    }

    /** Build a copy of [stroke] whose brush color is lightness-inverted (spec §6.10). */
    private fun invertStroke(stroke: Stroke): Stroke {
        val b = stroke.brush
        val invertedBrush = Brush.createWithColorIntArgb(
            b.family, ColorInverter.invert(b.colorIntArgb), b.size, b.epsilon,
        )
        return stroke.copy(invertedBrush)
    }
}
