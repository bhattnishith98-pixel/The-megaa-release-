package com.example

import com.example.lang.StudioCapability
import com.example.runtime.CustomOsKernel
import com.example.runtime.PythonStdLibBridge
import com.example.vm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CustomOsAndHardwareTest {

    @Test
    fun customOsKernelProcessSchedulerVfsIpcAndStopwatchesWork() {
        val tempDir = Files.createTempDirectory("os_kernel_test").toFile()
        try {
            val proc = CustomOsKernel.spawnProcess("sensor_daemon", "/bin/sensord", priority = -5)
            assertEquals("RUNNING", proc.state)
            assertEquals(-5, proc.priority)
            assertTrue(CustomOsKernel.listProcesses().any { it.pid == proc.pid })
            assertTrue(CustomOsKernel.setProcessPriority(proc.pid, 2))
            assertTrue(CustomOsKernel.yieldProcess(proc.pid))
            assertTrue(CustomOsKernel.killProcess(proc.pid, "SIGTERM"))
            assertFalse(CustomOsKernel.killProcess(1, "SIGKILL")) // init protected

            assertTrue(CustomOsKernel.mountVfs("/custom", "tmpfs"))
            val writtenPath = CustomOsKernel.writeVfs("/custom/config.cfg", "turbo=1", tempDir)
            assertEquals("/custom/config.cfg", writtenPath)
            assertEquals("turbo=1", CustomOsKernel.readVfs("/custom/config.cfg", tempDir))
            assertTrue(CustomOsKernel.listVfs("/custom").contains("/custom/config.cfg"))
            assertTrue(CustomOsKernel.deleteVfs("/custom/config.cfg", tempDir))

            CustomOsKernel.ensureChannel("bus1")
            assertEquals(1, CustomOsKernel.ipcSend("bus1", "hello_ipc"))
            assertEquals("hello_ipc", CustomOsKernel.ipcRecv("bus1"))
            assertTrue(CustomOsKernel.acquireLock("mtx1", "ownerA"))
            assertFalse(CustomOsKernel.acquireLock("mtx1", "ownerB"))
            assertTrue(CustomOsKernel.releaseLock("mtx1", "ownerA"))

            val sw = "test_sw"
            CustomOsKernel.resetStopwatch(sw)
            CustomOsKernel.startStopwatch(sw)
            Thread.sleep(15)
            val elapsed = CustomOsKernel.stopStopwatch(sw)
            assertTrue("Expected >= 5ms elapsed, got $elapsed", elapsed >= 5L)

            val bench = CustomOsKernel.benchCpu(5_000)
            assertEquals(5_000, bench["iterations"])
            assertTrue((bench["ops_per_sec"] as Long) > 0L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun studioVmExecutesCustomOsSyscallsThroughStudioLang() = runBlocking {
        val tempDir = Files.createTempDirectory("vm_os_test").toFile()
        try {
            val code = """
                def run_kernel_check():
                    u = kernel.boot_info()
                    p = proc.spawn("worker_1", "/bin/worker", -2)
                    vfs.write("/ram/msg.txt", "ok_vfs")
                    txt = vfs.read("/ram/msg.txt")
                    ipc.send("ch_alpha", "ping")
                    got = ipc.recv("ch_alpha")
                    proc.kill(p["pid"])
                    return f"{u['sysname']}|{txt}|{got}"

                log.info(run_kernel_check())
            """.trimIndent()
            val compiler = StudioCompiler(workspaceRoot = tempDir)
            val compiled = compiler.compile(code)
            assertTrue("Compile failed: ${compiled.diagnostics}", compiled.success && compiled.bytecode != null)

            val dispatcher = object : HostApiDispatcher {
                override suspend fun invokeHost(
                    qualifiedName: String,
                    args: List<StudioValue>,
                    line: Int,
                    logSink: (level: String, message: String) -> Unit
                ): StudioValue = when (qualifiedName) {
                    "log.info" -> { logSink("INFO", args.joinToString(" ") { it.asDisplayString() }); NoneVal }
                    "kernel.boot_info" -> MapVal(linkedMapOf("sysname" to TextVal(CustomOsKernel.uname()["sysname"].toString())))
                    "proc.spawn" -> {
                        val p = CustomOsKernel.spawnProcess(args[0].asDisplayString(), args[1].asDisplayString(), args[2].asDouble().toInt())
                        MapVal(linkedMapOf("pid" to NumVal(p.pid.toDouble()), "name" to TextVal(p.name)))
                    }
                    "proc.kill" -> BoolVal(CustomOsKernel.killProcess(args[0].asDouble().toInt()))
                    "vfs.write" -> BoolVal(CustomOsKernel.writeVfs(args[0].asDisplayString(), args[1].asDisplayString(), tempDir).isNotBlank())
                    "vfs.read" -> TextVal(CustomOsKernel.readVfs(args[0].asDisplayString(), tempDir))
                    "ipc.send" -> { CustomOsKernel.ipcSend(args[0].asDisplayString(), args[1].asDisplayString()); BoolVal(true) }
                    "ipc.recv" -> TextVal(CustomOsKernel.ipcRecv(args[0].asDisplayString()))
                    else -> PythonStdLibBridge.dispatchOrNull(qualifiedName, args, line, tempDir, logSink)
                        ?: throw StudioVmException("TEST404", "Unknown API $qualifiedName", line)
                }
            }
            val vm = StudioVm(CapabilityManager(StudioCapability.entries.toSet()), dispatcher)
            val res = vm.execute(compiled.bytecode!!)
            assertTrue("VM failed: ${res.error}", res.success)
            assertEquals("SwatantraOS|ok_vfs|ping", res.logs.last().message)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
