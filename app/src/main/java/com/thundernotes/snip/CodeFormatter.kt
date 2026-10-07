package com.thundernotes.snip

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

/**
 * The code formatter (spec §7.3.4c: "OCR → code formatter engine → formatted
 * code with proper tabs & colors"). Bridges the pure [CodeHighlighter]
 * tokenizer + [CodeTheme] colour map → an Android [SpannableStringBuilder]
 * with per-token [ForegroundColorSpan]s that a textbox renderer can display.
 *
 * Two layers (mirrors the CanvasDocument / CompletedStrokesView split):
 *  1. **Pure** — [colorSpans] returns a list of [ColorSpan](start, end, color)
 *     with no Android dependency. Unit-testable on the JVM (see
 *     `CodeFormatterTest`).
 *  2. **Android adapter** — [toSpannable] builds the actual
 *     [SpannableStringBuilder] from [colorSpans]. This is the thin host-side
 *     glue (like [com.thundernotes.canvas.StrokeRecordConverter]).
 *
 * **Font policy (spec §6.10 + user request):** the *base* font of the code
 * textbox is **Patrick Hand** (the handwriting default for snipped content —
 * same as text + equation snips). The coloured spans are font-agnostic
 * ([ForegroundColorSpan] applies on top of whatever Typeface the textbox
 * renders with), so Patrick Hand works as the default. The user can switch
 * the textbox font to JetBrains Mono (monospace) afterward if they want
 * column-aligned code — the colours survive the font change because they're
 * separate spans, not baked into the text.
 *
 * **Tab handling**: tabs are preserved verbatim (the formatter does NOT
 * convert tabs to spaces — mirrors [CodeHighlighter] + Notein's
 * "preserve raw indentation" behaviour). The textbox renderer is responsible
 * for tab rendering width.
 */
object CodeFormatter {

    /**
     * A coloured run: tokens [start, end) in the source code get [colorArgb].
     * Pure data — no Android dependency.
     */
    data class ColorSpan(val start: Int, val end: Int, val colorArgb: Int)

    /**
     * Pure: compute the list of coloured spans for [code] in [language] under
     * [theme]. The spans are contiguous + cover the whole string (every char
     * is in exactly one span) — so a renderer can apply them left-to-right
     * without gaps. Adjacent tokens of the same colour are merged (fewer spans
     * → smaller parcel + faster layout).
     *
     * Returns an empty list for empty code.
     */
    fun colorSpans(code: String, language: CodeLanguage, theme: CodeTheme): List<ColorSpan> {
        if (code.isEmpty()) return emptyList()
        val tokens = CodeHighlighter.tokenize(code, language)
        if (tokens.isEmpty()) return listOf(ColorSpan(0, code.length, theme.plain))

        val out = mutableListOf<ColorSpan>()
        var start = 0
        for (token in tokens) {
            val end = start + token.text.length
            if (end > code.length) break  // safety: tokenizer shouldn't overrun
            val color = colorFor(token.type, theme)
            // Merge with the previous span if same colour (reduces span count).
            val prev = out.lastOrNull()
            if (prev != null && prev.colorArgb == color && prev.end == start) {
                out[out.lastIndex] = ColorSpan(prev.start, end, color)
            } else {
                out.add(ColorSpan(start, end, color))
            }
            start = end
        }
        // If the tokenizer left a trailing gap (shouldn't, but be defensive),
        // close it with a plain span.
        if (start < code.length) {
            val prev = out.lastOrNull()
            if (prev != null && prev.colorArgb == theme.plain && prev.end == start) {
                out[out.lastIndex] = ColorSpan(prev.start, code.length, theme.plain)
            } else {
                out.add(ColorSpan(start, code.length, theme.plain))
            }
        }
        return out
    }

    /** Map a [CodeHighlighter.TokenType] → its colour in [theme]. */
    private fun colorFor(type: CodeHighlighter.TokenType, theme: CodeTheme): Int = when (type) {
        CodeHighlighter.TokenType.KEYWORD   -> theme.keyword
        CodeHighlighter.TokenType.STRING   -> theme.string
        CodeHighlighter.TokenType.COMMENT   -> theme.comment
        CodeHighlighter.TokenType.NUMBER   -> theme.number
        CodeHighlighter.TokenType.FUNCTION -> theme.function
        CodeHighlighter.TokenType.OPERATOR -> theme.operator
        CodeHighlighter.TokenType.PLAIN    -> theme.plain
    }

    /**
     * Android adapter: build a [SpannableStringBuilder] for [code] in
     * [language] under [theme], with [ForegroundColorSpan]s applied per
     * [colorSpans]. The base Typeface is NOT set here (the textbox renderer
     * sets Patrick Hand / JetBrains Mono per the textbox's fontFamily field —
     * colours are independent of font). Comments are italicised (mirrors VS
     * Code / One Dark Pro / Monokai convention: `font_style: italic` on
     * comments in the official theme JSONs).
     */
    fun toSpannable(code: String, language: CodeLanguage, theme: CodeTheme): SpannableStringBuilder {
        val sb = SpannableStringBuilder(code)
        if (code.isEmpty()) return sb
        val tokens = CodeHighlighter.tokenize(code, language)
        var start = 0
        for (token in tokens) {
            val end = start + token.text.length
            if (end > code.length) break
            val color = colorFor(token.type, theme)
            sb.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            // Italicise comments (official theme convention).
            if (token.type == CodeHighlighter.TokenType.COMMENT) {
                sb.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start = end
        }
        return sb
    }
}
