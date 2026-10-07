package com.thundernotes.snip

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Regex-based syntax tokenizer (spec §7.3.4c: "OCR → code formatter engine
 * → formatted code with proper tabs & colors"). Zero-dependency, pure-Kotlin.
 *
 * Tokenises raw code into a list of [Token]s (text + type). The [CodeFormatter]
 * maps each token type to a colour via [CodeTheme].
 *
 * Supports 4 languages (TS/JS/JSX, Python, Java, C++) with their official
 * keyword sets (from the language specs — see [CodeLanguage.keywords]).
 *
 * **Tab handling**: tabs are preserved as-is (the formatter renders with a
 * monospace font + the raw text's indentation). No tab→space conversion.
 *
 * Pure + unit-testable (operates on a String → List<Token>, no Android).
 */
object CodeHighlighter {

    enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, OPERATOR, PLAIN }

    data class Token(val text: String, val type: TokenType)

    /**
     * Tokenise [code] for [language] → a list of [Token]s in reading order.
     * Each token is a contiguous run of the same type. Whitespace is PLAIN.
     */
    fun tokenize(code: String, language: CodeLanguage): List<Token> {
        val tokens = mutableListOf<Token>()
        val keywords = language.keywords

        // Build a combined regex that matches (in priority order):
        // 1. Block comments (/* ... */) — multi-line.
        // 2. Line comments (//... or #...).
        // 3. Strings ("...", '...', `...` for TS/JS).
        // 4. Numbers (123, 1.5, 0x1A, 0b10, 1_000).
        // 5. Identifiers (word chars).
        // 6. Operators (single chars: + - * / = < > ! & | ^ ~ %).
        // 7. Everything else (whitespace, punctuation) → PLAIN.
        val stringPattern = if (language == CodeLanguage.PYTHON) {
            """\{\{.*?\}\}|"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?'''"""
        } else {
            """"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|`[^`\\]*(?:\\.[^`\\]*)*`"""
        }
        val pattern = Pattern.compile(
            """(?<comment>/\*[\s\S]*?\*/|${language.lineComment}[^\n]*)|""" +
            """(?<string>$stringPattern)|""" +
            """(?<number>\b0[xX][0-9a-fA-F_]+|\b0[bB][01_]+|\b\d[\d_]*\.?\d*(?:[eE][+-]?\d+)?\b)|""" +
            """(?<identifier>[A-Za-z_$][A-Za-z0-9_$]*)|""" +
            """(?<operator>[+\-*/%=<>!&|^~%]+)""",
        )

        val matcher = pattern.matcher(code)
        var lastEnd = 0
        while (matcher.find()) {
            // Gap between last token + this match → plain (whitespace/punctuation).
            if (matcher.start() > lastEnd) {
                tokens.add(Token(code.substring(lastEnd, matcher.start()), TokenType.PLAIN))
            }
            val text = matcher.group()
            val type = when {
                matcher.group("comment") != null -> TokenType.COMMENT
                matcher.group("string") != null -> TokenType.STRING
                matcher.group("number") != null -> TokenType.NUMBER
                matcher.group("identifier") != null -> {
                    // Check if it's a keyword or a function call (followed by `(`).
                    if (text in keywords) TokenType.KEYWORD
                    else {
                        // Look ahead: if the next non-whitespace char is `(` → function.
                        val after = code.drop(matcher.end()).dropWhile { it.isWhitespace() }
                        if (after.startsWith("(")) TokenType.FUNCTION
                        else TokenType.PLAIN
                    }
                }
                matcher.group("operator") != null -> TokenType.OPERATOR
                else -> TokenType.PLAIN
            }
            tokens.add(Token(text, type))
            lastEnd = matcher.end()
        }
        // Trailing plain (after the last match).
        if (lastEnd < code.length) {
            tokens.add(Token(code.substring(lastEnd), TokenType.PLAIN))
        }
        return tokens
    }
}
