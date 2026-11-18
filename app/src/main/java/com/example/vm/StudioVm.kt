package com.example.vm

import com.example.lang.BinaryOp
import com.example.lang.StudioCapability
import com.example.lang.UnaryOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

data class ExecutionPolicy(
    val maxInstructions: Long = 250_000L,
    val maxStackDepth: Int = 512,
    val maxRecursionDepth: Int = 64,
    val maxAllocations: Int = 50_000,
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
 * Custom Virtual Machine for verified StudioLang bytecode.
 * Uses a pre-allocated operand stack, explicit call frames, resource quotas,
 * timeout enforcement, capability checks, and cooperative cancellation.
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

        val logs = mutableListOf<VmLogEntry>()
        val startTime = System.currentTimeMillis()
        var instructionsRun = 0L
        var allocations = 0

        // Reusable pre-allocated operand stack & globals
        val stack = arrayOfNulls<StudioValue>(policy.maxStackDepth)
        var sp = 0
        val globals = mutableMapOf<String, StudioValue>()

        data class TryHandler(val frameDepth: Int, val catchPc: Int, val savedSp: Int)
        data class CallFrame(
            val meta: FunctionBytecodeMeta,
            val locals: Array<StudioValue>,
            var pc: Int,
            val baseSp: Int
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

        val entryMeta = bytecode.functions.firstOrNull { it.index == entryFunctionIndex }
            ?: return VmExecutionResult(false, NoneVal, logs, 0, 0, 0, "Entry function $entryFunctionIndex missing.")

        val entryLocals = Array<StudioValue>(entryMeta.localCount.coerceAtLeast(1)) { NoneVal }
        initialArgs.forEachIndexed { idx, v ->
            if (idx < entryLocals.size) entryLocals[idx] = v
        }
        frames.addLast(CallFrame(entryMeta, entryLocals, entryMeta.startPc, 0))

        try {
            while (frames.isNotEmpty()) {
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

                val frame = frames.last()
                if (frame.pc >= frame.meta.endPc) {
                    val ret = if (sp > frame.baseSp) pop() else NoneVal
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
                val line = bytecode.lineTable.getOrElse(pc) { 1 }

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
                        Opcode.UNARY -> {
                            val v = pop()
                            push(if (a == 0) NumVal(-v.asDouble()) else BoolVal(!v.asBool()))
                        }
                        Opcode.BINARY -> {
                            val r = pop()
                            val l = pop()
                            val binOp = BinaryOp.entries[a]
                            push(evalBinary(l, binOp, r, line))
                        }
                        Opcode.MAKE_LIST -> {
                            trackAlloc()
                            val items = MutableList(a) { NoneVal as StudioValue }
                            for (i in a - 1 downTo 0) items[i] = pop()
                            push(ListVal(items))
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
                            if (target is MapVal) target.entries[member] = value
                            else throw StudioVmException("VM004", "Cannot set property '$member' on ${target.type.label}.", line)
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
                                    val i = idx.asDouble().toInt()
                                    if (i !in target.items.indices) throw StudioVmException("VM005", "List index $i out of bounds.", line)
                                    target.items[i] = value
                                }
                                is MapVal -> target.entries[idx.asDisplayString()] = value
                                else -> throw StudioVmException("VM005", "Cannot index-assign ${target.type.label}.", line)
                            }
                        }
                        Opcode.JUMP -> frame.pc = wide
                        Opcode.JUMP_IF_FALSE -> if (!pop().asBool()) frame.pc = wide
                        Opcode.JUMP_IF_TRUE -> if (pop().asBool()) frame.pc = wide
                        Opcode.CALL_FUNC -> {
                            if (frames.size >= policy.maxRecursionDepth) {
                                throw StudioVmException("VM013", "Recursion depth limit (${policy.maxRecursionDepth}) exceeded.", line)
                            }
                            val callee = bytecode.functions.first { it.index == a }
                            val args = MutableList(b) { NoneVal as StudioValue }
                            for (i in b - 1 downTo 0) args[i] = pop()
                            val newLocals = Array<StudioValue>(callee.localCount.coerceAtLeast(1)) { NoneVal }
                            args.forEachIndexed { idx, v -> if (idx < newLocals.size) newLocals[idx] = v }
                            frames.addLast(CallFrame(callee, newLocals, callee.startPc, sp))
                        }
                        Opcode.CALL_HOST -> {
                            val qualifiedName = (bytecode.constPool[a] as TextVal).value
                            val args = MutableList(b) { NoneVal as StudioValue }
                            for (i in b - 1 downTo 0) args[i] = pop()

                            // Dual capability check before executing host API
                            val sig = com.example.lang.StudioAnalyzer.DEFAULT_HOST_SIGNATURES[qualifiedName]
                                ?: throw StudioVmException("SEC001", "Unlisted host API '$qualifiedName'.", line)
                            val cap = sig.requiredCapability
                            if (cap != null && !capabilityManager.isGranted(cap)) {
                                throw StudioVmException(
                                    "SEC003",
                                    "Missing Automation Studio capability '${cap.id}' for '$qualifiedName'. Grant it in Security & Capabilities.",
                                    line
                                )
                            }

                            val res = hostDispatcher.invokeHost(qualifiedName, args, line) { lvl, msg ->
                                logs += VmLogEntry(lvl, msg, line)
                            }
                            push(res)
                        }
                        Opcode.RETURN -> {
                            val ret = if (sp > frame.baseSp) pop() else NoneVal
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

    private fun resolveMember(target: StudioValue, member: String): StudioValue = when (target) {
        is MapVal -> target.entries[member] ?: NoneVal
        is ListVal -> when (member) {
            "length", "len" -> NumVal(target.items.size.toDouble())
            else -> NoneVal
        }
        is TextVal -> when (member) {
            "length", "len" -> NumVal(target.value.length.toDouble())
            else -> NoneVal
        }
        else -> NoneVal
    }

    private fun resolveIndex(target: StudioValue, idx: StudioValue, line: Int): StudioValue = when (target) {
        is ListVal -> {
            val i = idx.asDouble().toInt()
            target.items.getOrElse(i) {
                throw StudioVmException("VM006", "Index $i out of bounds for list of size ${target.items.size}.", line)
            }
        }
        is MapVal -> target.entries[idx.asDisplayString()] ?: NoneVal
        is TextVal -> {
            val i = idx.asDouble().toInt()
            if (i !in target.value.indices) throw StudioVmException("VM006", "Text index $i out of bounds.", line)
            TextVal(target.value[i].toString())
        }
        else -> throw StudioVmException("VM006", "Cannot index into ${target.type.label}.", line)
    }

    private fun evalBinary(l: StudioValue, op: BinaryOp, r: StudioValue, line: Int): StudioValue = when (op) {
        BinaryOp.ADD -> if (l is TextVal || r is TextVal) {
            TextVal(l.asDisplayString() + r.asDisplayString())
        } else {
            NumVal(l.asDouble() + r.asDouble())
        }
        BinaryOp.SUB -> NumVal(l.asDouble() - r.asDouble())
        BinaryOp.MUL -> NumVal(l.asDouble() * r.asDouble())
        BinaryOp.DIV -> {
            val rv = r.asDouble()
            if (rv == 0.0) throw StudioVmException("VM007", "Division by zero.", line)
            NumVal(l.asDouble() / rv)
        }
        BinaryOp.MOD -> {
            val rv = r.asDouble()
            if (rv == 0.0) throw StudioVmException("VM007", "Modulo by zero.", line)
            NumVal(l.asDouble() % rv)
        }
        BinaryOp.POW -> NumVal(l.asDouble().pow(r.asDouble()))
        BinaryOp.EQ -> BoolVal(l == r)
        BinaryOp.NEQ -> BoolVal(l != r)
        BinaryOp.LT -> BoolVal(l.asDouble() < r.asDouble())
        BinaryOp.LTE -> BoolVal(l.asDouble() <= r.asDouble())
        BinaryOp.GT -> BoolVal(l.asDouble() > r.asDouble())
        BinaryOp.GTE -> BoolVal(l.asDouble() >= r.asDouble())
        BinaryOp.IN -> when (r) {
            is ListVal -> BoolVal(l in r.items)
            is MapVal -> BoolVal(l.asDisplayString() in r.entries)
            is TextVal -> BoolVal(l.asDisplayString() in r.value)
            else -> BoolVal(false)
        }
        BinaryOp.IS -> BoolVal(l == r)
        BinaryOp.AND -> BoolVal(l.asBool() && r.asBool())
        BinaryOp.OR -> BoolVal(l.asBool() || r.asBool())
    }
}
