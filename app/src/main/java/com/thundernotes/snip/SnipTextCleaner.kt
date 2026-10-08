package com.thundernotes.snip

/**
 * Cleans the raw text that vision-LLM snip engines return before it becomes
 * a [SnipResult]. Pure + unit-testable.
 *
 * Vision models regularly wrap their answer in markdown fences
 * (` ```python … ``` `) or dollar-sign math delimiters (`$…$` / `$$…$$`)
 * despite prompts telling them not to — this object strips those wrappers
 * so the recognized content is raw (spec §7.3.4c: the OCR result should be
 * "ONLY the raw code" / plain text).
 */
object SnipTextCleaner {

    /**
     * Remove markdown code fences around a whole answer:
     * a leading ` ```lang ` (or `~~~lang`) line + a trailing ` ``` ` line.
     * Only strips when the answer *starts* with a fence — fences in the
     * middle of real code are left alone.
     */
    fun stripCodeFences(code: String): String {
        var r = code.trim()
        val opening = Regex("^```[\\w+#.-]*[ \\t]*\\r?\\n?|^~~~[\\w+#.-]*[ \\t]*\\r?\\n?")
        if (opening.containsMatchIn(r)) {
            r = r.replaceFirst(opening, "")
            r = r.replaceFirst(Regex("```[ \\t]*\\r?\\n?[ \\t]*$|~~~[ \\t]*\\r?\\n?[ \\t]*$"), "")
        }
        return r.trim()
    }

    /**
     * Strip a single pair of wrapping display-math delimiters (`$$…$$`) or a
     * *single* inline `$…$` pair (only when BOTH the first and last non-space
     * chars are `$` — interior dollars used by real LaTeX are untouched).
     */
    fun stripMathDollars(latex: String): String {
        var r = latex.trim()
        if (r.length >= 4 && r.startsWith("$$") && r.endsWith("$$")) {
            r = r.removePrefix("$$").removeSuffix("$$").trim()
        } else if (r.length >= 2 && r.startsWith("$") && r.endsWith("$") &&
            !r.startsWith("$$") && !r.endsWith("$$")) {
            r = r.removePrefix("$").removeSuffix("$").trim()
        }
        return r
    }
}
