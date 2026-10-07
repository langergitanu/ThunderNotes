package com.thundernotes.snip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CodeFormatter.colorSpans] — the pure layer that
 * computes coloured runs (start, end, colorArgb) from the [CodeHighlighter]
 * tokenizer + [CodeTheme]. The Android [CodeFormatter.toSpannable] adapter
 * is a thin host-side glue (builds a SpannableStringBuilder from these spans)
 * — its logic is fully exercised by the pure tests below.
 */
class CodeFormatterTest {

    // ─── Coverage + boundaries ───────────────────────────────────────────

    @Test fun `spans cover the whole string with no gaps`() {
        val code = "const x = 42"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.ONE_DARK_PRO)
        // First span starts at 0.
        assertEquals(0, spans.first().start)
        // Last span ends at code length.
        assertEquals(code.length, spans.last().end)
        // No gaps: each span's start == previous span's end.
        for (i in 1 until spans.size) {
            assertEquals(spans[i - 1].end, spans[i].start)
        }
    }

    @Test fun `empty code returns empty span list`() {
        assertTrue(CodeFormatter.colorSpans("", CodeLanguage.JAVA, CodeTheme.GITHUB_DARK).isEmpty())
    }

    @Test fun `whitespace-only code returns one plain span covering everything`() {
        val code = "   \n\t  "
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.CPP, CodeTheme.MONOKAI)
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(code.length, spans[0].end)
        assertEquals(CodeTheme.MONOKAI.plain, spans[0].colorArgb)
    }

    // ─── Colours per token type (official theme colours) ─────────────────

    @Test fun `keyword span uses One Dark Pro keyword colour`() {
        val code = "const"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.ONE_DARK_PRO)
        assertEquals(1, spans.size)
        assertEquals(CodeTheme.ONE_DARK_PRO.keyword, spans[0].colorArgb)
    }

    @Test fun `string span uses Dark Modern string colour`() {
        val code = "\"hello\""
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.PYTHON, CodeTheme.DARK_MODERN)
        assertEquals(1, spans.size)
        assertEquals(CodeTheme.DARK_MODERN.string, spans[0].colorArgb)
    }

    @Test fun `number span uses GitHub Dark number colour`() {
        val code = "42"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.JAVA, CodeTheme.GITHUB_DARK)
        assertEquals(1, spans.size)
        assertEquals(CodeTheme.GITHUB_DARK.number, spans[0].colorArgb)
    }

    @Test fun `comment span uses Monokai comment colour`() {
        val code = "// hi"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.CPP, CodeTheme.MONOKAI)
        assertTrue(spans.isNotEmpty())
        assertEquals(CodeTheme.MONOKAI.comment, spans.last().colorArgb)
    }

    @Test fun `function call span uses the function colour`() {
        val code = "foo()"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.JAVA, CodeTheme.GITHUB_DARK)
        // "foo" → FUNCTION, "()" → plain/operator.
        val fnSpan = spans.firstOrNull { it.colorArgb == CodeTheme.GITHUB_DARK.function }
        assertTrue("Expected a function-coloured span for foo()", fnSpan != null)
        assertEquals(0, fnSpan!!.start)
        assertEquals(3, fnSpan.end)
    }

    // ─── Span merging (same-colour adjacent tokens collapse) ────────────

    @Test fun `adjacent plain tokens merge into one span`() {
        // "x" + " " + "+" + " " → all PLAIN/operator except "42" → NUMBER.
        // Actually "x" is PLAIN, " " PLAIN, "+" OPERATOR, " " PLAIN.
        // One Dark Pro: plain == operator? No (plain=ABB2BF, operator=56B6C2).
        // So plain+plain merge; operator stays separate.
        val code = "x  y"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.ONE_DARK_PRO)
        // "x", "  ", "y" are all PLAIN → should merge into one span.
        val plainSpans = spans.filter { it.colorArgb == CodeTheme.ONE_DARK_PRO.plain }
        assertEquals(1, plainSpans.size)
        assertEquals(0, plainSpans[0].start)
        assertEquals(code.length, plainSpans[0].end)
    }

    @Test fun `different-colour tokens stay separate`() {
        val code = "const \"s\""
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.ONE_DARK_PRO)
        // const → KEYWORD, " " → PLAIN, "s" in string → STRING.
        val colours = spans.map { it.colorArgb }.toSet()
        assertTrue(colours.contains(CodeTheme.ONE_DARK_PRO.keyword))
        assertTrue(colours.contains(CodeTheme.ONE_DARK_PRO.string))
    }

    // ─── Multi-language smoke tests ──────────────────────────────────────

    @Test fun `Python def returns function-coloured span`() {
        val code = "def foo():"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.PYTHON, CodeTheme.DARK_MODERN)
        // "def" → KEYWORD, "foo" → FUNCTION (followed by "("), "()" → plain/op, ":" → plain.
        assertTrue(spans.any { it.colorArgb == CodeTheme.DARK_MODERN.keyword && it.end <= 3 })
        assertTrue(spans.any { it.colorArgb == CodeTheme.DARK_MODERN.function })
    }

    @Test fun `Java class keyword span`() {
        val code = "public class Foo {}"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.JAVA, CodeTheme.GITHUB_DARK)
        assertTrue(spans.any { it.colorArgb == CodeTheme.GITHUB_DARK.keyword })
        // "public" + "class" both keywords, but separated by a space (plain) →
        // they end up as separate keyword spans (with a plain span between).
        val kwSpans = spans.filter { it.colorArgb == CodeTheme.GITHUB_DARK.keyword }
        assertTrue(kwSpans.size >= 2)
    }

    @Test fun `C++ include line comment`() {
        val code = "#include <iostream>"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.CPP, CodeTheme.MONOKAI)
        // In C++, "#" is not a line-comment marker (that's Python). So this
        // is treated as operators/identifiers — the test just checks it
        // doesn't crash + covers the whole string.
        assertEquals(code.length, spans.last().end)
    }

    @Test fun `C++ block comment is one comment span`() {
        val code = "/* hi */"
        val spans = CodeFormatter.colorSpans(code, CodeLanguage.CPP, CodeTheme.MONOKAI)
        assertEquals(1, spans.size)
        assertEquals(CodeTheme.MONOKAI.comment, spans[0].colorArgb)
    }

    // ─── Theme independence ──────────────────────────────────────────────

    @Test fun `same code under different themes yields different colours`() {
        val code = "const"
        val oneDark = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.ONE_DARK_PRO)
        val monokai = CodeFormatter.colorSpans(code, CodeLanguage.TYPESCRIPT, CodeTheme.MONOKAI)
        assertEquals(CodeTheme.ONE_DARK_PRO.keyword, oneDark.first().colorArgb)
        assertEquals(CodeTheme.MONOKAI.keyword, monokai.first().colorArgb)
        assertTrue(oneDark.first().colorArgb != monokai.first().colorArgb)
    }
}
