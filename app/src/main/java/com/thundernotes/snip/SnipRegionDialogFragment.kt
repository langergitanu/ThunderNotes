package com.thundernotes.snip

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.DialogFragment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.thundernotes.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The **region-selection + confirmation step** of every snip flow (spec §7.3.4:
 * "Area captured → Confirmation → Sent for …"). Shown right after a bitmap is
 * captured — either from the in-canvas AI-snip button
 * ([com.thundernotes.ui.canvas.CanvasActivity]) or from the floating overlay
 * button ([SnipCaptureActivity] via MediaProjection).
 *
 * What the user sees:
 *  - The captured screenshot, fitted to the screen.
 *  - A drag-to-select rectangle (drag **outside** the rect to draw a new one,
 *    drag **inside** it to move it around).
 *  - Three actions: **Confirm** (crop to the rect + continue to the snip-type
 *    selector), **Whole image** (skip cropping), **Cancel** (abort the snip).
 *
 * On Confirm/Whole-image the cropped [Bitmap] is placed in
 * [SnipBottomSheet.bitmap] and [SnipBottomSheet] is shown from this dialog's
 * host activity. On Cancel the static bitmap is cleared and the host activity
 * (if it is [SnipCaptureActivity]) finishes itself via the sheet's dismiss
 * hook — see [SnipBottomSheet.onDismissed].
 *
 * Touch-to-bitmap coordinate mapping: the preview ImageView scales the bitmap
 * with FIT_CENTER (uniform scale, centered). The selection overlay sits on top
 * of the ImageView with identical bounds, so both views share the same
 * fitted-rect math (see [RegionSelectionView.selectionInBitmap]).
 */
class SnipRegionDialogFragment : DialogFragment() {

    companion object {
        /** Static holder for the bitmap to confirm (too large for a Bundle). */
        @Volatile
        var bitmap: Bitmap? = null
    }

    private lateinit var overlay: RegionSelectionView

    /** True once Continue handed control to [SnipBottomSheet] — a later
     *  dismiss (sheet-driven) must NOT re-fire the cancel path. */
    private var handedOff = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val bmp = bitmap
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (bmp == null) {
            // Defensive: launched without a bitmap → nothing to confirm.
            // Dismiss via the normal path so the cancel hook (host finish)
            // fires too.
            Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
            android.os.Handler(Looper.getMainLooper()).post { dismiss() }
            return dialog
        }
        dialog.setContentView(buildView(bmp))
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return dialog
    }

    /** Build the full-screen UI: title bar + image/overlay + action row. */
    private fun buildView(bmp: Bitmap): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE101014.toInt())
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.snip_region_title)
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(6))
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.snip_region_hint)
            setTextColor(0xFFB0B0B8.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        })

        // Preview image + selection overlay, sharing identical bounds.
        val frame = FrameLayout(ctx)
        val preview = ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        overlay = RegionSelectionView(ctx).apply { setBitmapSize(bmp.width, bmp.height) }
        frame.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        frame.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // Action row: Cancel | Whole image | Confirm.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        row.addView(Button(ctx).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener {
                SnipBottomSheet.bitmap = null   // abort: nothing to snip
                dismiss()
            }
        })
        row.addView(Button(ctx).apply {
            text = getString(R.string.snip_region_whole_image)
            setOnClickListener { continueWith(bmp) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(10); rightMargin = dp(10) })
        row.addView(Button(ctx).apply {
            text = getString(R.string.snip_region_confirm)
            setOnClickListener {
                // NOTE: qualified with `this@SnipRegionDialogFragment` because
                // inside Button.apply{} the bare name `overlay` would resolve to
                // android.view.View.getOverlay() (the Button's own property).
                val sel = this@SnipRegionDialogFragment.overlay.selectionInBitmap()
                if (sel == null) {
                    Toast.makeText(ctx, getString(R.string.snip_region_none),
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val cropped = try {
                    Bitmap.createBitmap(
                        bmp, sel[0], sel[1], sel[2] - sel[0], sel[3] - sel[1],
                    )
                } catch (e: Exception) {
                    null  // degenerate crop (0-size) → fall back to whole image
                }
                continueWith(cropped ?: bmp)
            }
        })
        root.addView(row)
        return root
    }

    /** Hand the (cropped) bitmap to the snip-type selector + close this dialog. */
    private fun continueWith(bmp: Bitmap) {
        handedOff = true
        SnipBottomSheet.bitmap = bmp
        dismiss()
        SnipBottomSheet().show(parentFragmentManager, "snip")
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!handedOff) {
            // Cancelled (button or back-press) → abort the snip: clear the
            // staged bitmap and let the capture host (SnipCaptureActivity)
            // finish itself via the sheet's dismiss hook.
            SnipBottomSheet.bitmap = null
            SnipBottomSheet.onDismissed?.invoke()
            SnipBottomSheet.onDismissed = null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─── The drag-to-select overlay ────────────────────────────────────────

    /**
     * A transparent touch surface drawn over the preview that renders — and
     * lets the user manipulate — the selection rectangle. The rectangle's
     * coordinates live in **view** space; [selectionInBitmap] converts them
     * to bitmap pixels using the same FIT_CENTER math the preview applies.
     */
    private class RegionSelectionView(context: Context) : View(context) {

        private class Fitted(val left: Float, val top: Float, val scale: Float)

        private var bmpW = 0
        private var bmpH = 0
        private var selLeft = 0f
        private var selTop = 0f
        private var selRight = -1f   // -1 → no selection yet
        private var selBottom = -1f

        private var mode = 0         // 0 = idle, 1 = drawing a new rect, 2 = moving it
        private var grabDx = 0f      // offset of the finger inside the rect (move mode)
        private var grabDy = 0f

        private val dimPaint = Paint().apply { color = 0x66000000 }
        private val borderPaint = Paint().apply {
            color = 0xFFFF5252.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val fillPaint = Paint().apply {
            color = 0x22FF5252; style = Paint.Style.FILL
        }

        fun setBitmapSize(w: Int, h: Int) { bmpW = w; bmpH = h }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (selRight < selLeft || selBottom < selTop) return
            val r = RectF(selLeft, selTop, selRight, selBottom)
            // Dim the four regions outside the selection.
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, dimPaint)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dimPaint)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, dimPaint)
            // Highlight + border the selection itself.
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, borderPaint)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val inside = selRight >= selLeft &&
                        ev.x in selLeft..selRight && ev.y in selTop..selBottom
                    if (inside) {
                        mode = 2
                        grabDx = ev.x - selLeft
                        grabDy = ev.y - selTop
                    } else {
                        mode = 1
                        selLeft = ev.x; selTop = ev.y
                        selRight = ev.x; selBottom = ev.y
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {  // drawing: drag a corner
                        selRight = ev.x.coerceIn(0f, width.toFloat())
                        selBottom = ev.y.coerceIn(0f, height.toFloat())
                    } else if (mode == 2) {  // moving: translate the whole rect
                        val w = selRight - selLeft
                        val h = selBottom - selTop
                        selLeft = (ev.x - grabDx).coerceIn(0f, (width - w).coerceAtLeast(0f))
                        selTop = (ev.y - grabDy).coerceIn(0f, (height - h).coerceAtLeast(0f))
                        selRight = selLeft + w
                        selBottom = selTop + h
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Normalize the rect + drop accidental taps (tiny rects).
                    if (selRight < selLeft) { val t = selLeft; selLeft = selRight; selRight = t }
                    if (selBottom < selTop) { val t = selTop; selTop = selBottom; selBottom = t }
                    if (selRight - selLeft < MIN_SELECTION_PX ||
                        selBottom - selTop < MIN_SELECTION_PX) {
                        selRight = -1f; selBottom = -1f
                    }
                    mode = 0
                    invalidate()
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        /**
         * Convert the selection to `[x0, y0, x1, y1]` **bitmap-pixel** coords
         * (clamped to the bitmap bounds). Null when no valid selection exists.
         */
        fun selectionInBitmap(): IntArray? {
            if (bmpW <= 0 || bmpH <= 0 || selRight < selLeft) return null
            val f = fitted()
            val x0 = ((selLeft - f.left) / f.scale).roundToInt().coerceIn(0, bmpW - 1)
            val y0 = ((selTop - f.top) / f.scale).roundToInt().coerceIn(0, bmpH - 1)
            val x1 = ((selRight - f.left) / f.scale).roundToInt().coerceIn(x0 + 1, bmpW)
            val y1 = ((selBottom - f.top) / f.scale).roundToInt().coerceIn(y0 + 1, bmpH)
            return intArrayOf(x0, y0, x1, y1)
        }

        /** FIT_CENTER math: where + at what scale the bitmap sits in this view. */
        private fun fitted(): Fitted {
            val scale = min(width.toFloat() / bmpW, height.toFloat() / bmpH)
            val drawW = bmpW * scale
            return Fitted((width - drawW) / 2f, (height - bmpH * scale) / 2f, scale)
        }

        companion object {
            /** Minimum selection size in view px — prevents accidental 1-px crops. */
            private const val MIN_SELECTION_PX = 24f
        }
    }
}
