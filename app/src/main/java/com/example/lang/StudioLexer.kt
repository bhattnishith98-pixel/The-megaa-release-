package com.example.lang

enum class TokenType {
    // Literals & identifiers
    IDENTIFIER,
    NUMBER,
    TEXT,
    DURATION,
    COMMENT,

    // Keywords
    KW_LET,
    KW_CONST,
    KW_DEF,
    KW_RETURN,
    KW_IF,
    KW_ELIF,
    KW_ELSE,
    KW_FOR,
    KW_IN,
    KW_WHILE,
    KW_REPEAT,
    KW_TIMES,
    KW_BREAK,
    KW_CONTINUE,
    KW_TRY,
    KW_ON,
    KW_ERROR,
    KW_RAISE,
    KW_RETRY,
    KW_WAIT,
    KW_TIMEOUT,
    KW_USE,
    KW_AS,
    KW_NEEDS,
    KW_CONFIRM,
    KW_TRIGGER,
    KW_CONDITION,
    KW_CONSTRAINT,
    KW_ACTION,
    KW_BLOCK,
    KW_MACRO,
    KW_APP,
    KW_SCREEN,
    KW_SOURCE,
    KW_WHEN,
    KW_EMIT,
    KW_ONLY,
    KW_DO,
    KW_TRUE,
    KW_FALSE,
    KW_NONE,
    KW_AND,
    KW_OR,
    KW_NOT,
    KW_IS,

    // Operators & delimiters
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    POWER,
    PLUS_EQ,
    MINUS_EQ,
    STAR_EQ,
    SLASH_EQ,
    EQ,
    EQ_EQ,
    BANG_EQ,
    LT,
    LT_EQ,
    GT,
    GT_EQ,
    ARROW,      // ->
    COLON,      // :
    COMMA,      // ,
    DOT,        // .
    LPAREN,     // (
    RPAREN,     // )
    LBRACKET,   // [
    RBRACKET,   // ]
    LBRACE,     // {
    RBRACE,     // }

    // Structural
    NEWLINE,
    INDENT,
    DEDENT,
    EOF
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val span: SourceSpan,
    val literal: Any? = null
)

/**
 * Table-driven indentation-sensitive lexer for StudioLang.
 * Keyword mappings live in [KEYWORDS] so keywords can be renamed or localized
 * without rewriting the tokenizer state machine.
 */
class StudioLexer(
    private val source: String,
    private val keywordTable: Map<String, TokenType> = DEFAULT_KEYWORDS
) {
    private val diagnostics = mutableListOf<StudioDiagnostic>()
    private val tokens = mutableListOf<Token>()

    fun tokenize(): LexResult {
        val lines = source.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val indentStack = ArrayDeque<Int>().apply { addLast(0) }
        var parenDepth = 0

        for ((lineIdx, rawLine) in lines.withIndex()) {
            val lineNum = lineIdx + 1
            if (rawLine.isEmpty()) continue

            // Check leading whitespace before first non-space char
            var pos = 0
            var spaces = 0
            var hasTab = false
            while (pos < rawLine.length) {
                when (rawLine[pos]) {
                    ' ' -> {
                        spaces++
                        pos++
                    }
                    '\t' -> {
                        hasTab = true
                        pos++
                    }
                    else -> break
                }
            }

            // Skip blank lines or comment-only lines without emitting INDENT/DEDENT
            if (pos >= rawLine.length || rawLine[pos] == '#') {
                if (pos < rawLine.length && rawLine[pos] == '#') {
                    tokens += Token(
                        TokenType.COMMENT,
                        rawLine.substring(pos),
                        SourceSpan(lineNum, pos + 1, rawLine.length + 1)
                    )
                }
                continue
            }

            if (hasTab) {
                diagnostics += StudioDiagnostic(
                    code = "LEX001",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Tab indentation is not allowed in ${LanguageConfig.LANGUAGE_NAME}; use ${LanguageConfig.INDENT_WIDTH} spaces.",
                    span = SourceSpan(lineNum, 1, pos + 1),
                    hint = "Replace tabs with 4 spaces per indentation level."
                )
                // Treat tab as 4 spaces for recovery
                spaces = rawLine.takeWhile { it == ' ' || it == '\t' }
                    .sumOf { if (it == '\t') LanguageConfig.INDENT_WIDTH else 1 }
            }

            if (parenDepth == 0) {
                if (spaces % LanguageConfig.INDENT_WIDTH != 0) {
                    diagnostics += StudioDiagnostic(
                        code = "LEX002",
                        severity = DiagnosticSeverity.WARNING,
                        message = "Indentation of $spaces spaces is not a multiple of ${LanguageConfig.INDENT_WIDTH}.",
                        span = SourceSpan(lineNum, 1, spaces + 1),
                        hint = "Use multiples of 4 spaces (4, 8, 12...)."
                    )
                }
                val currentIndent = indentStack.last()
                if (spaces > currentIndent) {
                    indentStack.addLast(spaces)
                    tokens += Token(TokenType.INDENT, "", SourceSpan(lineNum, 1, spaces + 1))
                } else if (spaces < currentIndent) {
                    while (indentStack.size > 1 && indentStack.last() > spaces) {
                        indentStack.removeLast()
                        tokens += Token(TokenType.DEDENT, "", SourceSpan(lineNum, 1, spaces + 1))
                    }
                    if (indentStack.last() != spaces) {
                        diagnostics += StudioDiagnostic(
                            code = "LEX003",
                            severity = DiagnosticSeverity.ERROR,
                            message = "Inconsistent dedent to $spaces spaces (expected ${indentStack.last()}).",
                            span = SourceSpan(lineNum, 1, spaces + 1)
                        )
                    }
                }
            }

            // Scan tokens on this line
            var i = pos
            while (i < rawLine.length) {
                val c = rawLine[i]
                when {
                    c == ' ' || c == '\t' -> i++
                    c == '#' -> {
                        tokens += Token(
                            TokenType.COMMENT,
                            rawLine.substring(i),
                            SourceSpan(lineNum, i + 1, rawLine.length + 1)
                        )
                        break
                    }
                    c == '"' || c == '\'' -> {
                        val quote = c
                        val start = i
                        i++
                        val sb = StringBuilder()
                        var closed = false
                        while (i < rawLine.length) {
                            val ch = rawLine[i]
                            if (ch == '\\' && i + 1 < rawLine.length) {
                                i++
                                when (rawLine[i]) {
                                    'n' -> sb.append('\n')
                                    't' -> sb.append('\t')
                                    '\\' -> sb.append('\\')
                                    '"' -> sb.append('"')
                                    '\'' -> sb.append('\'')
                                    else -> sb.append(rawLine[i])
                                }
                                i++
                            } else if (ch == quote) {
                                closed = true
                                i++
                                break
                            } else {
                                sb.append(ch)
                                i++
                            }
                        }
                        if (!closed) {
                            diagnostics += StudioDiagnostic(
                                code = "LEX004",
                                severity = DiagnosticSeverity.ERROR,
                                message = "Unterminated text literal.",
                                span = SourceSpan(lineNum, start + 1, rawLine.length + 1),
                                hint = "Add a closing $quote character."
                            )
                        }
                        tokens += Token(
                            TokenType.TEXT,
                            rawLine.substring(start, i),
                            SourceSpan(lineNum, start + 1, i + 1),
                            literal = sb.toString()
                        )
                    }
                    c.isDigit() -> {
                        val start = i
                        var hasDot = false
                        while (i < rawLine.length) {
                            val ch = rawLine[i]
                            if (ch.isDigit()) {
                                i++
                            } else if (ch == '.' && !hasDot && i + 1 < rawLine.length && rawLine[i + 1].isDigit()) {
                                hasDot = true
                                i++
                            } else {
                                break
                            }
                        }
                        val numText = rawLine.substring(start, i)
                        // Check duration suffix: ms, s, m, h
                        val suffixStart = i
                        if (i + 1 < rawLine.length && rawLine[i] == 'm' && rawLine[i + 1] == 's' &&
                            (i + 2 >= rawLine.length || !rawLine[i + 2].isLetterOrDigit())
                        ) {
                            i += 2
                            val millis = (numText.toDoubleOrNull() ?: 0.0).toLong()
                            tokens += Token(
                                TokenType.DURATION,
                                rawLine.substring(start, i),
                                SourceSpan(lineNum, start + 1, i + 1),
                                literal = millis
                            )
                        } else if (i < rawLine.length && rawLine[i] in charArrayOf('s', 'm', 'h') &&
                            (i + 1 >= rawLine.length || !rawLine[i + 1].isLetterOrDigit() && rawLine[i + 1] != '_')
                        ) {
                            val unit = rawLine[i]
                            i++
                            val base = numText.toDoubleOrNull() ?: 0.0
                            val millis = when (unit) {
                                's' -> (base * 1000).toLong()
                                'm' -> (base * 60_000).toLong()
                                'h' -> (base * 3_600_000).toLong()
                                else -> base.toLong()
                            }
                            tokens += Token(
                                TokenType.DURATION,
                                rawLine.substring(start, i),
                                SourceSpan(lineNum, start + 1, i + 1),
                                literal = millis
                            )
                        } else {
                            i = suffixStart
                            val value: Double = numText.toDoubleOrNull() ?: 0.0
                            tokens += Token(
                                TokenType.NUMBER,
                                numText,
                                SourceSpan(lineNum, start + 1, i + 1),
                                literal = value
                            )
                        }
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < rawLine.length && (rawLine[i].isLetterOrDigit() || rawLine[i] == '_')) {
                            i++
                        }
                        val word = rawLine.substring(start, i)
                        val kwType = keywordTable[word]
                        tokens += if (kwType != null) {
                            Token(kwType, word, SourceSpan(lineNum, start + 1, i + 1))
                        } else {
                            Token(TokenType.IDENTIFIER, word, SourceSpan(lineNum, start + 1, i + 1), literal = word)
                        }
                    }
                    else -> {
                        val start = i
                        val next = if (i + 1 < rawLine.length) rawLine[i + 1] else '\u0000'
                        val twoChar = "$c$next"
                        when (twoChar) {
                            "**" -> { tokens += Token(TokenType.POWER, "**", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "+=" -> { tokens += Token(TokenType.PLUS_EQ, "+=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "-=" -> { tokens += Token(TokenType.MINUS_EQ, "-=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "*=" -> { tokens += Token(TokenType.STAR_EQ, "*=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "/=" -> { tokens += Token(TokenType.SLASH_EQ, "/=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "==" -> { tokens += Token(TokenType.EQ_EQ, "==", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "!=" -> { tokens += Token(TokenType.BANG_EQ, "!=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "<=" -> { tokens += Token(TokenType.LT_EQ, "<=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            ">=" -> { tokens += Token(TokenType.GT_EQ, ">=", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            "->" -> { tokens += Token(TokenType.ARROW, "->", SourceSpan(lineNum, start + 1, start + 3)); i += 2 }
                            else -> {
                                val single = SINGLE_CHAR_TOKENS[c]
                                if (single != null) {
                                    if (c == '(' || c == '[' || c == '{') parenDepth++
                                    if (c == ')' || c == ']' || c == '}') parenDepth = (parenDepth - 1).coerceAtLeast(0)
                                    tokens += Token(single, c.toString(), SourceSpan(lineNum, start + 1, start + 2))
                                    i++
                                } else {
                                    diagnostics += StudioDiagnostic(
                                        code = "LEX005",
                                        severity = DiagnosticSeverity.ERROR,
                                        message = "Unexpected character '$c'.",
                                        span = SourceSpan(lineNum, start + 1, start + 2)
                                    )
                                    i++
                                }
                            }
                        }
                    }
                }
            }

            if (parenDepth == 0) {
                tokens += Token(TokenType.NEWLINE, "\n", SourceSpan(lineNum, rawLine.length + 1, rawLine.length + 2))
            }
        }

        val lastLine = lines.size.coerceAtLeast(1)
        while (indentStack.size > 1) {
            indentStack.removeLast()
            tokens += Token(TokenType.DEDENT, "", SourceSpan(lastLine, 1, 1))
        }
        tokens += Token(TokenType.EOF, "", SourceSpan(lastLine, 1, 1))
        return LexResult(tokens, diagnostics)
    }

    data class LexResult(
        val tokens: List<Token>,
        val diagnostics: List<StudioDiagnostic>
    )

    companion object {
        val DEFAULT_KEYWORDS: Map<String, TokenType> = mapOf(
            "let" to TokenType.KW_LET,
            "const" to TokenType.KW_CONST,
            "def" to TokenType.KW_DEF,
            "return" to TokenType.KW_RETURN,
            "if" to TokenType.KW_IF,
            "elif" to TokenType.KW_ELIF,
            "else" to TokenType.KW_ELSE,
            "for" to TokenType.KW_FOR,
            "in" to TokenType.KW_IN,
            "while" to TokenType.KW_WHILE,
            "repeat" to TokenType.KW_REPEAT,
            "times" to TokenType.KW_TIMES,
            "break" to TokenType.KW_BREAK,
            "continue" to TokenType.KW_CONTINUE,
            "try" to TokenType.KW_TRY,
            "on" to TokenType.KW_ON,
            "error" to TokenType.KW_ERROR,
            "raise" to TokenType.KW_RAISE,
            "retry" to TokenType.KW_RETRY,
            "wait" to TokenType.KW_WAIT,
            "timeout" to TokenType.KW_TIMEOUT,
            "use" to TokenType.KW_USE,
            "as" to TokenType.KW_AS,
            "needs" to TokenType.KW_NEEDS,
            "confirm" to TokenType.KW_CONFIRM,
            "trigger" to TokenType.KW_TRIGGER,
            "condition" to TokenType.KW_CONDITION,
            "constraint" to TokenType.KW_CONSTRAINT,
            "action" to TokenType.KW_ACTION,
            "block" to TokenType.KW_BLOCK,
            "macro" to TokenType.KW_MACRO,
            "app" to TokenType.KW_APP,
            "screen" to TokenType.KW_SCREEN,
            "source" to TokenType.KW_SOURCE,
            "when" to TokenType.KW_WHEN,
            "emit" to TokenType.KW_EMIT,
            "only" to TokenType.KW_ONLY,
            "do" to TokenType.KW_DO,
            "true" to TokenType.KW_TRUE,
            "false" to TokenType.KW_FALSE,
            "none" to TokenType.KW_NONE,
            "and" to TokenType.KW_AND,
            "or" to TokenType.KW_OR,
            "not" to TokenType.KW_NOT,
            "is" to TokenType.KW_IS
        )

        private val SINGLE_CHAR_TOKENS: Map<Char, TokenType> = mapOf(
            '+' to TokenType.PLUS,
            '-' to TokenType.MINUS,
            '*' to TokenType.STAR,
            '/' to TokenType.SLASH,
            '%' to TokenType.PERCENT,
            '=' to TokenType.EQ,
            '<' to TokenType.LT,
            '>' to TokenType.GT,
            ':' to TokenType.COLON,
            ',' to TokenType.COMMA,
            '.' to TokenType.DOT,
            '(' to TokenType.LPAREN,
            ')' to TokenType.RPAREN,
            '[' to TokenType.LBRACKET,
            ']' to TokenType.RBRACKET,
            '{' to TokenType.LBRACE,
            '}' to TokenType.RBRACE
        )
    }
}
