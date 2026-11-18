package com.example.lang

/**
 * Recursive descent parser with error recovery and contextual diagnostics for StudioLang.
 */
class StudioParser(rawTokens: List<Token>) {
    // Strip comment tokens for parsing while keeping line/col spans exact.
    private val tokens: List<Token> = rawTokens.filter { it.type != TokenType.COMMENT }
    private var pos = 0
    val diagnostics = mutableListOf<StudioDiagnostic>()

    fun parse(): ParseResult {
        val stmts = mutableListOf<Stmt>()
        val topNeeds = mutableListOf<String>()
        skipNewlines()
        while (!isAtEnd()) {
            try {
                val stmt = parseStatement()
                if (stmt != null) {
                    stmts += stmt
                    if (stmt is NeedsStmt) {
                        topNeeds += stmt.capabilities
                    }
                }
            } catch (_: ParseSyncException) {
                synchronize()
            }
            skipNewlines()
        }
        return ParseResult(ProgramNode(stmts, topNeeds.distinct()), diagnostics.toList())
    }

    data class ParseResult(
        val program: ProgramNode,
        val diagnostics: List<StudioDiagnostic>
    )

    private class ParseSyncException : RuntimeException()

    private fun parseStatement(): Stmt? {
        skipNewlines()
        if (isAtEnd()) return null
        val t = peek()
        return when (t.type) {
            TokenType.KW_USE -> parseUse()
            TokenType.KW_NEEDS -> parseNeeds()
            TokenType.KW_LET -> parseVarDecl(isConst = false)
            TokenType.KW_CONST -> parseVarDecl(isConst = true)
            TokenType.KW_DEF -> parseFuncDef()
            TokenType.KW_TRIGGER -> parseTriggerDef()
            TokenType.KW_CONDITION -> parseConditionDef()
            TokenType.KW_CONSTRAINT -> parseConstraintDef()
            TokenType.KW_ACTION -> parseActionDef()
            TokenType.KW_BLOCK -> parseBlockDef()
            TokenType.KW_MACRO -> parseMacroDef()
            TokenType.KW_APP -> if (peekNext()?.type == TokenType.DOT) parseExprOrAssign() else parseAppDef()
            TokenType.KW_SCREEN -> if (peekNext()?.type == TokenType.DOT) parseExprOrAssign() else parseScreenDef()
            TokenType.KW_IF -> parseIf()
            TokenType.KW_FOR -> parseFor()
            TokenType.KW_WHILE -> parseWhile()
            TokenType.KW_REPEAT -> parseRepeat()
            TokenType.KW_BREAK -> { advance(); consumeLineEnd(); BreakStmt(t.span) }
            TokenType.KW_CONTINUE -> { advance(); consumeLineEnd(); ContinueStmt(t.span) }
            TokenType.KW_RETURN -> parseReturn()
            TokenType.KW_TRY -> parseTry()
            TokenType.KW_RAISE -> parseRaise()
            TokenType.KW_RETRY -> parseRetry()
            TokenType.KW_TIMEOUT -> parseTimeout()
            TokenType.KW_CONFIRM -> parseConfirm()
            TokenType.DEDENT, TokenType.EOF -> null
            else -> parseExprOrAssign()
        }
    }

    private fun parseUse(): UseStmt {
        val kw = advance()
        val path = mutableListOf(consumeIdentifier("Expected module name after 'use'.").lexeme)
        while (match(TokenType.DOT)) {
            path += consumeIdentifier("Expected submodule name after '.'.").lexeme
        }
        val alias = if (match(TokenType.KW_AS)) {
            consumeIdentifier("Expected alias identifier after 'as'.").lexeme
        } else null
        consumeLineEnd()
        return UseStmt(path, alias, kw.span)
    }

    private fun parseNeeds(): NeedsStmt {
        val kw = advance()
        val caps = mutableListOf<String>()
        caps += consumeIdentifier("Expected capability name after 'needs'.").lexeme
        while (match(TokenType.COMMA)) {
            caps += consumeIdentifier("Expected capability name after ','.").lexeme
        }
        consumeLineEnd()
        return NeedsStmt(caps, kw.span)
    }

    private fun parseVarDecl(isConst: Boolean): VarDeclStmt {
        val kw = advance()
        val nameTok = consumeIdentifier("Expected variable name.")
        val typeHint = if (match(TokenType.COLON)) parseTypeHint() else null
        consume(TokenType.EQ, "Expected '=' in ${if (isConst) "const" else "let"} declaration.")
        val value = parseExpression()
        consumeLineEnd()
        return VarDeclStmt(nameTok.lexeme, isConst, typeHint, value, kw.span)
    }

    private fun parseFuncDef(): FuncDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected function name after 'def'.").lexeme
        val params = parseParamList()
        val retType = if (match(TokenType.ARROW)) parseTypeHint() else null
        val body = parseIndentedBlock("function '$name'")
        return FuncDefStmt(name, params, retType, body, kw.span)
    }

    private fun parseConditionDef(): ConditionDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected condition name.").lexeme
        val params = if (check(TokenType.LPAREN)) parseParamList() else emptyList()
        val retType = if (match(TokenType.ARROW)) parseTypeHint() else StudioType.BOOL
        val body = parseIndentedBlock("condition '$name'")
        return ConditionDefStmt(name, params, retType, body, kw.span)
    }

    private fun parseConstraintDef(): ConstraintDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected constraint name.").lexeme
        val params = if (check(TokenType.LPAREN)) parseParamList() else emptyList()
        val retType = if (match(TokenType.ARROW)) parseTypeHint() else StudioType.BOOL
        val body = parseIndentedBlock("constraint '$name'")
        return ConstraintDefStmt(name, params, retType, body, kw.span)
    }

    private fun parseActionDef(): ActionDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected action name.").lexeme
        val params = if (check(TokenType.LPAREN)) parseParamList() else emptyList()
        val retType = if (match(TokenType.ARROW)) parseTypeHint() else null
        val rawBody = parseIndentedBlock("action '$name'")
        val needs = rawBody.filterIsInstance<NeedsStmt>().flatMap { it.capabilities }
        val body = rawBody.filterNot { it is NeedsStmt }
        return ActionDefStmt(name, params, retType, needs, body, kw.span)
    }

    private fun parseBlockDef(): BlockDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected block name.").lexeme
        val params = if (check(TokenType.LPAREN)) parseParamList() else emptyList()
        val retType = if (match(TokenType.ARROW)) parseTypeHint() else null
        val rawBody = parseIndentedBlock("block '$name'")
        val needs = rawBody.filterIsInstance<NeedsStmt>().flatMap { it.capabilities }
        val body = rawBody.filterNot { it is NeedsStmt }
        return BlockDefStmt(name, params, retType, needs, body, kw.span)
    }

    private fun parseTriggerDef(): TriggerDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected trigger name.").lexeme
        consumeColonAndNewline("trigger '$name'")
        consume(TokenType.INDENT, "Expected indented body for trigger '$name'.")
        var sourceExpr: Expr? = null
        var whenExpr: Expr? = null
        var emitExpr: Expr? = null
        val needs = mutableListOf<String>()
        val extraBody = mutableListOf<Stmt>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            when {
                match(TokenType.KW_SOURCE) -> {
                    sourceExpr = parseExpression()
                    consumeLineEnd()
                }
                match(TokenType.KW_WHEN) -> {
                    whenExpr = parseExpression()
                    consumeLineEnd()
                }
                match(TokenType.KW_EMIT) -> {
                    emitExpr = parseExpression()
                    consumeLineEnd()
                }
                check(TokenType.KW_NEEDS) -> {
                    needs += parseNeeds().capabilities
                }
                else -> {
                    parseStatement()?.let { extraBody += it }
                }
            }
            skipNewlines()
        }
        match(TokenType.DEDENT)
        return TriggerDefStmt(name, sourceExpr, whenExpr, emitExpr, needs, extraBody, kw.span)
    }

    private fun parseMacroDef(): MacroDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected macro name.").lexeme
        consumeColonAndNewline("macro '$name'")
        consume(TokenType.INDENT, "Expected indented body for macro '$name'.")
        var onTrigger: Expr? = null
        var onlyIf: Expr? = null
        val needs = mutableListOf<String>()
        val doBody = mutableListOf<Stmt>()
        var errorVar: String? = null
        val errorBody = mutableListOf<Stmt>()

        skipNewlines()
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            when {
                check(TokenType.KW_ON) && peekNext()?.type == TokenType.KW_ERROR -> {
                    advance() // on
                    advance() // error
                    errorVar = if (check(TokenType.IDENTIFIER)) advance().lexeme else "err"
                    errorBody += parseIndentedBlock("on error")
                }
                match(TokenType.KW_ON) -> {
                    onTrigger = parseExpression()
                    consumeLineEnd()
                }
                match(TokenType.KW_ONLY) -> {
                    consume(TokenType.KW_IF, "Expected 'if' after 'only'.")
                    onlyIf = parseExpression()
                    consumeLineEnd()
                }
                match(TokenType.KW_DO) -> {
                    doBody += parseIndentedBlock("macro do")
                }
                check(TokenType.KW_NEEDS) -> {
                    needs += parseNeeds().capabilities
                }
                else -> {
                    // Allow concise macros with direct statements too
                    parseStatement()?.let { doBody += it }
                }
            }
            skipNewlines()
        }
        match(TokenType.DEDENT)
        return MacroDefStmt(name, onTrigger, onlyIf, needs, doBody, errorVar, errorBody, kw.span)
    }

    private fun parseAppDef(): AppDefStmt {
        val kw = advance()
        val name = if (check(TokenType.TEXT)) advance().literal as String
        else consumeIdentifier("Expected app name.").lexeme
        val body = parseIndentedBlock("app '$name'")
        return AppDefStmt(name, body, kw.span)
    }

    private fun parseScreenDef(): ScreenDefStmt {
        val kw = advance()
        val name = if (check(TokenType.TEXT)) advance().literal as String
        else consumeIdentifier("Expected screen name.").lexeme
        val body = parseIndentedBlock("screen '$name'")
        return ScreenDefStmt(name, body, kw.span)
    }

    private fun parseIf(): IfStmt {
        val kw = advance()
        val firstCond = parseExpression()
        val firstBody = parseIndentedBlock("if")
        val branches = mutableListOf(IfBranch(firstCond, firstBody, kw.span))
        skipNewlines()
        while (check(TokenType.KW_ELIF)) {
            val elifTok = advance()
            val cond = parseExpression()
            val body = parseIndentedBlock("elif")
            branches += IfBranch(cond, body, elifTok.span)
            skipNewlines()
        }
        val elseBody = if (match(TokenType.KW_ELSE)) {
            parseIndentedBlock("else")
        } else emptyList()
        return IfStmt(branches, elseBody, kw.span)
    }

    private fun parseFor(): ForStmt {
        val kw = advance()
        val itemName = consumeIdentifier("Expected loop variable after 'for'.").lexeme
        consume(TokenType.KW_IN, "Expected 'in' in for loop.")
        val iterable = parseExpression()
        val body = parseIndentedBlock("for")
        return ForStmt(itemName, iterable, body, kw.span)
    }

    private fun parseWhile(): WhileStmt {
        val kw = advance()
        val cond = parseExpression()
        val body = parseIndentedBlock("while")
        return WhileStmt(cond, body, kw.span)
    }

    private fun parseRepeat(): RepeatStmt {
        val kw = advance()
        val countExpr = parseExpression()
        consume(TokenType.KW_TIMES, "Expected 'times' after repeat count.")
        val body = parseIndentedBlock("repeat")
        return RepeatStmt(countExpr, body, kw.span)
    }

    private fun parseReturn(): ReturnStmt {
        val kw = advance()
        val value = if (!check(TokenType.NEWLINE) && !check(TokenType.EOF) && !check(TokenType.DEDENT)) {
            parseExpression()
        } else null
        consumeLineEnd()
        return ReturnStmt(value, kw.span)
    }

    private fun parseTry(): TryStmt {
        val kw = advance()
        val tryBody = parseIndentedBlock("try")
        skipNewlines()
        var errorVar: String? = null
        val catchBody = if (match(TokenType.KW_ON)) {
            consume(TokenType.KW_ERROR, "Expected 'error' after 'on' in try block.")
            errorVar = if (check(TokenType.IDENTIFIER)) advance().lexeme else "err"
            parseIndentedBlock("on error")
        } else {
            diagnostics += StudioDiagnostic(
                "PAR005",
                DiagnosticSeverity.ERROR,
                "A 'try:' block must be followed by 'on error <name>:'.",
                kw.span,
                hint = "Add 'on error e:' at the same indentation as 'try:'."
            )
            emptyList()
        }
        return TryStmt(tryBody, errorVar, catchBody, kw.span)
    }

    private fun parseRaise(): RaiseStmt {
        val kw = advance()
        val msg = parseExpression()
        consumeLineEnd()
        return RaiseStmt(msg, kw.span)
    }

    private fun parseRetry(): RetryStmt {
        val kw = advance()
        val countExpr = parseExpression()
        consume(TokenType.KW_TIMES, "Expected 'times' after retry count.")
        val waitExpr = if (match(TokenType.COMMA)) {
            consume(TokenType.KW_WAIT, "Expected 'wait <duration>' after ',' in retry.")
            parseExpression()
        } else null
        val body = parseIndentedBlock("retry")
        return RetryStmt(countExpr, waitExpr, body, kw.span)
    }

    private fun parseTimeout(): TimeoutStmt {
        val kw = advance()
        val duration = parseExpression()
        val body = parseIndentedBlock("timeout")
        return TimeoutStmt(duration, body, kw.span)
    }

    private fun parseConfirm(): ConfirmStmt {
        val kw = advance()
        val prompt = parseExpression()
        consumeLineEnd()
        return ConfirmStmt(prompt, kw.span)
    }

    private fun parseExprOrAssign(): Stmt {
        val expr = parseExpression()
        val assignOp = when (peek().type) {
            TokenType.EQ -> AssignOp.SET
            TokenType.PLUS_EQ -> AssignOp.ADD
            TokenType.MINUS_EQ -> AssignOp.SUB
            TokenType.STAR_EQ -> AssignOp.MUL
            TokenType.SLASH_EQ -> AssignOp.DIV
            else -> null
        }
        return if (assignOp != null) {
            advance()
            val value = parseExpression()
            consumeLineEnd()
            AssignStmt(expr, assignOp, value, expr.span)
        } else {
            consumeLineEnd()
            ExprStmt(expr, expr.span)
        }
    }

    private fun parseParamList(): List<ParamSpec> {
        consume(TokenType.LPAREN, "Expected '(' before parameter list.")
        val params = mutableListOf<ParamSpec>()
        if (!check(TokenType.RPAREN)) {
            do {
                val idTok = consumeIdentifier("Expected parameter name.")
                val typeHint = if (match(TokenType.COLON)) parseTypeHint() else null
                val defaultVal = if (match(TokenType.EQ)) parseExpression() else null
                params += ParamSpec(idTok.lexeme, typeHint, defaultVal, idTok.span)
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameter list.")
        return params
    }

    private fun parseTypeHint(): StudioType {
        val tok = advance()
        val resolved = StudioType.fromLabel(tok.lexeme)
        if (resolved == null) {
            diagnostics += StudioDiagnostic(
                "PAR006",
                DiagnosticSeverity.WARNING,
                "Unknown type hint '${tok.lexeme}', treating as 'any'.",
                tok.span,
                hint = "Valid types: number, text, bool, list, map, none, time, duration, file, node, any."
            )
            return StudioType.ANY
        }
        return resolved
    }

    private fun parseIndentedBlock(label: String): List<Stmt> {
        consumeColonAndNewline(label)
        if (!match(TokenType.INDENT)) {
            diagnostics += StudioDiagnostic(
                "PAR004",
                DiagnosticSeverity.ERROR,
                "Expected an indented block for $label.",
                peek().span,
                hint = "Indent the next line by 4 spaces."
            )
            return emptyList()
        }
        val stmts = mutableListOf<Stmt>()
        skipNewlines()
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            try {
                parseStatement()?.let { stmts += it }
            } catch (_: ParseSyncException) {
                synchronize()
            }
            skipNewlines()
        }
        match(TokenType.DEDENT)
        return stmts
    }

    private fun consumeColonAndNewline(label: String) {
        consume(TokenType.COLON, "Expected ':' to open $label block.")
        consumeLineEnd()
    }

    private fun parseExpression(): Expr = parseOr()

    private fun parseOr(): Expr {
        var expr = parseAnd()
        while (match(TokenType.KW_OR)) {
            val right = parseAnd()
            expr = BinaryExpr(expr, BinaryOp.OR, right, expr.span)
        }
        return expr
    }

    private fun parseAnd(): Expr {
        var expr = parseNot()
        while (match(TokenType.KW_AND)) {
            val right = parseNot()
            expr = BinaryExpr(expr, BinaryOp.AND, right, expr.span)
        }
        return expr
    }

    private fun parseNot(): Expr {
        if (match(TokenType.KW_NOT)) {
            val opTok = previous()
            return UnaryExpr(UnaryOp.NOT, parseNot(), opTok.span)
        }
        return parseComparison()
    }

    private fun parseComparison(): Expr {
        var expr = parseAdditive()
        while (true) {
            val op = when (peek().type) {
                TokenType.EQ_EQ -> BinaryOp.EQ
                TokenType.BANG_EQ -> BinaryOp.NEQ
                TokenType.LT -> BinaryOp.LT
                TokenType.LT_EQ -> BinaryOp.LTE
                TokenType.GT -> BinaryOp.GT
                TokenType.GT_EQ -> BinaryOp.GTE
                TokenType.KW_IN -> BinaryOp.IN
                TokenType.KW_IS -> BinaryOp.IS
                else -> null
            } ?: break
            advance()
            val right = parseAdditive()
            expr = BinaryExpr(expr, op, right, expr.span)
        }
        return expr
    }

    private fun parseAdditive(): Expr {
        var expr = parseMultiplicative()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = if (advance().type == TokenType.PLUS) BinaryOp.ADD else BinaryOp.SUB
            val right = parseMultiplicative()
            expr = BinaryExpr(expr, op, right, expr.span)
        }
        return expr
    }

    private fun parseMultiplicative(): Expr {
        var expr = parsePower()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            val op = when (advance().type) {
                TokenType.STAR -> BinaryOp.MUL
                TokenType.SLASH -> BinaryOp.DIV
                else -> BinaryOp.MOD
            }
            val right = parsePower()
            expr = BinaryExpr(expr, op, right, expr.span)
        }
        return expr
    }

    private fun parsePower(): Expr {
        val left = parseUnary()
        if (match(TokenType.POWER)) {
            val right = parsePower()
            return BinaryExpr(left, BinaryOp.POW, right, left.span)
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (match(TokenType.MINUS)) {
            val opTok = previous()
            return UnaryExpr(UnaryOp.NEG, parseUnary(), opTok.span)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            expr = when {
                match(TokenType.LPAREN) -> {
                    val args = mutableListOf<ArgSpec>()
                    if (!check(TokenType.RPAREN)) {
                        do {
                            if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQ) {
                                val nameTok = advance()
                                advance() // '='
                                val v = parseExpression()
                                args += ArgSpec(nameTok.lexeme, v, nameTok.span)
                            } else {
                                val v = parseExpression()
                                args += ArgSpec(null, v, v.span)
                            }
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RPAREN, "Expected ')' after call arguments.")
                    CallExpr(expr, args, expr.span)
                }
                match(TokenType.DOT) -> {
                    val member = consumeIdentifier("Expected property or method name after '.'.").lexeme
                    MemberAccess(expr, member, expr.span)
                }
                match(TokenType.LBRACKET) -> {
                    val idx = parseExpression()
                    consume(TokenType.RBRACKET, "Expected ']' after index.")
                    IndexAccess(expr, idx, expr.span)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        return when (t.type) {
            TokenType.NUMBER -> { advance(); NumberLit((t.literal as? Double) ?: 0.0, t.span) }
            TokenType.TEXT -> { advance(); TextLit((t.literal as? String) ?: "", t.span) }
            TokenType.DURATION -> { advance(); DurationLit((t.literal as? Long) ?: 0L, t.lexeme, t.span) }
            TokenType.KW_TRUE -> { advance(); BoolLit(true, t.span) }
            TokenType.KW_FALSE -> { advance(); BoolLit(false, t.span) }
            TokenType.KW_NONE -> { advance(); NoneLit(t.span) }
            TokenType.IDENTIFIER, TokenType.KW_SCREEN -> { advance(); VarRef(t.lexeme, t.span) }
            TokenType.LPAREN -> {
                advance()
                val inner = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after grouped expression.")
                inner
            }
            TokenType.LBRACKET -> {
                advance()
                val items = mutableListOf<Expr>()
                if (!check(TokenType.RBRACKET)) {
                    do {
                        items += parseExpression()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RBRACKET, "Expected ']' after list literal.")
                ListLit(items, t.span)
            }
            TokenType.LBRACE -> {
                advance()
                val entries = mutableListOf<MapEntryLit>()
                if (!check(TokenType.RBRACE)) {
                    do {
                        val keyTok = advance()
                        val key = when {
                            keyTok.type == TokenType.TEXT -> (keyTok.literal as? String) ?: ""
                            keyTok.type == TokenType.IDENTIFIER -> keyTok.lexeme
                            keyTok.lexeme.isNotEmpty() && keyTok.lexeme[0].isLetter() -> keyTok.lexeme
                            else -> {
                                diagnostics += StudioDiagnostic(
                                    "PAR007",
                                    DiagnosticSeverity.ERROR,
                                    "Expected map key name or text.",
                                    keyTok.span
                                )
                                keyTok.lexeme
                            }
                        }
                        consume(TokenType.COLON, "Expected ':' after map key '$key'.")
                        val value = parseExpression()
                        entries += MapEntryLit(key, value, keyTok.span)
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RBRACE, "Expected '}' after map literal.")
                MapLit(entries, t.span)
            }
            else -> {
                val hint = if (t.lexeme == "elseif") "Did you mean 'elif'?" else null
                diagnostics += StudioDiagnostic(
                    "PAR001",
                    DiagnosticSeverity.ERROR,
                    "Unexpected token '${t.lexeme.ifEmpty { t.type.name }}'.",
                    t.span,
                    hint = hint
                )
                throw ParseSyncException()
            }
        }
    }

    private fun consumeIdentifier(message: String): Token {
        if (check(TokenType.IDENTIFIER)) return advance()
        // Allow contextual keywords as member names after '.'
        val cur = peek()
        if (cur.lexeme.isNotEmpty() && cur.lexeme[0].isLetter()) {
            return advance()
        }
        diagnostics += StudioDiagnostic("PAR002", DiagnosticSeverity.ERROR, message, cur.span)
        throw ParseSyncException()
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        val cur = peek()
        diagnostics += StudioDiagnostic("PAR003", DiagnosticSeverity.ERROR, message, cur.span)
        throw ParseSyncException()
    }

    private fun consumeLineEnd() {
        while (match(TokenType.NEWLINE)) { /* consume */ }
    }

    private fun skipNewlines() {
        while (check(TokenType.NEWLINE)) advance()
    }

    private fun synchronize() {
        while (!isAtEnd()) {
            if (peek().type == TokenType.NEWLINE) {
                advance()
                return
            }
            if (peek().type == TokenType.DEDENT) return
            advance()
        }
    }

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun check(type: TokenType): Boolean = !isAtEnd() && peek().type == type
    private fun peek(): Token = tokens.getOrElse(pos) { tokens.last() }
    private fun peekNext(): Token? = tokens.getOrNull(pos + 1)
    private fun previous(): Token = tokens[(pos - 1).coerceAtLeast(0)]
    private fun advance(): Token {
        if (!isAtEnd()) pos++
        return previous()
    }
    private fun isAtEnd(): Boolean = pos >= tokens.size || tokens[pos].type == TokenType.EOF
}
