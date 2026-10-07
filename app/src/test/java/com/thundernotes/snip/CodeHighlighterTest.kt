package com.thundernotes.snip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CodeHighlighter] + [CodeTheme] + [CodeLanguage].
 * Tests keyword sets, tokenisation (strings, comments, numbers, functions,
 * operators), + theme colour mappings.
 */
class CodeHighlighterTest {

    // ─── Keyword sets (official, from language specs) ─────────────────────

    @Test fun `TS keywords include const, function, return, async`() {
        val kw = CodeLanguage.TYPESCRIPT.keywords
        assertTrue("const" in kw)
        assertTrue("function" in kw)
        assertTrue("return" in kw)
        assertTrue("async" in kw)
        assertTrue("await" in kw)
        assertTrue("interface" in kw)
    }

    @Test fun `Python keywords include def, class, return, lambda`() {
        val kw = CodeLanguage.PYTHON.keywords
        assertTrue("def" in kw)
        assertTrue("class" in kw)
        assertTrue("return" in kw)
        assertTrue("lambda" in kw)
        assertTrue("True" in kw)
        assertTrue("False" in kw)
    }

    @Test fun `Java keywords include public, class, void, return`() {
        val kw = CodeLanguage.JAVA.keywords
        assertTrue("public" in kw)
        assertTrue("class" in kw)
        assertTrue("void" in kw)
        assertTrue("return" in kw)
        assertTrue("synchronized" in kw)
    }

    @Test fun `C++ keywords include int, class, template, constexpr`() {
        val kw = CodeLanguage.CPP.keywords
        assertTrue("int" in kw)
        assertTrue("class" in kw)
        assertTrue("template" in kw)
        assertTrue("constexpr" in kw)
        assertTrue("nullptr" in kw)
    }

    // ─── Theme colours (official, from theme repos) ───────────────────────

    @Test fun `One Dark Pro has the official token colours`() {
        val t = CodeTheme.ONE_DARK_PRO
        assertEquals(0xFFC678DD.toInt(), t.keyword)
        assertEquals(0xFF98C379.toInt(), t.string)
        assertEquals(0xFF7F848E.toInt(), t.comment)
        assertEquals(0xFFD19A66.toInt(), t.number)
        assertEquals(0xFF61AFEF.toInt(), t.function)
    }

    @Test fun `Dark Modern has the VS Code dark_plus colours`() {
        val t = CodeTheme.DARK_MODERN
        assertEquals(0xFF569CD6.toInt(), t.keyword)
        assertEquals(0xFFCE9178.toInt(), t.string)
        assertEquals(0xFF6A9955.toInt(), t.comment)
    }

    @Test fun `default theme for TS is One Dark Pro`() {
        assertEquals(CodeTheme.ONE_DARK_PRO, CodeTheme.forLanguage(CodeLanguage.TYPESCRIPT))
    }

    @Test fun `default theme for Python is Dark Modern`() {
        assertEquals(CodeTheme.DARK_MODERN, CodeTheme.forLanguage(CodeLanguage.PYTHON))
    }

    @Test fun `default theme for Java is GitHub Dark`() {
        assertEquals(CodeTheme.GITHUB_DARK, CodeTheme.forLanguage(CodeLanguage.JAVA))
    }

    @Test fun `default theme for C++ is Monokai`() {
        assertEquals(CodeTheme.MONOKAI, CodeTheme.forLanguage(CodeLanguage.CPP))
    }

    // ─── Tokeniser ────────────────────────────────────────────────────────

    @Test fun `tokenise TS keyword + string + plain`() {
        val code = "const x = \"hello\""
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.TYPESCRIPT)
        // const → KEYWORD, x → PLAIN, = → OPERATOR, "hello" → STRING
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.KEYWORD && it.text == "const" })
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.STRING && it.text == "\"hello\"" })
    }

    @Test fun `tokenise line comment TS`() {
        val code = "// a comment\nconst x = 1"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.TYPESCRIPT)
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.COMMENT && it.text.contains("a comment") })
    }

    @Test fun `tokenise line comment (Python #)`() {
        val code = "# a comment\nx = 1"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.PYTHON)
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.COMMENT && it.text.contains("a comment") })
    }

    @Test fun `tokenise block comment`() {
        val code = "/* multi\nline\ncomment */ x = 1"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.JAVA)
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.COMMENT })
    }

    @Test fun `tokenise number`() {
        val code = "x = 42"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.PYTHON)
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.NUMBER && it.text == "42" })
    }

    @Test fun `tokenise function call (identifier followed by open paren)`() {
        val code = "foo(bar)"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.JAVA)
        assertTrue(tokens.any { it.type == CodeHighlighter.TokenType.FUNCTION && it.text == "foo" })
    }

    @Test fun `tokenise preserves tabs (no tab-to-space conversion)`() {
        val code = "function foo() {\n\treturn 1;\n}"
        val tokens = CodeHighlighter.tokenize(code, CodeLanguage.TYPESCRIPT)
        val reconstructed = tokens.joinToString("") { it.text }
        assertEquals(code, reconstructed)
    }

    @Test fun `tokenise empty code returns empty list`() {
        assertTrue(CodeHighlighter.tokenize("", CodeLanguage.JAVA).isEmpty())
    }

    @Test fun `tokenise plain text only returns one PLAIN token`() {
        val tokens = CodeHighlighter.tokenize("   \n\t  ", CodeLanguage.CPP)
        assertEquals(1, tokens.size)
        assertEquals(CodeHighlighter.TokenType.PLAIN, tokens[0].type)
    }
}
