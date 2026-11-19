package com.example.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Automation Studio Custom OS Kernel Layer (`kernel.*`, `proc.*`, `ipc.*`, `vfs.*`, `stopwatch.*`).
 * Provides a deterministic, preemptive userspace process table, RAM/disk overlay VFS,
 * message-passing IPC channels, mutex locks, nanosecond scheduler metrics, and kernel syscalls
 * engineered to run on Android and JVM tests.
 */
object CustomOsKernel {

    data class KernelProcess(
        val pid: Int,
        val name: String,
        val command: String,
        val priority: Int, // -20 (highest) to +19 (lowest)
        val state: String, // "RUNNING", "READY", "SLEEPING", "TERMINATED"
        val createdAtMs: Long,
        val cpuTimeNs: Long = 0L,
        val exitCode: Int? = null
    )

    data class VfsNode(
        val path: String,
        val content: String,
        val permissions: String = "rw-r--r--",
        val owner: String = "kernel",
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val bootTimeNs = System.nanoTime()
    private val bootTimeMs = System.currentTimeMillis()
    private val nextPid = AtomicInteger(100)
    private val syscallCount = AtomicLong(0L)
    private val contextSwitches = AtomicLong(0L)

    private val processTable = ConcurrentHashMap<Int, KernelProcess>()
    private val vfsMountPoints = ConcurrentHashMap<String, String>()
    private val vfsFiles = ConcurrentHashMap<String, VfsNode>()
    private val ipcChannels = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()
    private val mutexLocks = ConcurrentHashMap<String, String>()
    private val stopwatches = ConcurrentHashMap<String, StopwatchState>()

    private data class StopwatchState(
        val startedNs: Long = 0L,
        val accumulatedNs: Long = 0L,
        val running: Boolean = false
    )

    init {
        resetDefaults()
    }

    fun resetDefaults() {
        processTable[1] = KernelProcess(
            pid = 1,
            name = "init",
            command = "/sbin/studio_init",
            priority = -10,
            state = "RUNNING",
            createdAtMs = bootTimeMs
        )
        vfsMountPoints["/"] = "overlayfs"
        vfsMountPoints["/ram"] = "tmpfs"
        vfsMountPoints["/proc"] = "procfs"
        vfsMountPoints["/dev"] = "devtmpfs"
        vfsFiles["/proc/version"] = VfsNode(
            path = "/proc/version",
            content = "Swatantra-Kernel 6.12-android-hybrid (ARM64/x86_64 POSIX+Capability)",
            permissions = "r--r--r--"
        )
    }

    private fun countSyscall() {
        syscallCount.incrementAndGet()
    }

    // ---- KERNEL TELEMETRY & SYSCALLS ----

    fun uname(): Map<String, Any> {
        countSyscall()
        return mapOf(
            "sysname" to "SwatantraOS",
            "release" to "6.12.0-studio",
            "arch" to (System.getProperty("os.arch") ?: "aarch64"),
            "host_os" to (System.getProperty("os.name") ?: "Android"),
            "jvm_cores" to Runtime.getRuntime().availableProcessors(),
            "uptime_ms" to uptimeMs()
        )
    }

    fun uptimeMs(): Long = (System.nanoTime() - bootTimeNs) / 1_000_000L

    fun stats(): Map<String, Any> {
        countSyscall()
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        val totalMb = rt.maxMemory() / (1024.0 * 1024.0)
        return mapOf(
            "uptime_ms" to uptimeMs(),
            "syscalls" to syscallCount.get(),
            "context_switches" to contextSwitches.get(),
            "processes_active" to processTable.values.count { it.state != "TERMINATED" },
            "processes_total" to processTable.size,
            "vfs_nodes" to vfsFiles.size,
            "ipc_channels" to ipcChannels.size,
            "locks_held" to mutexLocks.size,
            "cpu_cores" to rt.availableProcessors(),
            "heap_used_mb" to ((usedMb * 100.0).toLong() / 100.0),
            "heap_max_mb" to ((totalMb * 100.0).toLong() / 100.0)
        )
    }

    fun benchCpu(iterations: Int = 50_000): Map<String, Any> {
        countSyscall()
        val count = iterations.coerceIn(1_000, 1_000_000)
        val t0 = System.nanoTime()
        var acc = 0x9E3779B9L
        for (i in 1..count) {
            acc = (acc * 6364136223846793005L + i.toLong()) and 0x7FFFFFFFL
        }
        val elapsedNs = (System.nanoTime() - t0).coerceAtLeast(1L)
        val opsPerSec = (count.toDouble() / elapsedNs.toDouble()) * 1_000_000_000.0
        contextSwitches.incrementAndGet()
        return mapOf(
            "iterations" to count,
            "elapsed_us" to (elapsedNs / 1000.0),
            "ops_per_sec" to opsPerSec.toLong(),
            "checksum" to acc
        )
    }

    fun syscall(name: String, arg: String = ""): Map<String, Any> {
        countSyscall()
        return when (name.lowercase()) {
            "sys_uname" -> uname()
            "sys_uptime" -> mapOf("uptime_ms" to uptimeMs())
            "sys_gc" -> {
                System.gc()
                mapOf("gc" to true, "stats" to stats())
            }
            "sys_yield" -> {
                yieldProcess(1)
                mapOf("yielded" to true, "context_switches" to contextSwitches.get())
            }
            "sys_pid" -> mapOf("pid" to (android.os.Process.myPid()))
            "sys_uid" -> mapOf("uid" to (android.os.Process.myUid()))
            else -> mapOf("syscall" to name, "arg" to arg, "status" to "ok", "seq" to syscallCount.get())
        }
    }

    // ---- PROCESS SCHEDULER ----

    fun spawnProcess(name: String, command: String = "", priority: Int = 0): KernelProcess {
        countSyscall()
        val pid = nextPid.incrementAndGet()
        val proc = KernelProcess(
            pid = pid,
            name = name.ifBlank { "proc_$pid" },
            command = command.ifBlank { name },
            priority = priority.coerceIn(-20, 19),
            state = "RUNNING",
            createdAtMs = System.currentTimeMillis()
        )
        processTable[pid] = proc
        contextSwitches.incrementAndGet()
        return proc
    }

    fun listProcesses(includeTerminated: Boolean = false): List<KernelProcess> {
        countSyscall()
        return processTable.values
            .filter { includeTerminated || it.state != "TERMINATED" }
            .sortedWith(compareBy<KernelProcess> { it.priority }.thenBy { it.pid })
    }

    fun killProcess(pid: Int, signal: String = "SIGTERM"): Boolean {
        countSyscall()
        if (pid == 1) return false // Protect kernel init
        val cur = processTable[pid] ?: return false
        processTable[pid] = cur.copy(
            state = "TERMINATED",
            exitCode = if (signal == "SIGKILL") 137 else 143
        )
        contextSwitches.incrementAndGet()
        return true
    }

    fun setProcessPriority(pid: Int, priority: Int): Boolean {
        countSyscall()
        val cur = processTable[pid] ?: return false
        processTable[pid] = cur.copy(priority = priority.coerceIn(-20, 19))
        return true
    }

    fun yieldProcess(pid: Int): Boolean {
        countSyscall()
        contextSwitches.incrementAndGet()
        val cur = processTable[pid] ?: return false
        processTable[pid] = cur.copy(cpuTimeNs = cur.cpuTimeNs + 50_000L)
        return true
    }

    // ---- VIRTUAL FILESYSTEM OVERLAY ----

    fun normalizeVfsPath(raw: String): String {
        val cleaned = raw.trim().replace('\\', '/')
        val withSlash = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        val stack = ArrayDeque<String>()
        withSlash.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun mountVfs(mountPath: String, fsType: String = "overlayfs"): Boolean {
        countSyscall()
        val norm = normalizeVfsPath(mountPath)
        vfsMountPoints[norm] = fsType.ifBlank { "overlayfs" }
        return true
    }

    fun writeVfs(path: String, content: String, backingRoot: File? = null): String {
        countSyscall()
        val norm = normalizeVfsPath(path)
        vfsFiles[norm] = VfsNode(path = norm, content = content)
        if (backingRoot != null && !norm.startsWith("/ram") && !norm.startsWith("/proc")) {
            runCatching {
                val rel = norm.trimStart('/')
                if (rel.isNotBlank()) {
                    val out = File(backingRoot, "vfs/$rel")
                    out.parentFile?.mkdirs()
                    out.writeText(content)
                }
            }
        }
        return norm
    }

    fun readVfs(path: String, backingRoot: File? = null): String {
        countSyscall()
        val norm = normalizeVfsPath(path)
        vfsFiles[norm]?.let { return it.content }
        if (backingRoot != null && !norm.startsWith("/ram") && !norm.startsWith("/proc")) {
            val f = File(backingRoot, "vfs/${norm.trimStart('/')}")
            if (f.isFile) return f.readText()
        }
        return ""
    }

    fun listVfs(prefixPath: String = "/"): List<String> {
        countSyscall()
        val norm = normalizeVfsPath(prefixPath)
        val keys = vfsFiles.keys.filter { it == norm || norm == "/" || it.startsWith("$norm/") }
        return keys.sorted()
    }

    fun deleteVfs(path: String, backingRoot: File? = null): Boolean {
        countSyscall()
        val norm = normalizeVfsPath(path)
        val removed = vfsFiles.remove(norm) != null
        if (backingRoot != null) {
            runCatching { File(backingRoot, "vfs/${norm.trimStart('/')}").delete() }
        }
        return removed
    }

    fun vfsMounts(): Map<String, String> {
        countSyscall()
        return vfsMountPoints.toMap()
    }

    // ---- IPC CHANNELS & MUTEXES ----

    fun ensureChannel(name: String): Boolean {
        countSyscall()
        ipcChannels.putIfAbsent(name.ifBlank { "default" }, ConcurrentLinkedDeque())
        return true
    }

    fun ipcSend(channel: String, message: String): Int {
        countSyscall()
        val q = ipcChannels.computeIfAbsent(channel.ifBlank { "default" }) { ConcurrentLinkedDeque() }
        q.addLast(message)
        return q.size
    }

    fun ipcRecv(channel: String): String {
        countSyscall()
        return ipcChannels[channel.ifBlank { "default" }]?.pollFirst().orEmpty()
    }

    fun ipcPeek(channel: String): List<String> {
        countSyscall()
        return ipcChannels[channel.ifBlank { "default" }]?.toList().orEmpty()
    }

    fun acquireLock(lockName: String, owner: String = "vm"): Boolean {
        countSyscall()
        val existing = mutexLocks.putIfAbsent(lockName, owner)
        return existing == null || existing == owner
    }

    fun releaseLock(lockName: String, owner: String = "vm"): Boolean {
        countSyscall()
        val cur = mutexLocks[lockName] ?: return true
        return if (cur == owner || owner == "*") {
            mutexLocks.remove(lockName) != null
        } else {
            false
        }
    }

    // ---- STOPWATCHES (PDF Triggers / Actions / Constraints) ----

    fun startStopwatch(name: String): Long {
        val key = name.ifBlank { "default" }
        val cur = stopwatches[key]
        if (cur != null && cur.running) return elapsedStopwatchMs(key)
        stopwatches[key] = StopwatchState(
            startedNs = System.nanoTime(),
            accumulatedNs = cur?.accumulatedNs ?: 0L,
            running = true
        )
        return elapsedStopwatchMs(key)
    }

    fun stopStopwatch(name: String): Long {
        val key = name.ifBlank { "default" }
        val cur = stopwatches[key] ?: return 0L
        val total = if (cur.running) cur.accumulatedNs + (System.nanoTime() - cur.startedNs) else cur.accumulatedNs
        stopwatches[key] = StopwatchState(startedNs = 0L, accumulatedNs = total, running = false)
        return total / 1_000_000L
    }

    fun resetStopwatch(name: String): Long {
        val key = name.ifBlank { "default" }
        stopwatches[key] = StopwatchState()
        return 0L
    }

    fun elapsedStopwatchMs(name: String): Long {
        val key = name.ifBlank { "default" }
        val cur = stopwatches[key] ?: return 0L
        val total = if (cur.running) cur.accumulatedNs + (System.nanoTime() - cur.startedNs) else cur.accumulatedNs
        return total / 1_000_000L
    }
}
