package com.thundernotes.ui.canvas

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.canvas.TextBoxRecord
import com.thundernotes.data.entity.FontFamily
import com.thundernotes.data.entity.UnderlineType

/**
 * The textbox editor popup (spec §7.1 — "Bold, Italic, Underline (thin, thick,
 * dashed, wavy), Font Size, Font Family, Fill Color"). The full formatting
 * set; the textbox schema ([TextBoxRecord]) already carries every field — this
 * sheet is the UI that lets the user edit them after a textbox is placed.
 *
 * Launched by long-pressing a rendered textbox in [CanvasActivity]. On Apply,
 * the activity removes the old TextView + re-renders with the updated record
 * (so the user sees the change live).
 *
 * The font list mirrors the 10 spec fonts (§7.1 + §6.10 Row 7c):
 * 2 sans-serif (Noto Sans, Inter) + 2 serif (STIX Two Text, Noto Serif) +
 * 6 handwriting (Patrick Hand, Short Stack, Comic Neue, Caveat, Kalam,
 * Edu AU VIC WA NT Hand) + JetBrains Mono for code textboxes.
 *
 * ── NEWCOMER PRIMER: what is a BottomSheetDialogFragment? ──────────────
 * A [BottomSheetDialogFragment] is a Dialog that slides up from the bottom
 * and DIMS the rest of the screen — used all over this app for contextual
 * menus + editors (this sheet, the snip-type selector, all library
 * 3-dot sheets). Key lifecycle facts: `onCreateView` builds its content
 * (NOT in the activity's XML); `show(fragmentManager, tag)` slides it in;
 * `dismiss()` slides it out; and it survives rotation, unlike a plain
 * AlertDialog. Communication with the host happens through a callback
 * property set before `show()` (see [onApply]) — simple + explicit.
 */
class TextboxEditorBottomSheet : BottomSheetDialogFragment() {

    /** Callback with the updated record (same id → the host swaps the view). */
    private var onApply: ((TextBoxRecord) -> Unit)? = null
    private var original: TextBoxRecord? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val orig = original ?: return TextView(ctx).also { dismiss() }
        val pad = 48
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 32, pad, pad)
            // Title
            addView(TextView(ctx).apply {
                text = getString(R.string.textbox_editor_title)
                textSize = 16f
                setPadding(0, 0, 0, 16)
            })
            // Text content (multi-line, pre-filled).
            val textInput = EditText(ctx).apply {
                hint = getString(R.string.canvas_textbox_hint)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(false)
                setLines(3)
                maxLines = 8
                setText(orig.text)
                setSelection(orig.text.length)
                setPadding(32, 16, 32, 16)
            }
            addView(textInput)
            // Bold + italic toggles (horizontal).
            val styleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 8)
            }
            val boldCb = CheckBox(ctx).apply {
                text = getString(R.string.textbox_bold)
                isChecked = orig.bold
            }
            val italicCb = CheckBox(ctx).apply {
                text = getString(R.string.textbox_italic)
                isChecked = orig.italic
            }
            styleRow.addView(boldCb)
            styleRow.addView(italicCb)
            addView(styleRow)
            // Underline type (spinner — none/thin/thick/dashed/wavy).
            val underlineSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        getString(R.string.textbox_underline_none),
                        getString(R.string.textbox_underline_thin),
                        getString(R.string.textbox_underline_thick),
                        getString(R.string.textbox_underline_dashed),
                        getString(R.string.textbox_underline_wavy),
                    )
                )
                // Coerce the original underline int into the 0..4 range.
                setSelection(orig.underline.coerceIn(0, 4))
            }
            addView(labelled(ctx, getString(R.string.textbox_underline), underlineSpinner))
            // Font family (spinner — 10 spec fonts + JetBrains Mono for code).
            val fontSpinner = Spinner(ctx).apply {
                val fontNames = (FontFamily.ALL + listOf(10)).map { idx ->
                    fontFamilyDisplayName(idx)
                }
                adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, fontNames
                )
                setSelection(orig.fontFamily.coerceIn(0, 10))
            }
            addView(labelled(ctx, getString(R.string.textbox_font_family), fontSpinner))
            // Font size (spinner — 10/12/14/16/18/20/24/28/32sp).
            val sizeSpinner = Spinner(ctx).apply {
                val sizes = listOf(10, 12, 14, 16, 18, 20, 24, 28, 32)
                adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    sizes.map { "${it}sp" }
                )
                val idx = sizes.indexOfFirst { it.toFloat() == orig.fontSizeSp }
                setSelection(if (idx >= 0) idx else 3)
                tag = sizes  // stash for read-back
            }
            addView(labelled(ctx, getString(R.string.textbox_font_size), sizeSpinner))
            // Text color (spinner — ThunderDark palette's 8 colors).
            val colorSpinner = Spinner(ctx).apply {
                val colors = EditorPalette.THUNDER_DARK
                adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    colors.mapIndexed { i, _ -> "Color ${i + 1}" }
                )
                val idx = colors.indexOfFirst { it == orig.colorArgb }
                setSelection(if (idx >= 0) idx else 0)
                tag = colors
            }
            addView(labelled(ctx, getString(R.string.textbox_text_color), colorSpinner))
            // Fill color (checkbox for transparent + spinner for the 8 colors).
            val fillTransparentCb = CheckBox(ctx).apply {
                text = getString(R.string.textbox_fill_transparent)
                isChecked = orig.fillColor == null
            }
            val fillSpinner = Spinner(ctx).apply {
                val colors = EditorPalette.THUNDER_LIGHT
                adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    colors.mapIndexed { i, _ -> "Fill ${i + 1}" }
                )
                val idx = orig.fillColor?.let { fc -> colors.indexOfFirst { it == fc } } ?: 0
                setSelection(if (idx >= 0) idx else 0)
                tag = colors
            }
            val fillRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                addView(fillTransparentCb)
                addView(fillSpinner)
            }
            addView(labelled(ctx, getString(R.string.textbox_fill_color), fillRow))
            // Apply + Cancel buttons.
            addView(Button(ctx).apply {
                text = getString(R.string.textbox_apply)
                setOnClickListener {
                    val sizes = sizeSpinner.tag as List<Int>
                    val colors = colorSpinner.tag as List<Int>
                    val fillColors = fillSpinner.tag as List<Int>
                    val updated = orig.copy(
                        text = textInput.text?.toString().orEmpty().trim(),
                        bold = boldCb.isChecked,
                        italic = italicCb.isChecked,
                        underline = underlineSpinner.selectedItemPosition,
                        fontFamily = fontSpinner.selectedItemPosition,
                        fontSizeSp = sizes[sizeSpinner.selectedItemPosition].toFloat(),
                        colorArgb = colors[colorSpinner.selectedItemPosition],
                        fillColor = if (fillTransparentCb.isChecked) null
                            else fillColors[fillSpinner.selectedItemPosition],
                    )
                    if (updated.text.isEmpty()) {
                        Toast.makeText(requireContext(),
                            R.string.textbox_empty_text, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    onApply?.invoke(updated)
                    dismiss()
                }
            })
            addView(Button(ctx).apply {
                text = getString(android.R.string.cancel)
                setOnClickListener { dismiss() }
            })
        }
    }

    /** Helper: a labelled row (label TextView above the child View). */
    private fun labelled(ctx: android.content.Context, label: String, child: View): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#666666"))
            })
            addView(child)
        }
    }

    /** Human-readable font name for the spinner. */
    private fun fontFamilyDisplayName(idx: Int): String = when (idx) {
        0 -> "Noto Sans (sans-serif)"
        1 -> "Inter (sans-serif)"
        2 -> "STIX Two Text (serif)"
        3 -> "Noto Serif (serif)"
        4 -> "Patrick Hand (handwriting)"
        5 -> "Short Stack (handwriting)"
        6 -> "Comic Neue (handwriting)"
        7 -> "Caveat (handwriting)"
        8 -> "Kalam (handwriting)"
        9 -> "Edu AU VIC WA NT Hand (handwriting)"
        10 -> "JetBrains Mono (code)"
        else -> "Default"
    }

    companion object {
        /** Launch with the original [TextBoxRecord] + the apply callback. */
        fun newInstance(
            original: TextBoxRecord,
            onApply: (TextBoxRecord) -> Unit,
        ): TextboxEditorBottomSheet = TextboxEditorBottomSheet().apply {
            this.original = original
            this.onApply = onApply
        }
    }
}
