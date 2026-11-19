package com.example.vm

import com.example.lang.BinaryOp
import com.example.lang.LanguageConfig
import com.example.lang.StudioCapability
import com.example.lang.UnaryOp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Custom compact 32-bit stack-based bytecode ISA for StudioLang VM.
 * Instruction encoding:
 *   [31..24]: opcode (8 bits)
 *   [23..12]: operand A (12 bits unsigned, 0..4095)
 *   [11..0] : operand B (12 bits unsigned, 0..4095)
 */
object Opcode {
    const val NOP = 0
    const val LOAD_CONST = 1      // A = constPool index
    const val LOAD_LOCAL = 2      // A = local slot
    const val STORE_LOCAL = 3     // A = local slot
    const val LOAD_GLOBAL = 4     // A = constPool(text name)
    const val STORE_GLOBAL = 5    // A = constPool(text name)
    const val POP = 6
    const val DUP = 7
    const val UNARY = 8           // A = 0:NEG, 1:NOT, 2:BIT_NOT
    const val BINARY = 9          // A = BinaryOp.ordinal
    const val MAKE_LIST = 10      // A = element count
    const val MAKE_MAP = 11       // A = entry count
    const val GET_MEMBER = 12     // A = constPool(member name)
    const val SET_MEMBER = 13     // A = constPool(member name)
    const val GET_INDEX = 14
    const val SET_INDEX = 15
    const val JUMP = 16           // AB = target PC
    const val JUMP_IF_FALSE = 17  // AB = target PC
    const val JUMP_IF_TRUE = 18   // AB = target PC
    const val CALL_FUNC = 19      // A = funcIndex, B = argCount
    const val CALL_HOST = 20      // A = constPool(qualifiedName), B = argCount
    const val RETURN = 21
    const val TRY_BEGIN = 22      // AB = catch PC
    const val TRY_END = 23
    const val STORE_EXC = 24      // A = local slot
    const val RAISE = 25
    const val CONFIRM = 26
    const val SLEEP = 27
    const val TIMEOUT_BEGIN = 28
    const val TIMEOUT_END = 29

    // Extended Python & OOP opcodes
    const val MAKE_TUPLE = 30     // A = element count
    const val MAKE_SET = 31       // A = element count
    const val SLICE_GET = 32      // pops step, stop, start, target
    const val DEL_INDEX = 33      // pops key/index, target
    const val CALL_DYNAMIC = 34   // A = argCount (pops args then callee)
    const val CALL_METHOD = 35    // A = constPool(methodName), B = argCount (pops args then receiver)
    const val UNPACK = 36         // A = target count (pops iterable, pushes N items top-down)

    fun pack(op: Int, a: Int = 0, b: Int = 0): Int =
        ((op and 0xFF) shl 24) or ((a and 0xFFF) shl 12) or (b and 0xFFF)

    fun packWide(op: Int, ab: Int): Int =
        ((op and 0xFF) shl 24) or (ab and 0xFFFFFF)

    fun opcode(ins: Int): Int = (ins ushr 24) and 0xFF
    fun argA(ins: Int): Int = (ins ushr 12) and 0xFFF
    fun argB(ins: Int): Int = ins and 0xFFF
    fun argWide(ins: Int): Int = ins and 0xFFFFFF
}

data class ClassBytecodeMeta(
    val name: String,
    val parentName: String?,
    val methodFuncIndices: Map<String, Int>
)

data class FunctionBytecodeMeta(
    val index: Int,
    val name: String,
    val kind: CallableKind,
    val startPc: Int,
    val endPc: Int,
    val paramCount: Int,
    val localCount: Int,
    val localNames: List<String>,
    val requiredNeeds: Set<StudioCapability>,
    val paramNames: List<String> = emptyList(),
    val hasVarArg: Boolean = false,
    val hasKwArg: Boolean = false
)

data class CompiledBytecode(
    val magic: Int = LanguageConfig.BYTECODE_MAGIC,
    val version: Int = LanguageConfig.BYTECODE_VERSION,
    val constPool: List<StudioValue>,
    val functions: List<FunctionBytecodeMeta>,
    val macros: List<IrMacroDef>,
    val triggers: List<IrTriggerDef>,
    val requiredCapabilities: Set<StudioCapability>,
    val instructions: IntArray,
    val lineTable: IntArray,
    val classes: List<ClassBytecodeMeta> = emptyList()
) {
    fun encodeToBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(magic)
            out.writeInt(version)
            out.writeInt(constPool.size)
            for (c in constPool) {
                when (c) {
                    is NumVal -> { out.writeByte(1); out.writeDouble(c.value) }
                    is TextVal -> { out.writeByte(2); out.writeUTF(c.value) }
                    is BoolVal -> { out.writeByte(3); out.writeBoolean(c.value) }
                    is DurationVal -> { out.writeByte(4); out.writeLong(c.millis) }
                    NoneVal -> out.writeByte(5)
                    is CallableVal -> {
                        out.writeByte(6)
                        out.writeInt(c.funcIndex)
                        out.writeUTF(c.name)
                        out.writeBoolean(c.isClass)
                    }
                    else -> out.writeByte(5)
                }
            }
            out.writeInt(requiredCapabilities.size)
            for (cap in requiredCapabilities) out.writeUTF(cap.id)
            out.writeInt(functions.size)
            for (fn in functions) {
                out.writeInt(fn.index)
                out.writeUTF(fn.name)
                out.writeInt(fn.kind.ordinal)
                out.writeInt(fn.startPc)
                out.writeInt(fn.endPc)
                out.writeInt(fn.paramCount)
                out.writeInt(fn.localCount)
            }
            out.writeInt(instructions.size)
            for (ins in instructions) out.writeInt(ins)
            for (ln in lineTable) out.writeInt(ln)
        }
        return baos.toByteArray()
    }

    companion object {
        fun decodeFromBytes(bytes: ByteArray): CompiledBytecode {
            DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
                val magic = inp.readInt()
                val version = inp.readInt()
                val cSize = inp.readInt()
                val consts = mutableListOf<StudioValue>()
                repeat(cSize) {
                    consts += when (inp.readByte().toInt()) {
                        1 -> NumVal(inp.readDouble())
                        2 -> TextVal(inp.readUTF())
                        3 -> BoolVal(inp.readBoolean())
                        4 -> DurationVal(inp.readLong())
                        6 -> CallableVal(inp.readInt(), inp.readUTF(), isClass = inp.readBoolean())
                        else -> NoneVal
                    }
                }
                val capCount = inp.readInt()
                val caps = linkedSetOf<StudioCapability>()
                repeat(capCount) {
                    StudioCapability.fromId(inp.readUTF())?.let { caps += it }
                }
                val fnCount = inp.readInt()
                val fns = mutableListOf<FunctionBytecodeMeta>()
                repeat(fnCount) {
                    fns += FunctionBytecodeMeta(
                        index = inp.readInt(),
                        name = inp.readUTF(),
                        kind = CallableKind.entries[inp.readInt()],
                        startPc = inp.readInt(),
                        endPc = inp.readInt(),
                        paramCount = inp.readInt(),
                        localCount = inp.readInt(),
                        localNames = emptyList(),
                        requiredNeeds = emptySet()
                    )
                }
                val insCount = inp.readInt()
                val ins = IntArray(insCount) { inp.readInt() }
                val lines = IntArray(insCount) { inp.readInt() }
                return CompiledBytecode(
                    magic = magic,
                    version = version,
                    constPool = consts,
                    functions = fns,
                    macros = emptyList(),
                    triggers = emptyList(),
                    requiredCapabilities = caps,
                    instructions = ins,
                    lineTable = lines
                )
            }
        }
    }
}

class BytecodeCompiler {
    private val constPool = mutableListOf<StudioValue>()
    private val constLookup = mutableMapOf<StudioValue, Int>()

    fun compile(module: IrModule): CompiledBytecode {
        constPool.clear()
        constLookup.clear()

        val instructions = mutableListOf<Int>()
        val lines = mutableListOf<Int>()
        val fnMetas = mutableListOf<FunctionBytecodeMeta>()

        for (fn in module.functions) {
            val startPc = instructions.size
            val labelToPc = mutableMapOf<Int, Int>()
            val pendingJumps = mutableListOf<Triple<Int, Int, Int>>()

            for (op in fn.ops) {
                when (op) {
                    is IrLabel -> labelToPc[op.id] = instructions.size
                    is IrLoadConst -> emit(instructions, lines, Opcode.pack(Opcode.LOAD_CONST, constIdx(op.value)), op.line)
                    is IrLoadLocal -> emit(instructions, lines, Opcode.pack(Opcode.LOAD_LOCAL, op.slot), op.line)
                    is IrStoreLocal -> emit(instructions, lines, Opcode.pack(Opcode.STORE_LOCAL, op.slot), op.line)
                    is IrLoadGlobal -> emit(instructions, lines, Opcode.pack(Opcode.LOAD_GLOBAL, constIdx(TextVal(op.name))), op.line)
                    is IrStoreGlobal -> emit(instructions, lines, Opcode.pack(Opcode.STORE_GLOBAL, constIdx(TextVal(op.name))), op.line)
                    is IrPop -> emit(instructions, lines, Opcode.pack(Opcode.POP), op.line)
                    is IrDup -> emit(instructions, lines, Opcode.pack(Opcode.DUP), op.line)
                    is IrUnary -> {
                        val uCode = when (op.op) {
                            UnaryOp.NEG -> 0
                            UnaryOp.NOT -> 1
                            UnaryOp.BIT_NOT -> 2
                        }
                        emit(instructions, lines, Opcode.pack(Opcode.UNARY, uCode), op.line)
                    }
                    is IrBinary -> emit(instructions, lines, Opcode.pack(Opcode.BINARY, op.op.ordinal), op.line)
                    is IrMakeList -> emit(instructions, lines, Opcode.pack(Opcode.MAKE_LIST, op.count), op.line)
                    is IrMakeTuple -> emit(instructions, lines, Opcode.pack(Opcode.MAKE_TUPLE, op.count), op.line)
                    is IrMakeSet -> emit(instructions, lines, Opcode.pack(Opcode.MAKE_SET, op.count), op.line)
                    is IrMakeMap -> emit(instructions, lines, Opcode.pack(Opcode.MAKE_MAP, op.entryCount), op.line)
                    is IrGetMember -> emit(instructions, lines, Opcode.pack(Opcode.GET_MEMBER, constIdx(TextVal(op.member))), op.line)
                    is IrSetMember -> emit(instructions, lines, Opcode.pack(Opcode.SET_MEMBER, constIdx(TextVal(op.member))), op.line)
                    is IrGetIndex -> emit(instructions, lines, Opcode.pack(Opcode.GET_INDEX), op.line)
                    is IrSetIndex -> emit(instructions, lines, Opcode.pack(Opcode.SET_INDEX), op.line)
                    is IrSliceGet -> emit(instructions, lines, Opcode.pack(Opcode.SLICE_GET), op.line)
                    is IrDelIndex -> emit(instructions, lines, Opcode.pack(Opcode.DEL_INDEX), op.line)
                    is IrUnpack -> emit(instructions, lines, Opcode.pack(Opcode.UNPACK, op.count), op.line)
                    is IrJump -> {
                        pendingJumps += Triple(instructions.size, Opcode.JUMP, op.targetLabel)
                        emit(instructions, lines, Opcode.packWide(Opcode.JUMP, 0), op.line)
                    }
                    is IrJumpIfFalse -> {
                        pendingJumps += Triple(instructions.size, Opcode.JUMP_IF_FALSE, op.targetLabel)
                        emit(instructions, lines, Opcode.packWide(Opcode.JUMP_IF_FALSE, 0), op.line)
                    }
                    is IrJumpIfTrue -> {
                        pendingJumps += Triple(instructions.size, Opcode.JUMP_IF_TRUE, op.targetLabel)
                        emit(instructions, lines, Opcode.packWide(Opcode.JUMP_IF_TRUE, 0), op.line)
                    }
                    is IrCallFunc -> emit(instructions, lines, Opcode.pack(Opcode.CALL_FUNC, op.funcIndex, op.argCount), op.line)
                    is IrCallHost -> emit(instructions, lines, Opcode.pack(Opcode.CALL_HOST, constIdx(TextVal(op.qualifiedName)), op.argCount), op.line)
                    is IrCallDynamic -> emit(instructions, lines, Opcode.pack(Opcode.CALL_DYNAMIC, op.argCount), op.line)
                    is IrCallMethod -> emit(instructions, lines, Opcode.pack(Opcode.CALL_METHOD, constIdx(TextVal(op.methodName)), op.argCount), op.line)
                    is IrReturn -> emit(instructions, lines, Opcode.pack(Opcode.RETURN), op.line)
                    is IrTryBegin -> {
                        pendingJumps += Triple(instructions.size, Opcode.TRY_BEGIN, op.catchLabel)
                        emit(instructions, lines, Opcode.packWide(Opcode.TRY_BEGIN, 0), op.line)
                    }
                    is IrTryEnd -> emit(instructions, lines, Opcode.pack(Opcode.TRY_END), op.line)
                    is IrStoreException -> emit(instructions, lines, Opcode.pack(Opcode.STORE_EXC, op.slot), op.line)
                    is IrRaise -> emit(instructions, lines, Opcode.pack(Opcode.RAISE), op.line)
                    is IrConfirm -> emit(instructions, lines, Opcode.pack(Opcode.CONFIRM), op.line)
                    is IrSleep -> emit(instructions, lines, Opcode.pack(Opcode.SLEEP), op.line)
                    is IrTimeoutBegin -> emit(instructions, lines, Opcode.pack(Opcode.TIMEOUT_BEGIN), op.line)
                    is IrTimeoutEnd -> emit(instructions, lines, Opcode.pack(Opcode.TIMEOUT_END), op.line)
                }
            }

            for ((pc, opcode, lblId) in pendingJumps) {
                val targetPc = labelToPc[lblId] ?: instructions.size
                instructions[pc] = Opcode.packWide(opcode, targetPc)
            }

            val endPc = instructions.size
            fnMetas += FunctionBytecodeMeta(
                index = fn.index,
                name = fn.name,
                kind = fn.kind,
                startPc = startPc,
                endPc = endPc,
                paramCount = fn.params.size,
                localCount = fn.localNames.size,
                localNames = fn.localNames,
                requiredNeeds = fn.requiredNeeds,
                paramNames = fn.params,
                hasVarArg = fn.hasVarArg,
                hasKwArg = fn.hasKwArg
            )
        }

        return CompiledBytecode(
            constPool = constPool.toList(),
            functions = fnMetas,
            macros = module.macros,
            triggers = module.triggers,
            requiredCapabilities = module.requiredCapabilities,
            instructions = instructions.toIntArray(),
            lineTable = lines.toIntArray(),
            classes = module.classes
        )
    }

    private fun constIdx(v: StudioValue): Int = constLookup.getOrPut(v) {
        constPool += v
        constPool.lastIndex
    }

    private fun emit(ins: MutableList<Int>, lines: MutableList<Int>, word: Int, line: Int) {
        ins += word
        lines += line
    }
}
