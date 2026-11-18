package com.example.vm

import com.example.lang.*

sealed interface IrOp {
    val line: Int
}

data class IrLabel(val id: Int, override val line: Int) : IrOp
data class IrLoadConst(val value: StudioValue, override val line: Int) : IrOp
data class IrLoadLocal(val slot: Int, val name: String, override val line: Int) : IrOp
data class IrStoreLocal(val slot: Int, val name: String, override val line: Int) : IrOp
data class IrLoadGlobal(val name: String, override val line: Int) : IrOp
data class IrStoreGlobal(val name: String, override val line: Int) : IrOp
data class IrUnary(val op: UnaryOp, override val line: Int) : IrOp
data class IrBinary(val op: BinaryOp, override val line: Int) : IrOp
data class IrMakeList(val count: Int, override val line: Int) : IrOp
data class IrMakeMap(val entryCount: Int, override val line: Int) : IrOp
data class IrGetMember(val member: String, override val line: Int) : IrOp
data class IrSetMember(val member: String, override val line: Int) : IrOp
data class IrGetIndex(override val line: Int) : IrOp
data class IrSetIndex(override val line: Int) : IrOp
data class IrJump(val targetLabel: Int, override val line: Int) : IrOp
data class IrJumpIfFalse(val targetLabel: Int, override val line: Int) : IrOp
data class IrJumpIfTrue(val targetLabel: Int, override val line: Int) : IrOp
data class IrPop(override val line: Int) : IrOp
data class IrDup(override val line: Int) : IrOp
data class IrCallFunc(val funcIndex: Int, val argCount: Int, override val line: Int) : IrOp
data class IrCallHost(val qualifiedName: String, val argCount: Int, override val line: Int) : IrOp
data class IrReturn(val hasValue: Boolean, override val line: Int) : IrOp
data class IrTryBegin(val catchLabel: Int, override val line: Int) : IrOp
data class IrTryEnd(override val line: Int) : IrOp
data class IrStoreException(val slot: Int, override val line: Int) : IrOp
data class IrRaise(override val line: Int) : IrOp
data class IrConfirm(override val line: Int) : IrOp
data class IrSleep(override val line: Int) : IrOp
data class IrTimeoutBegin(override val line: Int) : IrOp
data class IrTimeoutEnd(override val line: Int) : IrOp

enum class CallableKind {
    MAIN,
    FUNC,
    ACTION,
    BLOCK,
    CONDITION,
    CONSTRAINT,
    TRIGGER,
    MACRO_DO,
    MACRO_ONLY_IF,
    MACRO_ERROR
}

data class IrFunction(
    val index: Int,
    val name: String,
    val kind: CallableKind,
    val params: List<String>,
    val localNames: List<String>,
    val ops: List<IrOp>,
    val requiredNeeds: Set<StudioCapability> = emptySet()
)

data class IrMacroDef(
    val name: String,
    val triggerText: String,
    val onlyIfFuncIndex: Int?,
    val doFuncIndex: Int,
    val errorFuncIndex: Int?,
    val errorVarSlot: Int,
    val needs: Set<StudioCapability>
)

data class IrTriggerDef(
    val name: String,
    val sourceText: String,
    val whenFuncIndex: Int?,
    val emitFuncIndex: Int?,
    val needs: Set<StudioCapability>
)

data class IrModule(
    val functions: List<IrFunction>,
    val macros: List<IrMacroDef>,
    val triggers: List<IrTriggerDef>,
    val requiredCapabilities: Set<StudioCapability>
)

/**
 * Generates linear stack-oriented IR from a validated StudioLang AST.
 */
class IrGenerator {
    private var nextLabelId = 1
    private val functions = mutableListOf<IrFunction>()
    private val funcIndexByName = mutableMapOf<String, Int>()
    private val macros = mutableListOf<IrMacroDef>()
    private val triggers = mutableListOf<IrTriggerDef>()
    private val allNeeds = linkedSetOf<StudioCapability>()

    fun generate(program: ProgramNode, analysis: StudioAnalyzer.AnalysisResult): IrModule {
        allNeeds += analysis.requiredCapabilities

        // Reserve function indices: 0 = __main__
        funcIndexByName.clear()
        functions.clear()
        macros.clear()
        triggers.clear()

        var nextIdx = 1
        for (stmt in program.statements) {
            when (stmt) {
                is FuncDefStmt -> funcIndexByName[stmt.name] = nextIdx++
                is ActionDefStmt -> funcIndexByName[stmt.name] = nextIdx++
                is BlockDefStmt -> funcIndexByName[stmt.name] = nextIdx++
                is ConditionDefStmt -> funcIndexByName[stmt.name] = nextIdx++
                is ConstraintDefStmt -> funcIndexByName[stmt.name] = nextIdx++
                else -> Unit
            }
        }

        val mainBuilder = FunctionBuilder(0, "__main__", CallableKind.MAIN, emptyList())
        for (stmt in program.statements) {
            when (stmt) {
                is FuncDefStmt -> compileCallable(
                    funcIndexByName.getValue(stmt.name), stmt.name, CallableKind.FUNC,
                    stmt.params, stmt.body, emptySet()
                )
                is ActionDefStmt -> compileCallable(
                    funcIndexByName.getValue(stmt.name), stmt.name, CallableKind.ACTION,
                    stmt.params, stmt.body, stmt.needs.mapNotNull { StudioCapability.fromId(it) }.toSet()
                )
                is BlockDefStmt -> compileCallable(
                    funcIndexByName.getValue(stmt.name), stmt.name, CallableKind.BLOCK,
                    stmt.params, stmt.body, stmt.needs.mapNotNull { StudioCapability.fromId(it) }.toSet()
                )
                is ConditionDefStmt -> compileCallable(
                    funcIndexByName.getValue(stmt.name), stmt.name, CallableKind.CONDITION,
                    stmt.params, stmt.body, emptySet()
                )
                is ConstraintDefStmt -> compileCallable(
                    funcIndexByName.getValue(stmt.name), stmt.name, CallableKind.CONSTRAINT,
                    stmt.params, stmt.body, emptySet()
                )
                is TriggerDefStmt -> compileTrigger(stmt)
                is MacroDefStmt -> compileMacro(stmt)
                is AppDefStmt, is ScreenDefStmt -> {
                    // App & Screen metadata are compiled by AppModelExtractor; record top-level marker
                    mainBuilder.emit(IrLoadConst(TextVal("app:${stmt.span.line}"), stmt.span.line))
                    mainBuilder.emit(IrPop(stmt.span.line))
                }
                else -> mainBuilder.emitStmt(stmt)
            }
        }
        mainBuilder.emit(IrLoadConst(NoneVal, 1))
        mainBuilder.emit(IrReturn(hasValue = true, line = 1))

        val allFuncs = listOf(mainBuilder.build()) + functions.sortedBy { it.index }
        return IrModule(
            functions = allFuncs,
            macros = macros.toList(),
            triggers = triggers.toList(),
            requiredCapabilities = allNeeds.toSet()
        )
    }

    private fun compileCallable(
        idx: Int,
        name: String,
        kind: CallableKind,
        params: List<ParamSpec>,
        body: List<Stmt>,
        needs: Set<StudioCapability>
    ) {
        val fb = FunctionBuilder(idx, name, kind, params.map { it.name }, needs)
        // Assign default parameter values if passed none
        params.forEachIndexed { pIdx, p ->
            if (p.defaultValue != null) {
                val skipLbl = newLabel()
                fb.emit(IrLoadLocal(pIdx, p.name, p.span.line))
                fb.emit(IrLoadConst(NoneVal, p.span.line))
                fb.emit(IrBinary(BinaryOp.NEQ, p.span.line))
                fb.emit(IrJumpIfTrue(skipLbl, p.span.line))
                fb.emitExpr(p.defaultValue)
                fb.emit(IrStoreLocal(pIdx, p.name, p.span.line))
                fb.emit(IrLabel(skipLbl, p.span.line))
            }
        }
        body.forEach { fb.emitStmt(it) }
        fb.emit(IrLoadConst(NoneVal, body.lastOrNull()?.span?.line ?: 1))
        fb.emit(IrReturn(hasValue = true, line = body.lastOrNull()?.span?.line ?: 1))
        functions += fb.build()
    }

    private fun compileTrigger(stmt: TriggerDefStmt) {
        val needs = stmt.needs.mapNotNull { StudioCapability.fromId(it) }.toSet()
        allNeeds += needs
        val whenIdx = stmt.whenExpr?.let { expr ->
            val idx = functions.size + funcIndexByName.size + 1
            val fb = FunctionBuilder(idx, "${stmt.name}__when", CallableKind.TRIGGER, listOf("event"), needs)
            fb.emitExpr(expr)
            fb.emit(IrReturn(hasValue = true, line = expr.span.line))
            functions += fb.build()
            idx
        }
        val emitIdx = stmt.emitExpr?.let { expr ->
            val idx = functions.size + funcIndexByName.size + 1
            val fb = FunctionBuilder(idx, "${stmt.name}__emit", CallableKind.TRIGGER, listOf("event"), needs)
            fb.emitExpr(expr)
            fb.emit(IrReturn(hasValue = true, line = expr.span.line))
            functions += fb.build()
            idx
        }
        triggers += IrTriggerDef(
            name = stmt.name,
            sourceText = stmt.sourceExpr?.let { StudioFormatter.formatExpr(it) } ?: "manual",
            whenFuncIndex = whenIdx,
            emitFuncIndex = emitIdx,
            needs = needs
        )
    }

    private fun compileMacro(stmt: MacroDefStmt) {
        val needs = stmt.needs.mapNotNull { StudioCapability.fromId(it) }.toSet()
        allNeeds += needs
        val onlyIfIdx = stmt.onlyIf?.let { cond ->
            val idx = functions.size + funcIndexByName.size + 1
            val fb = FunctionBuilder(idx, "${stmt.name}__only_if", CallableKind.MACRO_ONLY_IF, listOf("event"), needs)
            fb.emitExpr(cond)
            fb.emit(IrReturn(hasValue = true, line = cond.span.line))
            functions += fb.build()
            idx
        }
        val doIdx = functions.size + funcIndexByName.size + 1
        val doFb = FunctionBuilder(doIdx, "${stmt.name}__do", CallableKind.MACRO_DO, listOf("event"), needs)
        stmt.doBody.forEach { doFb.emitStmt(it) }
        doFb.emit(IrLoadConst(NoneVal, stmt.span.line))
        doFb.emit(IrReturn(hasValue = true, line = stmt.span.line))
        functions += doFb.build()

        var errorSlot = 0
        val errorIdx = if (stmt.errorBody.isNotEmpty()) {
            val idx = functions.size + funcIndexByName.size + 1
            val errName = stmt.errorVar ?: "e"
            val errFb = FunctionBuilder(idx, "${stmt.name}__on_error", CallableKind.MACRO_ERROR, listOf(errName), needs)
            errorSlot = 0
            stmt.errorBody.forEach { errFb.emitStmt(it) }
            errFb.emit(IrLoadConst(NoneVal, stmt.span.line))
            errFb.emit(IrReturn(hasValue = true, line = stmt.span.line))
            functions += errFb.build()
            idx
        } else null

        macros += IrMacroDef(
            name = stmt.name,
            triggerText = stmt.onTrigger?.let { StudioFormatter.formatExpr(it) } ?: "manual",
            onlyIfFuncIndex = onlyIfIdx,
            doFuncIndex = doIdx,
            errorFuncIndex = errorIdx,
            errorVarSlot = errorSlot,
            needs = needs
        )
    }

    private fun newLabel(): Int = nextLabelId++

    private inner class FunctionBuilder(
        val index: Int,
        val name: String,
        val kind: CallableKind,
        params: List<String>,
        val needs: Set<StudioCapability> = emptySet()
    ) {
        private val ops = mutableListOf<IrOp>()
        private val localSlots = linkedMapOf<String, Int>()
        private val loopBreakLabels = ArrayDeque<Int>()
        private val loopContinueLabels = ArrayDeque<Int>()
        private val paramNames = params.toList()

        init {
            params.forEach { slotOf(it) }
        }

        fun slotOf(varName: String): Int = localSlots.getOrPut(varName) { localSlots.size }
        fun emit(op: IrOp) { ops += op }

        fun build(): IrFunction = IrFunction(
            index = index,
            name = name,
            kind = kind,
            params = paramNames,
            localNames = localSlots.keys.toList(),
            ops = ops.toList(),
            requiredNeeds = needs
        )

        fun emitStmt(stmt: Stmt) {
            val line = stmt.span.line
            when (stmt) {
                is UseStmt, is NeedsStmt -> Unit
                is VarDeclStmt -> {
                    emitExpr(stmt.initializer)
                    val slot = slotOf(stmt.name)
                    emit(IrStoreLocal(slot, stmt.name, line))
                }
                is AssignStmt -> {
                    when (val target = stmt.target) {
                        is VarRef -> {
                            val slot = slotOf(target.name)
                            if (stmt.op == AssignOp.SET) {
                                emitExpr(stmt.value)
                            } else {
                                emit(IrLoadLocal(slot, target.name, line))
                                emitExpr(stmt.value)
                                val binOp = when (stmt.op) {
                                    AssignOp.ADD -> BinaryOp.ADD
                                    AssignOp.SUB -> BinaryOp.SUB
                                    AssignOp.MUL -> BinaryOp.MUL
                                    AssignOp.DIV -> BinaryOp.DIV
                                    AssignOp.SET -> BinaryOp.ADD
                                }
                                emit(IrBinary(binOp, line))
                            }
                            emit(IrStoreLocal(slot, target.name, line))
                        }
                        is MemberAccess -> {
                            emitExpr(target.target)
                            emitExpr(stmt.value)
                            emit(IrSetMember(target.member, line))
                        }
                        is IndexAccess -> {
                            emitExpr(target.target)
                            emitExpr(target.index)
                            emitExpr(stmt.value)
                            emit(IrSetIndex(line))
                        }
                        else -> Unit
                    }
                }
                is IfStmt -> {
                    val endLbl = newLabel()
                    stmt.branches.forEach { br ->
                        val nextBranchLbl = newLabel()
                        emitExpr(br.condition)
                        emit(IrJumpIfFalse(nextBranchLbl, br.span.line))
                        br.body.forEach { emitStmt(it) }
                        emit(IrJump(endLbl, br.span.line))
                        emit(IrLabel(nextBranchLbl, br.span.line))
                    }
                    stmt.elseBody.forEach { emitStmt(it) }
                    emit(IrLabel(endLbl, line))
                }
                is WhileStmt -> {
                    val startLbl = newLabel()
                    val endLbl = newLabel()
                    loopContinueLabels.addLast(startLbl)
                    loopBreakLabels.addLast(endLbl)
                    emit(IrLabel(startLbl, line))
                    emitExpr(stmt.condition)
                    emit(IrJumpIfFalse(endLbl, line))
                    stmt.body.forEach { emitStmt(it) }
                    emit(IrJump(startLbl, line))
                    emit(IrLabel(endLbl, line))
                    loopBreakLabels.removeLast()
                    loopContinueLabels.removeLast()
                }
                is RepeatStmt -> {
                    val idxSlot = slotOf("__rep_i_${nextLabelId}")
                    val limitSlot = slotOf("__rep_n_${nextLabelId}")
                    val startLbl = newLabel()
                    val stepLbl = newLabel()
                    val endLbl = newLabel()
                    emit(IrLoadConst(NumVal(0.0), line))
                    emit(IrStoreLocal(idxSlot, "__rep_i", line))
                    emitExpr(stmt.countExpr)
                    emit(IrStoreLocal(limitSlot, "__rep_n", line))
                    loopContinueLabels.addLast(stepLbl)
                    loopBreakLabels.addLast(endLbl)
                    emit(IrLabel(startLbl, line))
                    emit(IrLoadLocal(idxSlot, "__rep_i", line))
                    emit(IrLoadLocal(limitSlot, "__rep_n", line))
                    emit(IrBinary(BinaryOp.LT, line))
                    emit(IrJumpIfFalse(endLbl, line))
                    stmt.body.forEach { emitStmt(it) }
                    emit(IrLabel(stepLbl, line))
                    emit(IrLoadLocal(idxSlot, "__rep_i", line))
                    emit(IrLoadConst(NumVal(1.0), line))
                    emit(IrBinary(BinaryOp.ADD, line))
                    emit(IrStoreLocal(idxSlot, "__rep_i", line))
                    emit(IrJump(startLbl, line))
                    emit(IrLabel(endLbl, line))
                    loopBreakLabels.removeLast()
                    loopContinueLabels.removeLast()
                }
                is ForStmt -> {
                    val listSlot = slotOf("__for_list_${nextLabelId}")
                    val idxSlot = slotOf("__for_i_${nextLabelId}")
                    val itemSlot = slotOf(stmt.itemName)
                    val startLbl = newLabel()
                    val stepLbl = newLabel()
                    val endLbl = newLabel()
                    emitExpr(stmt.iterable)
                    emit(IrStoreLocal(listSlot, "__for_list", line))
                    emit(IrLoadConst(NumVal(0.0), line))
                    emit(IrStoreLocal(idxSlot, "__for_i", line))
                    loopContinueLabels.addLast(stepLbl)
                    loopBreakLabels.addLast(endLbl)
                    emit(IrLabel(startLbl, line))
                    emit(IrLoadLocal(idxSlot, "__for_i", line))
                    emit(IrLoadLocal(listSlot, "__for_list", line))
                    emit(IrCallHost("list.len", 1, line))
                    emit(IrBinary(BinaryOp.LT, line))
                    emit(IrJumpIfFalse(endLbl, line))
                    emit(IrLoadLocal(listSlot, "__for_list", line))
                    emit(IrLoadLocal(idxSlot, "__for_i", line))
                    emit(IrGetIndex(line))
                    emit(IrStoreLocal(itemSlot, stmt.itemName, line))
                    stmt.body.forEach { emitStmt(it) }
                    emit(IrLabel(stepLbl, line))
                    emit(IrLoadLocal(idxSlot, "__for_i", line))
                    emit(IrLoadConst(NumVal(1.0), line))
                    emit(IrBinary(BinaryOp.ADD, line))
                    emit(IrStoreLocal(idxSlot, "__for_i", line))
                    emit(IrJump(startLbl, line))
                    emit(IrLabel(endLbl, line))
                    loopBreakLabels.removeLast()
                    loopContinueLabels.removeLast()
                }
                is BreakStmt -> loopBreakLabels.lastOrNull()?.let { emit(IrJump(it, line)) }
                is ContinueStmt -> loopContinueLabels.lastOrNull()?.let { emit(IrJump(it, line)) }
                is ReturnStmt -> {
                    if (stmt.value != null) emitExpr(stmt.value) else emit(IrLoadConst(NoneVal, line))
                    emit(IrReturn(hasValue = true, line = line))
                }
                is TryStmt -> {
                    val catchLbl = newLabel()
                    val endLbl = newLabel()
                    emit(IrTryBegin(catchLbl, line))
                    stmt.tryBody.forEach { emitStmt(it) }
                    emit(IrTryEnd(line))
                    emit(IrJump(endLbl, line))
                    emit(IrLabel(catchLbl, line))
                    val errSlot = slotOf(stmt.errorVar ?: "e")
                    emit(IrStoreException(errSlot, line))
                    stmt.catchBody.forEach { emitStmt(it) }
                    emit(IrLabel(endLbl, line))
                }
                is RaiseStmt -> {
                    emitExpr(stmt.messageExpr)
                    emit(IrRaise(line))
                }
                is RetryStmt -> {
                    val attemptSlot = slotOf("__retry_i_${nextLabelId}")
                    val maxSlot = slotOf("__retry_n_${nextLabelId}")
                    val loopLbl = newLabel()
                    val catchLbl = newLabel()
                    val endLbl = newLabel()
                    emit(IrLoadConst(NumVal(0.0), line))
                    emit(IrStoreLocal(attemptSlot, "__retry_i", line))
                    emitExpr(stmt.countExpr)
                    emit(IrStoreLocal(maxSlot, "__retry_n", line))
                    emit(IrLabel(loopLbl, line))
                    emit(IrTryBegin(catchLbl, line))
                    stmt.body.forEach { emitStmt(it) }
                    emit(IrTryEnd(line))
                    emit(IrJump(endLbl, line))
                    emit(IrLabel(catchLbl, line))
                    val tmpErrSlot = slotOf("__retry_err_${nextLabelId}")
                    emit(IrStoreException(tmpErrSlot, line))
                    emit(IrLoadLocal(attemptSlot, "__retry_i", line))
                    emit(IrLoadConst(NumVal(1.0), line))
                    emit(IrBinary(BinaryOp.ADD, line))
                    emit(IrStoreLocal(attemptSlot, "__retry_i", line))
                    emit(IrLoadLocal(attemptSlot, "__retry_i", line))
                    emit(IrLoadLocal(maxSlot, "__retry_n", line))
                    emit(IrBinary(BinaryOp.LT, line))
                    val rethrowLbl = newLabel()
                    emit(IrJumpIfFalse(rethrowLbl, line))
                    if (stmt.waitExpr != null) {
                        emitExpr(stmt.waitExpr)
                        emit(IrSleep(line))
                    }
                    emit(IrJump(loopLbl, line))
                    emit(IrLabel(rethrowLbl, line))
                    emit(IrLoadLocal(tmpErrSlot, "__retry_err", line))
                    emit(IrGetMember("message", line))
                    emit(IrRaise(line))
                    emit(IrLabel(endLbl, line))
                }
                is TimeoutStmt -> {
                    emitExpr(stmt.durationExpr)
                    emit(IrTimeoutBegin(line))
                    stmt.body.forEach { emitStmt(it) }
                    emit(IrTimeoutEnd(line))
                }
                is ConfirmStmt -> {
                    emitExpr(stmt.promptExpr)
                    emit(IrConfirm(line))
                }
                is ExprStmt -> {
                    emitExpr(stmt.expr)
                    emit(IrPop(line))
                }
                else -> Unit
            }
        }

        fun emitExpr(expr: Expr) {
            val line = expr.span.line
            when (expr) {
                is NumberLit -> emit(IrLoadConst(NumVal(expr.value), line))
                is TextLit -> emit(IrLoadConst(TextVal(expr.value), line))
                is BoolLit -> emit(IrLoadConst(BoolVal(expr.value), line))
                is NoneLit -> emit(IrLoadConst(NoneVal, line))
                is DurationLit -> emit(IrLoadConst(DurationVal(expr.millis), line))
                is ListLit -> {
                    expr.elements.forEach { emitExpr(it) }
                    emit(IrMakeList(expr.elements.size, line))
                }
                is MapLit -> {
                    expr.entries.forEach { entry ->
                        emit(IrLoadConst(TextVal(entry.key), entry.span.line))
                        emitExpr(entry.value)
                    }
                    emit(IrMakeMap(expr.entries.size, line))
                }
                is VarRef -> {
                    val slot = localSlots[expr.name]
                    if (slot != null) {
                        emit(IrLoadLocal(slot, expr.name, line))
                    } else if (expr.name in funcIndexByName) {
                        emit(IrLoadConst(CallableVal(funcIndexByName.getValue(expr.name), expr.name), line))
                    } else {
                        emit(IrLoadGlobal(expr.name, line))
                    }
                }
                is MemberAccess -> {
                    emitExpr(expr.target)
                    emit(IrGetMember(expr.member, line))
                }
                is IndexAccess -> {
                    emitExpr(expr.target)
                    emitExpr(expr.index)
                    emit(IrGetIndex(line))
                }
                is UnaryExpr -> {
                    emitExpr(expr.operand)
                    emit(IrUnary(expr.op, line))
                }
                is BinaryExpr -> {
                    when (expr.op) {
                        BinaryOp.AND -> {
                            val falseLbl = newLabel()
                            val endLbl = newLabel()
                            emitExpr(expr.left)
                            emit(IrJumpIfFalse(falseLbl, line))
                            emitExpr(expr.right)
                            emit(IrJump(endLbl, line))
                            emit(IrLabel(falseLbl, line))
                            emit(IrLoadConst(BoolVal(false), line))
                            emit(IrLabel(endLbl, line))
                        }
                        BinaryOp.OR -> {
                            val trueLbl = newLabel()
                            val endLbl = newLabel()
                            emitExpr(expr.left)
                            emit(IrJumpIfTrue(trueLbl, line))
                            emitExpr(expr.right)
                            emit(IrJump(endLbl, line))
                            emit(IrLabel(trueLbl, line))
                            emit(IrLoadConst(BoolVal(true), line))
                            emit(IrLabel(endLbl, line))
                        }
                        else -> {
                            emitExpr(expr.left)
                            emitExpr(expr.right)
                            emit(IrBinary(expr.op, line))
                        }
                    }
                }
                is CallExpr -> {
                    val qual = extractQualifiedName(expr.callee)
                    if (qual != null && qual in StudioAnalyzer.DEFAULT_HOST_SIGNATURES) {
                        expr.args.forEach { emitExpr(it.value) }
                        emit(IrCallHost(qual, expr.args.size, line))
                    } else if (expr.callee is VarRef && expr.callee.name in funcIndexByName) {
                        expr.args.forEach { emitExpr(it.value) }
                        emit(IrCallFunc(funcIndexByName.getValue(expr.callee.name), expr.args.size, line))
                    } else if (qual != null) {
                        expr.args.forEach { emitExpr(it.value) }
                        emit(IrCallHost(qual, expr.args.size, line))
                    } else {
                        expr.args.forEach { emitExpr(it.value) }
                        emit(IrCallHost("unknown", expr.args.size, line))
                    }
                }
            }
        }

        private fun extractQualifiedName(expr: Expr): String? = when (expr) {
            is VarRef -> expr.name
            is MemberAccess -> extractQualifiedName(expr.target)?.let { "$it.${expr.member}" }
            else -> null
        }
    }
}
