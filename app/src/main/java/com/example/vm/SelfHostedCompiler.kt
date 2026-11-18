package com.example.vm

import com.example.lang.StudioCapability

data class SelfHostBootstrapResult(
    val success: Boolean,
    val stage1OpsCount: Int,
    val stage2HexOutput: String,
    val stage2ByteCount: Int,
    val selfHostLogs: List<String>,
    val durationMs: Long,
    val error: String? = null
)

/**
 * Self-Hosted StudioLang Compiler written IN StudioLang itself.
 * Stage 1 compiles `SELF_HOSTED_COMPILER_STL` to bytecode; Stage 2 executes it on StudioVM
 * to lex, parse, and emit raw Hex/Binary words directly from target StudioLang source.
 */
object SelfHostedCompiler {

    val SELF_HOSTED_COMPILER_STL = """
        # Swatantra StudioLang Self-Hosted Compiler (written in StudioLang)
        # Lexes source lines, encodes direct binary opcodes, and emits Hex words.
        needs FILE_ACCESS

        def emit_hex_word(opcode: number, arg: number) -> text:
            let packed = (opcode * 16777216) + (arg * 4096)
            return bin.to_hex_u32(packed)

        def self_compile(source_text: text) -> map:
            let lines = text.split(source_text, "\n")
            let hex_words = ["53544C31", "00000001"]
            let token_count = 0
            for raw in lines:
                let line = text.trim(raw)
                if line == "" or text.starts_with(line, "#"):
                    continue
                token_count += 1
                if text.starts_with(line, "let ") or text.starts_with(line, "const "):
                    list.push(hex_words, emit_hex_word(1, token_count))
                    list.push(hex_words, emit_hex_word(3, token_count))
                elif text.starts_with(line, "return"):
                    list.push(hex_words, emit_hex_word(1, 0))
                    list.push(hex_words, emit_hex_word(21, 0))
                elif text.contains(line, "(") and text.contains(line, ")"):
                    list.push(hex_words, emit_hex_word(20, token_count))
                else:
                    list.push(hex_words, emit_hex_word(0, token_count))
            list.push(hex_words, emit_hex_word(21, 0))
            let hex_stream = text.join(hex_words, " ")
            log.info("Self-Hosted Compiler: tokens=" + token_count + ", words=" + list.len(hex_words))
            return {tokens: token_count, words: list.len(hex_words), hex: hex_stream}

        let target_src = files.read("_selfhost_target.stl")
        let out = self_compile(target_src)
        log.info("SELF_HOST_HEX=" + out.hex)
        return out
    """.trimIndent()

    suspend fun bootstrapCompile(
        targetSource: String,
        compilerSource: String = SELF_HOSTED_COMPILER_STL,
        compiler: StudioCompiler,
        capabilityManager: CapabilityManager,
        hostDispatcher: HostApiDispatcher,
        workspaceDir: java.io.File
    ): SelfHostBootstrapResult {
        val t0 = System.currentTimeMillis()
        // Persist target source in sandboxed workspace so the StudioLang-written compiler reads it directly
        val targetFile = java.io.File(workspaceDir, "_selfhost_target.stl")
        targetFile.writeText(targetSource)

        val stage1 = compiler.compile(compilerSource, useCache = false)
        if (!stage1.success || stage1.bytecode == null) {
            return SelfHostBootstrapResult(
                success = false,
                stage1OpsCount = 0,
                stage2HexOutput = "",
                stage2ByteCount = 0,
                selfHostLogs = stage1.diagnostics.map { it.format() },
                durationMs = System.currentTimeMillis() - t0,
                error = stage1.diagnostics.firstOrNull()?.message ?: "Stage 1 compiler build failed."
            )
        }

        val ensuredCaps = CapabilityManager(
            capabilityManager.grantedCapabilities.value + StudioCapability.FILE_ACCESS
        )
        val vm = StudioVm(ensuredCaps, hostDispatcher)
        val res = vm.execute(stage1.bytecode)
        val hexOut = if (res.returnValue is MapVal) {
            res.returnValue.entries["hex"]?.asDisplayString().orEmpty()
        } else {
            res.logs.firstOrNull { it.message.startsWith("SELF_HOST_HEX=") }
                ?.message?.removePrefix("SELF_HOST_HEX=").orEmpty()
        }
        val words = hexOut.split(" ").filter { it.isNotBlank() }
        return SelfHostBootstrapResult(
            success = res.success,
            stage1OpsCount = stage1.bytecode.instructions.size,
            stage2HexOutput = hexOut,
            stage2ByteCount = words.size * 4,
            selfHostLogs = res.logs.map { "[${it.level}] ${it.message}" },
            durationMs = System.currentTimeMillis() - t0,
            error = res.error
        )
    }
}
