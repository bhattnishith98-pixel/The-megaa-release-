package com.example.lang

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Token-driven syntax highlighter for the StudioLang code editor.
 */
object StudioHighlighter {
    private val colorKeyword = Color(0xFFC084FC)    // Violet
    private val colorAutomation = Color(0xFF22D3EE) // Cyan
    private val colorString = Color(0xFF34D399)     // Emerald
    private val colorNumber = Color(0xFFFBBF24)     // Amber
    private val colorComment = Color(0xFF64748B)    // Muted Slate
    private val colorOperator = Color(0xFF94A3B8)
    private val colorDefault = Color(0xFFE2E8F0)

    private val automationKeywords = setOf(
        "trigger", "condition", "constraint", "action", "block", "macro",
        "app", "screen", "source", "when", "emit", "only", "do",
        "needs", "confirm", "retry", "wait", "timeout"
    )

    private val controlKeywords = StudioLexer.DEFAULT_KEYWORDS.keys - automationKeywords

    fun highlight(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '#' -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = colorComment)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\' && j + 1 < code.length) {
                            j += 2
                        } else if (code[j] == quote) {
                            j++
                            break
                        } else if (code[j] == '\n') {
                            break
                        } else {
                            j++
                        }
                    }
                    withStyle(SpanStyle(color = colorString)) {
                        append(code.substring(i, j))
                    }
                    i = j
                }
                c.isDigit() -> {
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.')) j++
                    withStyle(SpanStyle(color = colorNumber)) {
                        append(code.substring(i, j))
                    }
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    when {
                        word in automationKeywords -> withStyle(
                            SpanStyle(color = colorAutomation, fontWeight = FontWeight.SemiBold)
                        ) { append(word) }
                        word in controlKeywords -> withStyle(
                            SpanStyle(color = colorKeyword, fontWeight = FontWeight.SemiBold)
                        ) { append(word) }
                        else -> withStyle(SpanStyle(color = colorDefault)) { append(word) }
                    }
                    i = j
                }
                c in "+-*/%=<>!:,.()[]{}" -> {
                    withStyle(SpanStyle(color = colorOperator)) { append(c) }
                    i++
                }
                else -> {
                    withStyle(SpanStyle(color = colorDefault)) { append(c) }
                    i++
                }
            }
        }
    }
}
