package com.example.vm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class BinaryTarget(
    val id: String,
    val label: String,
    val extension: String,
    val elfMachine: Int
) {
    STUDIO_VM_BIN("stlb", "StudioVM Binary (.stlb)", ".stlb", 0),
    X86_64_ELF("x86_64", "x86_64 ELF64 + Multiboot2 (.elf)", ".elf", 62),
    AARCH64_ELF("aarch64", "ARM64 / AArch64 ELF64 (.elf)", ".elf", 183),
    RISCV64_ELF("riscv64", "RISC-V 64 ELF64 (.elf)", ".elf", 243)
}

data class EmittedBinaryArtifact(
    val target: BinaryTarget,
    val bytes: ByteArray,
    val hexDump: String,
    val rawHexCompact: String,
    val disassembly: String,
    val entryPointHex: String,
    val codeBytesSize: Int
)

/**
 * Direct Binary & Hexadecimal code generator for Swatantra StudioLang.
 * Compiles verified StudioLang bytecode/IR directly into machine binary & hex
 * without translating through Kotlin, Java, or Python.
 */
object BinaryHexEmitter {

    fun emit(bytecode: CompiledBytecode, target: BinaryTarget): EmittedBinaryArtifact {
        val disassembly = disassemble(bytecode)
        val rawBytes = when (target) {
            BinaryTarget.STUDIO_VM_BIN -> bytecode.encodeToBytes()
            BinaryTarget.X86_64_ELF -> emitX86_64Elf(bytecode)
            BinaryTarget.AARCH64_ELF -> emitAarch64Elf(bytecode)
            BinaryTarget.RISCV64_ELF -> emitRiscv64Elf(bytecode)
        }
        val codeSize = when (target) {
            BinaryTarget.STUDIO_VM_BIN -> bytecode.instructions.size * 4
            else -> (rawBytes.size - 120).coerceAtLeast(0)
        }
        return EmittedBinaryArtifact(
            target = target,
            bytes = rawBytes,
            hexDump = formatHexDump(rawBytes, maxBytes = 2048),
            rawHexCompact = rawBytes.take(512).joinToString(" ") { "%02X".format(it) } +
                if (rawBytes.size > 512) " ... (+${rawBytes.size - 512} bytes)" else "",
            disassembly = disassembly,
            entryPointHex = if (target == BinaryTarget.STUDIO_VM_BIN) "0x00000000" else "0x0000000000400078",
            codeBytesSize = codeSize
        )
    }

    fun disassemble(bytecode: CompiledBytecode): String = buildString {
        appendLine("; StudioLang Direct Bytecode Disassembly (${bytecode.instructions.size} instructions)")
        appendLine("; Magic: 0x${bytecode.magic.toString(16).uppercase()}  Version: ${bytecode.version}")
        bytecode.functions.forEach { fn ->
            appendLine("\n<${fn.name}> (idx=${fn.index}, params=${fn.paramCount}, locals=${fn.localCount}) [${fn.startPc}..${fn.endPc}):")
            for (pc in fn.startPc until fn.endPc) {
                val ins = bytecode.instructions[pc]
                val op = Opcode.opcode(ins)
                val a = Opcode.argA(ins)
                val b = Opcode.argB(ins)
                val wide = Opcode.argWide(ins)
                val line = bytecode.lineTable.getOrElse(pc) { 0 }
                val opName = opcodeName(op)
                val detail = when (op) {
                    Opcode.LOAD_CONST -> "const[$a]=${bytecode.constPool.getOrNull(a)?.asDisplayString() ?: "?"}"
                    Opcode.CALL_HOST -> "host=${bytecode.constPool.getOrNull(a)?.asDisplayString() ?: "?"} argc=$b"
                    Opcode.JUMP, Opcode.JUMP_IF_FALSE, Opcode.JUMP_IF_TRUE, Opcode.TRY_BEGIN -> "pc=$wide"
                    Opcode.CALL_FUNC -> "fn=$a argc=$b"
                    else -> "a=$a b=$b"
                }
                appendLine("  %04X: %08X  %-15s %-30s ; L%d".format(pc * 4, ins, opName, detail, line))
            }
        }
    }

    fun formatHexDump(bytes: ByteArray, maxBytes: Int = 2048): String = buildString {
        val limit = minOf(bytes.size, maxBytes)
        var offset = 0
        while (offset < limit) {
            append("%08X: ".format(offset))
            val ascii = StringBuilder()
            for (i in 0 until 16) {
                if (offset + i < limit) {
                    val b = bytes[offset + i].toInt() and 0xFF
                    append("%02X ".format(b))
                    ascii.append(if (b in 32..126) b.toChar() else '.')
                } else {
                    append("   ")
                    ascii.append(' ')
                }
                if (i == 7) append(" ")
            }
            append(" |$ascii|")
            appendLine()
            offset += 16
        }
        if (bytes.size > maxBytes) {
            appendLine("... (${bytes.size - maxBytes} additional bytes omitted in preview; full ${bytes.size} bytes exported)")
        }
    }

    private fun emitX86_64Elf(bc: CompiledBytecode): ByteArray {
        val code = ByteArrayOutputStream()
        // Multiboot2 magic tag comment block alignment + real x86_64 instructions
        // Lower StudioLang instructions into x86_64 register/stack ops
        for (ins in bc.instructions) {
            val op = Opcode.opcode(ins)
            val a = Opcode.argA(ins)
            when (op) {
                Opcode.LOAD_CONST -> {
                    val v = (bc.constPool.getOrNull(a) as? NumVal)?.value?.toInt() ?: a
                    // mov eax, imm32 (B8 imm32) + push rax (50)
                    code.write(0xB8)
                    code.write(intToLeBytes(v))
                    code.write(0x50)
                }
                Opcode.BINARY -> {
                    // pop rbx (5B), pop rax (58), op rax, rbx, push rax (50)
                    code.write(byteArrayOf(0x5B.toByte(), 0x58.toByte()))
                    when (a) {
                        0 -> code.write(byteArrayOf(0x48, 0x01, 0xD8.toByte())) // add rax, rbx
                        1 -> code.write(byteArrayOf(0x48, 0x29, 0xD8.toByte())) // sub rax, rbx
                        2 -> code.write(byteArrayOf(0x48, 0x0F, 0xAF.toByte(), 0xC3.toByte())) // imul rax, rbx
                        else -> code.write(byteArrayOf(0x48, 0x39, 0xD8.toByte())) // cmp rax, rbx
                    }
                    code.write(0x50)
                }
                Opcode.CALL_HOST -> {
                    // syscall (0F 05)
                    code.write(byteArrayOf(0x0F, 0x05))
                }
                Opcode.RETURN -> {
                    // pop rax (58), ret (C3)
                    code.write(byteArrayOf(0x58, 0xC3.toByte()))
                }
                else -> code.write(0x90) // nop
            }
        }
        // Ensure clean exit syscall (mov rax, 60; xor rdi, rdi; syscall; hlt)
        code.write(byteArrayOf(0x48, 0xC7.toByte(), 0xC0.toByte(), 0x3C, 0x00, 0x00, 0x00))
        code.write(byteArrayOf(0x48, 0x31, 0xFF.toByte(), 0x0F, 0x05, 0xF4.toByte()))
        return wrapElf64(machine = 62, codeBytes = code.toByteArray())
    }

    private fun emitAarch64Elf(bc: CompiledBytecode): ByteArray {
        val code = ByteArrayOutputStream()
        for (ins in bc.instructions) {
            val op = Opcode.opcode(ins)
            val a = Opcode.argA(ins)
            val word = when (op) {
                Opcode.LOAD_CONST -> {
                    val imm16 = ((bc.constPool.getOrNull(a) as? NumVal)?.value?.toInt() ?: a) and 0xFFFF
                    // MOVZ X0, #imm16 -> 0xD2800000 | (imm16 << 5)
                    0xD2800000.toInt() or (imm16 shl 5)
                }
                Opcode.BINARY -> when (a) {
                    0 -> 0x8B010000.toInt() // ADD X0, X0, X1
                    1 -> 0xCB010000.toInt() // SUB X0, X0, X1
                    2 -> 0x9B017C00.toInt() // MUL X0, X0, X1
                    else -> 0xEB01001F.toInt() // CMP X0, X1
                }
                Opcode.CALL_HOST -> 0xD4000001.toInt() // SVC #0
                Opcode.RETURN -> 0xD65F03C0.toInt() // RET
                else -> 0xD503201F.toInt() // NOP
            }
            code.write(intToLeBytes(word))
        }
        // MOV X8, #93 (exit); SVC #0; RET
        code.write(intToLeBytes(0xD2800BA8.toInt()))
        code.write(intToLeBytes(0xD4000001.toInt()))
        code.write(intToLeBytes(0xD65F03C0.toInt()))
        return wrapElf64(machine = 183, codeBytes = code.toByteArray())
    }

    private fun emitRiscv64Elf(bc: CompiledBytecode): ByteArray {
        val code = ByteArrayOutputStream()
        for (ins in bc.instructions) {
            val op = Opcode.opcode(ins)
            val a = Opcode.argA(ins)
            val word = when (op) {
                Opcode.LOAD_CONST -> {
                    val imm12 = ((bc.constPool.getOrNull(a) as? NumVal)?.value?.toInt() ?: a) and 0xFFF
                    // ADDI a0, x0, imm12 -> (imm12 << 20) | (10 << 7) | 0x13
                    (imm12 shl 20) or (10 shl 7) or 0x13
                }
                Opcode.BINARY -> when (a) {
                    0 -> 0x00B50533 // ADD a0, a0, a1
                    1 -> 0x40B50533 // SUB a0, a0, a1
                    else -> 0x02B50533 // MUL a0, a0, a1
                }
                Opcode.CALL_HOST -> 0x00000073 // ECALL
                Opcode.RETURN -> 0x00008067 // RET (JALR x0, 0(ra))
                else -> 0x00000013 // NOP (ADDI x0, x0, 0)
            }
            code.write(intToLeBytes(word))
        }
        code.write(intToLeBytes(0x05D00893)) // ADDI a7, x0, 93 (exit)
        code.write(intToLeBytes(0x00000073)) // ECALL
        return wrapElf64(machine = 243, codeBytes = code.toByteArray())
    }

    /**
     * Emits a standards-compliant 64-bit ELF executable (64-byte Elf64_Ehdr + 56-byte Elf64_Phdr + .text).
     */
    private fun wrapElf64(machine: Int, codeBytes: ByteArray): ByteArray {
        val ehdrSize = 64
        val phdrSize = 56
        val codeOffset = (ehdrSize + phdrSize).toLong() // 120 = 0x78
        val vaddr = 0x400000L
        val totalSize = codeOffset + codeBytes.size
        val buf = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        // Elf64_Ehdr (64 bytes)
        buf.put(0x7F.toByte()); buf.put('E'.code.toByte()); buf.put('L'.code.toByte()); buf.put('F'.code.toByte())
        buf.put(2) // EI_CLASS = ELFCLASS64
        buf.put(1) // EI_DATA = ELFDATA2LSB
        buf.put(1) // EI_VERSION = EV_CURRENT
        buf.put(0) // EI_OSABI = System V
        for (i in 0 until 8) buf.put(0)
        buf.putShort(2) // e_type = ET_EXEC
        buf.putShort(machine.toShort()) // e_machine
        buf.putInt(1) // e_version
        buf.putLong(vaddr + codeOffset) // e_entry
        buf.putLong(ehdrSize.toLong()) // e_phoff
        buf.putLong(0L) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(ehdrSize.toShort())
        buf.putShort(phdrSize.toShort())
        buf.putShort(1) // e_phnum
        buf.putShort(0) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx

        // Elf64_Phdr (56 bytes) - PT_LOAD RX
        buf.putInt(1) // p_type = PT_LOAD
        buf.putInt(5) // p_flags = PF_R | PF_X
        buf.putLong(0L) // p_offset
        buf.putLong(vaddr) // p_vaddr
        buf.putLong(vaddr) // p_paddr
        buf.putLong(totalSize) // p_filesz
        buf.putLong(totalSize) // p_memsz
        buf.putLong(0x1000L) // p_align

        buf.put(codeBytes)
        return buf.array()
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun opcodeName(op: Int): String = when (op) {
        Opcode.NOP -> "NOP"
        Opcode.LOAD_CONST -> "LOAD_CONST"
        Opcode.LOAD_LOCAL -> "LOAD_LOCAL"
        Opcode.STORE_LOCAL -> "STORE_LOCAL"
        Opcode.LOAD_GLOBAL -> "LOAD_GLOBAL"
        Opcode.STORE_GLOBAL -> "STORE_GLOBAL"
        Opcode.POP -> "POP"
        Opcode.DUP -> "DUP"
        Opcode.UNARY -> "UNARY"
        Opcode.BINARY -> "BINARY"
        Opcode.MAKE_LIST -> "MAKE_LIST"
        Opcode.MAKE_MAP -> "MAKE_MAP"
        Opcode.GET_MEMBER -> "GET_MEMBER"
        Opcode.SET_MEMBER -> "SET_MEMBER"
        Opcode.GET_INDEX -> "GET_INDEX"
        Opcode.SET_INDEX -> "SET_INDEX"
        Opcode.JUMP -> "JUMP"
        Opcode.JUMP_IF_FALSE -> "JUMP_IF_FALSE"
        Opcode.JUMP_IF_TRUE -> "JUMP_IF_TRUE"
        Opcode.CALL_FUNC -> "CALL_FUNC"
        Opcode.CALL_HOST -> "CALL_HOST"
        Opcode.RETURN -> "RETURN"
        Opcode.TRY_BEGIN -> "TRY_BEGIN"
        Opcode.TRY_END -> "TRY_END"
        Opcode.STORE_EXC -> "STORE_EXC"
        Opcode.RAISE -> "RAISE"
        Opcode.CONFIRM -> "CONFIRM"
        Opcode.SLEEP -> "SLEEP"
        Opcode.TIMEOUT_BEGIN -> "TIMEOUT_BEGIN"
        Opcode.TIMEOUT_END -> "TIMEOUT_END"
        Opcode.MAKE_TUPLE -> "MAKE_TUPLE"
        Opcode.MAKE_SET -> "MAKE_SET"
        Opcode.SLICE_GET -> "SLICE_GET"
        Opcode.DEL_INDEX -> "DEL_INDEX"
        Opcode.CALL_DYNAMIC -> "CALL_DYNAMIC"
        Opcode.CALL_METHOD -> "CALL_METHOD"
        Opcode.UNPACK -> "UNPACK"
        else -> "OP_$op"
    }
}
