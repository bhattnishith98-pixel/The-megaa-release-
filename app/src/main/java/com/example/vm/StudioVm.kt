package com.example.vm

import com.example.lang.BinaryOp
import com.example.lang.StudioAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.pow

data class ExecutionPolicy(
    val maxInstructions: Long = 1_000_000L,
    val maxStackDepth: Int = 512,
    val maxRecursionDepth: Int = 128,
    val maxAllocations: Int = 150_000,
    val defaultTimeoutMs: Long = 30_000L
)

data class VmLogEntry(
    val level: String,
    val message: String,
    val line: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

data class VmExecutionResult(
    val success: Boolean,
    val returnValue: StudioValue,
    val logs: List<VmLogEntry>,
    val instructionsExecuted: Long,
    val allocationsCount: Int,
    val durationMs: Long,
    val error: String? = null
)

interface HostApiDispatcher {
    suspend fun invokeHost(
        qualifiedName: String,
        args: List<StudioValue>,
        line: Int,
        logSink: (level: String, message: String) -> Unit
    ): StudioValue
}

interface ExecutionDebugger {
    suspend fun onStep(pc: Int, line: Int, funcName: String, localsSnapshot: Map<String, String>)
    suspend fun requestConfirm(prompt: String): Boolean
}

/**
 * High-performance Custom Virtual Machine for verified StudioLang & Python bytecode.
 * Features O(1) indexed dispatch, batched cancellation/timeout polling,
 * OOP class instantiation & bound methods, HOF lambdas (map/filter/sorted),
 * full Python collection methods & slicing, and strict capability enforcement.
 */
class StudioVm(
    private val capabilityManager: CapabilityManager,
    private val hostDispatcher: HostApiDispatcher,
    private val policy: ExecutionPolicy = ExecutionPolicy()
) {
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }
    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    suspend fun execute(
        bytecode: CompiledBytecode,
        entryFunctionIndex: Int = 0,
        initialArgs: List<StudioValue> = emptyList(),
        debugger: ExecutionDebugger? = null
    ): VmExecutionResult {
        cancelled.set(false)
        paused.set(false)

        val verify = BytecodeVerifier.verify(bytecode, maxStackDepth = policy.maxStackDepth)
        if (!verify.isValid) {
            return VmExecutionResult(
                success = false,
                returnValue = NoneVal,
                logs = verify.diagnostics.map { VmLogEntry("ERROR", it.format(), it.span.line) },
                instructionsExecuted = 0,
                allocationsCount = 0,
                durationMs = 0,
                error = verify.diagnostics.first().message
            )
        }

        val maxFnIdx = bytecode.functions.maxOfOrNull { it.index } ?: 0
        val fnTable = arrayOfNulls<FunctionBytecodeMeta>(maxFnIdx + 1)
        val fnMapFallback = hashMapOf<Int, FunctionBytecodeMeta>()
        for (f in bytecode.functions) {
            if (f.index in fnTable.indices) fnTable[f.index] = f
            fnMapFallback[f.index] = f
        }
        fun getFn(index: Int): FunctionBytecodeMeta =
            (if (index in fnTable.indices) fnTable[index] else null)
                ?: fnMapFallback[index]
                ?: throw StudioVmException("VM008", "Function index $index not found.")

        val logs = mutableListOf<VmLogEntry>()
        val startTime = System.currentTimeMillis()
        var instructionsRun = 0L
        var allocations = 0

        val stack = arrayOfNulls<StudioValue>(policy.maxStackDepth)
        var sp = 0
        val globals = hashMapOf<String, StudioValue>()

        data class TryHandler(val frameDepth: Int, val catchPc: Int, val savedSp: Int)
        data class CallFrame(
            val meta: FunctionBytecodeMeta,
            val locals: Array<StudioValue>,
            var pc: Int,
            val baseSp: Int,
            val overrideReturn: StudioValue? = null
        )

        val frames = ArrayDeque<CallFrame>()
        val tryStack = ArrayDeque<TryHandler>()
        var activeTimeoutEndMs: Long? = null

        fun push(v: StudioValue) {
            if (sp >= policy.maxStackDepth) throw StudioVmException("VM001", "Operand stack overflow.")
            stack[sp++] = v
        }

        fun pop(): StudioValue {
            if (sp <= 0) throw StudioVmException("VM002", "Operand stack underflow.")
            val v = stack[--sp]!!
            stack[sp] = null
            return v
        }

        fun trackAlloc() {
            allocations++
            if (allocations > policy.maxAllocations) {
                throw StudioVmException("VM009", "Allocation limit (${policy.maxAllocations}) exceeded.")
            }
        }

        fun makeFrame(
            callee: FunctionBytecodeMeta,
            args: List<StudioValue>,
            baseSpPos: Int,
            overrideReturn: StudioValue? = null
        ): CallFrame {
            val newLocals = Array<StudioValue>(callee.localCount.coerceAtLeast(1)) { NoneVal }
            if (callee.hasVarArg && callee.paramCount > 0) {
                val fixedCount = callee.paramCount - 1
                for (i in 0 until fixedCount) {
                    if (i < args.size && i < newLocals.size) newLocals[i] = args[i]
                }
                val rest = if (args.size > fixedCount) args.subList(fixedCount, args.size).toMutableList() else mutableListOf()
                if (fixedCount < newLocals.size) newLocals[fixedCount] = ListVal(rest)
            } else {
                for (i in args.indices) {
                    if (i < newLocals.size) newLocals[i] = args[i]
                }
            }
            return CallFrame(callee, newLocals, callee.startPc, baseSpPos, overrideReturn)
        }

        suspend fun invokeHostSafe(qualifiedName: String, args: List<StudioValue>, line: Int): StudioValue {
            val sig = StudioAnalyzer.DEFAULT_HOST_SIGNATURES[qualifiedName]
                ?: throw StudioVmException("SEC001", "Unlisted host API '$qualifiedName'.", line)
            val cap = sig.requiredCapability
            if (cap != null && !capabilityManager.isGranted(cap)) {
                throw StudioVmException(
                    "SEC003",
                    "Missing Automation Studio capability '${cap.id}' for '$qualifiedName'. Grant it in Security & Capabilities.",
                    line
                )
            }
            return hostDispatcher.invokeHost(qualifiedName, args, line) { lvl, msg ->
                logs += VmLogEntry(lvl, msg, line)
            }
        }

        suspend fun invokeCallableSubVm(callable: CallableVal, callArgs: List<StudioValue>, line: Int): StudioValue {
            val targetMeta = getFn(callable.funcIndex)
            val actualArgs = if (callable.boundSelf != null) listOf(callable.boundSelf) + callArgs else callArgs
            val targetFrameDepth = frames.size
            frames.addLast(makeFrame(targetMeta, actualArgs, sp))
            while (frames.size > targetFrameDepth) {
                val f = frames.last()
                if (f.pc >= f.meta.endPc) {
                    val ret = if (sp > f.baseSp) pop() else NoneVal
                    frames.removeLast()
                    return f.overrideReturn ?: ret
                }
                instructionsRun++
                if (instructionsRun > policy.maxInstructions) {
                    throw StudioVmException("VM012", "Instruction budget (${policy.maxInstructions}) exceeded.", line)
                }
                val p = f.pc++
                val instruction = bytecode.instructions[p]
                val subOp = Opcode.opcode(instruction)
                val subA = Opcode.argA(instruction)
                val subB = Opcode.argB(instruction)
                val subWide = Opcode.argWide(instruction)
                val subLine = bytecode.lineTable.getOrElse(p) { line }
                if (subOp == Opcode.RETURN) {
                    val ret = if (sp > f.baseSp) pop() else NoneVal
                    sp = f.baseSp
                    frames.removeLast()
                    if (frames.size == targetFrameDepth) return f.overrideReturn ?: ret
                    push(f.overrideReturn ?: ret)
                    continue
                }
                // Execute single instruction inline for lambdas/callbacks
                when (subOp) {
                    Opcode.LOAD_CONST -> push(bytecode.constPool[subA])
                    Opcode.LOAD_LOCAL -> push(f.locals[subA])
                    Opcode.STORE_LOCAL -> f.locals[subA] = pop()
                    Opcode.LOAD_GLOBAL -> push(globals[(bytecode.constPool[subA] as TextVal).value] ?: NamespaceVal((bytecode.constPool[subA] as TextVal).value))
                    Opcode.STORE_GLOBAL -> globals[(bytecode.constPool[subA] as TextVal).value] = pop()
                    Opcode.POP -> if (sp > f.baseSp) pop()
                    Opcode.DUP -> { val top = pop(); push(top); push(top) }
                    Opcode.UNARY -> push(evalUnary(subA, pop()))
                    Opcode.BINARY -> { val r = pop(); val l = pop(); push(evalBinary(l, BinaryOp.entries[subA], r, subLine)) }
                    Opcode.MAKE_LIST -> { val items = MutableList(subA) { NoneVal as StudioValue }; for (i in subA - 1 downTo 0) items[i] = pop(); push(ListVal(items)) }
                    Opcode.MAKE_TUPLE -> { val items = MutableList(subA) { NoneVal as StudioValue }; for (i in subA - 1 downTo 0) items[i] = pop(); push(TupleVal(items)) }
                    Opcode.GET_MEMBER -> { val m = (bytecode.constPool[subA] as TextVal).value; push(resolveMember(pop(), m)) }
                    Opcode.GET_INDEX -> { val idx = pop(); push(resolveIndex(pop(), idx, subLine)) }
                    Opcode.JUMP -> f.pc = subWide
                    Opcode.JUMP_IF_FALSE -> if (!pop().asBool()) f.pc = subWide
                    Opcode.JUMP_IF_TRUE -> if (pop().asBool()) f.pc = subWide
                    Opcode.CALL_HOST -> {
                        val qn = (bytecode.constPool[subA] as TextVal).value
                        val hArgs = MutableList(subB) { NoneVal as StudioValue }
                        for (i in subB - 1 downTo 0) hArgs[i] = pop()
                        push(invokeHostSafe(qn, hArgs, subLine))
                    }
                    Opcode.CALL_FUNC -> {
                        val cMeta = getFn(subA)
                        val fArgs = MutableList(subB) { NoneVal as StudioValue }
                        for (i in subB - 1 downTo 0) fArgs[i] = pop()
                        frames.addLast(makeFrame(cMeta, fArgs, sp))
                    }
                    Opcode.CALL_METHOD -> {
                        val mName = (bytecode.constPool[subA] as TextVal).value
                        val mArgs = MutableList(subB) { NoneVal as StudioValue }
                        for (i in subB - 1 downTo 0) mArgs[i] = pop()
                        val recv = pop()
                        push(evalBuiltinMethod(recv, mName, mArgs, subLine) ?: NoneVal)
                    }
                    else -> throw StudioVmException("VM014", "Unsupported opcode $subOp in callback.", subLine)
                }
            }
            return NoneVal
        }

        val entryMeta = try {
            getFn(entryFunctionIndex)
        } catch (_: Exception) {
            return VmExecutionResult(false, NoneVal, logs, 0, 0, 0, "Entry function $entryFunctionIndex missing.")
        }
        frames.addLast(makeFrame(entryMeta, initialArgs, 0))

        try {
            while (frames.isNotEmpty()) {
                // Batch cooperative cancellation & wall-clock timeout every 64 instructions for huge VM speedup
                if ((instructionsRun and 63L) == 0L || debugger != null) {
                    coroutineContext.ensureActive()
                    if (cancelled.get()) {
                        throw StudioVmException("VM010", "Execution cancelled by user.")
                    }
                    while (paused.get() && !cancelled.get()) {
                        delay(50)
                    }
                    val now = System.currentTimeMillis()
                    val timeoutLimit = activeTimeoutEndMs ?: (startTime + policy.defaultTimeoutMs)
                    if (now > timeoutLimit) {
                        throw StudioVmException("VM011", "Execution timed out.")
                    }
                }

                val frame = frames.last()
                if (frame.pc >= frame.meta.endPc) {
                    val ret = frame.overrideReturn ?: if (sp > frame.baseSp) pop() else NoneVal
                    sp = frame.baseSp
                    frames.removeLast()
                    if (frames.isNotEmpty()) push(ret) else {
                        return VmExecutionResult(
                            success = true,
                            returnValue = ret,
                            logs = logs,
                            instructionsExecuted = instructionsRun,
                            allocationsCount = allocations,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }
                    continue
                }

                instructionsRun++
                if (instructionsRun > policy.maxInstructions) {
                    throw StudioVmException("VM012", "Instruction budget (${policy.maxInstructions}) exceeded.")
                }

                val pc = frame.pc++
                val ins = bytecode.instructions[pc]
                val op = Opcode.opcode(ins)
                val a = Opcode.argA(ins)
                val b = Opcode.argB(ins)
                val wide = Opcode.argWide(ins)
                val line = if (pc < bytecode.lineTable.size) bytecode.lineTable[pc] else 1

                if (debugger != null) {
                    val localsView = buildMap {
                        frame.meta.localNames.forEachIndexed { idx, n ->
                            if (idx < frame.locals.size && !n.startsWith("__")) {
                                put(n, frame.locals[idx].asDisplayString())
                            }
                        }
                    }
                    debugger.onStep(pc, line, frame.meta.name, localsView)
                }

                try {
                    when (op) {
                        Opcode.NOP -> Unit
                        Opcode.LOAD_CONST -> push(bytecode.constPool[a])
                        Opcode.LOAD_LOCAL -> push(frame.locals[a])
                        Opcode.STORE_LOCAL -> frame.locals[a] = pop()
                        Opcode.LOAD_GLOBAL -> {
                            val name = (bytecode.constPool[a] as TextVal).value
                            push(globals[name] ?: NamespaceVal(name))
                        }
                        Opcode.STORE_GLOBAL -> {
                            val name = (bytecode.constPool[a] as TextVal).value
                            globals[name] = pop()
                        }
                        Opcode.POP -> if (sp > frame.baseSp) pop()
                        Opcode.DUP -> {
                            val top = pop()
                            push(top); push(top)
                        }
                        Opcode.UNARY -> push(evalUnary(a, pop()))
                        Opcode.BINARY -> {
                            val r = pop()
                            val l = pop()
                            push(evalBinary(l, BinaryOp.entries[a], r, line))
                        }
                        Opcode.MAKE_LIST -> {
                            trackAlloc()
                            val items = MutableList(a) { NoneVal as StudioValue }
                            for (i in a - 1 downTo 0) items[i] = pop()
                            push(ListVal(items))
                        }
                        Opcode.MAKE_TUPLE -> {
                            trackAlloc()
                            val items = MutableList(a) { NoneVal as StudioValue }
                            for (i in a - 1 downTo 0) items[i] = pop()
                            push(TupleVal(items))
                        }
                        Opcode.MAKE_SET -> {
                            trackAlloc()
                            val arr = arrayOfNulls<StudioValue>(a)
                            for (i in a - 1 downTo 0) arr[i] = pop()
                            val set = LinkedHashSet<StudioValue>(a * 2 + 1)
                            for (v in arr) set.add(v!!)
                            push(SetVal(set))
                        }
                        Opcode.MAKE_MAP -> {
                            trackAlloc()
                            val pairs = ArrayList<Pair<String, StudioValue>>(a)
                            repeat(a) {
                                val v = pop()
                                val k = pop().asDisplayString()
                                pairs += k to v
                            }
                            val map = linkedMapOf<String, StudioValue>()
                            for (i in pairs.lastIndex downTo 0) {
                                map[pairs[i].first] = pairs[i].second
                            }
                            push(MapVal(map))
                        }
                        Opcode.GET_MEMBER -> {
                            val member = (bytecode.constPool[a] as TextVal).value
                            val target = pop()
                            push(resolveMember(target, member))
                        }
                        Opcode.SET_MEMBER -> {
                            val member = (bytecode.constPool[a] as TextVal).value
                            val value = pop()
                            val target = pop()
                            when (target) {
                                is MapVal -> target.entries[member] = value
                                is ObjectVal -> target.fields[member] = value
                                else -> throw StudioVmException("VM004", "Cannot set property '$member' on ${target.type.label}.", line)
                            }
                        }
                        Opcode.GET_INDEX -> {
                            val idx = pop()
                            val target = pop()
                            push(resolveIndex(target, idx, line))
                        }
                        Opcode.SET_INDEX -> {
                            val value = pop()
                            val idx = pop()
                            val target = pop()
                            when (target) {
                                is ListVal -> {
                                    var i = idx.asDouble().toInt()
                                    if (i < 0) i += target.items.size
                                    if (i !in target.items.indices) throw StudioVmException("VM005", "List index $i out of bounds.", line)
                                    target.items[i] = value
                                }
                                is MapVal -> target.entries[idx.asDisplayString()] = value
                                else -> throw StudioVmException("VM005", "Cannot index-assign ${target.type.label}.", line)
                            }
                        }
                        Opcode.SLICE_GET -> {
                            val stepV = pop()
                            val stopV = pop()
                            val startV = pop()
                            val target = pop()
                            trackAlloc()
                            push(evalSlice(target, startV, stopV, stepV, line))
                        }
                        Opcode.DEL_INDEX -> {
                            val idx = pop()
                            val target = pop()
                            when (target) {
                                is ListVal -> {
                                    var i = idx.asDouble().toInt()
                                    if (i < 0) i += target.items.size
                                    if (i in target.items.indices) target.items.removeAt(i)
                                }
                                is MapVal -> target.entries.remove(idx.asDisplayString())
                                is SetVal -> target.items.remove(idx)
                                else -> throw StudioVmException("VM005", "Cannot delete item from ${target.type.label}.", line)
                            }
                        }
                        Opcode.UNPACK -> {
                            val seq = pop()
                            val items = toIterableList(seq, line)
                            if (items.size < a) {
                                throw StudioVmException("VM006", "Not enough values to unpack (expected $a, got ${items.size}).", line)
                            }
                            for (i in a - 1 downTo 0) {
                                push(items[i])
                            }
                        }
                        Opcode.JUMP -> frame.pc = wide
                        Opcode.JUMP_IF_FALSE -> if (!pop().asBool()) frame.pc = wide
                        Opcode.JUMP_IF_TRUE -> if (pop().asBool()) frame.pc = wide
                        Opcode.CALL_FUNC -> {
                            if (frames.size >= policy.maxRecursionDepth) {
                                throw StudioVmException("VM013", "Recursion depth limit (${policy.maxRecursionDepth}) exceeded.", line)
                            }
                            val callee = getFn(a)
                            val args = MutableList(b) { NoneVal as StudioValue }
                            for (i in b - 1 downTo 0) args[i] = pop()
                            frames.addLast(makeFrame(callee, args, sp))
                        }
                        Opcode.CALL_DYNAMIC -> {
                            if (frames.size >= policy.maxRecursionDepth) {
                                throw StudioVmException("VM013", "Recursion depth limit (${policy.maxRecursionDepth}) exceeded.", line)
                            }
                            val args = MutableList(a) { NoneVal as StudioValue }
                            for (i in a - 1 downTo 0) args[i] = pop()
                            val calleeVal = pop()
                            if (calleeVal !is CallableVal) {
                                throw StudioVmException("VM008", "Object of type '${calleeVal.type.label}' is not callable.", line)
                            }
                            if (calleeVal.isClass) {
                                trackAlloc()
                                val instance = ObjectVal(calleeVal.name, linkedMapOf(), calleeVal.methods.toMutableMap())
                                val initIdx = instance.methods["__init__"]
                                if (initIdx != null) {
                                    val initMeta = getFn(initIdx)
                                    frames.addLast(makeFrame(initMeta, listOf(instance) + args, sp, overrideReturn = instance))
                                } else {
                                    push(instance)
                                }
                            } else if (calleeVal.funcIndex == -2) {
                                push(invokeHostSafe(calleeVal.name, args, line))
                            } else {
                                val callee = getFn(calleeVal.funcIndex)
                                val finalArgs = if (calleeVal.boundSelf != null) listOf(calleeVal.boundSelf) + args else args
                                frames.addLast(makeFrame(callee, finalArgs, sp))
                            }
                        }
                        Opcode.CALL_METHOD -> {
                            val methodName = (bytecode.constPool[a] as TextVal).value
                            val args = MutableList(b) { NoneVal as StudioValue }
                            for (i in b - 1 downTo 0) args[i] = pop()
                            val receiver = pop()
                            when {
                                receiver is NamespaceVal -> {
                                    push(invokeHostSafe("${receiver.name}.$methodName", args, line))
                                }
                                receiver is ObjectVal -> {
                                    val mIdx = receiver.methods[methodName]
                                        ?: throw StudioVmException("VM004", "'${receiver.className}' object has no method '$methodName'.", line)
                                    val mMeta = getFn(mIdx)
                                    frames.addLast(makeFrame(mMeta, listOf(receiver) + args, sp))
                                }
                                receiver is MapVal && receiver.entries[methodName] is CallableVal -> {
                                    val fnVal = receiver.entries[methodName] as CallableVal
                                    val mMeta = getFn(fnVal.funcIndex)
                                    frames.addLast(makeFrame(mMeta, args, sp))
                                }
                                else -> {
                                    val res = evalBuiltinMethod(receiver, methodName, args, line)
                                        ?: throw StudioVmException("VM004", "'${receiver.type.label}' has no method '$methodName'.", line)
                                    push(res)
                                }
                            }
                        }
                        Opcode.CALL_HOST -> {
                            val qualifiedName = (bytecode.constPool[a] as TextVal).value
                            val args = MutableList(b) { NoneVal as StudioValue }
                            for (i in b - 1 downTo 0) args[i] = pop()

                            when (qualifiedName) {
                                "py.iter_items" -> push(ListVal(toIterableList(args.firstOrNull() ?: NoneVal, line).toMutableList()))
                                "py.set_add" -> {
                                    val setV = args.getOrNull(0) as? SetVal
                                        ?: throw StudioVmException("VM002", "Expected set in set comprehension.", line)
                                    setV.items.add(args.getOrElse(1) { NoneVal })
                                    push(setV)
                                }
                                "py.map" -> {
                                    val fnVal = args.getOrNull(0) as? CallableVal
                                        ?: throw StudioVmException("VM002", "map() first argument must be callable.", line)
                                    val seq = toIterableList(args.getOrElse(1) { ListVal() }, line)
                                    val outList = mutableListOf<StudioValue>()
                                    for (elem in seq) {
                                        outList += invokeCallableSubVm(fnVal, listOf(elem), line)
                                    }
                                    push(ListVal(outList))
                                }
                                "py.filter" -> {
                                    val fnVal = args.getOrNull(0)
                                    val seq = toIterableList(args.getOrElse(1) { ListVal() }, line)
                                    val outList = mutableListOf<StudioValue>()
                                    for (elem in seq) {
                                        val keep = if (fnVal == null || fnVal == NoneVal) elem.asBool()
                                        else if (fnVal is CallableVal) invokeCallableSubVm(fnVal, listOf(elem), line).asBool()
                                        else throw StudioVmException("VM002", "filter() first argument must be callable or None.", line)
                                        if (keep) outList += elem
                                    }
                                    push(ListVal(outList))
                                }
                                "py.sorted" -> {
                                    val seq = toIterableList(args.getOrElse(0) { ListVal() }, line)
                                    val keyFn = args.getOrNull(1) as? CallableVal
                                    val reverse = args.getOrNull(2)?.asBool() == true
                                    val keyed = seq.map { elem ->
                                        val k = if (keyFn != null) invokeCallableSubVm(keyFn, listOf(elem), line) else elem
                                        elem to k
                                    }
                                    val sorted = keyed.sortedWith { p1, p2 -> compareValuesDynamic(p1.second, p2.second) }
                                    val finalItems = if (reverse) sorted.reversed().map { it.first } else sorted.map { it.first }
                                    push(ListVal(finalItems.toMutableList()))
                                }
                                else -> push(invokeHostSafe(qualifiedName, args, line))
                            }
                        }
                        Opcode.RETURN -> {
                            val ret = frame.overrideReturn ?: if (sp > frame.baseSp) pop() else NoneVal
                            sp = frame.baseSp
                            frames.removeLast()
                            if (frames.isNotEmpty()) {
                                push(ret)
                            } else {
                                return VmExecutionResult(
                                    success = true,
                                    returnValue = ret,
                                    logs = logs,
                                    instructionsExecuted = instructionsRun,
                                    allocationsCount = allocations,
                                    durationMs = System.currentTimeMillis() - startTime
                                )
                            }
                        }
                        Opcode.TRY_BEGIN -> tryStack.addLast(TryHandler(frames.size, wide, sp))
                        Opcode.TRY_END -> if (tryStack.isNotEmpty() && tryStack.last().frameDepth == frames.size) {
                            tryStack.removeLast()
                        }
                        Opcode.STORE_EXC -> {
                            val errVal = pop()
                            frame.locals[a] = errVal
                        }
                        Opcode.RAISE -> {
                            val msg = pop().asDisplayString()
                            throw StudioVmException("USER_ERR", msg, line)
                        }
                        Opcode.CONFIRM -> {
                            val prompt = pop().asDisplayString()
                            val approved = debugger?.requestConfirm(prompt) ?: true
                            if (!approved) {
                                throw StudioVmException("SEC004", "User cancelled confirmation: $prompt", line)
                            }
                        }
                        Opcode.SLEEP -> {
                            val d = pop()
                            val ms = when (d) {
                                is DurationVal -> d.millis
                                is NumVal -> d.value.toLong()
                                else -> 0L
                            }.coerceIn(0L, 10_000L)
                            if (ms > 0) delay(ms)
                        }
                        Opcode.TIMEOUT_BEGIN -> {
                            val d = pop()
                            val ms = when (d) {
                                is DurationVal -> d.millis
                                is NumVal -> d.value.toLong()
                                else -> policy.defaultTimeoutMs
                            }
                            activeTimeoutEndMs = System.currentTimeMillis() + ms
                        }
                        Opcode.TIMEOUT_END -> activeTimeoutEndMs = null
                    }
                } catch (e: StudioVmException) {
                    if (tryStack.isNotEmpty()) {
                        val handler = tryStack.removeLast()
                        while (frames.size > handler.frameDepth) frames.removeLast()
                        while (sp > handler.savedSp) stack[--sp] = null
                        val errMap = MapVal(
                            linkedMapOf(
                                "code" to TextVal(e.code),
                                "message" to TextVal(e.message),
                                "line" to NumVal(e.line.toDouble())
                            )
                        )
                        push(errMap)
                        frames.last().pc = handler.catchPc
                    } else {
                        throw e
                    }
                }
            }
            return VmExecutionResult(
                success = true,
                returnValue = NoneVal,
                logs = logs,
                instructionsExecuted = instructionsRun,
                allocationsCount = allocations,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudioVmException) {
            logs += VmLogEntry("ERROR", e.message, e.line)
            return VmExecutionResult(
                success = false,
                returnValue = NoneVal,
                logs = logs,
                instructionsExecuted = instructionsRun,
                allocationsCount = allocations,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Unexpected VM failure"
            logs += VmLogEntry("ERROR", msg)
            return VmExecutionResult(
                success = false,
                returnValue = NoneVal,
                logs = logs,
                instructionsExecuted = instructionsRun,
                allocationsCount = allocations,
                durationMs = System.currentTimeMillis() - startTime,
                error = msg
            )
        }
    }

    private fun evalUnary(code: Int, v: StudioValue): StudioValue = when (code) {
        0 -> NumVal(-v.asDouble())
        2 -> NumVal(v.asDouble().toLong().inv().toDouble())
        else -> BoolVal(!v.asBool())
    }

    private fun resolveMember(target: StudioValue, member: String): StudioValue = when (target) {
        is ObjectVal -> target.fields[member] ?: target.methods[member]?.let { idx ->
            CallableVal(idx, "${target.className}.$member", boundSelf = target)
        } ?: NoneVal
        is MapVal -> target.entries[member] ?: NoneVal
        is ListVal -> when (member) {
            "length", "len" -> NumVal(target.items.size.toDouble())
            else -> NoneVal
        }
        is TupleVal -> when (member) {
            "length", "len" -> NumVal(target.items.size.toDouble())
            else -> NoneVal
        }
        is SetVal -> when (member) {
            "length", "len" -> NumVal(target.items.size.toDouble())
            else -> NoneVal
        }
        is TextVal -> when (member) {
            "length", "len" -> NumVal(target.value.length.toDouble())
            else -> NoneVal
        }
        is NamespaceVal -> when ("${target.name}.$member") {
            "math.pi" -> NumVal(kotlin.math.PI)
            "math.e" -> NumVal(kotlin.math.E)
            "math.tau" -> NumVal(kotlin.math.PI * 2.0)
            else -> NoneVal
        }
        else -> NoneVal
    }

    private fun resolveIndex(target: StudioValue, idx: StudioValue, line: Int): StudioValue = when (target) {
        is ListVal -> {
            var i = idx.asDouble().toInt()
            if (i < 0) i += target.items.size
            target.items.getOrElse(i) {
                throw StudioVmException("VM006", "Index $i out of bounds for list of size ${target.items.size}.", line)
            }
        }
        is TupleVal -> {
            var i = idx.asDouble().toInt()
            if (i < 0) i += target.items.size
            target.items.getOrElse(i) {
                throw StudioVmException("VM006", "Index $i out of bounds for tuple of size ${target.items.size}.", line)
            }
        }
        is MapVal -> target.entries[idx.asDisplayString()] ?: NoneVal
        is TextVal -> {
            var i = idx.asDouble().toInt()
            if (i < 0) i += target.value.length
            if (i !in target.value.indices) throw StudioVmException("VM006", "Text index $i out of bounds.", line)
            TextVal(target.value[i].toString())
        }
        else -> throw StudioVmException("VM006", "Cannot index into ${target.type.label}.", line)
    }

    private fun evalSlice(target: StudioValue, startV: StudioValue, stopV: StudioValue, stepV: StudioValue, line: Int): StudioValue {
        val len = when (target) {
            is ListVal -> target.items.size
            is TupleVal -> target.items.size
            is TextVal -> target.value.length
            else -> throw StudioVmException("VM006", "Cannot slice ${target.type.label}.", line)
        }
        val step = if (stepV == NoneVal) 1 else stepV.asDouble().toInt()
        if (step == 0) throw StudioVmException("VM006", "Slice step cannot be zero.", line)

        fun normalizeIdx(raw: Int): Int {
            val adj = if (raw < 0) raw + len else raw
            return if (step > 0) adj.coerceIn(0, len) else adj.coerceIn(-1, len - 1)
        }

        val start = if (startV == NoneVal) {
            if (step > 0) 0 else len - 1
        } else normalizeIdx(startV.asDouble().toInt())

        val stop = if (stopV == NoneVal) {
            if (step > 0) len else -1
        } else normalizeIdx(stopV.asDouble().toInt())

        val indices = mutableListOf<Int>()
        var i = start
        if (step > 0) {
            while (i < stop) { indices += i; i += step }
        } else {
            while (i > stop) { indices += i; i += step }
        }

        return when (target) {
            is ListVal -> ListVal(indices.mapTo(mutableListOf()) { target.items[it] })
            is TupleVal -> TupleVal(indices.map { target.items[it] })
            is TextVal -> TextVal(buildString { for (idx in indices) append(target.value[idx]) })
            else -> NoneVal
        }
    }

    private fun toIterableList(seq: StudioValue, line: Int): List<StudioValue> = when (seq) {
        is ListVal -> seq.items
        is TupleVal -> seq.items
        is SetVal -> seq.items.toList()
        is MapVal -> seq.entries.keys.map { TextVal(it) }
        is TextVal -> seq.value.map { TextVal(it.toString()) }
        else -> throw StudioVmException("VM006", "Object of type '${seq.type.label}' is not iterable.", line)
    }

    private fun evalBuiltinMethod(receiver: StudioValue, method: String, args: List<StudioValue>, line: Int): StudioValue? = when (receiver) {
        is TextVal -> when (method) {
            "upper" -> TextVal(receiver.value.uppercase())
            "lower" -> TextVal(receiver.value.lowercase())
            "strip" -> TextVal(receiver.value.trim())
            "lstrip" -> TextVal(receiver.value.trimStart())
            "rstrip" -> TextVal(receiver.value.trimEnd())
            "title" -> TextVal(receiver.value.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } })
            "capitalize" -> TextVal(receiver.value.replaceFirstChar { it.uppercaseChar() })
            "split" -> {
                val sep = args.getOrNull(0)?.asDisplayString()
                val parts = if (sep == null || sep.isEmpty()) receiver.value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                else receiver.value.split(sep)
                ListVal(parts.mapTo(mutableListOf()) { TextVal(it) })
            }
            "join" -> {
                val list = toIterableList(args.getOrElse(0) { ListVal() }, line)
                TextVal(list.joinToString(receiver.value) { it.asDisplayString() })
            }
            "replace" -> TextVal(receiver.value.replace(args.getOrElse(0) { TextVal("") }.asDisplayString(), args.getOrElse(1) { TextVal("") }.asDisplayString()))
            "startswith" -> BoolVal(receiver.value.startsWith(args.getOrElse(0) { TextVal("") }.asDisplayString()))
            "endswith" -> BoolVal(receiver.value.endsWith(args.getOrElse(0) { TextVal("") }.asDisplayString()))
            "find" -> NumVal(receiver.value.indexOf(args.getOrElse(0) { TextVal("") }.asDisplayString()).toDouble())
            "count" -> {
                val sub = args.getOrElse(0) { TextVal("") }.asDisplayString()
                if (sub.isEmpty()) NumVal((receiver.value.length + 1).toDouble())
                else {
                    var count = 0; var idx = 0
                    while (true) {
                        val found = receiver.value.indexOf(sub, idx)
                        if (found == -1) break
                        count++; idx = found + sub.length
                    }
                    NumVal(count.toDouble())
                }
            }
            "isdigit" -> BoolVal(receiver.value.isNotEmpty() && receiver.value.all { it.isDigit() })
            "isalpha" -> BoolVal(receiver.value.isNotEmpty() && receiver.value.all { it.isLetter() })
            "format" -> {
                var out = receiver.value
                args.forEachIndexed { i, arg ->
                    val s = arg.asDisplayString()
                    out = if (out.contains("{}")) out.replaceFirst("{}", s) else out.replace("{$i}", s)
                }
                TextVal(out)
            }
            else -> null
        }
        is ListVal -> when (method) {
            "append" -> { receiver.items.add(args.getOrElse(0) { NoneVal }); NoneVal }
            "extend" -> { receiver.items.addAll(toIterableList(args.getOrElse(0) { ListVal() }, line)); NoneVal }
            "pop" -> {
                if (receiver.items.isEmpty()) throw StudioVmException("VM006", "pop from empty list", line)
                val idx = args.getOrNull(0)?.asDouble()?.toInt()?.let { if (it < 0) it + receiver.items.size else it } ?: (receiver.items.lastIndex)
                receiver.items.removeAt(idx.coerceIn(receiver.items.indices))
            }
            "insert" -> {
                val idx = args.getOrElse(0) { NumVal(0.0) }.asDouble().toInt().coerceIn(0, receiver.items.size)
                receiver.items.add(idx, args.getOrElse(1) { NoneVal })
                NoneVal
            }
            "remove" -> {
                val target = args.getOrElse(0) { NoneVal }
                receiver.items.remove(target)
                NoneVal
            }
            "clear" -> { receiver.items.clear(); NoneVal }
            "copy" -> ListVal(receiver.items.toMutableList())
            "count" -> NumVal(receiver.items.count { it == args.getOrElse(0) { NoneVal } }.toDouble())
            "index" -> NumVal(receiver.items.indexOf(args.getOrElse(0) { NoneVal }).toDouble())
            "reverse" -> { receiver.items.reverse(); NoneVal }
            "sort" -> {
                val rev = args.getOrNull(0)?.asBool() == true
                receiver.items.sortWith { a, b -> compareValuesDynamic(a, b) }
                if (rev) receiver.items.reverse()
                NoneVal
            }
            else -> null
        }
        is MapVal -> when (method) {
            "get" -> receiver.entries[args.getOrElse(0) { TextVal("") }.asDisplayString()] ?: args.getOrElse(1) { NoneVal }
            "keys" -> ListVal(receiver.entries.keys.mapTo(mutableListOf()) { TextVal(it) })
            "values" -> ListVal(receiver.entries.values.toMutableList())
            "items" -> ListVal(receiver.entries.entries.mapTo(mutableListOf()) { TupleVal(listOf(TextVal(it.key), it.value)) })
            "pop" -> receiver.entries.remove(args.getOrElse(0) { TextVal("") }.asDisplayString()) ?: args.getOrElse(1) { NoneVal }
            "update" -> {
                val other = args.getOrNull(0) as? MapVal
                other?.entries?.forEach { (k, v) -> receiver.entries[k] = v }
                NoneVal
            }
            "setdefault" -> {
                val key = args.getOrElse(0) { TextVal("") }.asDisplayString()
                receiver.entries.getOrPut(key) { args.getOrElse(1) { NoneVal } }
            }
            "clear" -> { receiver.entries.clear(); NoneVal }
            "copy" -> MapVal(LinkedHashMap(receiver.entries))
            else -> null
        }
        is SetVal -> when (method) {
            "add" -> { receiver.items.add(args.getOrElse(0) { NoneVal }); NoneVal }
            "discard", "remove" -> { receiver.items.remove(args.getOrElse(0) { NoneVal }); NoneVal }
            "clear" -> { receiver.items.clear(); NoneVal }
            "copy" -> SetVal(LinkedHashSet(receiver.items))
            "union" -> {
                val other = toIterableList(args.getOrElse(0) { SetVal() }, line)
                SetVal(LinkedHashSet<StudioValue>().apply { addAll(receiver.items); addAll(other) })
            }
            "intersection" -> {
                val other = toIterableList(args.getOrElse(0) { SetVal() }, line).toSet()
                SetVal(receiver.items.filterTo(LinkedHashSet()) { it in other })
            }
            "difference" -> {
                val other = toIterableList(args.getOrElse(0) { SetVal() }, line).toSet()
                SetVal(receiver.items.filterTo(LinkedHashSet()) { it !in other })
            }
            else -> null
        }
        is TupleVal -> when (method) {
            "count" -> NumVal(receiver.items.count { it == args.getOrElse(0) { NoneVal } }.toDouble())
            "index" -> NumVal(receiver.items.indexOf(args.getOrElse(0) { NoneVal }).toDouble())
            else -> null
        }
        else -> null
    }

    private fun compareValuesDynamic(a: StudioValue, b: StudioValue): Int = when {
        a is NumVal && b is NumVal -> a.value.compareTo(b.value)
        a is TextVal && b is TextVal -> a.value.compareTo(b.value)
        else -> a.asDisplayString().compareTo(b.asDisplayString())
    }

    private fun evalBinary(l: StudioValue, op: BinaryOp, r: StudioValue, line: Int): StudioValue = when (op) {
        BinaryOp.ADD -> when {
            l is ListVal && r is ListVal -> ListVal((l.items + r.items).toMutableList())
            l is TupleVal && r is TupleVal -> TupleVal(l.items + r.items)
            l is TextVal && r is TextVal -> TextVal(l.value + r.value, l.isSecret || r.isSecret)
            l is TextVal || r is TextVal -> TextVal(l.asDisplayString() + r.asDisplayString())
            else -> NumVal(l.asDouble() + r.asDouble())
        }
        BinaryOp.SUB -> when {
            l is SetVal && r is SetVal -> SetVal(l.items.filterTo(LinkedHashSet()) { it !in r.items })
            else -> NumVal(l.asDouble() - r.asDouble())
        }
        BinaryOp.MUL -> when {
            l is TextVal && r is NumVal -> TextVal(l.value.repeat(r.value.toInt().coerceAtLeast(0)))
            l is NumVal && r is TextVal -> TextVal(r.value.repeat(l.value.toInt().coerceAtLeast(0)))
            l is ListVal && r is NumVal -> {
                val n = r.value.toInt().coerceIn(0, 10000)
                val out = mutableListOf<StudioValue>()
                repeat(n) { out.addAll(l.items) }
                ListVal(out)
            }
            else -> NumVal(l.asDouble() * r.asDouble())
        }
        BinaryOp.DIV -> {
            val rv = r.asDouble()
            if (rv == 0.0) throw StudioVmException("VM007", "Division by zero.", line)
            NumVal(l.asDouble() / rv)
        }
        BinaryOp.FLOOR_DIV -> {
            val rv = r.asDouble()
            if (rv == 0.0) throw StudioVmException("VM007", "Division by zero.", line)
            NumVal(floor(l.asDouble() / rv))
        }
        BinaryOp.MOD -> {
            val rv = r.asDouble()
            if (rv == 0.0) throw StudioVmException("VM007", "Modulo by zero.", line)
            NumVal(l.asDouble() % rv)
        }
        BinaryOp.POW -> NumVal(l.asDouble().pow(r.asDouble()))
        BinaryOp.BIT_AND -> when {
            l is SetVal && r is SetVal -> SetVal(l.items.filterTo(LinkedHashSet()) { it in r.items })
            else -> NumVal((l.asDouble().toLong() and r.asDouble().toLong()).toDouble())
        }
        BinaryOp.BIT_OR -> when {
            l is SetVal && r is SetVal -> SetVal(LinkedHashSet<StudioValue>().apply { addAll(l.items); addAll(r.items) })
            else -> NumVal((l.asDouble().toLong() or r.asDouble().toLong()).toDouble())
        }
        BinaryOp.BIT_XOR -> NumVal((l.asDouble().toLong() xor r.asDouble().toLong()).toDouble())
        BinaryOp.SHL -> NumVal((l.asDouble().toLong() shl r.asDouble().toInt()).toDouble())
        BinaryOp.SHR -> NumVal((l.asDouble().toLong() shr r.asDouble().toInt()).toDouble())
        BinaryOp.EQ -> BoolVal(l == r)
        BinaryOp.NEQ -> BoolVal(l != r)
        BinaryOp.LT -> BoolVal(compareValuesDynamic(l, r) < 0)
        BinaryOp.LTE -> BoolVal(compareValuesDynamic(l, r) <= 0)
        BinaryOp.GT -> BoolVal(compareValuesDynamic(l, r) > 0)
        BinaryOp.GTE -> BoolVal(compareValuesDynamic(l, r) >= 0)
        BinaryOp.IN -> BoolVal(checkIn(l, r))
        BinaryOp.NOT_IN -> BoolVal(!checkIn(l, r))
        BinaryOp.IS -> BoolVal(l === r || l == r)
        BinaryOp.IS_NOT -> BoolVal(!(l === r || l == r))
        BinaryOp.AND -> BoolVal(l.asBool() && r.asBool())
        BinaryOp.OR -> BoolVal(l.asBool() || r.asBool())
    }

    private fun checkIn(item: StudioValue, container: StudioValue): Boolean = when (container) {
        is ListVal -> item in container.items
        is TupleVal -> item in container.items
        is SetVal -> item in container.items
        is MapVal -> item.asDisplayString() in container.entries
        is TextVal -> item.asDisplayString() in container.value
        else -> false
    }
}
