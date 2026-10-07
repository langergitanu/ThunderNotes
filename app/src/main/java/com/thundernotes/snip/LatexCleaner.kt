package com.thundernotes.snip

/**
 * Cleans + joins LaTeX strings from per-line OCR recognition, mirroring
 * `run_formula.py::clean_latex` + the multi-line join logic:
 *
 * 1. Strip `$$`, `\[`, `\]` wrappers the recognizer may add per line.
 * 2. Unwrap `\begin{aligned}…\end{aligned}` if the model already wrapped a single line.
 * 3. Collapse the common leading alignment ampersand `&`.
 * 4. Join per-line LaTeX with `\\` + wrap multi-line output in `\begin{aligned}…\end{aligned}`.
 *
 * Pure + unit-testable (no Android/Ink dependency).
 */
object LatexCleaner {

    /** Clean a single line of LaTeX (strip wrappers, collapse &). */
    fun cleanLine(s: String): String {
        var r = s.trim()
        for (w in listOf("$$", "\\[", "\\]")) r = r.replace(w, "")
        r = r.trim()
        // Unwrap aligned if the model wrapped a single line.
        if (r.startsWith("\\begin{aligned}")) r = r.removePrefix("\\begin{aligned}").trim()
        if (r.endsWith("\\end{aligned}")) r = r.removeSuffix("\\end{aligned}").trim()
        // Collapse leading alignment &.
        return r.lineSequence().map { it.trim().removePrefix("&").trim() }
            .filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Clean + join per-line LaTeX results into a single multi-line LaTeX block.
     * @param perLine the cleaned LaTeX from each text-row band (after [cleanLine]).
     * @param joinMode "aligned" (default), "newline", or "array".
     */
    fun joinMultiLine(perLine: List<String>, joinMode: String = "aligned"): String {
        val cleaned = perLine.map { cleanLine(it) }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.size == 1) return cleaned[0]

        val joined = cleaned.joinToString(" \\\\\n")
        return when (joinMode) {
            "aligned" -> "\\begin{aligned}\n$joined\n\\end{aligned}"
            "array" -> "\\begin{array}{l}\n$joined\n\\end{array}"
            else -> joined.replace("\n", " \\\\ ")  // newline → plain \\
        }
    }
}
