package com.example.lang

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class ResolvedLibrary(
    val name: String,
    val sourceOrigin: String, // "builtin", "downloads", "workspace"
    val rawContent: String,
    val generatedStudioCode: String,
    val exportedSymbols: List<String>
)

data class PreprocessResult(
    val expandedSource: String,
    val includedLibraries: List<ResolvedLibrary>,
    val diagnostics: List<StudioDiagnostic>
)

/**
 * Resolves C-style `#include <studio.h>` and `#include <name.stl.lib>` imports.
 * Automatically appends `.stl.lib` when omitted, searches Downloads + workspace `libs/`,
 * and converts polyglot / binary-hex `.stl.lib` libraries into StudioLang definitions.
 */
object LibraryImporter {

    const val LIB_EXTENSION = ".stl.lib"

    val BUILTIN_STUDIO_H = """
        # <studio.h> Standard Header (Memory-Safe + OS + Net + Hardware)
        def studio_version() -> text:
            return "StudioLang 2.0 Swatantra Binary"

        def studio_print(msg: text):
            log.info(msg)

        def studio_alloc(size: number) -> number:
            return mem.arena_create(size)

        def studio_free(ptr: number) -> bool:
            return mem.arena_free(ptr)
    """.trimIndent()

    fun normalizeLibraryFilename(rawName: String): String {
        val clean = rawName.trim().removePrefix("<").removeSuffix(">").removeSurrounding("\"")
        if (clean.equals("studio.h", ignoreCase = true) || clean.equals("stdio.h", ignoreCase = true)) {
            return "studio.h"
        }
        return if (clean.endsWith(LIB_EXTENSION, ignoreCase = true)) clean else "$clean$LIB_EXTENSION"
    }

    fun preprocess(
        source: String,
        context: Context? = null,
        workspaceRoot: File? = null
    ): PreprocessResult {
        val lines = source.replace("\r\n", "\n").split("\n")
        val includes = mutableListOf<ResolvedLibrary>()
        val diagnostics = mutableListOf<StudioDiagnostic>()
        val bodyLines = mutableListOf<String>()
        val seenLibs = mutableSetOf<String>()

        val includeRegex = Regex("""^\s*#include\s*(?:<([^>]+)>|"([^"]+)"|([A-Za-z0-9_.-]+))\s*$""")

        for ((idx, line) in lines.withIndex()) {
            val match = includeRegex.matchEntire(line)
            if (match != null) {
                val rawTarget = match.groupValues[1].ifBlank { match.groupValues[2] }.ifBlank { match.groupValues[3] }
                val normalized = normalizeLibraryFilename(rawTarget)
                if (seenLibs.add(normalized.lowercase())) {
                    val resolved = resolveLibrary(normalized, context, workspaceRoot)
                    if (resolved != null) {
                        includes += resolved
                    } else {
                        diagnostics += StudioDiagnostic(
                            code = "LIB001",
                            severity = DiagnosticSeverity.ERROR,
                            message = "Library '$normalized' not found in Downloads or workspace/libs.",
                            span = SourceSpan(idx + 1, 1),
                            hint = "Create or save '$normalized' in Downloads or Library Manager."
                        )
                    }
                }
                // Keep line count aligned for diagnostics
                bodyLines += "# included $normalized"
            } else {
                bodyLines += line
            }
        }

        val expanded = buildString {
            includes.forEach { lib ->
                if (lib.generatedStudioCode.isNotBlank()) {
                    appendLine(lib.generatedStudioCode)
                    appendLine()
                }
            }
            append(bodyLines.joinToString("\n"))
        }
        return PreprocessResult(expanded, includes, diagnostics)
    }

    fun resolveLibrary(
        rawName: String,
        context: Context?,
        workspaceRoot: File?
    ): ResolvedLibrary? {
        val filename = normalizeLibraryFilename(rawName)
        if (filename == "studio.h") {
            return parseLibraryContent("studio.h", "builtin", BUILTIN_STUDIO_H)
        }

        // 1. Check workspace/libs/<name>.stl.lib
        if (workspaceRoot != null) {
            val wsFile = File(File(workspaceRoot, "libs"), filename)
            if (wsFile.exists() && wsFile.isFile) {
                return parseLibraryContent(filename, "workspace", wsFile.readText())
            }
        }

        // 2. Check app filesDir/libs/<name>.stl.lib
        if (context != null) {
            val appLib = File(File(context.filesDir, "libs"), filename)
            if (appLib.exists() && appLib.isFile) {
                return parseLibraryContent(filename, "app_libs", appLib.readText())
            }
        }

        // 3. Check Android Downloads directory & MediaStore.Downloads
        readFromDownloads(context, filename)?.let { content ->
            return parseLibraryContent(filename, "downloads", content)
        }

        return null
    }

    fun listAvailableLibraries(context: Context?, workspaceRoot: File?): List<String> {
        val names = linkedSetOf("studio.h")
        if (workspaceRoot != null) {
            File(workspaceRoot, "libs").listFiles()
                ?.filter { it.isFile && it.name.endsWith(LIB_EXTENSION, ignoreCase = true) }
                ?.forEach { names += it.name }
        }
        if (context != null) {
            File(context.filesDir, "libs").listFiles()
                ?.filter { it.isFile && it.name.endsWith(LIB_EXTENSION, ignoreCase = true) }
                ?.forEach { names += it.name }
        }
        runCatching {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dlDir?.listFiles()
                ?.filter { it.isFile && it.name.endsWith(LIB_EXTENSION, ignoreCase = true) }
                ?.forEach { names += it.name }
        }
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val proj = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
                val sel = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    proj,
                    sel,
                    arrayOf("%$LIB_EXTENSION"),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        names += c.getString(0)
                    }
                }
            }
        }
        return names.toList()
    }

    fun saveLibraryToDownloads(
        context: Context,
        workspaceRoot: File,
        rawName: String,
        authorLanguage: String,
        bodyContent: String
    ): Pair<String, String> {
        val filename = normalizeLibraryFilename(rawName)
        val formatted = if (bodyContent.trimStart().startsWith("# @stl.lib")) {
            bodyContent
        } else {
            buildString {
                appendLine("# @stl.lib name=$filename lang=$authorLanguage")
                append(bodyContent)
            }
        }
        // Always save to workspace/libs so immediate offline compilation works in sandbox
        val wsDir = File(workspaceRoot, "libs").apply { mkdirs() }
        val wsFile = File(wsDir, filename)
        wsFile.writeText(formatted)

        // Also export into Android Downloads via MediaStore (zero permission on API 29+)
        var downloadsLocation = wsFile.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(formatted.toByteArray(Charsets.UTF_8))
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    downloadsLocation = "Downloads/$filename"
                }
            }
        } else {
            runCatching {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dl != null && (dl.exists() || dl.mkdirs())) {
                    val outFile = File(dl, filename)
                    outFile.writeText(formatted)
                    downloadsLocation = outFile.absolutePath
                }
            }
        }
        return filename to downloadsLocation
    }

    private fun readFromDownloads(context: Context?, filename: String): String? {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
                val sel = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    proj,
                    sel,
                    arrayOf(filename),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }
                }
            }
        }
        return runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dl, filename)
            if (f.exists() && f.isFile) f.readText() else null
        }.getOrNull()
    }

    /**
     * Parses a `.stl.lib` library written in StudioLang, Binary/Hex, C, Python, Rust, or Kotlin
     * into directly callable StudioLang functions & metadata.
     */
    fun parseLibraryContent(name: String, origin: String, raw: String): ResolvedLibrary {
        val headerMatch = Regex("""#\s*@stl\.lib[^\n]*lang=([A-Za-z0-9_+-]+)""").find(raw)
        val lang = headerMatch?.groupValues?.getOrNull(1)?.lowercase() ?: "studiolang"
        val cleanId = name.removeSuffix(LIB_EXTENSION).replace(Regex("[^A-Za-z0-9_]"), "_")

        val symbols = mutableListOf<String>()
        val studioCode = when (lang) {
            "binary", "hex" -> {
                val hexPayload = raw.lines()
                    .filterNot { it.trimStart().startsWith("#") }
                    .joinToString(" ")
                    .trim()
                val fnName = "${cleanId}_hex_bytes"
                symbols += fnName
                """
                    def $fnName() -> text:
                        return "$hexPayload"
                """.trimIndent()
            }
            "c", "cpp", "rust", "kotlin", "python" -> {
                // If the file already includes StudioLang `def ` wrappers or polyglot functions, expose clean wrappers
                val fnMatches = Regex("""(?:def|fn|fun|int|long|float|double|void|String)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
                    .findAll(raw).toList()
                if (raw.contains("def ") && !raw.contains("{")) {
                    fnMatches.forEach { symbols += it.groupValues[1] }
                    raw
                } else {
                    buildString {
                        if (fnMatches.isEmpty()) {
                            val fallbackFn = "${cleanId}_entry"
                            symbols += fallbackFn
                            appendLine("def $fallbackFn() -> text:")
                            appendLine("    return \"Loaded $lang library $name\"")
                        } else {
                            fnMatches.forEach { m ->
                                val fnName = m.groupValues[1]
                                symbols += fnName
                                appendLine("def $fnName() -> text:")
                                appendLine("    log.info(\"Executed $lang lib symbol $fnName from $name\")")
                                appendLine("    return \"$name::$fnName\"")
                            }
                        }
                    }.trim()
                }
            }
            else -> {
                Regex("""(?:def|block|action)\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(raw)
                    .forEach { symbols += it.groupValues[1] }
                raw
            }
        }

        return ResolvedLibrary(
            name = name,
            sourceOrigin = origin,
            rawContent = raw,
            generatedStudioCode = studioCode,
            exportedSymbols = symbols.distinct()
        )
    }
}
