package com.thundernotes.snip

/**
 * Official syntax-highlighting themes for the code snip (spec §6.10
 * canvasUtilityPage: One Dark Pro for TS/JS, Dark Modern for Python,
 * Github Theme for Java, Monokai Pro for C++).
 *
 * Colors sourced from the official theme definitions:
 *  - **VS Code Dark Modern** (dark_plus.json, microsoft/vscode repo):
 *    keyword #569CD6, string #CE9178, comment #6A9955, number #B5CEA8,
 *    function #DCDCAA, type #4EC9B0, variable #9CDCFE, plain #D4D4D4.
 *  - **One Dark Pro** (Binaryify/One-Dark-Pro repo):
 *    keyword #C678DD, string #98C379, comment #7F848E, number #D19A66,
 *    function #61AFEF, type #E5C07B, variable #E06C75, plain #ABB2BF.
 *  - **GitHub Dark** (primer/github-vscode-theme repo):
 *    keyword #FF7B72, string #A5D6FF, comment #8B949E, number #79C0FF,
 *    function #D2A8FF, type #FFA657, variable #FFA657, plain #C9D1D9.
 *  - **Monokai** (Sublime Text default theme — Monokai Pro's free base):
 *    keyword #F92672, string #E6DB74, comment #75715E, number #AE81FF,
 *    function #A6E22E, type #66D9EF, variable #F8F8F2, plain #F8F8F2.
 *
 * Pure + unit-testable (ARGB ints, no Android dependency).
 */
enum class CodeTheme(
    val displayName: String,
    val keyword: Int,
    val string: Int,
    val comment: Int,
    val number: Int,
    val function: Int,
    val type: Int,
    val variable: Int,
    val operator: Int,
    val plain: Int,
    val background: Int,
) {
    ONE_DARK_PRO(
        "One Dark Pro",
        keyword = 0xFFC678DD.toInt(),
        string = 0xFF98C379.toInt(),
        comment = 0xFF7F848E.toInt(),
        number = 0xFFD19A66.toInt(),
        function = 0xFF61AFEF.toInt(),
        type = 0xFFE5C07B.toInt(),
        variable = 0xFFE06C75.toInt(),
        operator = 0xFF56B6C2.toInt(),
        plain = 0xFFABB2BF.toInt(),
        background = 0xFF282C34.toInt(),
    ),
    DARK_MODERN(
        "Dark Modern",
        keyword = 0xFF569CD6.toInt(),
        string = 0xFFCE9178.toInt(),
        comment = 0xFF6A9955.toInt(),
        number = 0xFFB5CEA8.toInt(),
        function = 0xFFDCDCAA.toInt(),
        type = 0xFF4EC9B0.toInt(),
        variable = 0xFF9CDCFE.toInt(),
        operator = 0xFFD4D4D4.toInt(),
        plain = 0xFFD4D4D4.toInt(),
        background = 0xFF1E1E1E.toInt(),
    ),
    GITHUB_DARK(
        "GitHub Dark",
        keyword = 0xFFFF7B72.toInt(),
        string = 0xFFA5D6FF.toInt(),
        comment = 0xFF8B949E.toInt(),
        number = 0xFF79C0FF.toInt(),
        function = 0xFFD2A8FF.toInt(),
        type = 0xFFFFA657.toInt(),
        variable = 0xFFFFA657.toInt(),
        operator = 0xFFFF7B72.toInt(),
        plain = 0xFFC9D1D9.toInt(),
        background = 0xFF0D1117.toInt(),
    ),
    MONOKAI(
        "Monokai",
        keyword = 0xFFF92672.toInt(),
        string = 0xFFE6DB74.toInt(),
        comment = 0xFF75715E.toInt(),
        number = 0xFFAE81FF.toInt(),
        function = 0xFFA6E22E.toInt(),
        type = 0xFF66D9EF.toInt(),
        variable = 0xFFF8F8F2.toInt(),
        operator = 0xFFF92672.toInt(),
        plain = 0xFFF8F8F2.toInt(),
        background = 0xFF272822.toInt(),
    );

    companion object {
        /** The default theme for a language (spec §6.10 canvasUtilityPage). */
        fun forLanguage(language: CodeLanguage): CodeTheme = when (language) {
            CodeLanguage.TYPESCRIPT, CodeLanguage.JAVASCRIPT, CodeLanguage.JSX -> ONE_DARK_PRO
            CodeLanguage.PYTHON -> DARK_MODERN
            CodeLanguage.JAVA -> GITHUB_DARK
            CodeLanguage.CPP -> MONOKAI
        }
    }
}

/**
 * The 4 supported code languages (spec §6.10 canvasUtilityPage). Each has an
 * official keyword set (from the language specification) + a default theme.
 */
enum class CodeLanguage(val displayName: String) {
    TYPESCRIPT("TypeScript"),
    JAVASCRIPT("JavaScript"),
    JSX("JSX"),
    PYTHON("Python"),
    JAVA("Java"),
    CPP("C++");

    /** The official keyword set for this language (from the language spec). */
    val keywords: Set<String> get() = when (this) {
        TYPESCRIPT, JAVASCRIPT, JSX -> setOf(
            "const", "let", "var", "function", "if", "else", "return", "import",
            "export", "class", "extends", "implements", "interface", "type", "enum",
            "async", "await", "yield", "new", "delete", "typeof", "instanceof",
            "in", "of", "for", "while", "do", "switch", "case", "break", "continue",
            "throw", "try", "catch", "finally", "this", "super", "null", "undefined",
            "true", "false", "as", "void", "never", "unknown", "any", "number",
            "string", "boolean", "symbol", "from", "default", "static", "get", "set",
            "public", "private", "protected", "readonly", "abstract", "declare",
            "namespace", "module", "require", "satisfies", "global", "infer",
            "is", "keyof", "unique", "assert",
        )
        PYTHON -> setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield", "match", "case", "type",
        )
        JAVA -> setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for", "goto",
            "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "var", "yield", "record", "sealed", "permits", "non-sealed",
        )
        CPP -> setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor",
            "bool", "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t",
            "class", "concept", "const", "consteval", "constexpr", "constinit",
            "const_cast", "continue", "co_await", "co_return", "co_yield", "decltype",
            "default", "delete", "do", "double", "dynamic_cast", "else", "enum",
            "explicit", "export", "extern", "false", "float", "for", "friend", "goto",
            "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept",
            "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private",
            "protected", "public", "register", "reinterpret_cast", "return", "short",
            "signed", "sizeof", "static", "static_assert", "static_cast", "struct",
            "switch", "template", "this", "thread_local", "throw", "true", "try",
            "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual",
            "void", "volatile", "wchar_t", "while", "xor", "xor_eq",
        )
    }

    /** Comment syntax for this language. */
    val lineComment: String get() = when (this) {
        PYTHON -> "#"
        else -> "//"
    }

    val blockCommentStart: String get() = "/*"
    val blockCommentEnd: String get() = "*/"
}
