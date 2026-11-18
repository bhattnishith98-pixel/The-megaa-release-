package com.example.runtime

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit

/**
 * On-device Clang launcher for Android/ARM64 toolchain bundles supplied by the user.
 * The app intentionally does not ship host-PC NDK executables: those are not Android binaries.
 */
object NativeCompilerRuntime {
    data class InstallResult(val success: Boolean, val message: String)
    data class CompileResult(val success: Boolean, val output: String, val outputFile: File?)

    private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 1536L * 1024L * 1024L
    private const val MAX_ENTRIES = 120_000

    private fun toolchainDir(context: Context) = File(context.filesDir, "toolchains/clang-android-arm64")

    fun isInstalled(context: Context): Boolean {
        val root = toolchainDir(context)
        return File(root, "bin/clang").canExecute() && File(root, "bin/clang++").canExecute()
    }

    fun installFromZip(context: Context, uri: Uri): InstallResult {
        val base = File(context.filesDir, "toolchains")
        val staging = File(base, "clang-install-${System.currentTimeMillis()}")
        val payload = File(staging, "payload")
        val destination = toolchainDir(context)
        return try {
            base.mkdirs()
            payload.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open selected toolchain ZIP")
            var total = 0L
            var entries = 0
            input.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ENTRIES) { "Toolchain ZIP contains too many entries" }
                        val outFile = File(payload, entry.name).canonicalFile
                        require(outFile.path.startsWith(payload.canonicalPath + File.separator) || outFile == payload) {
                            "Unsafe ZIP path rejected"
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var entryBytes = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    total += count
                                    require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) {
                                        "Toolchain ZIP exceeds the safe extraction size"
                                    }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val clang = payload.walkTopDown().firstOrNull { it.isFile && it.name == "clang" && it.parentFile?.name == "bin" }
                ?: throw IllegalArgumentException("ZIP does not contain bin/clang")
            val clangxx = File(clang.parentFile, "clang++")
            require(clangxx.isFile) { "ZIP does not contain bin/clang++" }
            require(isAndroidArm64Elf(clang)) { "clang is not an Android ARM64 executable" }
            require(isAndroidArm64Elf(clangxx)) { "clang++ is not an Android ARM64 executable" }
            val relativeRoot = clang.parentFile.parentFile
            val normalized = File(staging, "normalized-toolchain")
            require(relativeRoot.renameTo(normalized)) { "Could not prepare toolchain folder" }
            normalized.walkTopDown().filter { it.isFile && (it.name == "clang" || it.name == "clang++") }
                .forEach { it.setExecutable(true, true) }
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            require(normalized.renameTo(destination)) { "Could not activate installed toolchain" }
            staging.deleteRecursively()
            InstallResult(true, "Android ARM64 Clang toolchain installed")
        } catch (e: Exception) {
            staging.deleteRecursively()
            InstallResult(false, e.message ?: "Toolchain installation failed")
        }
    }

    fun compileC(context: Context, projectRoot: File, source: File): CompileResult =
        compile(context, projectRoot, source, "bin/clang", listOf("-std=c17"))

    fun compileCpp(context: Context, projectRoot: File, source: File): CompileResult =
        compile(context, projectRoot, source, "bin/clang++", listOf("-std=c++20"))

    private fun compile(
        context: Context,
        projectRoot: File,
        source: File,
        compilerRelativePath: String,
        languageFlags: List<String>
    ): CompileResult {
        return try {
            val root = projectRoot.canonicalFile
            val input = source.canonicalFile
            require(input.path.startsWith(root.path + File.separator) && input.isFile) {
                "Source file must be inside the active project"
            }
            val compiler = File(toolchainDir(context), compilerRelativePath)
            require(compiler.canExecute()) { "Install a compatible Android ARM64 Clang toolchain first" }
            val output = File(root, "build/${input.nameWithoutExtension}").canonicalFile
            require(output.path.startsWith(root.path + File.separator)) { "Invalid output path" }
            output.parentFile?.mkdirs()
            val command = mutableListOf(compiler.absolutePath)
            command += languageFlags
            command += listOf("-O0", "-g", input.absolutePath, "-o", output.absolutePath)
            val logFile = File(root, "build/compiler-output.log")
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .apply {
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    val lib = File(toolchainDir(context), "lib")
                    if (lib.isDirectory) environment()["LD_LIBRARY_PATH"] = lib.absolutePath
                }
                .start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CompileResult(false, "Compiler timed out after 60 seconds", null)
            }
            val text = logFile.takeIf { it.isFile }?.readText().orEmpty()
            val ok = process.exitValue() == 0 && output.isFile
            CompileResult(ok, text.ifBlank { if (ok) "Compile succeeded" else "Compiler exited with ${process.exitValue()}" }, output.takeIf { ok })
        } catch (e: Exception) {
            CompileResult(false, e.message ?: "Compile failed", null)
        }
    }

    private fun isAndroidArm64Elf(file: File): Boolean = runCatching {
        val header = ByteArray(20)
        file.inputStream().use { input -> if (input.read(header) != header.size) return false }
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() && header[5] == 1.toByte() &&
            header[18] == 183.toByte() && header[19] == 0.toByte()
    }.getOrDefault(false)
}
