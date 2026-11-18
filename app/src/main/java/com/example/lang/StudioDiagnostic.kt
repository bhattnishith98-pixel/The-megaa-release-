package com.example.lang

data class SourceSpan(
    val line: Int,
    val column: Int,
    val endColumn: Int = column + 1
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO
}

data class StudioDiagnostic(
    val code: String,
    val severity: DiagnosticSeverity,
    val message: String,
    val span: SourceSpan,
    val hint: String? = null
) {
    fun format(): String {
        val base = "[$code] Line ${span.line}:${span.column} ${severity.name}: $message"
        return if (hint != null) "$base (Hint: $hint)" else base
    }
}
