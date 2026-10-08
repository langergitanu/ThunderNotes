package com.thundernotes.snip

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.canvas.inject.ClipboardItem
import com.thundernotes.canvas.inject.ThunderClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LaTeX Direct-Input → Stroke shortcut (spec §7.7 — "a separate button
 * that accepts LaTeX typed directly by the user, without any OCR/snipping").
 *
 * Reuses the same render → vector-stroke pipeline as the Equation Snip
 * ([LatexToStrokes]): typed LaTeX → KaTeX offscreen render → centerline
 * trace → StrokeGroup → [ThunderClipboard]. The user then pastes via the
 * glowing paste button (Row 2 top-left), exactly like an equation snip.
 *
 * **Why this exists:** the spec calls this out as "mistakenly omitted from
 * the spec — it MUST be implemented." It's a shortcut for users who already
 * have LaTeX (copied from elsewhere or typed manually) and want strokes
 * without snipping a rendered image.
 *
 * The pipeline is fully offline (KaTeX in an offscreen WebView). On any
 * failure, surface an error — never silently drop content (per spec §7.8.5).
 */
class LatexInputBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)
            // Title
            addView(TextView(ctx).apply {
                text = getString(R.string.canvas_latex_direct_title)
                textSize = 16f
                setPadding(0, 0, 0, 16)
            })
            // LaTeX input (multi-line; monospace font would be ideal but the
            // system default keeps the sheet dependency-free).
            val input = EditText(ctx).apply {
                hint = getString(R.string.canvas_latex_direct_hint)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(false)
                setLines(3)
                maxLines = 8
                setPadding(32, 16, 32, 16)
                // Pre-fill with a helpful sample so the user sees the format.
                setText("\\frac{a}{b} = \\frac{c}{d}")
                setSelection(text.length)
            }
            addView(input)
            // Render + Copy button
            addView(Button(ctx).apply {
                text = getString(R.string.canvas_latex_direct_title)
                setOnClickListener {
                    val latex = input.text?.toString().orEmpty().trim()
                    if (latex.isEmpty()) {
                        Toast.makeText(requireContext(),
                            R.string.canvas_latex_direct_empty, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    renderAndCopy(latex)
                }
            })
        }
    }

    /** Render the LaTeX → strokes → push to ThunderClipboard (background). */
    private fun renderAndCopy(latex: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val strokes = LatexToStrokes.convert(latex, requireContext())
                    if (strokes == null || strokes.isEmpty()) {
                        throw RuntimeException("KaTeX render or trace produced no strokes")
                    }
                    // Scale the traced group (KaTeX renders at a fixed 1080-px
                    // width) so the pasted equation fits the target page — same
                    // treatment the Equation Snip gets in SnipBottomSheet.
                    val (scaled, bbox) = com.thundernotes.snip.StrokeGroupScaler.fit(
                        strokes,
                        targetW = pasteW(), targetH = pasteH(),
                    )
                    ClipboardItem.StrokeGroup(strokes = scaled, bbox = bbox)
                }
            }
            result.fold(
                onSuccess = { item ->
                    ThunderClipboard.put(item)
                    Toast.makeText(requireContext(),
                        R.string.canvas_latex_direct_done, Toast.LENGTH_SHORT).show()
                    dismiss()
                },
                onFailure = { e ->
                    Toast.makeText(requireContext(),
                        getString(R.string.canvas_latex_direct_failed, e.message ?: "?"),
                        Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    /** Target page width for scaling (px) — the canvas page if known, else 700dp. */
    private fun pasteW(): Float =
        if (com.thundernotes.snip.SnipBottomSheet.pasteTargetWidthPx > 0f)
            com.thundernotes.snip.SnipBottomSheet.pasteTargetWidthPx * 0.55f
        else 700f * resources.displayMetrics.density * 0.55f

    /** Target page height for scaling (px) — the canvas page if known, else 990dp. */
    private fun pasteH(): Float =
        if (com.thundernotes.snip.SnipBottomSheet.pasteTargetHeightPx > 0f)
            com.thundernotes.snip.SnipBottomSheet.pasteTargetHeightPx * 0.4f
        else 990f * resources.displayMetrics.density * 0.4f
}
