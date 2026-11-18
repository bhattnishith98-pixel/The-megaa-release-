package com.example.ide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ProjectSourceKind(val id: String, val badge: String) {
    DEFAULT_SANDBOX("default_sandbox", "Built-in Sandbox"),
    IN_APP_PROJECT("in_app", "In-App Project"),
    SAF_EXTERNAL_FOLDER("saf_folder", "Device Folder (Direct)"),
    ZIP_IMPORT("zip_import", "ZIP Project"),
    GITHUB_REPO("github_repo", "GitHub Repo");

    companion object {
        fun fromId(id: String): ProjectSourceKind =
            entries.firstOrNull { it.id == id } ?: IN_APP_PROJECT
    }
}

enum class DetectedProjectType(val id: String, val label: String) {
    GODOT_4X("godot", "Godot 4.x"),
    ANDROID_GRADLE("android", "Android Gradle"),
    WEB_JS("web", "Web / JS"),
    PYTHON("python", "Python"),
    CPP_NATIVE("cpp", "C / C++"),
    STUDIOLANG("studiolang", "StudioLang"),
    GENERAL("general", "General Code");

    companion object {
        fun fromId(id: String): DetectedProjectType =
            entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

enum class ProjectTemplateKind(val id: String, val label: String) {
    BLANK("blank", "Blank"),
    GODOT_2D("godot_2d", "Godot 4.7.2 Game"),
    ANDROID_COMPOSE("android_compose", "Android Compose"),
    PYTHON_SCRIPT("python", "Python App"),
    CPP_NATIVE("cpp", "C / C++ Native"),
    WEB_HTML_JS("web", "Web HTML / JS")
}

enum class ProjectSyncState(val label: String) {
    IDLE("Ready"),
    SCANNING("Scanning files..."),
    SAVING("Saving changes..."),
    SYNCED("Synced"),
    ERROR("Sync error")
}

data class IdeProjectDescriptor(
    val id: String,
    val name: String,
    val sourceKind: ProjectSourceKind,
    val detectedType: DetectedProjectType,
    val localDirPath: String,
    val safTreeUriString: String? = null,
    val remoteUrl: String? = null,
    val fileCount: Int = 0,
    val skippedCount: Int = 0,
    val lastOpenedMs: Long = System.currentTimeMillis()
) {
    val isDirectFolder: Boolean get() = sourceKind == ProjectSourceKind.SAF_EXTERNAL_FOLDER && !safTreeUriString.isNullOrBlank()
}

data class LoadedProjectWorkspace(
    val descriptor: IdeProjectDescriptor,
    val files: List<IdeFileEntry>,
    val safDocUriByRelPath: Map<String, String> = emptyMap(),
    val skippedFilesCount: Int = 0,
    val summaryMessage: String
)

/**
 * Universal Multi-Project Storage Driver supporting:
 * 1. Android Storage Access Framework (SAF) device folders with direct read & write-back.
 * 2. Arbitrary .zip archives from device storage / Downloads (no fixed repo restrictions).
 * 3. Any public GitHub repository URL (`https://github.com/owner/repo` or `owner/repo`).
 * 4. Multiple independent in-app projects & starter templates saved inside `projects/`.
 */
object UniversalProjectManager {

    const val DEFAULT_PROJECT_ID = "default_sandbox"
    private const val REGISTRY_FILENAME = "projects_registry.json"
    private const val MAX_PROJECT_FILES = 650
    private const val MAX_FILE_BYTES = 512 * 1024L

    val SKIP_DIRECTORY_NAMES = setOf(
        ".git", ".gradle", ".idea", ".godot", ".import", ".cxx",
        "build", "node_modules", "target", "__pycache__", ".venv", "venv"
    )

    private val EDITABLE_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "gradle", "pro", "properties",
        "gd", "tscn", "tres", "godot", "gdshader", "cfg", "import",
        "py", "pyi", "stl", "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "html", "htm", "css", "scss", "sass", "svg",
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp",
        "rs", "go", "dart", "rb", "php", "lua", "cs",
        "json", "yaml", "yml", "toml", "ini", "env", "md", "txt", "sh", "bat", "sql", "csv"
    )

    private val EDITABLE_FILENAMES = setOf(
        "cmakelists.txt", "sconstruct", "makefile", "dockerfile",
        "readme", "license", ".gitignore", ".gitattributes", ".editorconfig", "procfile"
    )

    fun projectsRoot(workspaceRoot: File): File =
        File(workspaceRoot, "projects").apply { mkdirs() }

    fun defaultSandboxDescriptor(workspaceRoot: File, fileCount: Int = 0): IdeProjectDescriptor =
        IdeProjectDescriptor(
            id = DEFAULT_PROJECT_ID,
            name = "Automation Sandbox",
            sourceKind = ProjectSourceKind.DEFAULT_SANDBOX,
            detectedType = DetectedProjectType.GODOT_4X,
            localDirPath = File(workspaceRoot, "unified_ide").apply { mkdirs() }.absolutePath,
            fileCount = fileCount,
            lastOpenedMs = System.currentTimeMillis()
        )

    fun isEditableTextPath(relPath: String, mimeType: String? = null): Boolean {
        val fileName = relPath.substringAfterLast('/').lowercase(Locale.US)
        if (fileName in EDITABLE_FILENAMES) return true
        val ext = fileName.substringAfterLast('.', "")
        if (ext in EDITABLE_EXTENSIONS) return true
        if (!mimeType.isNullOrBlank() && mimeType.startsWith("text/", ignoreCase = true)) return true
        return false
    }

    fun shouldSkipDirectory(dirName: String): Boolean =
        dirName.lowercase(Locale.US) in SKIP_DIRECTORY_NAMES

    fun inferLanguageHint(path: String): String {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".gd") -> "gdscript"
            lower.endsWith(".tscn") || lower.endsWith(".tres") || lower.endsWith(".godot") -> "godot-scene"
            lower.endsWith(".py") -> "python"
            lower.endsWith(".stl") -> "studiolang"
            lower.endsWith(".kt") || lower.endsWith(".kts") -> "kotlin"
            lower.endsWith(".java") -> "java"
            lower.endsWith(".ts") || lower.endsWith(".tsx") -> "typescript"
            lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") -> "javascript"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "html"
            lower.endsWith(".css") || lower.endsWith(".scss") -> "css"
            lower.endsWith(".c") || lower.endsWith(".cc") || lower.endsWith(".cpp") ||
                lower.endsWith(".cxx") || lower.endsWith(".h") || lower.endsWith(".hpp") -> "cpp"
            lower.endsWith(".xml") -> "xml"
            lower.endsWith(".gradle") -> "gradle"
            lower.endsWith(".json") -> "json"
            lower.endsWith(".yaml") || lower.endsWith(".yml") -> "yaml"
            lower.endsWith(".md") -> "markdown"
            else -> "text"
        }
    }

    fun detectProjectType(paths: Collection<String>): DetectedProjectType {
        val lower = paths.map { it.lowercase(Locale.US) }
        return when {
            lower.any { it.endsWith("project.godot") || it.endsWith(".tscn") || it.endsWith(".gd") } ->
                DetectedProjectType.GODOT_4X
            lower.any { it.endsWith("androidmanifest.xml") || it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") } ->
                DetectedProjectType.ANDROID_GRADLE
            lower.any { it.endsWith("package.json") || it.endsWith("index.html") || it.endsWith(".tsx") || it.endsWith(".ts") } ->
                DetectedProjectType.WEB_JS
            lower.any { it.endsWith(".py") && (it.endsWith("main.py") || it.endsWith("requirements.txt") || it.endsWith("pyproject.toml")) } ->
                DetectedProjectType.PYTHON
            lower.any { it.endsWith("cmakelists.txt") || it.endsWith(".cpp") || it.endsWith(".c") } ->
                DetectedProjectType.CPP_NATIVE
            lower.any { it.endsWith(".stl") } ->
                DetectedProjectType.STUDIOLANG
            lower.any { it.endsWith(".py") } -> DetectedProjectType.PYTHON
            lower.any { it.endsWith(".kt") || it.endsWith(".java") } -> DetectedProjectType.ANDROID_GRADLE
            lower.any { it.endsWith(".js") } -> DetectedProjectType.WEB_JS
            else -> DetectedProjectType.GENERAL
        }
    }

    fun sanitizeRelativePath(rawPath: String): String {
        val normalized = rawPath.replace('\\', '/').trim().trimStart('/')
        val segments = normalized.split('/').filter { it.isNotBlank() && it != "." }
        require(segments.isNotEmpty()) { "Path cannot be blank" }
        require(segments.none { it == ".." }) { "Parent traversal '..' is not allowed in path: $rawPath" }
        return segments.joinToString("/")
    }

    // ---- PERSISTED RECENT PROJECTS REGISTRY ----

    fun loadRegistry(workspaceRoot: File): List<IdeProjectDescriptor> {
        val regFile = File(projectsRoot(workspaceRoot), REGISTRY_FILENAME)
        if (!regFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(regFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                IdeProjectDescriptor(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    name = o.optString("name", "Untitled Project"),
                    sourceKind = ProjectSourceKind.fromId(o.optString("sourceKind")),
                    detectedType = DetectedProjectType.fromId(o.optString("detectedType")),
                    localDirPath = o.optString("localDirPath", ""),
                    safTreeUriString = o.optString("safTreeUriString").takeIf { it.isNotBlank() },
                    remoteUrl = o.optString("remoteUrl").takeIf { it.isNotBlank() },
                    fileCount = o.optInt("fileCount", 0),
                    skippedCount = o.optInt("skippedCount", 0),
                    lastOpenedMs = o.optLong("lastOpenedMs", 0L)
                )
            }.sortedByDescending { it.lastOpenedMs }
        }.getOrDefault(emptyList())
    }

    fun saveRegistry(workspaceRoot: File, projects: List<IdeProjectDescriptor>) {
        val regFile = File(projectsRoot(workspaceRoot), REGISTRY_FILENAME)
        val arr = JSONArray()
        projects.take(30).forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("sourceKind", p.sourceKind.id)
                    put("detectedType", p.detectedType.id)
                    put("localDirPath", p.localDirPath)
                    put("safTreeUriString", p.safTreeUriString ?: "")
                    put("remoteUrl", p.remoteUrl ?: "")
                    put("fileCount", p.fileCount)
                    put("skippedCount", p.skippedCount)
                    put("lastOpenedMs", p.lastOpenedMs)
                }
            )
        }
        regFile.parentFile?.mkdirs()
        regFile.writeText(arr.toString(2))
    }

    // ---- LOCAL DIRECTORY PROJECTS & TEMPLATES ----

    fun createNewInAppProject(
        workspaceRoot: File,
        rawName: String,
        template: ProjectTemplateKind
    ): LoadedProjectWorkspace {
        val cleanName = rawName.trim().ifBlank { "MyProject_${System.currentTimeMillis() % 10000}" }
        val slug = cleanName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "project" }
        val id = "proj_${slug}_${UUID.randomUUID().toString().take(6)}"
        val projectDir = File(projectsRoot(workspaceRoot), id).apply { mkdirs() }
        val pkgSlug = slug.replace("_", "")

        val starterFiles: List<Pair<String, String>> = when (template) {
            ProjectTemplateKind.BLANK -> listOf(
                "README.md" to "# $cleanName\n\nCreated in Automation Studio.\n",
                "main.stl" to "log.info(\"Hello from $cleanName\")\n"
            )
            ProjectTemplateKind.GODOT_2D -> listOf(
                "project.godot" to com.example.runtime.GodotProjectWorkspace.defaultProjectGodot(cleanName),
                "scripts/player.gd" to """
                    |extends CharacterBody2D
                    |
                    |const SPEED := 240.0
                    |
                    |func _ready():
                    |    print("$cleanName Godot 4.7.2 Ready")
                    |
                    |func _input(event):
                    |    if event is InputEventScreenTouch and event.pressed:
                    |        position.y -= 20
                """.trimMargin(),
                "scenes/main.tscn" to """
                    |[gd_scene load_steps=2 format=3]
                    |
                    |[ext_resource type="Script" path="res://scripts/player.gd" id="1_player"]
                    |
                    |[node name="MainScene" type="Node2D"]
                    |
                    |[node name="Player" type="CharacterBody2D" parent="."]
                    |position = Vector2(200, 180)
                    |script = ExtResource("1_player")
                    |
                    |[node name="VisualRect" type="ColorRect" parent="Player"]
                    |offset_left = -24.0
                    |offset_top = -24.0
                    |offset_right = 24.0
                    |offset_bottom = 24.0
                    |color = Color(0.2, 0.82, 0.6, 1)
                """.trimMargin()
            )
            ProjectTemplateKind.ANDROID_COMPOSE -> listOf(
                "android/app/src/main/AndroidManifest.xml" to """
                    |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    |    package="com.example.$pkgSlug">
                    |    <uses-permission android:name="android.permission.INTERNET" />
                    |    <application android:label="$cleanName">
                    |        <activity android:name=".MainActivity" android:exported="true" />
                    |    </application>
                    |</manifest>
                """.trimMargin(),
                "android/app/build.gradle.kts" to """
                    |plugins { id("com.android.application") }
                    |android {
                    |    namespace = "com.example.$pkgSlug"
                    |    compileSdk = 36
                    |    defaultConfig { minSdk = 24; targetSdk = 36 }
                    |}
                """.trimMargin(),
                "android/app/src/main/java/com/example/studioapp/MainActivity.kt" to """
                    |package com.example.$pkgSlug
                    |
                    |import android.os.Bundle
                    |import androidx.activity.ComponentActivity
                    |import androidx.activity.compose.setContent
                    |import androidx.compose.foundation.layout.*
                    |import androidx.compose.material3.*
                    |import androidx.compose.runtime.*
                    |import androidx.compose.ui.Modifier
                    |import androidx.compose.ui.unit.dp
                    |
                    |class MainActivity : ComponentActivity() {
                    |    override fun onCreate(savedInstanceState: Bundle?) {
                    |        super.onCreate(savedInstanceState)
                    |        setContent {
                    |            Column(Modifier.padding(16.dp)) {
                    |                Text("$cleanName")
                    |                Button(onClick = {}) { Text("Run Action") }
                    |            }
                    |        }
                    |    }
                    |}
                """.trimMargin()
            )
            ProjectTemplateKind.PYTHON_SCRIPT -> listOf(
                "main.py" to """
                    |def main():
                    |    print("Running $cleanName in Python")
                    |    items = ["Godot", "Android", "Studio"]
                    |    for idx, name in enumerate(items, 1):
                    |        print(f"{idx}. {name}")
                    |
                    |if __name__ == "__main__":
                    |    main()
                """.trimMargin(),
                "requirements.txt" to "# Add project dependencies here\n"
            )
            ProjectTemplateKind.CPP_NATIVE -> listOf(
                "src/main.cpp" to """
                    |#include <iostream>
                    |#include <string>
                    |
                    |int main() {
                    |    std::string app = "$cleanName";
                    |    std::cout << "Hello from " << app << " (ARM64 C++)" << std::endl;
                    |    return 0;
                    |}
                """.trimMargin(),
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.22)\nproject($slug)\nadd_executable(app src/main.cpp)\n"
            )
            ProjectTemplateKind.WEB_HTML_JS -> listOf(
                "index.html" to """
                    |<!DOCTYPE html>
                    |<html>
                    |<head><meta charset="utf-8"><title>$cleanName</title><link rel="stylesheet" href="style.css"></head>
                    |<body>
                    |  <main><h1>$cleanName</h1><button id="btn">Tap Me</button><p id="out"></p></main>
                    |  <script src="app.js"></script>
                    |</body>
                    |</html>
                """.trimMargin(),
                "style.css" to "body { font-family: system-ui; background: #0b0f17; color: #f8fafc; padding: 24px; }\n",
                "app.js" to "document.getElementById('btn').onclick = () => { document.getElementById('out').textContent = '$cleanName clicked!'; };\n"
            )
        }

        val entries = starterFiles.map { (rel, body) ->
            val out = File(projectDir, rel)
            out.parentFile?.mkdirs()
            out.writeText(body)
            IdeFileEntry(
                path = rel,
                title = out.name,
                content = body,
                languageHint = inferLanguageHint(rel)
            )
        }
        val detected = detectProjectType(entries.map { it.path })
        val desc = IdeProjectDescriptor(
            id = id,
            name = cleanName,
            sourceKind = ProjectSourceKind.IN_APP_PROJECT,
            detectedType = detected,
            localDirPath = projectDir.absolutePath,
            fileCount = entries.size,
            lastOpenedMs = System.currentTimeMillis()
        )
        return LoadedProjectWorkspace(
            descriptor = desc,
            files = entries,
            summaryMessage = "Created project '$cleanName' (${template.label}, ${entries.size} file(s))."
        )
    }

    fun loadLocalProjectDir(descriptor: IdeProjectDescriptor): LoadedProjectWorkspace {
        val rootDir = File(descriptor.localDirPath)
        if (!rootDir.isDirectory) {
            return LoadedProjectWorkspace(
                descriptor = descriptor.copy(lastOpenedMs = System.currentTimeMillis()),
                files = emptyList(),
                summaryMessage = "Project folder missing on disk: ${rootDir.name}"
            )
        }
        val canonicalRoot = rootDir.canonicalPath
        val entries = mutableListOf<IdeFileEntry>()
        var skipped = 0
        rootDir.walkTopDown()
            .onEnter { dir -> !shouldSkipDirectory(dir.name) }
            .forEach { file ->
                if (!file.isFile) return@forEach
                val rel = file.canonicalPath.removePrefix(canonicalRoot)
                    .trimStart(File.separatorChar, '/')
                    .replace('\\', '/')
                if (rel.isBlank()) return@forEach
                if (entries.size >= MAX_PROJECT_FILES || file.length() > MAX_FILE_BYTES || !isEditableTextPath(rel)) {
                    skipped++
                    return@forEach
                }
                val content = runCatching { file.readText() }.getOrNull()
                if (content != null) {
                    entries += IdeFileEntry(
                        path = rel,
                        title = file.name,
                        content = content,
                        languageHint = inferLanguageHint(rel)
                    )
                } else {
                    skipped++
                }
            }
        val sorted = entries.sortedBy { it.path }
        val detected = detectProjectType(sorted.map { it.path })
        val updated = descriptor.copy(
            detectedType = detected,
            fileCount = sorted.size,
            skippedCount = skipped,
            lastOpenedMs = System.currentTimeMillis()
        )
        return LoadedProjectWorkspace(
            descriptor = updated,
            files = sorted,
            skippedFilesCount = skipped,
            summaryMessage = "Loaded '${updated.name}' (${sorted.size} editable file(s), $skipped skipped)."
        )
    }

    fun writeLocalFile(projectDir: File, relPath: String, content: String): File {
        val safeRel = sanitizeRelativePath(relPath)
        val canonicalRoot = projectDir.canonicalPath
        val out = File(projectDir, safeRel).canonicalFile
        require(out.path == canonicalRoot || out.path.startsWith(canonicalRoot + File.separator)) {
            "Unsafe path rejected: $relPath"
        }
        out.parentFile?.mkdirs()
        out.writeText(content)
        return out
    }

    fun deleteLocalFile(projectDir: File, relPath: String): Boolean {
        val safeRel = sanitizeRelativePath(relPath)
        val canonicalRoot = projectDir.canonicalPath
        val target = File(projectDir, safeRel).canonicalFile
        require(target.path.startsWith(canonicalRoot + File.separator)) {
            "Unsafe path rejected: $relPath"
        }
        return !target.exists() || target.delete()
    }

    // ---- UNIVERSAL ZIP IMPORT & EXPORT ----

    fun importAnyZipStream(
        workspaceRoot: File,
        zipDisplayName: String,
        inputStream: InputStream,
        sourceKind: ProjectSourceKind = ProjectSourceKind.ZIP_IMPORT,
        remoteUrl: String? = null
    ): LoadedProjectWorkspace {
        val baseTitle = zipDisplayName
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "ImportedProject" }
        val slug = baseTitle.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "project" }
        val id = "proj_${slug}_${UUID.randomUUID().toString().take(6)}"
        val stagingDir = File(projectsRoot(workspaceRoot), "_tmp_$id").apply { mkdirs() }
        val stagingCanonical = stagingDir.canonicalPath
        val buffer = ByteArray(8192)
        val rawPaths = mutableListOf<String>()
        var totalExpandedBytes = 0L
        val maxEntryBytes = 64L * 1024L * 1024L
        val maxArchiveBytes = 512L * 1024L * 1024L

        try {
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.isNotBlank() && !entry.isDirectory) {
                        val segs = clean.split('/')
                        if (segs.any { it == ".." }) {
                            throw SecurityException("Zip-Slip path rejected: $clean")
                        }
                        if (segs.none { shouldSkipDirectory(it) }) {
                            val outFile = File(stagingDir, clean).canonicalFile
                            if (!outFile.path.startsWith(stagingCanonical + File.separator)) {
                                throw SecurityException("Zip-Slip blocked: $clean")
                            }
                            outFile.parentFile?.mkdirs()
                            var entryBytes = 0L
                            FileOutputStream(outFile).buffered().use { fos ->
                                while (true) {
                                    val n = zis.read(buffer)
                                    if (n <= 0) break
                                    entryBytes += n
                                    totalExpandedBytes += n
                                    if (entryBytes > maxEntryBytes || totalExpandedBytes > maxArchiveBytes) {
                                        throw IllegalStateException("ZIP archive exceeds safe size cap.")
                                    }
                                    fos.write(buffer, 0, n)
                                }
                            }
                            rawPaths += clean
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Strip single wrapping archive root folder (e.g., `repo-main/...` or `godot-4.7.2-stable/...`)
            val targetDir = File(projectsRoot(workspaceRoot), id).apply { mkdirs() }
            val topDirs = rawPaths.mapNotNull { p -> p.substringBefore('/', "").takeIf { it.isNotEmpty() } }.distinct()
            val allNestedUnderOneRoot = topDirs.size == 1 && rawPaths.all { it.contains('/') }
            val sourceBase = if (allNestedUnderOneRoot) File(stagingDir, topDirs.first()) else stagingDir
            sourceBase.copyRecursively(targetDir, overwrite = true)
            stagingDir.deleteRecursively()

            val initialDesc = IdeProjectDescriptor(
                id = id,
                name = baseTitle,
                sourceKind = sourceKind,
                detectedType = DetectedProjectType.GENERAL,
                localDirPath = targetDir.absolutePath,
                remoteUrl = remoteUrl
            )
            return loadLocalProjectDir(initialDesc)
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }

    fun exportProjectToZip(
        project: IdeProjectDescriptor,
        inMemoryFiles: List<IdeFileEntry>,
        outZip: File
    ): File {
        outZip.parentFile?.mkdirs()
        val writtenRelPaths = mutableSetOf<String>()
        val rootDir = File(project.localDirPath)
        val canonicalRoot = if (rootDir.isDirectory) rootDir.canonicalPath else null

        ZipOutputStream(FileOutputStream(outZip).buffered()).use { zos ->
            // 1. Always write current live in-memory IDE buffers first
            inMemoryFiles.forEach { entry ->
                val clean = runCatching { sanitizeRelativePath(entry.path) }.getOrNull() ?: return@forEach
                if (clean.startsWith("repos/") || !writtenRelPaths.add(clean)) return@forEach
                zos.putNextEntry(ZipEntry(clean))
                zos.write(entry.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // 2. Include any untouched extra/binary files from local project directory if it exists
            if (canonicalRoot != null) {
                rootDir.walkTopDown()
                    .onEnter { dir -> !shouldSkipDirectory(dir.name) }
                    .forEach { file ->
                        if (!file.isFile) return@forEach
                        val rel = file.canonicalPath.removePrefix(canonicalRoot)
                            .trimStart(File.separatorChar, '/')
                            .replace('\\', '/')
                        if (rel.isBlank() || !writtenRelPaths.add(rel)) return@forEach
                        if (file.length() <= 16L * 1024L * 1024L) {
                            zos.putNextEntry(ZipEntry(rel))
                            file.inputStream().buffered().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
            }
        }
        return outZip
    }

    // ---- GITHUB ANY-REPO CLONING ----

    data class GithubCloneSpec(
        val projectName: String,
        val canonicalRepoUrl: String,
        val archiveUrls: List<String>
    )

    fun parseGithubArchiveCandidates(rawUrl: String, branchOverride: String? = null): GithubCloneSpec {
        val trimmed = rawUrl.trim().removeSuffix(".git").trimEnd('/')
        require(trimmed.isNotBlank()) { "Enter a GitHub repository URL or owner/repo." }
        if (trimmed.endsWith(".zip", ignoreCase = true)) {
            val zipTitle = trimmed.substringAfterLast('/').substringBeforeLast('.').ifBlank { "github_project" }
            return GithubCloneSpec(zipTitle, trimmed, listOf(trimmed))
        }

        val withoutScheme = trimmed
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trim('/')
        val parts = withoutScheme.split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "Use a GitHub repo like 'owner/repo' or 'https://github.com/owner/repo'." }
        val owner = parts[0]
        val repo = parts[1]
        val canonicalUrl = "https://github.com/$owner/$repo"

        val explicitBranch = branchOverride?.trim()?.takeIf { it.isNotEmpty() }
        val urlTag = if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag") parts[4] else null
        val urlBranch = if (parts.size >= 4 && parts[2] == "tree") parts.drop(3).joinToString("/") else null

        val urls = linkedSetOf<String>()
        if (explicitBranch != null) {
            urls += "https://github.com/$owner/$repo/archive/refs/heads/$explicitBranch.zip"
            urls += "https://github.com/$owner/$repo/archive/refs/tags/$explicitBranch.zip"
        }
        if (urlTag != null) {
            urls += "https://github.com/$owner/$repo/archive/refs/tags/$urlTag.zip"
        }
        if (urlBranch != null) {
            urls += "https://github.com/$owner/$repo/archive/refs/heads/$urlBranch.zip"
        }
        urls += "https://github.com/$owner/$repo/archive/refs/heads/main.zip"
        urls += "https://github.com/$owner/$repo/archive/refs/heads/master.zip"
        return GithubCloneSpec(repo, canonicalUrl, urls.toList())
    }

    fun cloneGithubRepoZip(
        workspaceRoot: File,
        rawUrl: String,
        branchOverride: String? = null
    ): LoadedProjectWorkspace {
        val spec = parseGithubArchiveCandidates(rawUrl, branchOverride)
        var lastError: Throwable? = null

        for (candidateUrl in spec.archiveUrls) {
            runCatching {
                var currentUrl = candidateUrl
                var redirects = 0
                var conn: HttpURLConnection? = null
                while (redirects < 5) {
                    val opened = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 45_000
                        setRequestProperty("User-Agent", "AutomationStudio-IDE/1.0")
                    }
                    val code = opened.responseCode
                    if (code in 300..399) {
                        val loc = opened.getHeaderField("Location") ?: error("Redirect missing Location")
                        opened.disconnect()
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        redirects++
                    } else {
                        conn = opened
                        break
                    }
                }
                val activeConn = conn ?: error("Too many redirects")
                try {
                    if (activeConn.responseCode !in 200..299) {
                        error("HTTP ${activeConn.responseCode} from $candidateUrl")
                    }
                    return activeConn.inputStream.use { stream ->
                        importAnyZipStream(
                            workspaceRoot = workspaceRoot,
                            zipDisplayName = "${spec.projectName}.zip",
                            inputStream = stream,
                            sourceKind = ProjectSourceKind.GITHUB_REPO,
                            remoteUrl = spec.canonicalRepoUrl
                        )
                    }
                } finally {
                    activeConn.disconnect()
                }
            }.onFailure { err ->
                lastError = err
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: "Failed to clone ${spec.canonicalRepoUrl}"
        )
    }

    // ---- ANDROID SAF DEVICE FOLDER (OPEN + DIRECT WRITE-BACK) ----

    fun queryTreeDisplayName(context: Context, treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "DeviceFolder"
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0).ifBlank { docId.substringAfterLast('/') }
            }
        }
        return docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "DeviceFolder" }
    }

    fun scanSafTree(
        context: Context,
        workspaceRoot: File,
        treeUri: Uri
    ): LoadedProjectWorkspace {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderName = queryTreeDisplayName(context, treeUri)
        val id = "saf_${treeUri.toString().hashCode().toUInt()}"
        val entries = mutableListOf<IdeFileEntry>()
        val docUriMap = linkedMapOf<String, String>()
        var skipped = 0

        fun walk(parentDocId: String, prefix: String) {
            if (entries.size >= MAX_PROJECT_FILES) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext() && entries.size < MAX_PROJECT_FILES) {
                    val childId = cursor.getString(idIdx) ?: continue
                    val displayName = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val size = if (!cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val relPath = if (prefix.isEmpty()) displayName else "$prefix/$displayName"

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!shouldSkipDirectory(displayName)) {
                            walk(childId, relPath)
                        } else {
                            skipped++
                        }
                    } else {
                        if (size <= MAX_FILE_BYTES && isEditableTextPath(relPath, mime)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val body = runCatching {
                                resolver.openInputStream(docUri)?.bufferedReader()?.use { it.readText() }
                            }.getOrNull()
                            if (body != null) {
                                entries += IdeFileEntry(
                                    path = relPath,
                                    title = displayName,
                                    content = body,
                                    languageHint = inferLanguageHint(relPath)
                                )
                                docUriMap[relPath] = docUri.toString()
                            } else {
                                skipped++
                            }
                        } else {
                            skipped++
                        }
                    }
                }
            }
        }

        walk(rootDocId, "")
        val sorted = entries.sortedBy { it.path }
        val detected = detectProjectType(sorted.map { it.path })
        val mirrorDir = File(projectsRoot(workspaceRoot), "_mirror_$id").apply { mkdirs() }
        val desc = IdeProjectDescriptor(
            id = id,
            name = folderName,
            sourceKind = ProjectSourceKind.SAF_EXTERNAL_FOLDER,
            detectedType = detected,
            localDirPath = mirrorDir.absolutePath,
            safTreeUriString = treeUri.toString(),
            fileCount = sorted.size,
            skippedCount = skipped,
            lastOpenedMs = System.currentTimeMillis()
        )
        return LoadedProjectWorkspace(
            descriptor = desc,
            files = sorted,
            safDocUriByRelPath = docUriMap,
            skippedFilesCount = skipped,
            summaryMessage = "Opened folder '$folderName' with direct write-back (${sorted.size} editable files, $skipped skipped)."
        )
    }

    fun writeSafFile(
        context: Context,
        treeUri: Uri,
        docUriMap: MutableMap<String, String>,
        relPath: String,
        content: String
    ): String {
        val safeRel = sanitizeRelativePath(relPath)
        val resolver = context.contentResolver
        val existingUriStr = docUriMap[safeRel]
        val targetUri: Uri = if (!existingUriStr.isNullOrBlank()) {
            Uri.parse(existingUriStr)
        } else {
            val parts = safeRel.split('/')
            var parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            for (dirName in parts.dropLast(1)) {
                parentDocId = findOrCreateSafChild(
                    context = context,
                    treeUri = treeUri,
                    parentDocId = parentDocId,
                    displayName = dirName,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
            val fileName = parts.last()
            val fileDocId = findOrCreateSafChild(
                context = context,
                treeUri = treeUri,
                parentDocId = parentDocId,
                displayName = fileName,
                mimeType = mimeTypeForPath(fileName)
            )
            val createdUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
            docUriMap[safeRel] = createdUri.toString()
            createdUri
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val stream = runCatching { resolver.openOutputStream(targetUri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(targetUri, "w")
            ?: error("Could not open SAF document for writing: $safeRel")
        stream.use { out ->
            out.write(bytes)
            out.flush()
        }
        return targetUri.toString()
    }

    fun deleteSafFile(
        context: Context,
        docUriMap: MutableMap<String, String>,
        relPath: String
    ): Boolean {
        val safeRel = sanitizeRelativePath(relPath)
        val uriStr = docUriMap[safeRel] ?: return false
        val deleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uriStr))
        }.getOrDefault(false)
        if (deleted) {
            docUriMap.remove(safeRel)
        }
        return deleted
    }

    private fun findOrCreateSafChild(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
        mimeType: String
    ): String {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                if (name == displayName) {
                    return cursor.getString(idIdx)
                }
            }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val createdUri = DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
            ?: error("Permission denied creating '$displayName' in device folder")
        return DocumentsContract.getDocumentId(createdUri)
    }

    private fun mimeTypeForPath(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".xml") -> "application/xml"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript"
            lower.endsWith(".md") -> "text/markdown"
            else -> "text/plain"
        }
    }

    fun queryDocumentFileName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "project.zip"
    }
}
