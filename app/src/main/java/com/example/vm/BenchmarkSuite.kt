package com.example.vm

import com.example.nodes.GraphAstConverter
import com.example.nodes.GraphNode
import com.example.nodes.NodeGraph

data class BenchmarkReport(
    val coldCompileUs: Long,
    val cachedCompileUs: Long,
    val vmLoopMs: Long,
    val vmInstructionsExecuted: Long,
    val instructionsPerSecond: Long,
    val originalIrOps: Int,
    val optimizedIrOps: Int,
    val foldedConstants: Int,
    val largeGraphNodesCount: Int,
    val largeGraphCodegenMs: Long,
    val heapDeltaKb: Long
)

/**
 * Reproducible benchmark suite measuring actual compilation speed, caching,
 * IR optimization reductions, VM throughput, and 1,000-node graph handling.
 */
object BenchmarkSuite {

    suspend fun runAll(
        compiler: StudioCompiler,
        capabilityManager: CapabilityManager,
        hostDispatcher: HostApiDispatcher
    ): BenchmarkReport {
        val sampleSource = """
            let folded = (10 + 20) * 3 - 5
            let acc = 0
            let i = 0
            while i < 2000:
                acc += (i % 7) + 1
                i += 1
            return acc + folded
        """.trimIndent()

        System.gc()
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        compiler.clearCache()
        val coldOut = compiler.compile(sampleSource, useCache = true)
        val cachedOut = compiler.compile(sampleSource, useCache = true)

        val vm = StudioVm(capabilityManager, hostDispatcher, ExecutionPolicy(maxInstructions = 500_000L))
        val execRes = if (coldOut.bytecode != null) vm.execute(coldOut.bytecode) else null

        // 1,000-node graph codegen benchmark
        val largeNodes = (1..1000).map { idx ->
            GraphNode(
                id = "n_$idx",
                typeId = "action.log",
                label = "Log $idx",
                x = (idx % 25) * 240f,
                y = (idx / 25) * 140f,
                params = mapOf("level" to "info", "message" to "\"Step $idx\"")
            )
        }
        val largeGraph = NodeGraph(schemaVersion = 1, name = "Bench1000", nodes = largeNodes)
        val gStart = System.nanoTime()
        GraphAstConverter.graphToSource(largeGraph)
        val gMs = (System.nanoTime() - gStart) / 1_000_000L

        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val deltaKb = ((memAfter - memBefore) / 1024L).coerceAtLeast(0L)

        val durMs = (execRes?.durationMs ?: 1L).coerceAtLeast(1L)
        val insCount = execRes?.instructionsExecuted ?: 0L
        val ips = (insCount * 1000L) / durMs

        return BenchmarkReport(
            coldCompileUs = coldOut.compileTimeUs,
            cachedCompileUs = cachedOut.compileTimeUs,
            vmLoopMs = execRes?.durationMs ?: 0L,
            vmInstructionsExecuted = insCount,
            instructionsPerSecond = ips,
            originalIrOps = coldOut.irStats?.originalOps ?: 0,
            optimizedIrOps = coldOut.irStats?.optimizedOps ?: 0,
            foldedConstants = coldOut.irStats?.foldedConstants ?: 0,
            largeGraphNodesCount = largeNodes.size,
            largeGraphCodegenMs = gMs,
            heapDeltaKb = deltaKb
        )
    }
}
