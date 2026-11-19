package com.example

import com.example.lang.StudioCapability
import com.example.runtime.PythonStdLibBridge
import com.example.vm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PythonStudioLangTest {

    private fun runProgram(code: String): VmExecutionResult = runBlocking {
        val tempDir = Files.createTempDirectory("py_lang_test").toFile()
        try {
            val compiler = StudioCompiler(workspaceRoot = tempDir)
            val compiled = compiler.compile(code)
            assertTrue(
                "Compile failed: ${compiled.diagnostics.joinToString("\n") { it.format() }}",
                compiled.success && compiled.bytecode != null
            )
            val caps = CapabilityManager(StudioCapability.entries.toSet())
            val dispatcher = object : HostApiDispatcher {
                override suspend fun invokeHost(
                    qualifiedName: String,
                    args: List<StudioValue>,
                    line: Int,
                    logSink: (level: String, message: String) -> Unit
                ): StudioValue {
                    if (qualifiedName == "log.info" || qualifiedName == "print") {
                        logSink("INFO", args.joinToString(" ") { it.asDisplayString() })
                        return NoneVal
                    }
                    return PythonStdLibBridge.dispatchOrNull(qualifiedName, args, line, tempDir, logSink)
                        ?: throw StudioVmException("TEST404", "Unknown host API: $qualifiedName", line)
                }
            }
            StudioVm(caps, dispatcher).execute(compiled.bytecode!!)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun classesInheritanceMethodsAndDunderInitWorkEndToEnd() {
        val code = """
            class Animal:
                def __init__(self, name, legs):
                    self.name = name
                    self.legs = legs
                def describe(self):
                    return f"{self.name}:{self.legs}"

            class Dog(Animal):
                def __init__(self, name):
                    self.name = name
                    self.legs = 4
                def speak(self):
                    return self.name + "!"

            d = Dog("Rex")
            log.info(d.describe() + "|" + d.speak())
        """.trimIndent()
        val res = runProgram(code)
        assertTrue("VM error: ${res.error}", res.success)
        assertEquals("Rex:4|Rex!", res.logs.last().message)
    }

    @Test
    fun comprehensionsSlicesLambdaHofAndUnpackingWork() {
        val code = """
            nums = [1, 2, 3, 4, 5, 6]
            evens_sq = [x * x for x in nums if x % 2 == 0]
            rev = nums[::-1]
            a, b = [10, 20]
            sq_map = py.map(lambda n: n + 1, [1, 2, 3])
            odds = py.filter(lambda n: n % 2 == 1, nums)
            s = {x % 3 for x in range(1, 7)}
            d = {k: k * 10 for k in [1, 2, 3]}
            neg = rev[-1]
            tern = "ok" if a < b else "no"
            log.info(f"{evens_sq}|{rev}|{a + b}|{sq_map}|{odds}|{len(s)}|{d[2]}|{neg}|{tern}")
        """.trimIndent()
        val res = runProgram(code)
        assertTrue("VM error: ${res.error}", res.success)
        val out = res.logs.last().message
        assertEquals("[4, 16, 36]|[6, 5, 4, 3, 2, 1]|30|[2, 3, 4]|[1, 3, 5]|3|20|1|ok", out)
    }

    @Test
    fun bitwiseFloorDivTryFinallyMatchWithYieldAndStdLibWork() {
        val code = """
            fd = 17 // 5
            bw = ((6 & 3) | (1 << 3)) ^ 2
            res_match = ""
            match fd:
                case 3:
                    res_match = "three"
                case _:
                    res_match = "other"

            def gen():
                yield 11
                yield 22

            collected = gen()

            seen_finally = False
            try:
                assert 2 == 1, "bad math"
            except AssertionError as err:
                res_match = res_match + ":caught"
            finally:
                seen_finally = True

            h = hashlib.sha256("abc")
            m = re.findall("\\d+", "a12b34")
            b64 = base64.encode("studio")
            b64_dec = base64.decode(b64)
            log.info(f"{fd}|{bw}|{res_match}|{seen_finally}|{collected}|{len(h)}|{m}|{b64_dec}")
        """.trimIndent()
        val res = runProgram(code)
        assertTrue("VM error: ${res.error}", res.success)
        val out = res.logs.last().message
        assertEquals("3|8|three:caught|true|[11, 22]|64|[12, 34]|studio", out)
    }

    @Test
    fun irOptimizerFoldsAlgebraicBitwiseAndConstantBranches() {
        val tempDir = Files.createTempDirectory("opt_test").toFile()
        try {
            val compiler = StudioCompiler(workspaceRoot = tempDir)
            val code = """
                def optimize_target():
                    x = (17 // 3) + (1 << 4)
                    y = x + 0
                    z = y * 1
                    if True:
                        a = z
                    else:
                        a = 999
                    return a
                log.info(optimize_target())
            """.trimIndent()
            val out = compiler.compile(code)
            assertTrue("Compile failed: ${out.diagnostics.joinToString("\n") { it.format() }}", out.success)
            val stats = out.irStats!!
            assertTrue("Expected optimizer to fold constants & dead branch", stats.foldedConstants > 0)
            assertTrue("Expected optimized ops <= original", stats.optimizedOps <= stats.originalOps)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
