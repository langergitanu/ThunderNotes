package com.thundernotes.snip

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.thundernotes.R
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Equation-snip LaTeX edit/re-render step (spec §7.3.4b: "OCR result
 * rendered to the user with LaTeX code → user may edit the LaTeX and
 * re-render → Confirmation again → result converted to native pen strokes").
 *
 * A suspendable bridge between the OCR pipeline (background) + the user's
 * edit/confirm (UI thread). Shows an AlertDialog with the OCR'd LaTeX
 * pre-filled in an EditText; the user can edit it; on Confirm the coroutine
 * resumes with the (possibly edited) LaTeX; on Cancel it resumes with null
 * (the caller falls back to a textbox so content is never silently dropped).
 *
 * The "Re-render" button re-runs [LatexToStrokes.convert] + shows a Toast
 * indicating success/failure, then returns the user to the dialog for further
 * edits or Confirm (so the user can iterate). This satisfies the spec's
 * "user may edit the LaTeX and re-render" loop.
 *
 * Must be called on the UI thread (AlertDialog requires it). The caller
 * (SnipBottomSheet.runSnipPipeline) switches to Main for this step.
 */
object LatexEditDialog {

    /**
     * Show the edit dialog pre-filled with [ocrLatex]. Suspends until the user
     * taps Confirm (resumes with the edited LaTeX) or Cancel (resumes null).
     */
    suspend fun edit(ocrLatex: String, context: Context): String? =
        suspendCancellableCoroutine { cont ->
            val input = EditText(context).apply {
                hint = context.getString(R.string.snip_latex_edit_hint)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(false)
                setLines(4)
                maxLines = 10
                setText(ocrLatex)
                setSelection(ocrLatex.length)
                setPadding(48, 24, 48, 24)
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
                addView(TextView(context).apply {
                    text = context.getString(R.string.snip_latex_edit_hint)
                    textSize = 12f
                    setPadding(8, 0, 8, 8)
                })
                addView(input)
            }
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.snip_latex_edit_title)
                .setView(container)
                .setPositiveButton(R.string.snip_latex_confirm) { d, _ ->
                    val edited = input.text?.toString().orEmpty().trim()
                    if (edited.isEmpty()) {
                        Toast.makeText(context, R.string.canvas_latex_direct_empty,
                            Toast.LENGTH_SHORT).show()
                        // Don't dismiss — let the user fix the empty input.
                        return@setPositiveButton
                    }
                    cont.resume(edited)
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { d, _ ->
                    cont.resume(null)
                    d.dismiss()
                }
                .setNeutralButton(R.string.snip_latex_re_render) { d, _ ->
                    // Re-render: re-run LatexToStrokes on the current input + report.
                    // The dialog stays open (don't dismiss) so the user can iterate.
                    val edited = input.text?.toString().orEmpty().trim()
                    if (edited.isEmpty()) return@setNeutralButton
                    val ctx = context
                    CoroutineScope(Dispatchers.IO).launch {
                        val ok = LatexToStrokes.convert(edited, ctx)
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(ctx,
                                if (ok != null && ok.isNotEmpty())
                                    R.string.canvas_latex_direct_done
                                else R.string.canvas_latex_direct_failed,
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Don't dismiss — return to the dialog.
                }
                .setOnCancelListener { cont.resume(null) }
                .create()
            // Resume null if the coroutine is cancelled (e.g. the user left
            // the activity) so the dialog doesn't leak.
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
}
