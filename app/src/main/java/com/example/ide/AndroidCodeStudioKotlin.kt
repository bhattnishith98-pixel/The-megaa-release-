package com.example.ide

/**
 * Clean-room Kotlin rebuild of core subsystems from `AndroidCSOfficial/android-code-studio`:
 * - build-tools/aapt2 (`AcsResourceTable` R.string / R.color / R.layout / R.drawable indexing & resolution)
 * - xml-inflater (`AcsXmlInflaterEngine` Android XML layout parser & attribute resolver)
 * - java-completion (`AcsKotlinJavaCompletion` missing Android/Compose import detector & quick-fix)
 */
object AndroidCodeStudioKotlin {

    data class ResourceTable(
        val strings: Map<String, String>,
        val colors: Map<String, String>,
        val layouts: List<String>,
        val drawables: List<String>
    ) {
        fun resolveValue(raw: String): String {
            return when {
                raw.startsWith("@string/") -> strings[raw.removePrefix("@string/")] ?: raw
                raw.startsWith("@color/") -> colors[raw.removePrefix("@color/")] ?: raw
                else -> raw
            }
        }

        val totalSymbols: Int get() = strings.size + colors.size + layouts.size + drawables.size
    }

    data class InflatedXmlWidget(
        val id: String,
        val tagName: String,
        val kind: String,
        val label: String,
        val orientation: String = "vertical"
    )

    object ResourceIndexer {
        private val stringEntryRegex = Regex("""<string\s+name="([^"]+)">([^<]*)</string>""")
        private val colorEntryRegex = Regex("""<color\s+name="([^"]+)">([^<]*)</color>""")

        fun indexWorkspace(files: List<IdeFileEntry>): ResourceTable {
            val strings = linkedMapOf<String, String>()
            val colors = linkedMapOf<String, String>()
            val layouts = mutableListOf<String>()
            val drawables = mutableListOf<String>()

            files.forEach { file ->
                val p = file.path.lowercase()
                when {
                    p.endsWith("strings.xml") -> {
                        stringEntryRegex.findAll(file.content).forEach { m ->
                            strings[m.groupValues[1]] = m.groupValues[2]
                        }
                    }
                    p.endsWith("colors.xml") -> {
                        colorEntryRegex.findAll(file.content).forEach { m ->
                            colors[m.groupValues[1]] = m.groupValues[2]
                        }
                    }
                    p.contains("/res/layout/") && p.endsWith(".xml") -> {
                        layouts += file.title.removeSuffix(".xml")
                    }
                    p.contains("/res/drawable/") && p.endsWith(".xml") -> {
                        drawables += file.title.removeSuffix(".xml")
                    }
                }
            }
            return ResourceTable(strings, colors, layouts, drawables)
        }
    }

    object XmlInflaterEngine {
        private val tagRegex = Regex("""<([A-Za-z0-9_.-]+)([^>]*)>""")
        private val textAttrRegex = Regex("""android:text\s*=\s*"([^"]+)"""")
        private val hintAttrRegex = Regex("""android:hint\s*=\s*"([^"]+)"""")
        private val idAttrRegex = Regex("""android:id\s*=\s*"@(?:\+)?id/([^"]+)"""")
        private val orientAttrRegex = Regex("""android:orientation\s*=\s*"([^"]+)"""")

        fun inflateXmlLayout(xmlContent: String, resources: ResourceTable): List<InflatedXmlWidget> {
            val out = mutableListOf<InflatedXmlWidget>()
            var idx = 0
            tagRegex.findAll(xmlContent).forEach { match ->
                val rawTag = match.groupValues[1]
                if (rawTag.startsWith("?") || rawTag.startsWith("!")) return@forEach
                val shortTag = rawTag.substringAfterLast('.')
                val attrs = match.groupValues[2]
                val rawText = textAttrRegex.find(attrs)?.groupValues?.getOrNull(1)
                val rawHint = hintAttrRegex.find(attrs)?.groupValues?.getOrNull(1)
                val rawId = idAttrRegex.find(attrs)?.groupValues?.getOrNull(1)
                val orientation = orientAttrRegex.find(attrs)?.groupValues?.getOrNull(1) ?: "vertical"

                val resolvedLabel = resources.resolveValue(rawText ?: rawHint ?: shortTag)
                val id = rawId ?: "${shortTag.lowercase()}_$idx"
                val kind = when (shortTag) {
                    "Button", "MaterialButton", "AppCompatButton" -> "BUTTON"
                    "TextView", "MaterialTextView" -> "TEXT"
                    "EditText", "TextInputEditText", "AppCompatEditText" -> "INPUT"
                    "Switch", "SwitchCompat", "SwitchMaterial", "CheckBox" -> "SWITCH"
                    "ProgressBar", "SeekBar" -> "PROGRESS"
                    else -> null
                }
                if (kind != null) {
                    out += InflatedXmlWidget(
                        id = id,
                        tagName = shortTag,
                        kind = kind,
                        label = resolvedLabel,
                        orientation = orientation
                    )
                    idx++
                }
            }
            return out
        }
    }

    object KotlinJavaCompletion {
        private val importRules: List<Pair<String, String>> = listOf(
            "@Composable" to "import androidx.compose.runtime.Composable",
            "Column(" to "import androidx.compose.foundation.layout.Column",
            "Row(" to "import androidx.compose.foundation.layout.Row",
            "Button(" to "import androidx.compose.material3.Button",
            "Text(" to "import androidx.compose.material3.Text",
            "OutlinedTextField(" to "import androidx.compose.material3.OutlinedTextField",
            "Switch(" to "import androidx.compose.material3.Switch",
            "Modifier" to "import androidx.compose.ui.Modifier",
            "Bundle" to "import android.os.Bundle"
        )

        fun detectMissingImports(kotlinOrJavaCode: String): List<String> {
            return importRules.mapNotNull { (symbol, importStmt) ->
                if (kotlinOrJavaCode.contains(symbol) && !kotlinOrJavaCode.contains(importStmt)) {
                    importStmt
                } else null
            }
        }

        fun addMissingImports(kotlinOrJavaCode: String): Pair<String, Int> {
            val missing = detectMissingImports(kotlinOrJavaCode)
            if (missing.isEmpty()) return kotlinOrJavaCode to 0
            val lines = kotlinOrJavaCode.lines().toMutableList()
            val pkgIdx = lines.indexOfFirst { it.trimStart().startsWith("package ") }
            val insertAt = if (pkgIdx >= 0) pkgIdx + 1 else 0
            val block = missing.joinToString("\n")
            lines.add(insertAt, block)
            return lines.joinToString("\n") to missing.size
        }
    }
}
