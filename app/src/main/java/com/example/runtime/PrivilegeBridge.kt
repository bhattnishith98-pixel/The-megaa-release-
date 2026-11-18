package com.example.runtime

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class PrivilegeEnvironmentStatus(
    val shizukuInstalled: Boolean,
    val shizukuRishPresent: Boolean,
    val rootBinaryPath: String?,
    val notes: String
) {
    val rootAvailable: Boolean get() = rootBinaryPath != null
    val shizukuAvailable: Boolean get() = shizukuInstalled
}

data class PrivilegedCommandResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val backend: String
)

/**
 * Honest privilege-tier bridge for advanced Android editing:
 * - Standard Android APIs work out of the box.
 * - ADB-level controls inspect Shizuku (`moe.shizuku.privileged.api`) availability cleanly.
 * - Superuser (`su`) commands inspect standard Android root binary paths cleanly.
 * Never fabricates privileged output when Shizuku or Root is absent.
 */
object PrivilegeBridge {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private val ROOT_CANDIDATE_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su"
    )

    fun inspectEnvironment(context: Context): PrivilegeEnvironmentStatus {
        val shizukuInstalled = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        val rishFile = File(context.filesDir, "rish")
        val rootPath = ROOT_CANDIDATE_PATHS.firstOrNull { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

        val notes = buildString {
            append(if (shizukuInstalled) "Shizuku manager installed." else "Shizuku ($SHIZUKU_PACKAGE) not installed.")
            append(" ")
            append(if (rootPath != null) "su found at $rootPath." else "No root `su` binary detected.")
            append(" Advanced Android system edits require Shizuku authorization or Root.")
        }

        return PrivilegeEnvironmentStatus(
            shizukuInstalled = shizukuInstalled,
            shizukuRishPresent = rishFile.exists(),
            rootBinaryPath = rootPath,
            notes = notes
        )
    }

    suspend fun execShizuku(context: Context, command: String): PrivilegedCommandResult = withContext(Dispatchers.IO) {
        val clean = command.trim()
        if (clean.isEmpty()) {
            return@withContext PrivilegedCommandResult(false, -1, "", "Command cannot be blank.", "shizuku")
        }
        val status = inspectEnvironment(context)
        if (!status.shizukuInstalled) {
            return@withContext PrivilegedCommandResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku ($SHIZUKU_PACKAGE) is not installed or authorized on this device. Install and start Shizuku for ADB-level Android edits.",
                backend = "shizuku"
            )
        }
        val rish = File(context.filesDir, "rish")
        if (!rish.exists()) {
            return@withContext PrivilegedCommandResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku app detected, but `rish` helper is not configured in app sandbox; export/authorize rish or use Standard Android APIs.",
                backend = "shizuku"
            )
        }
        runShellProcess(listOf(rish.absolutePath, "-c", clean), "shizuku")
    }

    suspend fun execRoot(context: Context, command: String): PrivilegedCommandResult = withContext(Dispatchers.IO) {
        val clean = command.trim()
        if (clean.isEmpty()) {
            return@withContext PrivilegedCommandResult(false, -1, "", "Command cannot be blank.", "root")
        }
        val status = inspectEnvironment(context)
        val su = status.rootBinaryPath
        if (su == null) {
            return@withContext PrivilegedCommandResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "Root `su` binary not detected on this device. Root access is required for superuser system modifications.",
                backend = "root"
            )
        }
        runShellProcess(listOf(su, "-c", clean), "root")
    }

    private fun runShellProcess(cmdArgs: List<String>, backend: String): PrivilegedCommandResult {
        return try {
            val proc = ProcessBuilder(cmdArgs).redirectErrorStream(false).start()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return PrivilegedCommandResult(false, -1, "", "Privileged command timed out after 10s.", backend)
            }
            val stdout = proc.inputStream.bufferedReader().readText().take(16_384)
            val stderr = proc.errorStream.bufferedReader().readText().take(16_384)
            val code = proc.exitValue()
            PrivilegedCommandResult(code == 0, code, stdout, stderr, backend)
        } catch (e: Exception) {
            PrivilegedCommandResult(false, -1, "", e.message ?: "Execution error", backend)
        }
    }
}
