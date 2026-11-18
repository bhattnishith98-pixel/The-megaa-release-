package com.example.vm

import android.content.Context
import com.example.lang.*
import java.io.File

data class CompilationOutput(
    val success: Boolean,
    val program: ProgramNode?,
    val analysis: StudioAnalyzer.AnalysisResult?,
    val irStats: IrOptimizer.OptimizationStats?,
    val bytecode: CompiledBytecode?,
    val diagnostics: List<StudioDiagnostic>,
    val compileTimeUs: Long,
    val fromCache: Boolean = false,
    val binaryArtifact: EmittedBinaryArtifact? = null,
    val includedLibraries: List<ResolvedLibrary> = emptyList()
)

/**
 * End-to-end Swatantra StudioLang direct-binary compilation pipeline:
 * Source -> #include/.stl.lib Preprocessor -> Lexer -> Parser -> Analyzer ->
 * IrGenerator -> IrOptimizer -> BytecodeCompiler -> BytecodeVerifier -> BinaryHexEmitter (.stlb / ELF64)
 */
class StudioCompiler(
    private val cacheCapacity: Int = 32,
    private val context: Context? = null,
    private val workspaceRoot: File? = null
) {
    private val cache = object : LinkedHashMap<String, CompilationOutput>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompilationOutput>?): Boolean {
            return size > cacheCapacity
        }
    }

    fun compile(
        source: String,
        useCache: Boolean = true,
        target: BinaryTarget = BinaryTarget.STUDIO_VM_BIN
    ): CompilationOutput {
        val t0 = System.nanoTime()
        val cacheKey = "${target.id}::$source"
        if (useCache) {
            synchronized(cache) {
                cache[cacheKey]?.let {
                    return it.copy(
                        fromCache = true,
                        compileTimeUs = ((System.nanoTime() - t0) / 1000L).coerceAtLeast(1L)
                    )
                }
            }
        }
        val allDiagnostics = mutableListOf<StudioDiagnostic>()

        // 1. Preprocess #include <studio.h> and #include <name.stl.lib>
        val prep = LibraryImporter.preprocess(source, context, workspaceRoot)
        allDiagnostics += prep.diagnostics
        if (prep.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationOutput(
                false, null, null, null, null, allDiagnostics,
                (System.nanoTime() - t0) / 1000L,
                includedLibraries = prep.includedLibraries
            )
        }

        val lexRes = StudioLexer(prep.expandedSource).tokenize()
        allDiagnostics += lexRes.diagnostics
        if (lexRes.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationOutput(
                false, null, null, null, null, allDiagnostics,
                (System.nanoTime() - t0) / 1000L,
                includedLibraries = prep.includedLibraries
            )
        }

        val parseRes = StudioParser(lexRes.tokens).parse()
        allDiagnostics += parseRes.diagnostics
        if (parseRes.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationOutput(
                false, parseRes.program, null, null, null, allDiagnostics,
                (System.nanoTime() - t0) / 1000L,
                includedLibraries = prep.includedLibraries
            )
        }

        val analysis = StudioAnalyzer().analyze(parseRes.program)
        allDiagnostics += analysis.diagnostics
        if (analysis.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationOutput(
                false, parseRes.program, analysis, null, null, allDiagnostics,
                (System.nanoTime() - t0) / 1000L,
                includedLibraries = prep.includedLibraries
            )
        }

        val rawIr = IrGenerator().generate(parseRes.program, analysis)
        val optRes = IrOptimizer.optimize(rawIr)
        val bytecode = BytecodeCompiler().compile(optRes.module)
        val verifyRes = BytecodeVerifier.verify(bytecode)
        allDiagnostics += verifyRes.diagnostics

        val binArtifact = if (verifyRes.isValid) BinaryHexEmitter.emit(bytecode, target) else null

        val output = CompilationOutput(
            success = verifyRes.isValid,
            program = parseRes.program,
            analysis = analysis,
            irStats = optRes.stats,
            bytecode = if (verifyRes.isValid) bytecode else null,
            diagnostics = allDiagnostics,
            compileTimeUs = (System.nanoTime() - t0) / 1000L,
            binaryArtifact = binArtifact,
            includedLibraries = prep.includedLibraries
        )
        if (output.success && useCache) {
            synchronized(cache) { cache[cacheKey] = output }
        }
        return output
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }
}
