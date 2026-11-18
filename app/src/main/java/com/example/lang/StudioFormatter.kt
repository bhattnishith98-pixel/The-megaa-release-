package com.example.lang

/**
 * Canonical formatter (`stl fmt` equivalent) for StudioLang AST.
 */
object StudioFormatter {
    fun formatSource(source: String): String {
        val lex = StudioLexer(source).tokenize()
        val parse = StudioParser(lex.tokens).parse()
        if (lex.diagnostics.any { it.severity == DiagnosticSeverity.ERROR } ||
            parse.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
        ) {
            return source // Never corrupt source with parse errors
        }
        return formatProgram(parse.program)
    }

    fun formatProgram(program: ProgramNode): String {
        val sb = StringBuilder()
        program.statements.forEachIndexed { idx, stmt ->
            appendStmt(sb, stmt, 0)
            if (idx < program.statements.lastIndex) {
                val isBlockDef = stmt is FuncDefStmt || stmt is MacroDefStmt ||
                    stmt is TriggerDefStmt || stmt is ActionDefStmt ||
                    stmt is BlockDefStmt || stmt is AppDefStmt || stmt is ScreenDefStmt
                if (isBlockDef) sb.append('\n')
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun indent(level: Int): String = "    ".repeat(level)

    private fun appendStmt(sb: StringBuilder, stmt: Stmt, level: Int) {
        val pad = indent(level)
        when (stmt) {
            is UseStmt -> {
                sb.append(pad).append("use ").append(stmt.modulePath.joinToString("."))
                if (stmt.alias != null) sb.append(" as ").append(stmt.alias)
                sb.append('\n')
            }
            is NeedsStmt -> sb.append(pad).append("needs ").append(stmt.capabilities.joinToString(", ")).append('\n')
            is VarDeclStmt -> {
                sb.append(pad).append(if (stmt.isConst) "const " else "let ").append(stmt.name)
                if (stmt.typeHint != null) sb.append(": ").append(stmt.typeHint.label)
                sb.append(" = ").append(formatExpr(stmt.initializer)).append('\n')
            }
            is AssignStmt -> {
                val opStr = when (stmt.op) {
                    AssignOp.SET -> "="
                    AssignOp.ADD -> "+="
                    AssignOp.SUB -> "-="
                    AssignOp.MUL -> "*="
                    AssignOp.DIV -> "/="
                }
                sb.append(pad).append(formatExpr(stmt.target)).append(' ').append(opStr)
                    .append(' ').append(formatExpr(stmt.value)).append('\n')
            }
            is FuncDefStmt -> {
                sb.append(pad).append("def ").append(stmt.name).append('(')
                    .append(formatParams(stmt.params)).append(')')
                if (stmt.returnType != null) sb.append(" -> ").append(stmt.returnType.label)
                sb.append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is ConditionDefStmt -> {
                sb.append(pad).append("condition ").append(stmt.name).append('(')
                    .append(formatParams(stmt.params)).append(')')
                if (stmt.returnType != null) sb.append(" -> ").append(stmt.returnType.label)
                sb.append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is ConstraintDefStmt -> {
                sb.append(pad).append("constraint ").append(stmt.name).append('(')
                    .append(formatParams(stmt.params)).append(')')
                if (stmt.returnType != null) sb.append(" -> ").append(stmt.returnType.label)
                sb.append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is ActionDefStmt -> {
                sb.append(pad).append("action ").append(stmt.name).append('(')
                    .append(formatParams(stmt.params)).append(')')
                if (stmt.returnType != null) sb.append(" -> ").append(stmt.returnType.label)
                sb.append(":\n")
                if (stmt.needs.isNotEmpty()) {
                    sb.append(indent(level + 1)).append("needs ").append(stmt.needs.joinToString(", ")).append('\n')
                }
                appendBody(sb, stmt.body, level + 1)
            }
            is BlockDefStmt -> {
                sb.append(pad).append("block ").append(stmt.name).append('(')
                    .append(formatParams(stmt.params)).append(')')
                if (stmt.returnType != null) sb.append(" -> ").append(stmt.returnType.label)
                sb.append(":\n")
                if (stmt.needs.isNotEmpty()) {
                    sb.append(indent(level + 1)).append("needs ").append(stmt.needs.joinToString(", ")).append('\n')
                }
                appendBody(sb, stmt.body, level + 1)
            }
            is TriggerDefStmt -> {
                sb.append(pad).append("trigger ").append(stmt.name).append(":\n")
                if (stmt.needs.isNotEmpty()) {
                    sb.append(indent(level + 1)).append("needs ").append(stmt.needs.joinToString(", ")).append('\n')
                }
                stmt.sourceExpr?.let { sb.append(indent(level + 1)).append("source ").append(formatExpr(it)).append('\n') }
                stmt.whenExpr?.let { sb.append(indent(level + 1)).append("when ").append(formatExpr(it)).append('\n') }
                stmt.emitExpr?.let { sb.append(indent(level + 1)).append("emit ").append(formatExpr(it)).append('\n') }
                stmt.body.forEach { appendStmt(sb, it, level + 1) }
            }
            is MacroDefStmt -> {
                sb.append(pad).append("macro ").append(stmt.name).append(":\n")
                if (stmt.needs.isNotEmpty()) {
                    sb.append(indent(level + 1)).append("needs ").append(stmt.needs.joinToString(", ")).append('\n')
                }
                stmt.onTrigger?.let { sb.append(indent(level + 1)).append("on ").append(formatExpr(it)).append('\n') }
                stmt.onlyIf?.let { sb.append(indent(level + 1)).append("only if ").append(formatExpr(it)).append('\n') }
                sb.append(indent(level + 1)).append("do:\n")
                appendBody(sb, stmt.doBody, level + 2)
                if (stmt.errorBody.isNotEmpty()) {
                    sb.append(indent(level + 1)).append("on error ").append(stmt.errorVar ?: "e").append(":\n")
                    appendBody(sb, stmt.errorBody, level + 2)
                }
            }
            is AppDefStmt -> {
                sb.append(pad).append("app ").append(stmt.name).append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is ScreenDefStmt -> {
                sb.append(pad).append("screen ").append(stmt.name).append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is IfStmt -> {
                stmt.branches.forEachIndexed { idx, br ->
                    val kw = if (idx == 0) "if" else "elif"
                    sb.append(pad).append(kw).append(' ').append(formatExpr(br.condition)).append(":\n")
                    appendBody(sb, br.body, level + 1)
                }
                if (stmt.elseBody.isNotEmpty()) {
                    sb.append(pad).append("else:\n")
                    appendBody(sb, stmt.elseBody, level + 1)
                }
            }
            is ForStmt -> {
                sb.append(pad).append("for ").append(stmt.itemName).append(" in ")
                    .append(formatExpr(stmt.iterable)).append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is WhileStmt -> {
                sb.append(pad).append("while ").append(formatExpr(stmt.condition)).append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is RepeatStmt -> {
                sb.append(pad).append("repeat ").append(formatExpr(stmt.countExpr)).append(" times:\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is BreakStmt -> sb.append(pad).append("break\n")
            is ContinueStmt -> sb.append(pad).append("continue\n")
            is ReturnStmt -> {
                sb.append(pad).append("return")
                if (stmt.value != null) sb.append(' ').append(formatExpr(stmt.value))
                sb.append('\n')
            }
            is TryStmt -> {
                sb.append(pad).append("try:\n")
                appendBody(sb, stmt.tryBody, level + 1)
                sb.append(pad).append("on error ").append(stmt.errorVar ?: "e").append(":\n")
                appendBody(sb, stmt.catchBody, level + 1)
            }
            is RaiseStmt -> sb.append(pad).append("raise ").append(formatExpr(stmt.messageExpr)).append('\n')
            is RetryStmt -> {
                sb.append(pad).append("retry ").append(formatExpr(stmt.countExpr)).append(" times")
                if (stmt.waitExpr != null) sb.append(", wait ").append(formatExpr(stmt.waitExpr))
                sb.append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is TimeoutStmt -> {
                sb.append(pad).append("timeout ").append(formatExpr(stmt.durationExpr)).append(":\n")
                appendBody(sb, stmt.body, level + 1)
            }
            is ConfirmStmt -> sb.append(pad).append("confirm ").append(formatExpr(stmt.promptExpr)).append('\n')
            is ExprStmt -> sb.append(pad).append(formatExpr(stmt.expr)).append('\n')
        }
    }

    private fun appendBody(sb: StringBuilder, body: List<Stmt>, level: Int) {
        if (body.isEmpty()) {
            sb.append(indent(level)).append("none\n")
        } else {
            body.forEach { appendStmt(sb, it, level) }
        }
    }

    private fun formatParams(params: List<ParamSpec>): String =
        params.joinToString(", ") { p ->
            buildString {
                append(p.name)
                if (p.typeHint != null) append(": ").append(p.typeHint.label)
                if (p.defaultValue != null) append(" = ").append(formatExpr(p.defaultValue))
            }
        }

    fun formatExpr(expr: Expr): String = when (expr) {
        is NumberLit -> if (expr.value % 1.0 == 0.0 && kotlin.math.abs(expr.value) < 1e12) {
            expr.value.toLong().toString()
        } else expr.value.toString()
        is TextLit -> "\"${expr.value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        is BoolLit -> if (expr.value) "true" else "false"
        is NoneLit -> "none"
        is DurationLit -> expr.raw
        is ListLit -> "[${expr.elements.joinToString(", ") { formatExpr(it) }}]"
        is MapLit -> "{${expr.entries.joinToString(", ") { "${it.key}: ${formatExpr(it.value)}" }}}"
        is VarRef -> expr.name
        is MemberAccess -> "${formatExpr(expr.target)}.${expr.member}"
        is IndexAccess -> "${formatExpr(expr.target)}[${formatExpr(expr.index)}]"
        is CallExpr -> {
            val args = expr.args.joinToString(", ") { arg ->
                if (arg.name != null) "${arg.name} = ${formatExpr(arg.value)}" else formatExpr(arg.value)
            }
            "${formatExpr(expr.callee)}($args)"
        }
        is UnaryExpr -> when (expr.op) {
            UnaryOp.NEG -> "-${formatExpr(expr.operand)}"
            UnaryOp.NOT -> "not ${formatExpr(expr.operand)}"
        }
        is BinaryExpr -> "${formatExpr(expr.left)} ${expr.op.symbol} ${formatExpr(expr.right)}"
    }
}
