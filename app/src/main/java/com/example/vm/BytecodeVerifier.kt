package com.example.vm

import com.example.lang.*

/**
 * Static bytecode verifier executed before any bytecode enters the VM.
 * Prevents malformed jump targets, out-of-bounds constants/locals,
 * stack underflows/overflows, and unlisted host API calls.
 */
object BytecodeVerifier {

    data class VerificationResult(
        val isValid: Boolean,
        val diagnostics: List<StudioDiagnostic>
    )

    fun verify(
        bytecode: CompiledBytecode,
        allowedHostApis: Map<String, StudioAnalyzer.HostFunctionSignature> = StudioAnalyzer.DEFAULT_HOST_SIGNATURES,
        maxStackDepth: Int = 512
    ): VerificationResult {
        val errors = mutableListOf<StudioDiagnostic>()

        if (bytecode.magic != LanguageConfig.BYTECODE_MAGIC) {
            errors += StudioDiagnostic(
                "VER001",
                DiagnosticSeverity.ERROR,
                "Invalid bytecode magic header: 0x${bytecode.magic.toString(16)}.",
                SourceSpan(1, 1)
            )
            return VerificationResult(false, errors)
        }
        if (bytecode.version != LanguageConfig.BYTECODE_VERSION) {
            errors += StudioDiagnostic(
                "VER002",
                DiagnosticSeverity.ERROR,
                "Unsupported bytecode version ${bytecode.version}.",
                SourceSpan(1, 1)
            )
            return VerificationResult(false, errors)
        }

        if (bytecode.instructions.isEmpty()) {
            errors += StudioDiagnostic("VER003", DiagnosticSeverity.ERROR, "Empty bytecode instruction stream.", SourceSpan(1, 1))
            return VerificationResult(false, errors)
        }

        for (fn in bytecode.functions) {
            if (fn.startPc < 0 || fn.endPc > bytecode.instructions.size || fn.startPc > fn.endPc) {
                errors += StudioDiagnostic(
                    "VER004",
                    DiagnosticSeverity.ERROR,
                    "Function '${fn.name}' has invalid PC range [${fn.startPc}, ${fn.endPc}).",
                    SourceSpan(1, 1)
                )
                continue
            }

            var stackDepth = 0
            for (pc in fn.startPc until fn.endPc) {
                val ins = bytecode.instructions[pc]
                val op = Opcode.opcode(ins)
                val a = Opcode.argA(ins)
                val b = Opcode.argB(ins)
                val wide = Opcode.argWide(ins)
                val line = bytecode.lineTable.getOrElse(pc) { 1 }

                when (op) {
                    Opcode.NOP -> Unit
                    Opcode.LOAD_CONST -> {
                        if (a !in bytecode.constPool.indices) {
                            errors += StudioDiagnostic("VER005", DiagnosticSeverity.ERROR, "Constant index $a out of bounds.", SourceSpan(line, 1))
                        }
                        stackDepth++
                    }
                    Opcode.LOAD_LOCAL -> {
                        if (a < 0 || a >= fn.localCount) {
                            errors += StudioDiagnostic("VER006", DiagnosticSeverity.ERROR, "Local slot $a out of bounds in '${fn.name}'.", SourceSpan(line, 1))
                        }
                        stackDepth++
                    }
                    Opcode.STORE_LOCAL -> {
                        if (a < 0 || a >= fn.localCount) {
                            errors += StudioDiagnostic("VER006", DiagnosticSeverity.ERROR, "Store local slot $a out of bounds in '${fn.name}'.", SourceSpan(line, 1))
                        }
                        stackDepth--
                    }
                    Opcode.LOAD_GLOBAL -> {
                        if (a !in bytecode.constPool.indices) {
                            errors += StudioDiagnostic("VER005", DiagnosticSeverity.ERROR, "Global name index $a out of bounds.", SourceSpan(line, 1))
                        }
                        stackDepth++
                    }
                    Opcode.STORE_GLOBAL -> {
                        if (a !in bytecode.constPool.indices) {
                            errors += StudioDiagnostic("VER005", DiagnosticSeverity.ERROR, "Global name index $a out of bounds.", SourceSpan(line, 1))
                        }
                        stackDepth--
                    }
                    Opcode.POP -> stackDepth--
                    Opcode.DUP -> stackDepth++
                    Opcode.UNARY -> { /* pops 1, pushes 1 */ }
                    Opcode.BINARY -> stackDepth-- // pops 2, pushes 1
                    Opcode.MAKE_LIST -> stackDepth = stackDepth - a + 1
                    Opcode.MAKE_MAP -> stackDepth = stackDepth - (a * 2) + 1
                    Opcode.GET_MEMBER -> {
                        if (a !in bytecode.constPool.indices) {
                            errors += StudioDiagnostic("VER005", DiagnosticSeverity.ERROR, "Member name index $a out of bounds.", SourceSpan(line, 1))
                        }
                    }
                    Opcode.SET_MEMBER -> {
                        if (a !in bytecode.constPool.indices) {
                            errors += StudioDiagnostic("VER005", DiagnosticSeverity.ERROR, "Member name index $a out of bounds.", SourceSpan(line, 1))
                        }
                        stackDepth -= 2
                    }
                    Opcode.GET_INDEX -> stackDepth-- // pops 2, pushes 1
                    Opcode.SET_INDEX -> stackDepth -= 3
                    Opcode.JUMP -> {
                        if (wide < fn.startPc || wide > fn.endPc) {
                            errors += StudioDiagnostic("VER007", DiagnosticSeverity.ERROR, "Jump target $wide out of function bounds.", SourceSpan(line, 1))
                        }
                    }
                    Opcode.JUMP_IF_FALSE, Opcode.JUMP_IF_TRUE -> {
                        if (wide < fn.startPc || wide > fn.endPc) {
                            errors += StudioDiagnostic("VER007", DiagnosticSeverity.ERROR, "Branch target $wide out of function bounds.", SourceSpan(line, 1))
                        }
                        stackDepth--
                    }
                    Opcode.CALL_FUNC -> {
                        if (bytecode.functions.none { it.index == a }) {
                            errors += StudioDiagnostic("VER008", DiagnosticSeverity.ERROR, "Call to undefined function index $a.", SourceSpan(line, 1))
                        }
                        stackDepth = stackDepth - b + 1
                    }
                    Opcode.CALL_HOST -> {
                        val nameVal = bytecode.constPool.getOrNull(a) as? TextVal
                        if (nameVal == null) {
                            errors += StudioDiagnostic("VER009", DiagnosticSeverity.ERROR, "Invalid host call name index $a.", SourceSpan(line, 1))
                        } else {
                            val sig = allowedHostApis[nameVal.value]
                            if (sig == null) {
                                errors += StudioDiagnostic(
                                    "VER010",
                                    DiagnosticSeverity.ERROR,
                                    "Host API '${nameVal.value}' is not on the security allowlist.",
                                    SourceSpan(line, 1)
                                )
                            } else if (sig.requiredCapability != null && sig.requiredCapability !in bytecode.requiredCapabilities) {
                                errors += StudioDiagnostic(
                                    "VER011",
                                    DiagnosticSeverity.ERROR,
                                    "Host API '${nameVal.value}' requires capability ${sig.requiredCapability.id} which is not declared.",
                                    SourceSpan(line, 1)
                                )
                            }
                        }
                        stackDepth = stackDepth - b + 1
                    }
                    Opcode.RETURN -> stackDepth = (stackDepth - 1).coerceAtLeast(0)
                    Opcode.TRY_BEGIN -> {
                        if (wide < fn.startPc || wide >= fn.endPc) {
                            errors += StudioDiagnostic("VER007", DiagnosticSeverity.ERROR, "Catch target $wide out of bounds.", SourceSpan(line, 1))
                        }
                    }
                    Opcode.TRY_END -> Unit
                    Opcode.STORE_EXC -> {
                        if (a < 0 || a >= fn.localCount) {
                            errors += StudioDiagnostic("VER006", DiagnosticSeverity.ERROR, "Exception slot $a out of bounds.", SourceSpan(line, 1))
                        }
                    }
                    Opcode.RAISE, Opcode.CONFIRM, Opcode.SLEEP, Opcode.TIMEOUT_BEGIN -> stackDepth--
                    Opcode.TIMEOUT_END -> Unit
                    else -> {
                        errors += StudioDiagnostic("VER012", DiagnosticSeverity.ERROR, "Unknown opcode $op at PC=$pc.", SourceSpan(line, 1))
                    }
                }

                if (stackDepth > maxStackDepth) {
                    errors += StudioDiagnostic("VER013", DiagnosticSeverity.ERROR, "Stack depth exceeded $maxStackDepth at PC=$pc.", SourceSpan(line, 1))
                    break
                }
            }
        }

        return VerificationResult(errors.isEmpty(), errors)
    }
}
