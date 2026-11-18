package com.example.ide

/**
 * Clean-room Kotlin rebuild of core subsystems from `microsoft/vscode` (MIT):
 * - src/vs/platform/commands/common/commands (CommandRegistry)
 * - src/vs/editor/common/model/textModel (Line comment toggle, duplicate line, trim whitespace, smart indent formatter)
 * - src/vs/workbench/services/search (Ripgrep-style workspace text & line-match search)
 * - src/vs/workbench/contrib/lsp (Contextual language completions)
 */
object VscodeCoreKotlin {

    data class VscodeCommandDescriptor(
        val id: String,
        val title: String,
        val keybindingHint: String
    )

    data class SearchLineHit(
        val filePath: String,
        val fileTitle: String,
        val lineNumber: Int,
        val linePreview: String
    )

    object CommandRegistry {
        val builtInCommands: List<VscodeCommandDescriptor> = listOf(
            VscodeCommandDescriptor("editor.action.commentLine", "Toggle Line Comment", "Ctrl+/"),
            VscodeCommandDescriptor("editor.action.duplicateLine", "Copy Line Down", "Shift+Alt+Down"),
            VscodeCommandDescriptor("editor.action.formatDocument", "Format Document", "Shift+Alt+F"),
            VscodeCommandDescriptor("editor.action.trimTrailingWhitespace", "Trim Trailing Whitespace", "Ctrl+K Ctrl+X")
        )

        fun executeTextCommand(commandId: String, content: String, languageHint: String): String {
            return when (commandId) {
                "editor.action.commentLine" -> TextModelOps.toggleComments(content, languageHint)
                "editor.action.duplicateLine" -> TextModelOps.duplicateLastNonEmptyLine(content)
                "editor.action.formatDocument" -> TextModelOps.formatDocument(content, languageHint)
                "editor.action.trimTrailingWhitespace" -> TextModelOps.trimTrailingWhitespace(content)
                else -> content
            }
        }
    }

    object TextModelOps {
        fun commentPrefixFor(languageHint: String): String = when (languageHint.lowercase()) {
            "python", "py", "gdscript", "gd", "studiolang", "stl" -> "# "
            "xml", "html" -> "<!-- "
            else -> "// "
        }

        fun toggleComments(text: String, languageHint: String): String {
            val lines = text.lines()
            if (lines.isEmpty()) return text
            val prefix = commentPrefixFor(languageHint)
            val isXml = prefix.startsWith("<!--")
            val nonEmpty = lines.filter { it.isNotBlank() }
            if (nonEmpty.isEmpty()) return text

            val allCommented = nonEmpty.all { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith(prefix.trim()) || (isXml && trimmed.startsWith("<!--"))
            }

            return lines.joinToString("\n") { line ->
                if (line.isBlank()) {
                    line
                } else if (allCommented) {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val rest = line.drop(indent.length)
                    if (isXml && rest.startsWith("<!--") && rest.endsWith("-->")) {
                        indent + rest.removePrefix("<!--").removeSuffix("-->").trim()
                    } else {
                        indent + rest.removePrefix(prefix).removePrefix(prefix.trim())
                    }
                } else {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val rest = line.drop(indent.length)
                    if (isXml) "$indent<!-- $rest -->" else "$indent$prefix$rest"
                }
            }
        }

        fun duplicateLastNonEmptyLine(text: String): String {
            val lines = text.lines().toMutableList()
            val idx = lines.indexOfLast { it.isNotBlank() }
            if (idx >= 0) {
                lines.add(idx + 1, lines[idx])
            }
            return lines.joinToString("\n")
        }

        fun trimTrailingWhitespace(text: String): String =
            text.lines().joinToString("\n") { it.trimEnd() }

        fun formatDocument(text: String, languageHint: String): String {
            val cleaned = trimTrailingWhitespace(text)
            val lang = languageHint.lowercase()
            if (lang == "gd" || lang == "gdscript" || lang == "py" || lang == "python" || lang == "stl" || lang == "studiolang") {
                // Normalize tabs to 4 spaces matching VS Code insertSpaces=true
                return cleaned.replace("\t", "    ")
            }
            // Brace-based auto-indentation for Kotlin, Java, JS, C++, JSON
            var indent = 0
            val out = StringBuilder()
            cleaned.lines().forEachIndexed { idx, rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("}") || trimmed.startsWith("]")) {
                    indent = (indent - 1).coerceAtLeast(0)
                }
                if (trimmed.isNotEmpty()) {
                    out.append("    ".repeat(indent)).append(trimmed)
                }
                if (idx < cleaned.lines().lastIndex) out.append('\n')
                val opens = trimmed.count { it == '{' || it == '[' }
                val closes = trimmed.count { it == '}' || it == ']' }
                val adjust = opens - closes + (if (trimmed.startsWith("}") || trimmed.startsWith("]")) 1 else 0)
                indent = (indent + adjust).coerceAtLeast(0)
            }
            return out.toString()
        }
    }

    object RipgrepSearch {
        fun searchWorkspace(
            files: List<IdeFileEntry>,
            query: String,
            caseSensitive: Boolean = false,
            maxHits: Int = 60
        ): List<SearchLineHit> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val hits = mutableListOf<SearchLineHit>()
            for (file in files) {
                val lines = file.content.lines()
                for (i in lines.indices) {
                    if (lines[i].contains(q, ignoreCase = !caseSensitive)) {
                        hits += SearchLineHit(
                            filePath = file.path,
                            fileTitle = file.title,
                            lineNumber = i + 1,
                            linePreview = lines[i].trim().take(90)
                        )
                        if (hits.size >= maxHits) return hits
                    }
                }
            }
            return hits
        }
    }

    object LspCompletionEngine {
        fun suggestCompletions(languageHint: String, prefix: String): List<String> {
            val p = prefix.trim().lowercase()
            val pool = when (languageHint.lowercase()) {
                "gd", "gdscript" -> listOf("func _ready()", "func _process(delta)", "move_and_slide()", "Vector2", "godot.spawn", "voice_speak")
                "py", "python" -> listOf("def _process(delta):", "move(dx, dy)", "set_prop(node, key, val)", "voice_speak(msg)")
                "stl", "studiolang" -> listOf("def run_automation()", "godot.set_prop", "acs.validate()", "voice.speak", "log.info")
                "kt", "kotlin" -> listOf("@Composable", "OutlinedTextField", "Button(onClick = {})", "Column", "Row", "Modifier.fillMaxWidth()")
                "xml" -> listOf("<LinearLayout", "<TextView", "<Button", "<EditText", "<Switch", "<CheckBox", "android:text=", "android:padding=")
                else -> listOf("function", "class", "return", "if", "for")
            }
            return if (p.isEmpty()) pool else pool.filter { it.lowercase().contains(p) }
        }
    }
}
