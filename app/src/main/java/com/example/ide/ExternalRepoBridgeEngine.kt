package com.example.ide

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import java.net.HttpURLConnection
import java.net.URL

enum class UpstreamRepoKind(
    val id: String,
    val displayName: String,
    val repoSlug: String,
    val repoUrl: String,
    val license: String,
    val expectedLayers: List<String>,
    val folderHint: String,
    val archiveFileName: String,
    val archiveDownloadUrl: String
) {
    VSCODE(
        id = "vscode",
        displayName = "Visual Studio Code",
        repoSlug = "microsoft/vscode",
        repoUrl = "https://github.com/microsoft/vscode",
        license = "MIT License",
        expectedLayers = listOf(
            "src/vs/base",
            "src/vs/platform",
            "src/vs/editor",
            "src/vs/workbench",
            "src/vs/code",
            "src/vs/server"
        ),
        folderHint = "external_repos/vscode",
        archiveFileName = "vscode-main.zip",
        archiveDownloadUrl = "https://github.com/microsoft/vscode/archive/refs/heads/main.zip"
    ),
    GODOT(
        id = "godot",
        displayName = "Godot Engine 4.7.2-stable",
        repoSlug = "godotengine/godot",
        repoUrl = "https://github.com/godotengine/godot/releases/tag/4.7.2-stable",
        license = "MIT License",
        expectedLayers = listOf(
            "core",
            "servers",
            "scene",
            "modules/gdscript",
            "editor"
        ),
        folderHint = "external_repos/godot",
        archiveFileName = "godot-4.7.2-stable.zip",
        archiveDownloadUrl = "https://github.com/godotengine/godot/archive/refs/tags/4.7.2-stable.zip"
    ),
    ANDROID_CODE_STUDIO(
        id = "android-code-studio",
        displayName = "Android Code Studio",
        repoSlug = "AndroidCSOfficial/android-code-studio",
        repoUrl = "https://github.com/AndroidCSOfficial/android-code-studio",
        license = "Open Source (MIT / GPL-3.0 Ecosystem)",
        expectedLayers = listOf(
            "app",
            "editor",
            "java-completion",
            "xml-inflater",
            "common",
            "build-tools"
        ),
        folderHint = "external_repos/android-code-studio",
        archiveFileName = "android-code-studio-main.zip",
        archiveDownloadUrl = "https://github.com/AndroidCSOfficial/android-code-studio/archive/refs/heads/main.zip"
    )
}

enum class RepoMountState(val badge: String) {
    CORE_BRIDGE_READY("Bridge Ready"),
    USER_REPO_MOUNTED("Repo Mounted")
}

data class MountedRepoStatus(
    val kind: UpstreamRepoKind,
    val state: RepoMountState = RepoMountState.CORE_BRIDGE_READY,
    val sourceOrigin: String = "Built-in Automation Studio Bridge",
    val indexedFileCount: Int = 0,
    val detectedLayers: List<String> = emptyList(),
    val sampleFilePaths: List<String> = emptyList(),
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

data class RepoBridgeDescriptor(
    val id: String,
    val title: String,
    val sourceLayer: String,
    val targetLayer: String,
    val workflowDescription: String,
    val lastStatus: String = "Ready",
    val lastVerifiedMs: Long = 0L
)

enum class RepoImportMode(
    val id: String,
    val title: String,
    val subtitle: String,
    val maxFiles: Int
) {
    FULL_SOURCE_AND_COMPILE(
        id = "full_compile",
        title = "Full Repo Source + Compile",
        subtitle = "Extract the complete repository archive without an arbitrary file-count cap; index supported text files.",
        maxFiles = Int.MAX_VALUE
    ),
    ESSENTIAL_LAYERS_ONLY(
        id = "essential",
        title = "Essential Layers Only",
        subtitle = "Extract only core/editor/scene/gdscript/xml-inflater/build-tools source layers.",
        maxFiles = 400
    ),
    COMPILED_KOTLIN_ONLY(
        id = "compiled_only",
        title = "Compiled Kotlin Core Only (Fast)",
        subtitle = "Use rebuilt Kotlin engines (VscodeCore, GodotEngine, AndroidCodeStudio) with starter files only.",
        maxFiles = 12
    )
}

data class InAppVaultZipEntry(
    val name: String,
    val absolutePath: String,
    val relativePath: String,
    val sizeBytes: Long,
    val detectedKind: UpstreamRepoKind
)

data class MountedRepoResult(
    val status: MountedRepoStatus,
    val indexedFiles: List<IdeFileEntry>,
    val summaryMessage: String
)

object ExternalRepoBridgeEngine {

    private val textExtensions = setOf(
        ".kt", ".java", ".xml", ".gradle", ".kts",
        ".gd", ".tscn", ".tres", ".godot", ".cfg",
        ".py", ".stl", ".js", ".ts", ".tsx", ".jsx",
        ".cpp", ".c", ".h", ".hpp", ".glsl",
        ".json", ".md", ".txt", ".yaml", ".yml", ".properties", ".toml"
    )

    fun defaultRepoStatuses(): List<MountedRepoStatus> =
        UpstreamRepoKind.entries.map { kind ->
            MountedRepoStatus(
                kind = kind,
                state = RepoMountState.CORE_BRIDGE_READY,
                sourceOrigin = "Built-in Offline Bridge (${kind.repoSlug})",
                indexedFileCount = 0,
                detectedLayers = kind.expectedLayers.take(3)
            )
        }

    fun defaultBridgeDescriptors(): List<RepoBridgeDescriptor> = listOf(
        RepoBridgeDescriptor(
            id = "bridge_vscode_to_godot",
            title = "VS Code Editor <-> Godot Attach Script Bridge",
            sourceLayer = "microsoft/vscode (src/vs/editor + workbench)",
            targetLayer = "godotengine/godot (modules/gdscript + StudioVm)",
            workflowDescription = "Routes multi-tab script edits in 7 languages (GD/PY/STL/KT/JAVA/JS/C++) straight into Godot Node bindings & StudioVm."
        ),
        RepoBridgeDescriptor(
            id = "bridge_godot_servers_vm",
            title = "Godot Core/Scene/Servers <-> 2D Viewport & VM Bridge",
            sourceLayer = "godotengine/godot (core + servers + scene)",
            targetLayer = "Automation Studio Canvas + StudioStdLib (godot.*)",
            workflowDescription = "Syncs SceneTree, PhysicsServer2D AABB collisions, RenderingServer canvas transforms, and .tscn scene serialization."
        ),
        RepoBridgeDescriptor(
            id = "bridge_acs_android_pipeline",
            title = "Android Code Studio <-> Live Preview & Gradle APK Bridge",
            sourceLayer = "AndroidCSOfficial/android-code-studio (app + xml-inflater)",
            targetLayer = "AppBuilder + Play-Policy Manifest Audit + APK CI ZIP",
            workflowDescription = "Parses Compose & XML layouts live, validates AndroidManifest.xml Play compliance, and packages exportable Gradle builds."
        )
    )

    fun detectRepoKind(entryPaths: Collection<String>, nameHint: String? = null): UpstreamRepoKind {
        val joined = entryPaths.joinToString("\n").lowercase()
        val hint = nameHint.orEmpty().lowercase()
        return when {
            joined.contains("src/vs/workbench") || joined.contains("src/vs/editor") || hint.contains("vscode") ->
                UpstreamRepoKind.VSCODE
            joined.contains("modules/gdscript") || joined.contains("core/object/") || joined.contains("sconstruct") || hint.contains("godot") ->
                UpstreamRepoKind.GODOT
            joined.contains("com/tom/rv2ide") || joined.contains("xml-inflater") || joined.contains("android-code-studio") || hint.contains("android-code-studio") ->
                UpstreamRepoKind.ANDROID_CODE_STUDIO
            joined.contains(".gd") || joined.contains(".tscn") || joined.contains(".cpp") ->
                UpstreamRepoKind.GODOT
            joined.contains("androidmanifest.xml") || joined.contains(".kt") || joined.contains(".java") ->
                UpstreamRepoKind.ANDROID_CODE_STUDIO
            else -> UpstreamRepoKind.VSCODE
        }
    }

    /**
     * Safely mounts any GitHub/local repository .zip (e.g. vscode-main.zip, godot-master.zip, android-code-studio-main.zip)
     * with strict Zip-Slip canonical path checks and a device-safe expanded-size guard.
     */
    fun mountFromZipStream(
        kindOverride: UpstreamRepoKind?,
        zipDisplayName: String,
        inputStream: InputStream,
        workspaceRoot: File,
        maxIndexedFiles: Int = 250,
        maxFileBytes: Int = 96 * 1024,
        importMode: RepoImportMode = RepoImportMode.FULL_SOURCE_AND_COMPILE
    ): MountedRepoResult {
        // Full Source + Compile intentionally has no arbitrary file-count cap. A per-entry and
        // total expanded-size guard protects Android devices from malformed ZIP bombs.
        val stagingRoot = File(workspaceRoot, "external_repos/_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        val stagingCanonical = stagingRoot.canonicalPath
        val rawPaths = mutableListOf<String>()
        val extractedTextFiles = mutableListOf<Pair<String, String>>()
        val buffer = ByteArray(8192)
        var totalExpandedBytes = 0L
        val maxEntryExpandedBytes = 512L * 1024L * 1024L
        val maxArchiveExpandedBytes = 4L * 1024L * 1024L * 1024L

        try {
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val cleanEntryPath = entry.name.replace('\\', '/').trimStart('/')
                    if (cleanEntryPath.isNotBlank() && !entry.isDirectory) {
                        val normalizedRel = stripGitHubRootFolder(cleanEntryPath)
                        val passesModeFilter = when (importMode) {
                            RepoImportMode.FULL_SOURCE_AND_COMPILE -> true
                            RepoImportMode.ESSENTIAL_LAYERS_ONLY -> isEssentialLayerPath(normalizedRel)
                            RepoImportMode.COMPILED_KOTLIN_ONLY -> false
                        }
                        if (!passesModeFilter) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        val outFile = File(stagingRoot, normalizedRel)
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(stagingCanonical + File.separator)) {
                            throw SecurityException("Blocked unsafe ZIP path traversal: ${entry.name}")
                        }
                        outFile.parentFile?.mkdirs()
                        var entryBytes = 0L
                        val indexBuffer = java.io.ByteArrayOutputStream()
                        FileOutputStream(outFile).use { output ->
                            while (true) {
                                val count = zis.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalExpandedBytes += count
                                if (entryBytes > maxEntryExpandedBytes || totalExpandedBytes > maxArchiveExpandedBytes) {
                                    throw IllegalStateException("ZIP expands beyond the safe on-device import allowance")
                                }
                                output.write(buffer, 0, count)
                                if (isIndexableTextFile(normalizedRel) && entryBytes <= maxFileBytes) {
                                    indexBuffer.write(buffer, 0, count)
                                }
                            }
                        }
                        rawPaths += normalizedRel
                        if (isIndexableTextFile(normalizedRel) && entryBytes <= maxFileBytes) {
                            extractedTextFiles += normalizedRel to indexBuffer.toString(Charsets.UTF_8.name())
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val resolvedKind = kindOverride ?: detectRepoKind(rawPaths, zipDisplayName)
            val targetRepoDir = File(workspaceRoot, resolvedKind.folderHint)
            if (targetRepoDir.exists() && !targetRepoDir.deleteRecursively()) {
                throw IllegalStateException("Could not replace existing repository folder")
            }
            targetRepoDir.parentFile?.mkdirs()
            if (!stagingRoot.renameTo(targetRepoDir)) {
                stagingRoot.copyRecursively(targetRepoDir, overwrite = true)
                stagingRoot.deleteRecursively()
            }

            return buildResultFromFiles(
                kind = resolvedKind,
                sourceOrigin = "ZIP (${importMode.title}): $zipDisplayName",
                allDiscoveredPaths = rawPaths,
                textPairs = extractedTextFiles
            )
        } catch (t: Throwable) {
            stagingRoot.deleteRecursively()
            throw t
        }

    }

    private fun isEssentialLayerPath(relPath: String): Boolean {
        val low = relPath.lowercase()
        val essentialTokens = listOf(
            "src/vs/editor", "src/vs/workbench", "src/vs/base",
            "core/", "scene/", "servers/", "modules/gdscript", "editor/",
            "xml-inflater", "java-completion", "build-tools", "app/src/main",
            "package.json", "settings.gradle", "sconstruct"
        )
        return essentialTokens.any { low.contains(it) } || !low.contains('/')
    }

    fun listInAppVaultZips(workspaceRoot: File): List<InAppVaultZipEntry> {
        val searchDirs = listOf(
            File(workspaceRoot, "in_app_zips"),
            File(workspaceRoot, "external_repos/_downloads"),
            File(workspaceRoot, "external_repos"),
            workspaceRoot
        )
        val seen = linkedSetOf<String>()
        val out = mutableListOf<InAppVaultZipEntry>()
        searchDirs.forEach { dir ->
            if (!dir.exists() || !dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".zip", ignoreCase = true) && seen.add(f.canonicalPath)) {
                    val rel = runCatching { f.relativeTo(workspaceRoot).path }.getOrDefault(f.name)
                    out += InAppVaultZipEntry(
                        name = f.name,
                        absolutePath = f.absolutePath,
                        relativePath = rel,
                        sizeBytes = f.length(),
                        detectedKind = detectRepoKind(listOf(f.name), f.name)
                    )
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun saveStreamToInAppVault(
        workspaceRoot: File,
        fileName: String,
        inputStream: InputStream
    ): File {
        val vaultDir = File(workspaceRoot, "in_app_zips").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        val target = File(vaultDir, safeName)
        FileOutputStream(target).use { out ->
            inputStream.copyTo(out)
        }
        return target
    }

    fun createReadyInAppRepoZip(
        kind: UpstreamRepoKind,
        workspaceRoot: File
    ): File {
        val vaultDir = File(workspaceRoot, "in_app_zips").apply { mkdirs() }
        val target = File(vaultDir, kind.archiveFileName)
        val entries: List<Pair<String, String>> = when (kind) {
            UpstreamRepoKind.VSCODE -> listOf(
                "vscode-main/package.json" to """{"name":"code-oss-dev","version":"1.98.0","license":"MIT"}""",
                "vscode-main/src/vs/editor/browser/editorBrowser.ts" to "export interface ICodeEditor { getValue(): string; setValue(v: string): void; }",
                "vscode-main/src/vs/workbench/workbench.desktop.main.ts" to "export class WorkbenchShell { layout(): void {} }",
                "vscode-main/src/vs/base/common/strings.ts" to "export function trim(s: string): string { return s.trim(); }"
            )
            UpstreamRepoKind.GODOT -> listOf(
                "godot-4.7.2-stable/SConstruct" to "env = Environment()\n# Godot Engine 4.7.2-stable SCons Manifest (https://github.com/godotengine/godot/releases/tag/4.7.2-stable)",
                "godot-4.7.2-stable/core/object/object.h" to "#pragma once\nclass Object { public: virtual void emit_signal(const char* name); };",
                "godot-4.7.2-stable/scene/2d/node_2d.h" to "#pragma once\nclass Node2D : public Object { public: float x = 0; float y = 0; };",
                "godot-4.7.2-stable/modules/gdscript/gdscript_vm.cpp" to "// Godot 4.7.2-stable GDScript VM EntryPoint bridged to GodotEngineKotlin & StudioVm\nvoid gd_step(float delta) {}"
            )
            UpstreamRepoKind.ANDROID_CODE_STUDIO -> listOf(
                "android-code-studio-main/settings.gradle.kts" to "include(\":app\", \":xml-inflater\", \":java-completion\", \":build-tools\")",
                "android-code-studio-main/app/src/main/java/com/tom/rv2ide/Main.kt" to "package com.tom.rv2ide\nclass AndroidCodeStudioApp { fun buildApk() = true }",
                "android-code-studio-main/xml-inflater/src/main/java/XmlInflater.kt" to "class XmlInflater { fun inflate(xml: String) = xml.isNotBlank() }",
                "android-code-studio-main/java-completion/src/main/java/JavaCompletion.kt" to "class JavaCompletion { fun suggest(prefix: String) = listOf(\"Button\", \"Text\") }"
            )
        }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (p, body) ->
                zos.putNextEntry(ZipEntry(p))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        target.writeBytes(baos.toByteArray())
        return target
    }

    /**
     * Scans workspaceRoot/external_repos/{vscode,godot,android-code-studio} for user-placed full repositories.
     */
    fun scanExternalReposDirectory(
        workspaceRoot: File,
        maxIndexedFilesPerRepo: Int = Int.MAX_VALUE,
        maxFileBytes: Int = 96 * 1024
    ): List<MountedRepoResult> {
        val externalBase = File(workspaceRoot, "external_repos")
        if (!externalBase.exists()) return emptyList()

        val results = mutableListOf<MountedRepoResult>()
        UpstreamRepoKind.entries.forEach { kind ->
            val repoDir = File(workspaceRoot, kind.folderHint)
            if (repoDir.exists() && repoDir.isDirectory) {
                val discovered = mutableListOf<String>()
                val pairs = mutableListOf<Pair<String, String>>()
                val baseCanonical = repoDir.canonicalPath
                repoDir.walkTopDown()
                    .onEnter { dir -> !dir.name.startsWith(".git") }
                    .forEach { file ->
                        if (file.isFile && file.name != ".gitkeep") {
                            val rel = file.canonicalPath.removePrefix(baseCanonical).trimStart(File.separatorChar, '/')
                                .replace('\\', '/')
                            discovered += rel
                            if (isIndexableTextFile(rel) && file.length() <= maxFileBytes) {
                                runCatching { file.readText() }.getOrNull()?.let { text ->
                                    pairs += rel to text
                                }
                            }
                        }
                    }

                if (discovered.isNotEmpty()) {
                    results += buildResultFromFiles(
                        kind = kind,
                        sourceOrigin = "Folder: ${kind.folderHint}",
                        allDiscoveredPaths = discovered,
                        textPairs = pairs
                    )
                }
            }
        }
        return results
    }

    private fun buildResultFromFiles(
        kind: UpstreamRepoKind,
        sourceOrigin: String,
        allDiscoveredPaths: List<String>,
        textPairs: List<Pair<String, String>>
    ): MountedRepoResult {
        val detectedLayers = kind.expectedLayers.filter { layer ->
            allDiscoveredPaths.any { p -> p.startsWith(layer, ignoreCase = true) }
        }
        val entries = textPairs.map { (rel, content) ->
            val vPath = "repos/${kind.id}/$rel"
            IdeFileEntry(
                path = vPath,
                title = rel.substringAfterLast('/'),
                content = content,
                languageHint = inferLanguageHint(rel)
            )
        }
        val status = MountedRepoStatus(
            kind = kind,
            state = RepoMountState.USER_REPO_MOUNTED,
            sourceOrigin = sourceOrigin,
            indexedFileCount = entries.size,
            detectedLayers = detectedLayers.ifEmpty { listOf("workspace-root") },
            sampleFilePaths = entries.take(8).map { it.path },
            lastUpdatedMs = System.currentTimeMillis()
        )
        return MountedRepoResult(
            status = status,
            indexedFiles = entries,
            summaryMessage = "Mounted ${kind.displayName} (${kind.repoSlug}): ${entries.size} source files indexed (${allDiscoveredPaths.size} total scanned), layers=${status.detectedLayers.joinToString(", ")}"
        )
    }

    private fun stripGitHubRootFolder(path: String): String {
        val parts = path.split('/')
        if (parts.size <= 1) return path
        val first = parts.first().lowercase()
        return if (
            first.endsWith("-main") ||
            first.endsWith("-master") ||
            first.endsWith("-stable") ||
            first.startsWith("godot-4.") ||
            first == "vscode" ||
            first == "godot" ||
            first == "android-code-studio"
        ) {
            parts.drop(1).joinToString("/")
        } else {
            path
        }
    }

    private fun isIndexableTextFile(path: String): Boolean {
        val lower = path.lowercase()
        val fileName = lower.substringAfterLast('/')
        if (fileName == "sconstruct" || fileName == "cmakelists.txt" || fileName == "package.json") return true
        return textExtensions.any { lower.endsWith(it) }
    }

    private fun inferLanguageHint(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".gd") -> "gdscript"
            lower.endsWith(".py") -> "python"
            lower.endsWith(".stl") -> "studiolang"
            lower.endsWith(".kt") -> "kotlin"
            lower.endsWith(".java") -> "java"
            lower.endsWith(".ts") || lower.endsWith(".tsx") -> "typescript"
            lower.endsWith(".js") || lower.endsWith(".jsx") -> "javascript"
            lower.endsWith(".cpp") || lower.endsWith(".c") || lower.endsWith(".h") -> "cpp"
            lower.endsWith(".xml") -> "xml"
            lower.endsWith(".tscn") || lower.endsWith(".tres") -> "godot-scene"
            lower.endsWith(".json") -> "json"
            else -> "text"
        }
    }

    private fun readUpToLimit(zis: ZipInputStream, maxBytes: Int, buffer: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = zis.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Streams a GitHub repository archive (e.g. godot-master.zip, android-code-studio-main.zip, vscode-main.zip)
     * directly onto local app storage via HTTPS and mounts it into the IDE with Zip-Slip protection.
     */
    fun downloadAndMountFromUrl(
        kind: UpstreamRepoKind,
        workspaceRoot: File,
        urlOverride: String? = null,
        importMode: RepoImportMode = RepoImportMode.FULL_SOURCE_AND_COMPILE,
        onBytesProgress: (Long) -> Unit = {}
    ): MountedRepoResult {
        val downloadsDir = File(workspaceRoot, "in_app_zips").apply { mkdirs() }
        val localZip = File(downloadsDir, kind.archiveFileName)
        var currentUrl = urlOverride ?: kind.archiveDownloadUrl
        var conn: HttpURLConnection? = null
        var redirects = 0
        while (redirects < 5) {
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AutomationStudio-IDE/1.0")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = loc
                redirects++
            } else {
                break
            }
        }
        val activeConn = conn ?: error("Unable to open connection to $currentUrl")
        try {
            if (activeConn.responseCode !in 200..299) {
                error("HTTP ${activeConn.responseCode} while fetching ${kind.archiveFileName}")
            }
            val buf = ByteArray(16 * 1024)
            var downloaded = 0L
            activeConn.inputStream.use { input ->
                FileOutputStream(localZip).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onBytesProgress(downloaded)
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
        return localZip.inputStream().use { stream ->
            mountFromZipStream(kind, kind.archiveFileName, stream, workspaceRoot, importMode = importMode)
        }
    }

    /**
     * Auto-mounts pre-bundled ZIPs if placed in assets/repos/{godot-master.zip,android-code-studio-main.zip,vscode-main.zip}.
     */
    fun scanBundledAssetZips(
        workspaceRoot: File,
        assetOpener: (String) -> InputStream?
    ): List<MountedRepoResult> {
        val mounted = mutableListOf<MountedRepoResult>()
        UpstreamRepoKind.entries.forEach { kind ->
            val assetPath = "repos/${kind.archiveFileName}"
            val stream = runCatching { assetOpener(assetPath) }.getOrNull()
            if (stream != null) {
                stream.use { s ->
                    mounted += mountFromZipStream(kind, "assets/$assetPath", s, workspaceRoot)
                }
            }
        }
        return mounted
    }
}
