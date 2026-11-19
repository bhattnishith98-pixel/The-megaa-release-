package com.vscodroid.studio

import java.io.File
import java.security.MessageDigest

enum class VscodroidToolchainKind(
    val id: String,
    val displayName: String,
    val version: String,
    val extensions: List<String>
) {
    NODE_JS("nodejs", "Node.js Runtime (VSCodroid)", "20.x", listOf("js", "mjs", "cjs", "ts", "jsx", "tsx", "json")),
    PYTHON("python", "Python 3 + StudioLang", "3.12", listOf("py", "stl")),
    CLANG_LLVM("clang", "Clang / LLVM C & C++", "18.x", listOf("c", "h", "cpp", "hpp", "cc")),
    OPENJDK("openjdk", "OpenJDK / Kotlin", "17.x", listOf("kt", "kts", "java")),
    GODOT_GDSCRIPT("gdscript", "Godot 4.7 GDScript / TSCN", "4.7.2", listOf("gd", "tscn", "godot")),
    RUBY("ruby", "Ruby Scripting", "3.3", listOf("rb")),
    SHELL("bash", "POSIX / Android Shell", "5.2", listOf("sh", "bash", "yml", "yaml", "md", "xml"))
}

data class VscodroidAarStatus(
    val libraryModule: String,
    val packageNamespace: String,
    val upstreamRepo: String,
    val upstreamVersion: String,
    val webViewCodeOssReady: Boolean,
    val toolchains: List<VscodroidToolchainKind>,
    val discoveredExternalAars: List<String>
)

data class VscodroidBufferSync(
    val filePath: String,
    val languageId: String,
    val lineCount: Int,
    val charCount: Int,
    val sha256Hex: String
)

/**
 * Runtime entrypoint for the bundled `:vscodroid` Android Library AAR module,
 * implementing the local offline Code-OSS WebView workbench & toolchain bridge
 * modeled after `https://github.com/rmyndharis/VSCodroid`.
 */
object VscodroidAarRuntime {

    const val UPSTREAM_REPO_URL = "https://github.com/rmyndharis/VSCodroid"
    const val MODULE_COORDINATE = ":vscodroid"
    const val NAMESPACE = "com.vscodroid.studio"
    const val WORKBENCH_VERSION = "Code-OSS 1.96 (VSCodroid AAR 1.0)"

    fun isWebViewAvailable(): Boolean = runCatching {
        Class.forName("android.webkit.WebView")
        true
    }.getOrElse { false }

    fun resolveLanguageId(fileNameOrPath: String): String {
        val ext = fileNameOrPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py" -> "python"
            "stl" -> "studiolang"
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "gd", "tscn", "godot" -> "gdscript"
            "c", "h" -> "c"
            "cpp", "cc", "hpp" -> "cpp"
            "xml", "html", "svg" -> "xml"
            "json" -> "json"
            "md" -> "markdown"
            "sh" -> "shellscript"
            else -> "plaintext"
        }
    }

    fun buildBufferMetadata(filePath: String, content: String): VscodroidBufferSync {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        val lines = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1
        return VscodroidBufferSync(
            filePath = filePath,
            languageId = resolveLanguageId(filePath),
            lineCount = lines,
            charCount = content.length,
            sha256Hex = hex
        )
    }

    fun inspectStatus(searchRoots: List<File> = emptyList()): VscodroidAarStatus {
        val discovered = linkedSetOf<String>()
        searchRoots.forEach { root ->
            val libsDir = File(root, "libs")
            if (libsDir.isDirectory) {
                libsDir.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".aar", ignoreCase = true) ||
                            f.name.endsWith(".jar", ignoreCase = true))
                    ) {
                        discovered += f.name
                    }
                }
            }
        }
        return VscodroidAarStatus(
            libraryModule = MODULE_COORDINATE,
            packageNamespace = NAMESPACE,
            upstreamRepo = UPSTREAM_REPO_URL,
            upstreamVersion = WORKBENCH_VERSION,
            webViewCodeOssReady = isWebViewAvailable(),
            toolchains = VscodroidToolchainKind.entries,
            discoveredExternalAars = discovered.toList().sorted()
        )
    }
}
