package com.thundernotes.ui.canvas

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import com.thundernotes.R
import com.thundernotes.canvas.EditorBrushConfig
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.ToolType
import java.util.UUID

/**
 * The real ink canvas surface — a [FrameLayout] that hosts AndroidX Ink's
 * [InProgressStrokesView] and routes touch input into real pen strokes.
 *
 * Built on the same public Jetpack `androidx.ink` library Notein uses
 * (Notein README §0/§2 — "Notein is built on Google's official androidx.ink;
 * it is NOT a hand-rolled ink engine"), so we inherit the same low-latency,
 * artifact-free stylus behaviour without porting Notein's rendering.
 *
 * **Drawing path** (per the real 1.1.0-alpha07 API):
 *  - ACTION_DOWN → [InProgressStrokesView.startStroke] with the current [Brush]
 *  - ACTION_MOVE → [addToStroke]
 *  - ACTION_UP   → [finishStroke]
 *  - the view's finished-strokes listener hands back the finished
 *    `androidx.ink.strokes.Stroke`; we capture a [StrokeRecord] (brush fields
 *    from the active config + x/y tracked from the MotionEvents + per-point
 *    attrs from MotionEvent pressure/time) and hand it to [onStrokeFinished].
 *
 * **Tool mapping** ([EditorBrushConfig] → androidx.ink):
 *  - PEN/HIGHLIGHTER → `StockBrushes.pressurePen()` / `.highlighter()` family
 *    + a colored `Brush.createWithColorIntArgb(family, color, size, epsilon)`.
 *  - ERASER → no brush; a tap on a finished stroke removes it
 *    (real stroke-collision eraser is a later refinement).
 *  - LASSO/SHAPE/TEXT → not wired to Ink yet (Phase 8b); the host ignores
 *    their touches + the tray already toasts "later phase".
 *
 * **Defensive**: Ink native-loader / GL init can fail on a non-target device.
 * If [InProgressStrokesView] construction throws, the host falls back to a
 * placeholder label so the editor never crashes — the pure tested
 * [com.thundernotes.canvas.CanvasDocument] layer stays intact.
 */
class CanvasInkHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Called when a stroke is finished + captured. The activity adds it to the document. */
    var onStrokeFinished: ((StrokeRecord) -> Unit)? = null

    /** Called when an eraser tap removes a stroke (by the host's stroke id). */
    var onStrokeRemoved: ((String) -> Unit)? = null

    /**
     * Eraser hit-test callback (Phase 9h — spec §6.10.6d). Given a tap point
     * (page-local coords), returns the record id of the finished stroke whose
     * bbox contains the point (within the eraser radius), or null. The host
     * then removes that stroke from the Ink view via [removeStrokeFromView].
     * Set by the activity (which has access to the document's stroke list).
     */
    var onErasePoint: ((x: Float, y: Float) -> String?)? = null

    /**
     * Eraser area callback (Phase 9h — spec §6.10.6d AREA type). Given a drag
     * rectangle (page-local), returns the record ids of all finished strokes
     * whose bbox intersects the rect. The host removes each from the Ink view.
     */
    var onEraseRect: ((l: Float, t: Float, r: Float, b: Float) -> List<String>)? = null

    /** Eraser mode (AREA drag-rect vs SHAPE tap) — set by the activity from the VM. */
    var eraserType: com.thundernotes.ui.canvas.EraserType =
        com.thundernotes.ui.canvas.EraserType.entries[com.thundernotes.ui.canvas.EraserType.DEFAULT_ORDINAL]

    /** Eraser hit radius in px (derived from the stroke-width slider × 4). */
    var eraserRadiusPx: Float = 32f

    private var inkView: InProgressStrokesView? = null
    private var inkAvailable: Boolean = false

    // Current brush config — updated by the activity when the tool/color/width changes.
    private var currentConfig: EditorBrushConfig? = null

    // In-progress stroke state.
    private var inProgressId: androidx.ink.authoring.InProgressStrokeId? = null
    private val trackedXy = mutableListOf<Float>()
    private val trackedAttrs = mutableListOf<Float>()
    private var downEventTime = 0L

    // Map: Ink in-progress-id → our StrokeRecord id, so the finished listener
    // can stamp the captured record with the id Ink reports (kept stable).
    private val inkIdToRecordId = mutableMapOf<androidx.ink.authoring.InProgressStrokeId, String>()

    // Map: our record id → Ink finished-stroke id (for undo → removeFinishedStrokes).
    private val recordIdToFinishedInkId = mutableMapOf<String, androidx.ink.authoring.InProgressStrokeId>()

    // Identity matrices — strokes are page-local (thunder-format-proposal Part C).
    private val identityMatrix = Matrix().apply { reset() }

    init {
        // Defensive: Ink native-loader / GL surface init can throw on a non-target
        // device or under memory pressure. Fall back to a placeholder so the editor
        // never crashes; the pure CanvasDocument layer is independent.
        try {
            val view = InProgressStrokesView(context)
            view.eagerInit()
            view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(
                    finished: Map<androidx.ink.authoring.InProgressStrokeId, androidx.ink.strokes.Stroke>,
                ) {
                    handleFinished(finished)
                }
            })
            view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(view)
            inkView = view
            inkAvailable = true
        } catch (t: Throwable) {
            inkAvailable = false
            // Placeholder label so the surface isn't blank when Ink can't init.
            val label = android.widget.TextView(context).apply {
                text = context.getString(R.string.canvas_ink_unavailable)
                setTextColor(0xFF8A8A8A.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(label)
        }
    }

    /** Update the active brush from the editor's tool/color/width selection. */
    fun setBrush(config: EditorBrushConfig) {
        currentConfig = config
    }

    /** The active editor tool (drives the TEXT-tool tap path — drawing tools route
     *  to start/add/finish; TEXT routes to [onTextTap]). Set alongside [setBrush]. */
    var currentTool: com.thundernotes.ui.canvas.EditorTool? = null

    /** The active pen line type (spec §6.10 Row 3a). DOTTED/DASHED switch the
     *  Ink brush family to [StockBrushes.dashedLine]; STRAIGHT keeps pressurePen. */
    var currentLineType: com.thundernotes.ui.canvas.LineType =
        com.thundernotes.ui.canvas.LineType.STRAIGHT

    /** Palm rejection (spec §6.1.9, on by default): when true, reject FINGER
     *  touches — only STYLUS (TOOL_TYPE_STYLUS) + ERASER (TOOL_TYPE_ERASER)
     *  reach the Ink view. Mirrors Notein's `getToolType(0) == STYLUS` filter. */
    var palmRejection: Boolean = true

    /** Called when the TEXT tool taps the canvas at (x, y) — the activity opens
     *  a text-input dialog + drops a TextBoxRecord at the tap. */
    var onTextTap: ((Float, Float) -> Unit)? = null

    /** Called when the FILLER tool taps (§6.10.6f) — the activity drops a
     *  translucent filled rect at (x, y) using the current color + stroke-width
     *  (which doubles as the fill size). */
    var onFillTap: ((Float, Float) -> Unit)? = null

    /** LASSO-tool drag (MOVE): (left, top, right, bottom) of the drag rect —
     *  the activity updates the lasso overlay. */
    var onLassoDrag: ((Float, Float, Float, Float) -> Unit)? = null

    /** LASSO-tool drag finalized (UP): the activity selects strokes + shows the
     *  9-function context menu. */
    var onLassoEnd: ((Float, Float, Float, Float) -> Unit)? = null

    /** SHAPE-tool drag (UP): the activity builds the shape (rect/circle/line)
     *  via [com.thundernotes.canvas.lasso.ShapeGeometry] inscribed in the box. */
    var onShapeEnd: ((Float, Float, Float, Float) -> Unit)? = null

    /** SHAPE-tool drag (MOVE): the activity previews the shape's bounding box
     *  on the lasso overlay. Shares [onLassoDrag] semantics. */
    var onShapeDrag: ((Float, Float, Float, Float) -> Unit)? = null

    private var lassoStart: Pair<Float, Float>? = null

    private fun currentBrush(): Brush? {
        val cfg = currentConfig ?: return null
        // Line type (spec §6.10 Row 3a): DOTTED/DASHED switch to the dashed family;
        // STRAIGHT keeps the tool's normal family. (Highlighter keeps its family —
        // a dashed highlighter is uncommon; the line-type applies to pens.)
        val family = when {
            currentLineType != com.thundernotes.ui.canvas.LineType.STRAIGHT
                && cfg.familyId != BrushFamily.THUNDER_HIGHLIGHTER_V1 ->
                StockBrushes.dashedLine()
            cfg.familyId == BrushFamily.THUNDER_HIGHLIGHTER_V1 ->
                StockBrushes.highlighter(SelfOverlap.ANY, StockBrushes.HighlighterVersion.V1)
            cfg.familyId == BrushFamily.THUNDER_FOUNTAIN_V1 ||
                cfg.familyId == BrushFamily.THUNDER_PENCIL_V1 ->
                StockBrushes.marker() // refined families are a brush-asset concern
            else -> StockBrushes.pressurePen() // THUNDER_BALLPOINT_V1 + default
        }
        return Brush.createWithColorIntArgb(family, cfg.colorArgb, cfg.sizeDp, cfg.epsilon)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = inkAvailable

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!inkAvailable) return false
        // Palm rejection (§6.1.9): reject FINGER touches; only STYLUS/ERASER draw.
        // Mirrors Notein's getToolType(0) == TOOL_TYPE_STYLUS filter (PDFView.java:3861).
        if (palmRejection && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            return false
        }
        val view = inkView ?: return false
        val cfg = currentConfig ?: return false

        // TEXT tool: don't draw — route the tap to the activity's text-input dialog.
        if (currentTool == com.thundernotes.ui.canvas.EditorTool.TEXT) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                onTextTap?.invoke(ev.getX(0), ev.getY(0))
            }
            return true  // consume the touch so it doesn't reach the Ink view
        }

        // FILLER tool (§6.10.6f): tap → the activity drops a filled shape
        // (a translucent rectangle stamp) at the tap point. True flood-fill on
        // a vector canvas is complex (rasterize → fill → re-vectorize); this
        // stamp approximation is functional + visible — the user picks size
        // (stroke-width slider) + color + opacity (a fixed 50% for the
        // highlighter-style fill; a future editor can expose the opacity slider).
        if (currentTool == com.thundernotes.ui.canvas.EditorTool.FILLER) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                onFillTap?.invoke(ev.getX(0), ev.getY(0))
            }
            return true
        }

        // LASSO tool: drag a rectangle (DOWN→start, MOVE→update overlay, UP→finalize+select).
        if (currentTool == com.thundernotes.ui.canvas.EditorTool.LASSO) {
            val x = ev.getX(0); val y = ev.getY(0)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lassoStart = x to y
                MotionEvent.ACTION_MOVE -> {
                    val s = lassoStart
                    if (s != null) onLassoDrag?.invoke(s.first, s.second, x, y)
                }
                MotionEvent.ACTION_UP -> {
                    val s = lassoStart; lassoStart = null
                    if (s != null) onLassoEnd?.invoke(s.first, s.second, x, y)
                }
            }
            return true
        }

        // SHAPE tool (spec §6.10 Row 3g): drag a box → on UP build the shape
        // (rect/circle/line) inscribed in it. MOVE previews the box on the lasso overlay.
        if (currentTool == com.thundernotes.ui.canvas.EditorTool.SHAPE) {
            val x = ev.getX(0); val y = ev.getY(0)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> lassoStart = x to y
                MotionEvent.ACTION_MOVE -> {
                    val s = lassoStart
                    if (s != null) onShapeDrag?.invoke(s.first, s.second, x, y)
                }
                MotionEvent.ACTION_UP -> {
                    val s = lassoStart; lassoStart = null
                    if (s != null) onShapeEnd?.invoke(s.first, s.second, x, y)
                }
            }
            return true
        }

        // Eraser: a tap (DOWN+UP with no significant MOVE) on a finished stroke removes it.
        if (cfg.isEraser) {
            return handleEraser(ev)
        }

        val pointerIndex = 0
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackedXy.clear(); trackedAttrs.clear()
                downEventTime = ev.eventTime
                capturePoint(ev)
                val brush = currentBrush() ?: return false
                val recordId = UUID.randomUUID().toString()
                inProgressId = try {
                    view.startStroke(ev, pointerIndex, brush, identityMatrix, identityMatrix)
                        .also { inkIdToRecordId[it] = recordId }
                } catch (t: Throwable) { null }
            }
            MotionEvent.ACTION_MOVE -> {
                capturePoint(ev)
                val id = inProgressId ?: return true
                try { view.addToStroke(ev, pointerIndex, id, ev) } catch (_: Throwable) {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                capturePoint(ev)
                val id = inProgressId ?: return true
                try {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) view.finishStroke(ev, pointerIndex, id)
                    else view.cancelStroke(id, ev)
                } catch (_: Throwable) {}
                inProgressId = null
            }
        }
        return true
    }

    private fun capturePoint(ev: MotionEvent) {
        // Capture ALL coalesced/historical points, not just the latest event.
        // AndroidX Ink's addToStroke processes them for the LIVE rendering, but
        // our StrokeRecord must mirror the same points or the persisted/reloaded
        // stroke loses its intermediate samples (visible as jagged strokes after
        // an undo→redo or app restart, and doubled sample spacing on 120/144Hz
        // displays like the OnePlus Pad 2 where many points coalesce per frame).
        val historyCount = ev.historySize
        for (h in 0 until historyCount) {
            trackedXy.add(ev.getHistoricalX(0, h))
            trackedXy.add(ev.getHistoricalY(0, h))
            trackedAttrs.add((ev.getHistoricalEventTime(h) - downEventTime).toFloat())
            trackedAttrs.add(pressureOrNone(ev.getHistoricalPressure(0, h)))
            trackedAttrs.add(0f)  // tilt — MotionEvent historical tilt extraction is complex; refine later
            trackedAttrs.add(0f)  // orientation
            trackedAttrs.add(0f)  // reserved (ATTR_STRIDE=5)
        }
        trackedXy.add(ev.getX(0))
        trackedXy.add(ev.getY(0))
        // 5 attrs per point: elapsedMs, pressure, tilt, orientation, 0
        trackedAttrs.add((ev.eventTime - downEventTime).toFloat())
        trackedAttrs.add(pressureOrNone(ev.getPressure(0)))
        trackedAttrs.add(0f)  // tilt
        trackedAttrs.add(0f)  // orientation
        trackedAttrs.add(0f)  // reserved (ATTR_STRIDE=5)
    }

    private fun pressureOrNone(p: Float): Float =
        if (p > 0f) p else 1f

    private fun handleFinished(
        finished: Map<androidx.ink.authoring.InProgressStrokeId, androidx.ink.strokes.Stroke>,
    ) {
        val view = inkView ?: return
        for ((inkId, stroke) in finished) {
            // Re-extract the brush from the finished stroke so the record matches
            // what Ink actually used (family/size/epsilon/color). The androidx.ink
            // Brush exposes .size / .epsilon / .colorIntArgb as Kotlin properties.
            val brush = stroke.brush
            val cfg = currentConfig ?: continue
            val recordId = inkIdToRecordId.remove(inkId) ?: UUID.randomUUID().toString()
            recordIdToFinishedInkId[recordId] = inkId
            val record = StrokeRecord(
                id = recordId,
                layerId = "layer-0",
                pageId = "page-0",
                creationTime = System.currentTimeMillis(),
                brushSize = brush.size,
                brushColorArgb = brush.colorIntArgb,
                brushEpsilon = brush.epsilon,
                brushFamilyId = cfg.familyId,
                toolType = cfg.toolType,
                inputXy = trackedXy.toList(),
                inputAttrs = trackedAttrs.toList(),
            )
            if (record.pointCount >= 1) {
                onStrokeFinished?.invoke(record)
            }
        }
        // Tracked buffers are reset on the next ACTION_DOWN; nothing to do here.
    }

    private fun handleEraser(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        // SHAPE eraser: tap to remove the stroke under the tap (point-in-bbox
        // hit-test with the eraser radius). Spec §6.10.6d "shape eraser".
        if (eraserType == com.thundernotes.ui.canvas.EraserType.SHAPE) {
            if (action != MotionEvent.ACTION_UP) return true
            val x = ev.x; val y = ev.y
            val id = onErasePoint?.invoke(x, y) ?: return true
            removeStrokeFromView(id)
            onStrokeRemoved?.invoke(id)
            return true
        }
        // AREA eraser: drag → on UP, remove every stroke whose bbox intersects
        // the drag rect. Spec §6.10.6d "area eraser".
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                eraserDragStart = ev.x to ev.y
                eraserDragCur = ev.x to ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                eraserDragCur = ev.x to ev.y
                invalidate()  // redraw the drag rect overlay
            }
            MotionEvent.ACTION_UP -> {
                val (sx, sy) = eraserDragStart ?: (ev.x to ev.y)
                val (ex, ey) = ev.x to ev.y
                val l = minOf(sx, ex); val t = minOf(sy, ey)
                val r = maxOf(sx, ex); val b = maxOf(sy, ey)
                val ids = onEraseRect?.invoke(l, t, r, b) ?: emptyList()
                ids.forEach { id ->
                    removeStrokeFromView(id)
                    onStrokeRemoved?.invoke(id)
                }
                eraserDragStart = null
                eraserDragCur = null
                invalidate()
            }
        }
        return true
    }

    /** Drag-rect state for the AREA eraser. */
    private var eraserDragStart: Pair<Float, Float>? = null
    private var eraserDragCur: Pair<Float, Float>? = null

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        // Draw the AREA-eraser drag rect overlay (spec §6.10.6d).
        val start = eraserDragStart
        val cur = eraserDragCur
        if (cur != null && start != null) {
            val l = minOf(start.first, cur.first)
            val t = minOf(start.second, cur.second)
            val r = maxOf(start.first, cur.first)
            val b = maxOf(start.second, cur.second)
            val paint = android.graphics.Paint().apply {
                color = 0x66FF5252  // semi-transparent red
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRect(l, t, r, b, paint)
        }
    }

    /** Remove a finished stroke from the Ink view (called when the document undoes an AddStroke). */
    fun removeStrokeFromView(recordId: String) {
        val inkId = recordIdToFinishedInkId.remove(recordId) ?: return
        try { inkView?.removeFinishedStrokes(setOf(inkId)) } catch (_: Throwable) {}
    }
}
