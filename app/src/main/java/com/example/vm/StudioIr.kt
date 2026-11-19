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
data class IrMakeTuple(val count: Int, override val line: Int) : IrOp
data class IrMakeSet(val count: Int, override val line: Int) : IrOp
data class IrMakeMap(val entryCount: Int, override val line: Int) : IrOp
data class IrGetMember(val member: String, override val line: Int) : IrOp
data class IrSetMember(val member: String, override val line: Int) : IrOp
data class IrGetIndex(override val line: Int) : IrOp
data class IrSetIndex(override val line: Int) : IrOp
data class IrSliceGet(override val line: Int) : IrOp
data class IrDelIndex(override val line: Int) : IrOp
data class IrUnpack(val count: Int, override val line: Int) : IrOp
data class IrJump(val targetLabel: Int, override val line: Int) : IrOp
data class IrJumpIfFalse(val targetLabel: Int, override val line: Int) : IrOp
data class IrJumpIfTrue(val targetLabel: Int, override val line: Int) : IrOp
data class IrPop(override val line: Int) : IrOp
data class IrDup(override val line: Int) : IrOp
data class IrCallFunc(val funcIndex: Int, val argCount: Int, override val line: Int) : IrOp
data class IrCallHost(val qualifiedName: String, val argCount: Int, override val line: Int) : IrOp
data class IrCallDynamic(val argCount: Int, override val line: Int) : IrOp
data class IrCallMethod(val methodName: String, val argCount: Int, override val line: Int) : IrOp
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
    MACRO_ERROR,
    LAMBDA,
    METHOD
}

data class IrFunction(
    val index: Int,
    val name: String,
    val kind: CallableKind,
    val params: List<String>,
    val localNames: List<String>,
    val ops: List<IrOp>,
    val requiredNeeds: Set<StudioCapability> = emptySet(),
    val hasVarArg: Boolean = false,
    val hasKwArg: Boolean = false
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
    val requiredCapabilities: Set<StudioCapability>,
    val classes: List<ClassBytecodeMeta> = emptyList()
)

/**
 * Generates linear stack-oriented IR from a validated StudioLang AST.
 */
class IrGenerator {
    private var nextLabelId = 1
    private var nextFuncIdx = 1
    private val functions = mutableListOf<IrFunction>()
    private val funcIndexByName = mutableMapOf<String, Int>()
    private val classMetaByName = mutableMapOf<String, ClassBytecodeMeta>()
    private val classes = mutableListOf<ClassBytecodeMeta>()
    private val macros = mutableListOf<IrMacroDef>()
    private val triggers = mutableListOf<IrTriggerDef>()
    private val allNeeds = linkedSetOf<StudioCapability>()
    private val moduleAliases = mutableMapOf<String, String>()

    fun generate(program: ProgramNode, analysis: StudioAnalyzer.AnalysisResult): IrModule {
        allNeeds.clear()
        allNeeds += analysis.requiredCapabilities

        nextFuncIdx = 1
        funcIndexByName.clear()
        classMetaByName.clear()
        classes.clear()
        functions.clear()
        macros.clear()
        triggers.clear()
        moduleAliases.clear()

        // First pass: assign unique monotonic function indices to top-level definitions
        for (stmt in program.statements) {
            when (stmt) {
                is FuncDefStmt -> funcIndexByName[stmt.name] = nextFuncIdx++
                is ActionDefStmt -> funcIndexByName[stmt.name] = nextFuncIdx++
                is BlockDefStmt -> funcIndexByName[stmt.name] = nextFuncIdx++
                is ConditionDefStmt -> funcIndexByName[stmt.name] = nextFuncIdx++
                is ConstraintDefStmt -> funcIndexByName[stmt.name] = nextFuncIdx++
                is ClassDefStmt -> preRegisterClass(stmt)
                is UseStmt -> {
                    if (stmt.importedNames.isEmpty() && stmt.alias != null) {
                        moduleAliases[stmt.alias] = stmt.modulePath.joinToString(".")
                    } else {
                        stmt.importedNames.forEach { (orig, alias) ->
                            moduleAliases[alias ?: orig] = "${stmt.modulePath.joinToString(".")}.$orig"
                        }
                    }
                }
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
                is ClassDefStmt -> compileClassBody(stmt)
                is TriggerDefStmt -> compileTrigger(stmt)
                is MacroDefStmt -> compileMacro(stmt)
                is AppDefStmt, is ScreenDefStmt -> {
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
            requiredCapabilities = allNeeds.toSet(),
            classes = classes.toList()
        )
    }

    private fun preRegisterClass(stmt: ClassDefStmt) {
        val methods = linkedMapOf<String, Int>()
        stmt.baseClass?.let { base ->
            classMetaByName[base]?.methodFuncIndices?.forEach { (mName, mIdx) ->
                methods[mName] = mIdx
            }
        }
        stmt.body.filterIsInstance<FuncDefStmt>().forEach { m ->
            methods[m.name] = nextFuncIdx++
        }
        val meta = ClassBytecodeMeta(stmt.name, stmt.baseClass, methods)
        classMetaByName[stmt.name] = meta
        classes += meta
    }

    private fun compileClassBody(stmt: ClassDefStmt) {
        val meta = classMetaByName[stmt.name] ?: return
        stmt.body.filterIsInstance<FuncDefStmt>().forEach { m ->
            val idx = meta.methodFuncIndices[m.name] ?: nextFuncIdx++
            compileCallable(idx, "${stmt.name}.${m.name}", CallableKind.METHOD, m.params, m.body, emptySet())
        }
    }

    private fun compileCallable(
        idx: Int,
        name: String,
        kind: CallableKind,
        params: List<ParamSpec>,
        body: List<Stmt>,
        needs: Set<StudioCapability>
    ) {
        val fb = FunctionBuilder(
            index = idx,
            name = name,
            kind = kind,
            params = params.map { it.name },
            needs = needs,
            hasVarArg = params.any { it.isVarArg },
            hasKwArg = params.any { it.isKwArg }
        )
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
        if (fb.isGenerator) {
            fb.emit(IrLoadLocal(fb.generatorAccSlot, "__yield_acc", body.lastOrNull()?.span?.line ?: 1))
            fb.emit(IrReturn(hasValue = true, line = body.lastOrNull()?.span?.line ?: 1))
        } else {
            fb.emit(IrLoadConst(NoneVal, body.lastOrNull()?.span?.line ?: 1))
            fb.emit(IrReturn(hasValue = true, line = body.lastOrNull()?.span?.line ?: 1))
        }
        functions += fb.build()
    }

    private fun compileTrigger(stmt: TriggerDefStmt) {
        val needs = stmt.needs.mapNotNull { StudioCapability.fromId(it) }.toSet()
        allNeeds += needs
        val whenIdx = stmt.whenExpr?.let { expr ->
            val idx = nextFuncIdx++
            val fb = FunctionBuilder(idx, "${stmt.name}__when", CallableKind.TRIGGER, listOf("event"), needs)
            fb.emitExpr(expr)
            fb.emit(IrReturn(hasValue = true, line = expr.span.line))
            functions += fb.build()
            idx
        }
        val emitIdx = stmt.emitExpr?.let { expr ->
            val idx = nextFuncIdx++
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
            val idx = nextFuncIdx++
            val fb = FunctionBuilder(idx, "${stmt.name}__only_if", CallableKind.MACRO_ONLY_IF, listOf("event"), needs)
            fb.emitExpr(cond)
            fb.emit(IrReturn(hasValue = true, line = cond.span.line))
            functions += fb.build()
            idx
        }
        val doIdx = nextFuncIdx++
        val doFb = FunctionBuilder(doIdx, "${stmt.name}__do", CallableKind.MACRO_DO, listOf("event"), needs)
        stmt.doBody.forEach { doFb.emitStmt(it) }
        doFb.emit(IrLoadConst(NoneVal, stmt.span.line))
        doFb.emit(IrReturn(hasValue = true, line = stmt.span.line))
        functions += doFb.build()

        var errorSlot = 0
        val errorIdx = if (stmt.errorBody.isNotEmpty()) {
            val idx = nextFuncIdx++
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
        val needs: Set<StudioCapability> = emptySet(),
        val hasVarArg: Boolean = false,
        val hasKwArg: Boolean = false
    ) {
        private val ops = mutableListOf<IrOp>()
        private val localSlots = linkedMapOf<String, Int>()
        private val declaredGlobals = mutableSetOf<String>()
        private val loopBreakLabels = ArrayDeque<Int>()
        private val loopContinueLabels = ArrayDeque<Int>()
        private val paramNames = params.toList()
        var isGenerator = false
        var generatorAccSlot = -1

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
            requiredNeeds = needs,
            hasVarArg = hasVarArg,
            hasKwArg = hasKwArg
        )

        fun emitStmt(stmt: Stmt) {
            val line = stmt.span.line
            when (stmt) {
                is UseStmt, is NeedsStmt, is PassStmt -> Unit
                is GlobalDeclStmt -> declaredGlobals += stmt.names
                is FuncDefStmt -> {
                    val idx = nextFuncIdx++
                    compileCallable(idx, stmt.name, CallableKind.FUNC, stmt.params, stmt.body, emptySet())
                    val slot = slotOf(stmt.name)
                    emit(IrLoadConst(CallableVal(idx, stmt.name), line))
                    emit(IrStoreLocal(slot, stmt.name, line))
                }
                is ClassDefStmt -> {
                    preRegisterClass(stmt)
                    compileClassBody(stmt)
                }
                is VarDeclStmt -> {
                    emitExpr(stmt.initializer)
                    storeNamed(stmt.name, line)
                }
                is AssignStmt -> {
                    when (val target = stmt.target) {
                        is VarRef -> {
                            if (stmt.op == AssignOp.SET) {
                                emitExpr(stmt.value)
                            } else {
                                loadNamed(target.name, line)
                                emitExpr(stmt.value)
                                val binOp = when (stmt.op) {
                                    AssignOp.ADD -> BinaryOp.ADD
                                    AssignOp.SUB -> BinaryOp.SUB
                                    AssignOp.MUL -> BinaryOp.MUL
                                    AssignOp.DIV -> BinaryOp.DIV
                                    AssignOp.MOD -> BinaryOp.MOD
                                    AssignOp.FLOOR_DIV -> BinaryOp.FLOOR_DIV
                                    AssignOp.POW -> BinaryOp.POW
                                    AssignOp.SET -> BinaryOp.ADD
                                }
                                emit(IrBinary(binOp, line))
                            }
                            storeNamed(target.name, line)
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
                is UnpackAssignStmt -> {
                    emitExpr(stmt.value)
                    emit(IrUnpack(stmt.targets.size, line))
                    stmt.targets.forEach { tName ->
                        if (tName == "_") emit(IrPop(line)) else storeNamed(tName, line)
                    }
                }
                is DelStmt -> {
                    if (stmt.target is IndexAccess) {
                        emitExpr(stmt.target.target)
                        emitExpr(stmt.target.index)
                        emit(IrDelIndex(line))
                    }
                }
                is AssertStmt -> {
                    val okLbl = newLabel()
                    emitExpr(stmt.condition)
                    emit(IrJumpIfTrue(okLbl, line))
                    if (stmt.messageExpr != null) emitExpr(stmt.messageExpr)
                    else emit(IrLoadConst(TextVal("AssertionError"), line))
                    emit(IrRaise(line))
                    emit(IrLabel(okLbl, line))
                }
                is WithStmt -> {
                    emitExpr(stmt.contextExpr)
                    if (stmt.alias != null) storeNamed(stmt.alias, line) else emit(IrPop(line))
                    stmt.body.forEach { emitStmt(it) }
                }
                is MatchStmt -> {
                    val subjSlot = slotOf("__match_${nextLabelId}")
                    val endLbl = newLabel()
                    emitExpr(stmt.subject)
                    emit(IrStoreLocal(subjSlot, "__match", line))
                    stmt.cases.forEach { c ->
                        val nextCaseLbl = newLabel()
                        if (c.pattern != null) {
                            emit(IrLoadLocal(subjSlot, "__match", c.span.line))
                            emitExpr(c.pattern)
                            emit(IrBinary(BinaryOp.EQ, c.span.line))
                            emit(IrJumpIfFalse(nextCaseLbl, c.span.line))
                        }
                        if (c.guard != null) {
                            emitExpr(c.guard)
                            emit(IrJumpIfFalse(nextCaseLbl, c.span.line))
                        }
                        c.body.forEach { emitStmt(it) }
                        emit(IrJump(endLbl, c.span.line))
                        emit(IrLabel(nextCaseLbl, c.span.line))
                    }
                    emit(IrLabel(endLbl, line))
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
                    val lenSlot = slotOf("__for_len_${nextLabelId}")
                    val startLbl = newLabel()
                    val stepLbl = newLabel()
                    val endLbl = newLabel()
                    emitExpr(stmt.iterable)
                    emit(IrCallHost("py.iter_items", 1, line))
                    emit(IrDup(line))
                    emit(IrStoreLocal(listSlot, "__for_list", line))
                    emit(IrCallHost("list.len", 1, line))
                    emit(IrStoreLocal(lenSlot, "__for_len", line))
                    emit(IrLoadConst(NumVal(0.0), line))
                    emit(IrStoreLocal(idxSlot, "__for_i", line))
                    loopContinueLabels.addLast(stepLbl)
                    loopBreakLabels.addLast(endLbl)
                    emit(IrLabel(startLbl, line))
                    emit(IrLoadLocal(idxSlot, "__for_i", line))
                    emit(IrLoadLocal(lenSlot, "__for_len", line))
                    emit(IrBinary(BinaryOp.LT, line))
                    emit(IrJumpIfFalse(endLbl, line))
                    emit(IrLoadLocal(listSlot, "__for_list", line))
                    emit(IrLoadLocal(idxSlot, "__for_i", line))
                    emit(IrGetIndex(line))
                    if (stmt.targets.size == 1) {
                        storeNamed(stmt.targets.first(), line)
                    } else {
                        emit(IrUnpack(stmt.targets.size, line))
                        stmt.targets.forEach { tName -> storeNamed(tName, line) }
                    }
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
                is YieldStmt -> {
                    if (!isGenerator) {
                        isGenerator = true
                        generatorAccSlot = slotOf("__yield_acc")
                        emit(IrMakeList(0, line))
                        emit(IrStoreLocal(generatorAccSlot, "__yield_acc", line))
                    }
                    emit(IrLoadLocal(generatorAccSlot, "__yield_acc", line))
                    if (stmt.value != null) emitExpr(stmt.value) else emit(IrLoadConst(NoneVal, line))
                    emit(IrCallHost("list.push", 2, line))
                    emit(IrPop(line))
                }
                is TryStmt -> {
                    val catchLbl = newLabel()
                    val endLbl = newLabel()
                    emit(IrTryBegin(catchLbl, line))
                    stmt.tryBody.forEach { emitStmt(it) }
                    emit(IrTryEnd(line))
                    stmt.finallyBody.forEach { emitStmt(it) }
                    emit(IrJump(endLbl, line))
                    emit(IrLabel(catchLbl, line))
                    val errSlot = slotOf(stmt.errorVar ?: "__err_${nextLabelId}")
                    emit(IrStoreException(errSlot, line))
                    if (stmt.catchBody.isNotEmpty()) {
                        stmt.catchBody.forEach { emitStmt(it) }
                    } else if (stmt.finallyBody.isNotEmpty()) {
                        stmt.finallyBody.forEach { emitStmt(it) }
                        emit(IrLoadLocal(errSlot, stmt.errorVar ?: "__err", line))
                        emit(IrGetMember("message", line))
                        emit(IrRaise(line))
                    }
                    stmt.finallyBody.forEach { emitStmt(it) }
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

        private fun loadNamed(name: String, line: Int) {
            if (name in declaredGlobals) {
                emit(IrLoadGlobal(name, line))
                return
            }
            val slot = localSlots[name]
            when {
                slot != null -> emit(IrLoadLocal(slot, name, line))
                name in funcIndexByName -> emit(IrLoadConst(CallableVal(funcIndexByName.getValue(name), name), line))
                name in classMetaByName -> {
                    val m = classMetaByName.getValue(name)
                    emit(IrLoadConst(CallableVal(-1, m.name, isClass = true, parentName = m.parentName, methods = m.methodFuncIndices), line))
                }
                name in StudioAnalyzer.DEFAULT_HOST_SIGNATURES -> emit(IrLoadConst(CallableVal(-2, name), line))
                else -> emit(IrLoadGlobal(name, line))
            }
        }

        private fun storeNamed(name: String, line: Int) {
            if (name in declaredGlobals) {
                emit(IrStoreGlobal(name, line))
            } else {
                val slot = slotOf(name)
                emit(IrStoreLocal(slot, name, line))
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
                is TupleLit -> {
                    expr.elements.forEach { emitExpr(it) }
                    emit(IrMakeTuple(expr.elements.size, line))
                }
                is SetLit -> {
                    expr.elements.forEach { emitExpr(it) }
                    emit(IrMakeSet(expr.elements.size, line))
                }
                is MapLit -> {
                    expr.entries.forEach { entry ->
                        if (entry.keyExpr != null) emitExpr(entry.keyExpr)
                        else emit(IrLoadConst(TextVal(entry.key), entry.span.line))
                        emitExpr(entry.value)
                    }
                    emit(IrMakeMap(expr.entries.size, line))
                }
                is VarRef -> loadNamed(expr.name, line)
                is MemberAccess -> {
                    emitExpr(expr.target)
                    emit(IrGetMember(expr.member, line))
                }
                is IndexAccess -> {
                    emitExpr(expr.target)
                    emitExpr(expr.index)
                    emit(IrGetIndex(line))
                }
                is SliceAccess -> {
                    emitExpr(expr.target)
                    if (expr.start != null) emitExpr(expr.start) else emit(IrLoadConst(NoneVal, line))
                    if (expr.stop != null) emitExpr(expr.stop) else emit(IrLoadConst(NoneVal, line))
                    if (expr.step != null) emitExpr(expr.step) else emit(IrLoadConst(NoneVal, line))
                    emit(IrSliceGet(line))
                }
                is TernaryExpr -> {
                    val falseLbl = newLabel()
                    val endLbl = newLabel()
                    emitExpr(expr.condition)
                    emit(IrJumpIfFalse(falseLbl, line))
                    emitExpr(expr.trueExpr)
                    emit(IrJump(endLbl, line))
                    emit(IrLabel(falseLbl, line))
                    emitExpr(expr.falseExpr)
                    emit(IrLabel(endLbl, line))
                }
                is LambdaExpr -> {
                    val idx = nextFuncIdx++
                    val lambdaName = "<lambda@${line}>"
                    compileCallable(idx, lambdaName, CallableKind.LAMBDA, expr.params, listOf(ReturnStmt(expr.body, expr.span)), emptySet())
                    emit(IrLoadConst(CallableVal(idx, lambdaName), line))
                }
                is ListCompExpr -> emitComprehension(
                    mode = CompMode.LIST,
                    vars = expr.varNames,
                    iterable = expr.iterable,
                    filter = expr.filterExpr,
                    elemExpr = expr.elementExpr,
                    valExpr = null,
                    line = line
                )
                is SetCompExpr -> emitComprehension(
                    mode = CompMode.SET,
                    vars = expr.varNames,
                    iterable = expr.iterable,
                    filter = expr.filterExpr,
                    elemExpr = expr.elementExpr,
                    valExpr = null,
                    line = line
                )
                is DictCompExpr -> emitComprehension(
                    mode = CompMode.DICT,
                    vars = expr.varNames,
                    iterable = expr.iterable,
                    filter = expr.filterExpr,
                    elemExpr = expr.keyExpr,
                    valExpr = expr.valueExpr,
                    line = line
                )
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
                    val qualRaw = extractQualifiedName(expr.callee)
                    val resolvedQual = resolveAliasedQual(qualRaw)
                    when {
                        // Direct call to known host signature (excluding HOF map/filter/sorted/getattr handled cleanly if needed)
                        resolvedQual != null && resolvedQual in StudioAnalyzer.DEFAULT_HOST_SIGNATURES &&
                            resolvedQual !in setOf("map", "filter", "sorted") -> {
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallHost(resolvedQual, expr.args.size, line))
                        }
                        expr.callee is VarRef && expr.callee.name in funcIndexByName -> {
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallFunc(funcIndexByName.getValue(expr.callee.name), expr.args.size, line))
                        }
                        expr.callee is VarRef && expr.callee.name in localSlots -> {
                            // Local variable holding lambda/function
                            emitExpr(expr.callee)
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallDynamic(expr.args.size, line))
                        }
                        expr.callee is VarRef && expr.callee.name in classMetaByName -> {
                            emitExpr(expr.callee)
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallDynamic(expr.args.size, line))
                        }
                        expr.callee is VarRef && (expr.callee.name == "map" || expr.callee.name == "filter" || expr.callee.name == "sorted") -> {
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallHost("py.${expr.callee.name}", expr.args.size, line))
                        }
                        expr.callee is MemberAccess -> {
                            // Could be a method call on an object/list/str/dict/set or namespace call
                            if (resolvedQual != null && expr.callee.target is VarRef &&
                                expr.callee.target.name in StudioAnalyzer.BUILTIN_NAMESPACES
                            ) {
                                expr.args.forEach { emitExpr(it.value) }
                                emit(IrCallHost(resolvedQual, expr.args.size, line))
                            } else {
                                emitExpr(expr.callee.target)
                                expr.args.forEach { emitExpr(it.value) }
                                emit(IrCallMethod(expr.callee.member, expr.args.size, line))
                            }
                        }
                        else -> {
                            emitExpr(expr.callee)
                            expr.args.forEach { emitExpr(it.value) }
                            emit(IrCallDynamic(expr.args.size, line))
                        }
                    }
                }
            }
        }

        private fun resolveAliasedQual(qual: String?): String? {
            if (qual == null) return null
            if (qual in moduleAliases) return moduleAliases[qual]
            val dot = qual.indexOf('.')
            if (dot > 0) {
                val head = qual.substring(0, dot)
                val resolvedHead = moduleAliases[head]
                if (resolvedHead != null) return "$resolvedHead${qual.substring(dot)}"
            }
            return qual
        }

        private fun emitComprehension(
            mode: CompMode,
            vars: List<String>,
            iterable: Expr,
            filter: Expr?,
            elemExpr: Expr,
            valExpr: Expr?,
            line: Int
        ) {
            val uid = nextLabelId++
            val accSlot = slotOf("__comp_acc_$uid")
            val listSlot = slotOf("__comp_list_$uid")
            val idxSlot = slotOf("__comp_i_$uid")
            val lenSlot = slotOf("__comp_len_$uid")
            val startLbl = newLabel()
            val stepLbl = newLabel()
            val endLbl = newLabel()

            when (mode) {
                CompMode.LIST -> emit(IrMakeList(0, line))
                CompMode.SET -> emit(IrMakeSet(0, line))
                CompMode.DICT -> emit(IrMakeMap(0, line))
            }
            emit(IrStoreLocal(accSlot, "__comp_acc_$uid", line))

            emitExpr(iterable)
            emit(IrCallHost("py.iter_items", 1, line))
            emit(IrDup(line))
            emit(IrStoreLocal(listSlot, "__comp_list_$uid", line))
            emit(IrCallHost("list.len", 1, line))
            emit(IrStoreLocal(lenSlot, "__comp_len_$uid", line))

            emit(IrLoadConst(NumVal(0.0), line))
            emit(IrStoreLocal(idxSlot, "__comp_i_$uid", line))

            emit(IrLabel(startLbl, line))
            emit(IrLoadLocal(idxSlot, "__comp_i_$uid", line))
            emit(IrLoadLocal(lenSlot, "__comp_len_$uid", line))
            emit(IrBinary(BinaryOp.LT, line))
            emit(IrJumpIfFalse(endLbl, line))

            emit(IrLoadLocal(listSlot, "__comp_list_$uid", line))
            emit(IrLoadLocal(idxSlot, "__comp_i_$uid", line))
            emit(IrGetIndex(line))
            if (vars.size == 1) {
                storeNamed(vars.first(), line)
            } else {
                emit(IrUnpack(vars.size, line))
                vars.forEach { storeNamed(it, line) }
            }

            if (filter != null) {
                emitExpr(filter)
                emit(IrJumpIfFalse(stepLbl, line))
            }

            when (mode) {
                CompMode.LIST -> {
                    emit(IrLoadLocal(accSlot, "__comp_acc_$uid", line))
                    emitExpr(elemExpr)
                    emit(IrCallHost("list.push", 2, line))
                    emit(IrPop(line))
                }
                CompMode.SET -> {
                    emit(IrLoadLocal(accSlot, "__comp_acc_$uid", line))
                    emitExpr(elemExpr)
                    emit(IrCallHost("py.set_add", 2, line))
                    emit(IrPop(line))
                }
                CompMode.DICT -> {
                    emit(IrLoadLocal(accSlot, "__comp_acc_$uid", line))
                    emitExpr(elemExpr)
                    emitExpr(valExpr!!)
                    emit(IrSetIndex(line))
                }
            }

            emit(IrLabel(stepLbl, line))
            emit(IrLoadLocal(idxSlot, "__comp_i_$uid", line))
            emit(IrLoadConst(NumVal(1.0), line))
            emit(IrBinary(BinaryOp.ADD, line))
            emit(IrStoreLocal(idxSlot, "__comp_i_$uid", line))
            emit(IrJump(startLbl, line))
            emit(IrLabel(endLbl, line))

            emit(IrLoadLocal(accSlot, "__comp_acc_$uid", line))
        }

        private fun extractQualifiedName(expr: Expr): String? = when (expr) {
            is VarRef -> expr.name
            is MemberAccess -> extractQualifiedName(expr.target)?.let { "$it.${expr.member}" }
            else -> null
        }
    }

    private enum class CompMode { LIST, SET, DICT }
}
