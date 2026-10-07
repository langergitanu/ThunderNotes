package com.thundernotes.snip

import com.thundernotes.canvas.StrokeRecord

/** The four snip types (spec §7.3.4). */
enum class SnipType {
    TEXT,
    EQUATION,
    CODE,
    DIAGRAM,
}

/**
 * The result of a snip recognition pass. Per spec §7.3.4:
 *  - Text/Code snips → [Text] / [Code] → pasted as textboxes.
 *  - Equation snips → [LaTeX] → rendered via KaTeX → centerline-traced → pasted as strokes.
 *  - Diagram snips → [Strokes] (traced directly from the image, no OCR).
 *
 * The FallbackSnipEngine returns the first non-failure result; the pipeline
 * (LatexToStrokes for equations, DiagramTracer for diagrams, code formatter
 * for code) consumes the [SnipResult] + builds a [com.thundernotes.canvas.inject.ClipboardItem].
 */
sealed class SnipResult {
    /** OCR'd plain text (Text snip). */
    data class Text(val text: String) : SnipResult()
    /** Recognised LaTeX (Equation snip — may be multi-line `\begin{aligned}`). */
    data class LaTeX(val latex: String) : SnipResult()
    /** OCR'd raw code (Code snip — syntax highlighting applied downstream). */
    data class Code(val rawCode: String, val language: String? = null) : SnipResult()
    /** Traced strokes (Diagram snip — the centerline tracer's output). */
    data class Strokes(val strokes: List<StrokeRecord>) : SnipResult()
}
