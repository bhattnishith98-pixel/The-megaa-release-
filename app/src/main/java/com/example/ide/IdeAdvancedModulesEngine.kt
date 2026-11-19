package com.example.ide

data class OutlineSymbol(
    val name: String,
    val kind: String,
    val line: Int
)

data class GitFileChange(
    val path: String,
    val status: String, // "M" modified, "A" added
    val addedLines: Int,
    val removedLines: Int,
    val previewDiff: String
)

data class GitCommitRecord(
    val id: String,
    val message: String,
    val fileCount: Int,
    val timestampMs: Long
)

enum class IdeEditorBackend(val label: String) {
    SORA_AAR("Sora AAR"),
    VSCODROID_AAR("VSCodroid AAR"),
    COMPOSE("Compose")
}

data class IdeEditorConfig(
    val fontSizeSp: Int = 12,
    val wordWrap: Boolean = true,
    val tabSpaces: Int = 4,
    val autoSave: Boolean = true,
    val backend: IdeEditorBackend = IdeEditorBackend.SORA_AAR
) {
    val useSoraAar: Boolean get() = backend == IdeEditorBackend.SORA_AAR
    val useVscodroidAar: Boolean get() = backend == IdeEditorBackend.VSCODROID_AAR
}

object IdeAdvancedModulesEngine {

    /**
     * VS Code Code Outline: extracts classes, functions, Godot callbacks, Composables, and XML tags.
     */
    fun extractCodeOutline(file: IdeFileEntry?): List<OutlineSymbol> {
        if (file == null || file.content.isBlank()) return emptyList()
        val symbols = mutableListOf<OutlineSymbol>()
        val fnKotlin = Regex("""(?:@Composable\s+)?(?:private\s+|public\s+|suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val fnGdPy = Regex("""(?:func|def|function)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val clsRegex = Regex("""(?:class|interface|object|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val signalRegex = Regex("""signal\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val xmlTagRegex = Regex("""<([A-Za-z0-9_.]+)(?:\s|>|$)""")

        file.content.lines().forEachIndexed { idx, line ->
            val ln = idx + 1
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) return@forEachIndexed

            clsRegex.find(trimmed)?.let {
                symbols += OutlineSymbol(it.groupValues[1], "class", ln)
            }
            fnKotlin.find(trimmed)?.let {
                symbols += OutlineSymbol("${it.groupValues[1]}()", "fun", ln)
            }
            fnGdPy.find(trimmed)?.let {
                symbols += OutlineSymbol("${it.groupValues[1]}()", "def", ln)
            }
            signalRegex.find(trimmed)?.let {
                symbols += OutlineSymbol(it.groupValues[1], "signal", ln)
            }
            if (file.path.endsWith(".xml", ignoreCase = true) && !trimmed.startsWith("</") && !trimmed.startsWith("<?")) {
                xmlTagRegex.find(trimmed)?.let {
                    symbols += OutlineSymbol("<${it.groupValues[1]}>", "tag", ln)
                }
            }
        }
        return symbols.take(60)
    }

    /**
     * VS Code Find & Replace in active document.
     */
    fun findAndReplace(
        source: String,
        query: String,
        replacement: String,
        replaceAll: Boolean
    ): Pair<String, Int> {
        if (query.isEmpty()) return source to 0
        var count = 0
        val regex = Regex(Regex.escape(query))
        if (replaceAll) {
            val replaced = regex.replace(source) {
                count++
                Regex.escapeReplacement(replacement)
            }
            return replaced to count
        } else {
            val m = regex.find(source) ?: return source to 0
            val out = source.replaceRange(m.range, replacement)
            return out to 1
        }
    }

    /**
     * VS Code Source Control: computes line diffs against committed baseline snapshots.
     */
    fun computeGitChanges(
        files: List<IdeFileEntry>,
        committedSnapshots: Map<String, String>
    ): List<GitFileChange> {
        val changes = mutableListOf<GitFileChange>()
        files.forEach { file ->
            val base = committedSnapshots[file.path]
            if (base == null) {
                val lines = file.content.lines()
                val diff = lines.take(8).joinToString("\n") { "+ $it" }
                changes += GitFileChange(file.path, "A", lines.size, 0, diff)
            } else if (base != file.content) {
                val oldLines = base.lines()
                val newLines = file.content.lines()
                val oldSet = oldLines.toHashSet()
                val newSet = newLines.toHashSet()
                val removed = oldLines.filter { it !in newSet }
                val added = newLines.filter { it !in oldSet }
                val preview = buildString {
                    removed.take(4).forEach { appendLine("- $it") }
                    added.take(4).forEach { appendLine("+ $it") }
                }.trimEnd()
                changes += GitFileChange(
                    path = file.path,
                    status = "M",
                    addedLines = added.size.coerceAtLeast(1),
                    removedLines = removed.size,
                    previewDiff = preview.ifBlank { "~ content modified" }
                )
            }
        }
        return changes
    }

    /**
     * Android Code Studio SDK & Deprecation Analyzer for Kotlin/Java/Gradle files.
     */
    fun analyzeAndroidApiDeprecations(file: IdeFileEntry): List<IdeDiagnostic> {
        val pathLower = file.path.lowercase()
        if (!pathLower.startsWith("android/") && !pathLower.endsWith(".kt") && !pathLower.endsWith(".java")) {
            return emptyList()
        }
        val rules = listOf(
            "AsyncTask" to "AsyncTask is deprecated in API 30: migrate to Kotlin Coroutines (viewModelScope.launch).",
            "PreferenceManager.getDefaultSharedPreferences" to "PreferenceManager is deprecated in API 29: migrate to Jetpack DataStore.",
            "startActivityForResult" to "startActivityForResult is deprecated: use ActivityResultContracts (registerForActivityResult).",
            "ProgressDialog" to "ProgressDialog is deprecated: use Material 3 CircularProgressIndicator or LinearProgressIndicator.",
            "http://" to "Cleartext HTTP detected: prefer HTTPS to comply with Android Network Security Policy."
        )
        val out = mutableListOf<IdeDiagnostic>()
        file.content.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
            rules.forEach { (needle, msg) ->
                if (line.contains(needle)) {
                    out += IdeDiagnostic(file.path, idx + 1, "WARNING", "[ACS SDK Lint] $msg")
                }
            }
        }
        return out
    }

    /**
     * Android Code Studio Asset Studio: generates a valid Material Vector Drawable XML.
     */
    fun generateVectorDrawableXml(preset: String, tintHex: String = "#38BDF8"): String {
        val pathData = when (preset.lowercase()) {
            "bolt" -> "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66c0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z"
            "shield" -> "M12,1L3,5v6c0,5.55 3.84,10.74 9,12c5.16,-1.26 9,-6.45 9,-12V5L12,1z"
            "heart" -> "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5C2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3C19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
            else -> "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2L9.19,8.63L2,9.24l5.46,4.73L5.82,21z"
        }
        return """
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="24dp"
            |    android:height="24dp"
            |    android:viewportWidth="24"
            |    android:viewportHeight="24">
            |    <path
            |        android:fillColor="$tintHex"
            |        android:pathData="$pathData" />
            |</vector>
        """.trimMargin()
    }

    /**
     * Android Code Studio Visual Layout Designer: injects a Compose or XML widget into source code.
     */
    fun insertAndroidWidget(source: String, isXml: Boolean, widgetKind: String, label: String): String {
        val safeLabel = label.replace("\"", "").ifBlank { "Action" }
        if (isXml) {
            val tag = when (widgetKind.uppercase()) {
                "BUTTON" -> """    <Button android:text="$safeLabel" />"""
                "INPUT" -> """    <EditText android:hint="$safeLabel" />"""
                "SWITCH" -> """    <Switch android:text="$safeLabel" />"""
                else -> """    <TextView android:text="$safeLabel" />"""
            }
            return if (source.contains("</LinearLayout>")) {
                source.replace("</LinearLayout>", "$tag\n</LinearLayout>")
            } else {
                "$source\n$tag"
            }
        } else {
            val snippet = when (widgetKind.uppercase()) {
                "BUTTON" -> """        Button(onClick = { count++ }) { Text("$safeLabel") }"""
                "INPUT" -> """        OutlinedTextField(value = "", onValueChange = {}, label = { Text("$safeLabel") })"""
                "SWITCH" -> """        Switch(checked = true, onCheckedChange = {})"""
                else -> """        Text("$safeLabel")"""
            }
            val lines = source.lines().toMutableList()
            val closeIdx = lines.indexOfLast { it.trim() == "}" }
            return if (closeIdx > 0) {
                lines.add(closeIdx, snippet)
                lines.joinToString("\n")
            } else {
                "$source\n$snippet"
            }
        }
    }
}
