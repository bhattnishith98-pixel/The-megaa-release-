package com.example.lang

/**
 * Recursive descent parser with error recovery and full Python + StudioLang syntax support.
 */
class StudioParser(rawTokens: List<Token>) {
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
        while (match(TokenType.SEMICOLON)) { /* skip empty semicolons */ }
        if (isAtEnd()) return null
        val t = peek()
        return when (t.type) {
            TokenType.KW_USE -> parseUse()
            TokenType.KW_IMPORT -> parseImport()
            TokenType.KW_FROM -> parseFromImport()
            TokenType.KW_NEEDS -> parseNeeds()
            TokenType.KW_LET -> parseVarDecl(isConst = false)
            TokenType.KW_CONST -> parseVarDecl(isConst = true)
            TokenType.KW_DEF -> parseFuncDef()
            TokenType.KW_CLASS -> parseClassDef()
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
            TokenType.KW_WITH -> parseWith()
            TokenType.KW_MATCH -> parseMatch()
            TokenType.KW_BREAK -> { advance(); consumeLineEnd(); BreakStmt(t.span) }
            TokenType.KW_CONTINUE -> { advance(); consumeLineEnd(); ContinueStmt(t.span) }
            TokenType.KW_PASS -> { advance(); consumeLineEnd(); PassStmt(t.span) }
            TokenType.KW_DEL -> parseDel()
            TokenType.KW_ASSERT -> parseAssert()
            TokenType.KW_GLOBAL -> parseGlobal()
            TokenType.KW_RETURN -> parseReturn()
            TokenType.KW_YIELD -> parseYield()
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

    private fun parseImport(): UseStmt {
        val kw = advance()
        val path = mutableListOf(consumeIdentifier("Expected module name after 'import'.").lexeme)
        while (match(TokenType.DOT)) {
            path += consumeIdentifier("Expected submodule name after '.'.").lexeme
        }
        val alias = if (match(TokenType.KW_AS)) {
            consumeIdentifier("Expected alias after 'as'.").lexeme
        } else null
        // Drain any additional comma-separated imports cleanly onto first UseStmt
        while (match(TokenType.COMMA)) {
            consumeIdentifier("Expected module name.")
            while (match(TokenType.DOT)) consumeIdentifier("Expected submodule name.")
            if (match(TokenType.KW_AS)) consumeIdentifier("Expected alias.")
        }
        consumeLineEnd()
        return UseStmt(path, alias, kw.span)
    }

    private fun parseFromImport(): UseStmt {
        val kw = advance()
        val path = mutableListOf(consumeIdentifier("Expected module name after 'from'.").lexeme)
        while (match(TokenType.DOT)) {
            path += consumeIdentifier("Expected submodule name.").lexeme
        }
        consume(TokenType.KW_IMPORT, "Expected 'import' in from-import statement.")
        val names = mutableListOf<Pair<String, String?>>()
        if (match(TokenType.STAR)) {
            names += "*" to null
        } else {
            val hasParens = match(TokenType.LPAREN)
            do {
                val n = consumeIdentifier("Expected imported symbol name.").lexeme
                val a = if (match(TokenType.KW_AS)) consumeIdentifier("Expected alias.").lexeme else null
                names += n to a
            } while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
            if (hasParens) consume(TokenType.RPAREN, "Expected ')' in import list.")
        }
        consumeLineEnd()
        return UseStmt(path, alias = null, span = kw.span, importedNames = names)
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

    private fun parseClassDef(): ClassDefStmt {
        val kw = advance()
        val name = consumeIdentifier("Expected class name after 'class'.").lexeme
        var baseClass: String? = null
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                baseClass = consumeIdentifier("Expected base class name.").lexeme
                while (match(TokenType.DOT)) {
                    baseClass += ".${consumeIdentifier("Expected base class identifier.").lexeme}"
                }
                while (match(TokenType.COMMA)) {
                    consumeIdentifier("Expected base class.")
                }
            }
            consume(TokenType.RPAREN, "Expected ')' after base class list.")
        }
        val body = parseIndentedBlock("class '$name'")
        return ClassDefStmt(name, baseClass, body, kw.span)
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
                    advance()
                    advance()
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
        val targets = mutableListOf<String>()
        val hasParen = match(TokenType.LPAREN)
        targets += consumeIdentifier("Expected loop variable after 'for'.").lexeme
        while (match(TokenType.COMMA)) {
            if (check(TokenType.RPAREN)) break
            targets += consumeIdentifier("Expected loop variable name after ','.").lexeme
        }
        if (hasParen) consume(TokenType.RPAREN, "Expected ')' after for targets.")
        consume(TokenType.KW_IN, "Expected 'in' in for loop.")
        val iterable = parseExpression()
        val body = parseIndentedBlock("for")
        return ForStmt(targets.first(), iterable, body, kw.span, targets = targets)
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

    private fun parseWith(): WithStmt {
        val kw = advance()
        val ctx = parseExpression()
        val alias = if (match(TokenType.KW_AS)) {
            consumeIdentifier("Expected variable name after 'as'.").lexeme
        } else null
        val body = parseIndentedBlock("with")
        return WithStmt(ctx, alias, body, kw.span)
    }

    private fun parseMatch(): MatchStmt {
        val kw = advance()
        val subject = parseExpression()
        consumeColonAndNewline("match")
        val cases = mutableListOf<MatchCase>()
        if (match(TokenType.INDENT)) {
            skipNewlines()
            while (!check(TokenType.DEDENT) && !isAtEnd()) {
                if (match(TokenType.KW_CASE)) {
                    val caseTok = previous()
                    val pattern: Expr? = if (check(TokenType.IDENTIFIER) && peek().lexeme == "_") {
                        advance()
                        null
                    } else {
                        parseExpression()
                    }
                    val guard = if (match(TokenType.KW_IF)) parseExpression() else null
                    val body = parseIndentedBlock("case")
                    cases += MatchCase(pattern, guard, body, caseTok.span)
                } else {
                    advance()
                }
                skipNewlines()
            }
            match(TokenType.DEDENT)
        }
        return MatchStmt(subject, cases, kw.span)
    }

    private fun parseDel(): DelStmt {
        val kw = advance()
        val target = parsePostfix()
        consumeLineEnd()
        return DelStmt(target, kw.span)
    }

    private fun parseAssert(): AssertStmt {
        val kw = advance()
        val cond = parseExpression()
        val msg = if (match(TokenType.COMMA)) parseExpression() else null
        consumeLineEnd()
        return AssertStmt(cond, msg, kw.span)
    }

    private fun parseGlobal(): GlobalDeclStmt {
        val kw = advance()
        val names = mutableListOf(consumeIdentifier("Expected variable name after 'global'.").lexeme)
        while (match(TokenType.COMMA)) {
            names += consumeIdentifier("Expected variable name after ','.").lexeme
        }
        consumeLineEnd()
        return GlobalDeclStmt(names, kw.span)
    }

    private fun parseReturn(): ReturnStmt {
        val kw = advance()
        val value = if (!isAtStmtEnd()) parseTupleOrExpr() else null
        consumeLineEnd()
        return ReturnStmt(value, kw.span)
    }

    private fun parseYield(): YieldStmt {
        val kw = advance()
        val value = if (!isAtStmtEnd()) parseTupleOrExpr() else null
        consumeLineEnd()
        return YieldStmt(value, kw.span)
    }

    private fun parseTry(): TryStmt {
        val kw = advance()
        val tryBody = parseIndentedBlock("try")
        skipNewlines()
        var errorVar: String? = null
        var exceptType: String? = null
        val catchBody = mutableListOf<Stmt>()

        while (check(TokenType.KW_EXCEPT) || (check(TokenType.KW_ON) && peekNext()?.type == TokenType.KW_ERROR)) {
            if (match(TokenType.KW_ON)) {
                consume(TokenType.KW_ERROR, "Expected 'error' after 'on' in try block.")
                errorVar = if (check(TokenType.IDENTIFIER)) advance().lexeme else "err"
                catchBody += parseIndentedBlock("on error")
            } else {
                advance() // except
                if (!check(TokenType.COLON)) {
                    exceptType = consumeIdentifier("Expected exception type in except.").lexeme
                    if (match(TokenType.KW_AS)) {
                        errorVar = consumeIdentifier("Expected variable name after 'as'.").lexeme
                    }
                }
                catchBody += parseIndentedBlock("except")
            }
            skipNewlines()
        }

        val finallyBody = if (match(TokenType.KW_FINALLY)) {
            parseIndentedBlock("finally")
        } else emptyList()

        if (catchBody.isEmpty() && finallyBody.isEmpty()) {
            diagnostics += StudioDiagnostic(
                "PAR005",
                DiagnosticSeverity.ERROR,
                "A 'try:' block must be followed by 'except:', 'on error e:', or 'finally:'.",
                kw.span,
                hint = "Add 'except Exception as e:' or 'on error e:' at the same indentation as 'try:'."
            )
        }
        return TryStmt(tryBody, errorVar, catchBody, kw.span, finallyBody = finallyBody, exceptType = exceptType)
    }

    private fun parseRaise(): RaiseStmt {
        val kw = advance()
        val msg = if (!isAtStmtEnd()) parseExpression() else TextLit("Error", kw.span)
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
        val first = parseExpression()

        // Annotated assignment: x: number = 10
        if (first is VarRef && match(TokenType.COLON)) {
            val hint = parseTypeHint()
            if (match(TokenType.EQ)) {
                val value = parseTupleOrExpr()
                consumeLineEnd()
                return VarDeclStmt(first.name, isConst = false, typeHint = hint, initializer = value, span = first.span)
            }
            consumeLineEnd()
            return VarDeclStmt(first.name, isConst = false, typeHint = hint, initializer = NoneLit(first.span), span = first.span)
        }

        // Comma-separated unpacking assignment: a, b = pair  OR  tuple expression
        if (match(TokenType.COMMA)) {
            val exprs = mutableListOf(first)
            if (!isAtStmtEnd() && !check(TokenType.EQ)) {
                do {
                    if (isAtStmtEnd() || check(TokenType.EQ)) break
                    exprs += parseExpression()
                } while (match(TokenType.COMMA))
            }
            if (match(TokenType.EQ)) {
                val rhs = parseTupleOrExpr()
                consumeLineEnd()
                val targets = exprs.map {
                    if (it is VarRef) it.name else {
                        diagnostics += StudioDiagnostic("PAR008", DiagnosticSeverity.ERROR, "Invalid unpacking target.", it.span)
                        "_"
                    }
                }
                return UnpackAssignStmt(targets, rhs, first.span)
            } else {
                consumeLineEnd()
                return ExprStmt(TupleLit(exprs, first.span), first.span)
            }
        }

        // Unpacking when wrapped in parentheses: (a, b) = pair
        val assignOp = when (peek().type) {
            TokenType.EQ -> AssignOp.SET
            TokenType.PLUS_EQ -> AssignOp.ADD
            TokenType.MINUS_EQ -> AssignOp.SUB
            TokenType.STAR_EQ -> AssignOp.MUL
            TokenType.SLASH_EQ -> AssignOp.DIV
            TokenType.PERCENT_EQ -> AssignOp.MOD
            TokenType.FLOOR_DIV_EQ -> AssignOp.FLOOR_DIV
            TokenType.POWER_EQ -> AssignOp.POW
            else -> null
        }

        return if (assignOp != null) {
            advance()
            val value = parseTupleOrExpr()
            consumeLineEnd()
            if (assignOp == AssignOp.SET && first is TupleLit && first.elements.all { it is VarRef }) {
                UnpackAssignStmt(first.elements.map { (it as VarRef).name }, value, first.span)
            } else {
                AssignStmt(first, assignOp, value, first.span)
            }
        } else {
            consumeLineEnd()
            ExprStmt(first, first.span)
        }
    }

    private fun parseTupleOrExpr(): Expr {
        val first = parseExpression()
        if (!check(TokenType.COMMA)) return first
        val items = mutableListOf(first)
        while (match(TokenType.COMMA)) {
            if (isAtStmtEnd()) break
            items += parseExpression()
        }
        return TupleLit(items, first.span)
    }

    private fun parseParamList(): List<ParamSpec> {
        consume(TokenType.LPAREN, "Expected '(' before parameter list.")
        val params = mutableListOf<ParamSpec>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break
                var isVarArg = false
                var isKwArg = false
                if (match(TokenType.POWER)) {
                    isKwArg = true
                } else if (match(TokenType.STAR)) {
                    isVarArg = true
                }
                val idTok = consumeIdentifier("Expected parameter name.")
                val typeHint = if (match(TokenType.COLON)) parseTypeHint() else null
                val defaultVal = if (match(TokenType.EQ)) parseExpression() else null
                params += ParamSpec(idTok.lexeme, typeHint, defaultVal, idTok.span, isVarArg = isVarArg, isKwArg = isKwArg)
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameter list.")
        return params
    }

    private fun parseTypeHint(): StudioType {
        val tok = advance()
        val normalized = when (tok.lexeme.lowercase()) {
            "int", "float", "double" -> "number"
            "str", "string" -> "text"
            "dict" -> "map"
            else -> tok.lexeme
        }
        val resolved = StudioType.fromLabel(normalized)
        if (resolved == null) {
            // Do not spam errors on custom class type annotations
            return StudioType.ANY
        }
        return resolved
    }

    private fun parseIndentedBlock(label: String): List<Stmt> {
        consume(TokenType.COLON, "Expected ':' to open $label block.")
        // Allow Python single-line suite e.g. `if x > 0: return x` or `def f(x): return x + 1`
        if (!check(TokenType.NEWLINE) && !check(TokenType.EOF)) {
            val inlineStmts = mutableListOf<Stmt>()
            parseStatement()?.let { inlineStmts += it }
            return inlineStmts
        }
        consumeLineEnd()
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

    private fun parseExpression(): Expr = parseLambda()

    private fun parseLambda(): Expr {
        if (match(TokenType.KW_LAMBDA)) {
            val kw = previous()
            val params = mutableListOf<ParamSpec>()
            if (!check(TokenType.COLON)) {
                do {
                    var isVarArg = false
                    var isKwArg = false
                    if (match(TokenType.POWER)) isKwArg = true
                    else if (match(TokenType.STAR)) isVarArg = true
                    val idTok = consumeIdentifier("Expected lambda parameter name.")
                    val defaultVal = if (match(TokenType.EQ)) parseTernary() else null
                    params += ParamSpec(idTok.lexeme, null, defaultVal, idTok.span, isVarArg = isVarArg, isKwArg = isKwArg)
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.COLON, "Expected ':' in lambda expression.")
            val body = parseExpression()
            return LambdaExpr(params, body, kw.span)
        }
        return parseTernary()
    }

    private fun parseTernary(): Expr {
        val trueExpr = parseOr()
        if (match(TokenType.KW_IF)) {
            val cond = parseOr()
            consume(TokenType.KW_ELSE, "Expected 'else' in conditional expression.")
            val falseExpr = parseExpression()
            return TernaryExpr(trueExpr, cond, falseExpr, trueExpr.span)
        }
        return trueExpr
    }

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
        var expr = parseBitOr()
        while (true) {
            val op = when {
                check(TokenType.EQ_EQ) -> { advance(); BinaryOp.EQ }
                check(TokenType.BANG_EQ) -> { advance(); BinaryOp.NEQ }
                check(TokenType.LT) -> { advance(); BinaryOp.LT }
                check(TokenType.LT_EQ) -> { advance(); BinaryOp.LTE }
                check(TokenType.GT) -> { advance(); BinaryOp.GT }
                check(TokenType.GT_EQ) -> { advance(); BinaryOp.GTE }
                check(TokenType.KW_IN) -> { advance(); BinaryOp.IN }
                check(TokenType.KW_NOT) && peekNext()?.type == TokenType.KW_IN -> { advance(); advance(); BinaryOp.NOT_IN }
                check(TokenType.KW_IS) && peekNext()?.type == TokenType.KW_NOT -> { advance(); advance(); BinaryOp.IS_NOT }
                check(TokenType.KW_IS) -> { advance(); BinaryOp.IS }
                else -> null
            } ?: break
            val right = parseBitOr()
            expr = BinaryExpr(expr, op, right, expr.span)
        }
        return expr
    }

    private fun parseBitOr(): Expr {
        var expr = parseBitXor()
        while (match(TokenType.PIPE)) {
            expr = BinaryExpr(expr, BinaryOp.BIT_OR, parseBitXor(), expr.span)
        }
        return expr
    }

    private fun parseBitXor(): Expr {
        var expr = parseBitAnd()
        while (match(TokenType.CARET)) {
            expr = BinaryExpr(expr, BinaryOp.BIT_XOR, parseBitAnd(), expr.span)
        }
        return expr
    }

    private fun parseBitAnd(): Expr {
        var expr = parseShift()
        while (match(TokenType.AMP)) {
            expr = BinaryExpr(expr, BinaryOp.BIT_AND, parseShift(), expr.span)
        }
        return expr
    }

    private fun parseShift(): Expr {
        var expr = parseAdditive()
        while (check(TokenType.SHL) || check(TokenType.SHR)) {
            val op = if (advance().type == TokenType.SHL) BinaryOp.SHL else BinaryOp.SHR
            expr = BinaryExpr(expr, op, parseAdditive(), expr.span)
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
        var expr = parseUnary()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.FLOOR_DIV) || check(TokenType.PERCENT)) {
            val op = when (advance().type) {
                TokenType.STAR -> BinaryOp.MUL
                TokenType.SLASH -> BinaryOp.DIV
                TokenType.FLOOR_DIV -> BinaryOp.FLOOR_DIV
                else -> BinaryOp.MOD
            }
            val right = parseUnary()
            expr = BinaryExpr(expr, op, right, expr.span)
        }
        return expr
    }

    private fun parseUnary(): Expr {
        if (match(TokenType.MINUS)) {
            val opTok = previous()
            return UnaryExpr(UnaryOp.NEG, parseUnary(), opTok.span)
        }
        if (match(TokenType.PLUS)) {
            return parseUnary()
        }
        if (match(TokenType.TILDE)) {
            val opTok = previous()
            return UnaryExpr(UnaryOp.BIT_NOT, parseUnary(), opTok.span)
        }
        return parsePower()
    }

    private fun parsePower(): Expr {
        val left = parsePostfix()
        if (match(TokenType.POWER)) {
            val right = parseUnary()
            return BinaryExpr(left, BinaryOp.POW, right, left.span)
        }
        return left
    }

    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            expr = when {
                match(TokenType.LPAREN) -> {
                    val args = mutableListOf<ArgSpec>()
                    if (!check(TokenType.RPAREN)) {
                        do {
                            if (check(TokenType.RPAREN)) break
                            if (match(TokenType.POWER)) {
                                val v = parseExpression()
                                args += ArgSpec(null, v, v.span, isKwSpread = true)
                            } else if (match(TokenType.STAR)) {
                                val v = parseExpression()
                                args += ArgSpec(null, v, v.span, isSpread = true)
                            } else if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQ) {
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
                    if (check(TokenType.COLON)) {
                        // [:stop] or [::step]
                        advance() // ':'
                        val stop = if (!check(TokenType.COLON) && !check(TokenType.RBRACKET)) parseExpression() else null
                        val step = if (match(TokenType.COLON) && !check(TokenType.RBRACKET)) parseExpression() else null
                        consume(TokenType.RBRACKET, "Expected ']' after slice.")
                        SliceAccess(expr, null, stop, step, expr.span)
                    } else {
                        val first = parseExpression()
                        if (match(TokenType.COLON)) {
                            val stop = if (!check(TokenType.COLON) && !check(TokenType.RBRACKET)) parseExpression() else null
                            val step = if (match(TokenType.COLON) && !check(TokenType.RBRACKET)) parseExpression() else null
                            consume(TokenType.RBRACKET, "Expected ']' after slice.")
                            SliceAccess(expr, first, stop, step, expr.span)
                        } else {
                            consume(TokenType.RBRACKET, "Expected ']' after index.")
                            IndexAccess(expr, first, expr.span)
                        }
                    }
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
            TokenType.F_STRING -> {
                advance()
                desugarFString((t.literal as? String) ?: "", t.span)
            }
            TokenType.DURATION -> { advance(); DurationLit((t.literal as? Long) ?: 0L, t.lexeme, t.span) }
            TokenType.KW_TRUE -> { advance(); BoolLit(true, t.span) }
            TokenType.KW_FALSE -> { advance(); BoolLit(false, t.span) }
            TokenType.KW_NONE -> { advance(); NoneLit(t.span) }
            TokenType.IDENTIFIER, TokenType.KW_SCREEN -> { advance(); VarRef(t.lexeme, t.span) }
            TokenType.LPAREN -> {
                advance()
                if (match(TokenType.RPAREN)) {
                    TupleLit(emptyList(), t.span)
                } else {
                    val first = parseExpression()
                    if (match(TokenType.COMMA)) {
                        val items = mutableListOf(first)
                        while (!check(TokenType.RPAREN) && !isAtEnd()) {
                            items += parseExpression()
                            if (!match(TokenType.COMMA)) break
                        }
                        consume(TokenType.RPAREN, "Expected ')' after tuple literal.")
                        TupleLit(items, t.span)
                    } else {
                        consume(TokenType.RPAREN, "Expected ')' after grouped expression.")
                        first
                    }
                }
            }
            TokenType.LBRACKET -> {
                advance()
                if (match(TokenType.RBRACKET)) {
                    ListLit(emptyList(), t.span)
                } else {
                    val first = parseExpression()
                    if (match(TokenType.KW_FOR)) {
                        val (vars, iterable, filter) = parseComprehensionTail()
                        consume(TokenType.RBRACKET, "Expected ']' after list comprehension.")
                        ListCompExpr(first, vars, iterable, filter, t.span)
                    } else {
                        val items = mutableListOf(first)
                        while (match(TokenType.COMMA)) {
                            if (check(TokenType.RBRACKET)) break
                            items += parseExpression()
                        }
                        consume(TokenType.RBRACKET, "Expected ']' after list literal.")
                        ListLit(items, t.span)
                    }
                }
            }
            TokenType.LBRACE -> {
                advance()
                if (match(TokenType.RBRACE)) {
                    MapLit(emptyList(), t.span)
                } else {
                    val first = parseExpression()
                    when {
                        match(TokenType.KW_FOR) -> {
                            val (vars, iterable, filter) = parseComprehensionTail()
                            consume(TokenType.RBRACE, "Expected '}' after set comprehension.")
                            SetCompExpr(first, vars, iterable, filter, t.span)
                        }
                        match(TokenType.COLON) -> {
                            val firstVal = parseExpression()
                            if (match(TokenType.KW_FOR)) {
                                val (vars, iterable, filter) = parseComprehensionTail()
                                consume(TokenType.RBRACE, "Expected '}' after dict comprehension.")
                                DictCompExpr(first, firstVal, vars, iterable, filter, t.span)
                            } else {
                                val entries = mutableListOf(entryFromExpr(first, firstVal, first.span))
                                while (match(TokenType.COMMA)) {
                                    if (check(TokenType.RBRACE)) break
                                    val kExpr = parseExpression()
                                    consume(TokenType.COLON, "Expected ':' in dict entry.")
                                    val vExpr = parseExpression()
                                    entries += entryFromExpr(kExpr, vExpr, kExpr.span)
                                }
                                consume(TokenType.RBRACE, "Expected '}' after map literal.")
                                MapLit(entries, t.span)
                            }
                        }
                        else -> {
                            val elems = mutableListOf(first)
                            while (match(TokenType.COMMA)) {
                                if (check(TokenType.RBRACE)) break
                                elems += parseExpression()
                            }
                            consume(TokenType.RBRACE, "Expected '}' after set literal.")
                            SetLit(elems, t.span)
                        }
                    }
                }
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

    private fun entryFromExpr(keyExpr: Expr, valExpr: Expr, span: SourceSpan): MapEntryLit {
        val staticKey = when (keyExpr) {
            is TextLit -> keyExpr.value
            is VarRef -> keyExpr.name
            is NumberLit -> if (keyExpr.value % 1.0 == 0.0) keyExpr.value.toLong().toString() else keyExpr.value.toString()
            else -> ""
        }
        val dynamic = if (keyExpr is TextLit || keyExpr is VarRef || keyExpr is NumberLit) null else keyExpr
        return MapEntryLit(staticKey, valExpr, span, keyExpr = dynamic)
    }

    private fun parseComprehensionTail(): Triple<List<String>, Expr, Expr?> {
        val vars = mutableListOf<String>()
        val hasParen = match(TokenType.LPAREN)
        vars += consumeIdentifier("Expected loop variable in comprehension.").lexeme
        while (match(TokenType.COMMA)) {
            if (check(TokenType.RPAREN)) break
            vars += consumeIdentifier("Expected variable name after ','.").lexeme
        }
        if (hasParen) consume(TokenType.RPAREN, "Expected ')' after comprehension targets.")
        consume(TokenType.KW_IN, "Expected 'in' in comprehension.")
        val iterable = parseOr()
        val filter = if (match(TokenType.KW_IF)) parseOr() else null
        return Triple(vars, iterable, filter)
    }

    private fun desugarFString(template: String, span: SourceSpan): Expr {
        val parts = mutableListOf<Expr>()
        var i = 0
        val literalBuf = StringBuilder()
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                if (i + 1 < template.length && template[i + 1] == '{') {
                    literalBuf.append('{')
                    i += 2
                    continue
                }
                if (literalBuf.isNotEmpty()) {
                    parts += TextLit(literalBuf.toString(), span)
                    literalBuf.clear()
                }
                val close = findMatchingBrace(template, i + 1)
                if (close == -1) {
                    literalBuf.append(template.substring(i))
                    break
                }
                val innerRaw = template.substring(i + 1, close)
                // Support optional format spec like {val:.2f} by splitting top-level ':'
                val colonIdx = findTopLevelColon(innerRaw)
                val exprCode = if (colonIdx != -1) innerRaw.substring(0, colonIdx).trim() else innerRaw.trim()
                val fmtSpec = if (colonIdx != -1) innerRaw.substring(colonIdx + 1).trim() else null
                val subLex = StudioLexer(exprCode).tokenize()
                val subParser = StudioParser(subLex.tokens)
                val parsed = try {
                    subParser.parseExpression()
                } catch (_: Exception) {
                    TextLit("{$exprCode}", span)
                }
                val wrapped = if (!fmtSpec.isNullOrEmpty()) {
                    CallExpr(VarRef("py.format_val", span), listOf(ArgSpec(null, parsed, span), ArgSpec(null, TextLit(fmtSpec, span), span)), span)
                } else {
                    CallExpr(VarRef("str", span), listOf(ArgSpec(null, parsed, span)), span)
                }
                parts += wrapped
                i = close + 1
            } else if (c == '}' && i + 1 < template.length && template[i + 1] == '}') {
                literalBuf.append('}')
                i += 2
            } else {
                literalBuf.append(c)
                i++
            }
        }
        if (literalBuf.isNotEmpty() || parts.isEmpty()) {
            parts += TextLit(literalBuf.toString(), span)
        }
        var acc = parts.first()
        for (idx in 1 until parts.size) {
            acc = BinaryExpr(acc, BinaryOp.ADD, parts[idx], span)
        }
        return acc
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findTopLevelColon(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun isAtStmtEnd(): Boolean =
        check(TokenType.NEWLINE) || check(TokenType.EOF) || check(TokenType.DEDENT) || check(TokenType.SEMICOLON)

    private fun consumeIdentifier(message: String): Token {
        if (check(TokenType.IDENTIFIER)) return advance()
        val cur = peek()
        if (cur.lexeme.isNotEmpty() && (cur.lexeme[0].isLetter() || cur.lexeme[0] == '_')) {
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
        while (match(TokenType.NEWLINE) || match(TokenType.SEMICOLON)) { /* consume */ }
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
