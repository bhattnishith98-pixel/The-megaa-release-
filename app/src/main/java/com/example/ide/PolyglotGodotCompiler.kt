package com.example.ide

import com.example.appbuilder.AppBuildLanguage
import com.example.appbuilder.AppDesign
import com.example.lang.DiagnosticSeverity
import com.example.vm.StudioCompiler
import org.json.JSONObject

data class ComposePreviewElement(
    val id: String,
    val kind: String, // "TEXT", "BUTTON", "SWITCH", "INPUT", "CARD"
    val label: String,
    val detail: String = ""
)

/**
 * Bundled zero-download Polyglot compiler & analyzer for Godot Node scripts and
 * Android Code Studio files. Translates GDScript, Python, Kotlin, Java, JavaScript,
 * C/C++, and StudioLang cleanly onto StudioLang/StudioVm (Play Policy System Integrity safe:
 * zero dynamic .dex/.jar/.so loading).
 */
object PolyglotGodotCompiler {

    fun starterScript(
        language: ScriptLanguage,
        nodeType: GodotNodeType,
        templateKind: ScriptTemplateKind,
        nodeName: String
    ): String {
        val safeName = nodeName.ifBlank { nodeType.label }
        return when (language) {
            ScriptLanguage.GDSCRIPT -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |# Godot GDScript attached to $safeName (${nodeType.label})
                    |var speed = 4.5
                    |
                    |func _ready():
                    |    print("GDScript ready on $safeName")
                    |
                    |func _process(delta):
                    |    move(speed, 0)
                """.trimMargin()
                ScriptTemplateKind.AI_PATROL -> """
                    |# Enemy AI Patrol in GDScript for $safeName
                    |var step_x = 3.0
                    |
                    |func _process(delta):
                    |    move(step_x, 0.5)
                """.trimMargin()
                ScriptTemplateKind.TOUCH_INPUT -> """
                    |# Touch reactive GDScript for $safeName
                    |func _ready():
                    |    set_prop("tint", "#22D3EE")
                    |    print("Touch zone armed on $safeName")
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |# Voice + Godot GDScript on $safeName
                    |func _ready():
                    |    voice_speak("$safeName script active")
                    |    move(6, -2)
                """.trimMargin()
                ScriptTemplateKind.EMPTY -> """
                    |func _ready():
                    |    print("$safeName initialized")
                """.trimMargin()
            }

            ScriptLanguage.PYTHON -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |# Universal Python Script attached to $safeName (${nodeType.label})
                    |speed = 4.0
                    |
                    |def _ready():
                    |    print("Python controller attached to $safeName")
                    |
                    |def _process(delta):
                    |    move(speed, 0.25)
                """.trimMargin()
                ScriptTemplateKind.AI_PATROL -> """
                    |# Python Patrol AI for $safeName
                    |def _process(delta):
                    |    move(2.5, 0)
                """.trimMargin()
                ScriptTemplateKind.TOUCH_INPUT -> """
                    |# Python Touch Responder on $safeName
                    |def _ready():
                    |    set_prop("label", "Tapped $safeName")
                    |    print("Touch handler bound in Python")
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |# Python + Voice Assistant on $safeName
                    |def _ready():
                    |    voice_speak("Python engine running $safeName")
                    |    set_prop("tint", "#34D399")
                """.trimMargin()
                ScriptTemplateKind.EMPTY -> """
                    |def _ready():
                    |    print("Python ready on $safeName")
                """.trimMargin()
            }

            ScriptLanguage.STUDIOLANG -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |# Automation StudioLang Native Godot Script for $safeName
                    |def _ready():
                    |    log.info("StudioLang active on $safeName")
                    |
                    |def _process(delta):
                    |    godot.move("$safeName", 4.0, 0.0)
                """.trimMargin()
                ScriptTemplateKind.AI_PATROL -> """
                    |# StudioLang AI Patrol on $safeName
                    |def _process(delta):
                    |    godot.move("$safeName", 3.0, 1.0)
                """.trimMargin()
                ScriptTemplateKind.TOUCH_INPUT -> """
                    |# StudioLang Touch & Automation Bridge on $safeName
                    |def _ready():
                    |    godot.set_prop("$safeName", "tint", "#A855F7")
                    |    log.info("StudioLang TouchZone ready")
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |# StudioLang + Voice Trigger on $safeName
                    |def _ready():
                    |    voice.speak("StudioLang node $safeName activated")
                    |    godot.move("$safeName", 8.0, 0.0)
                """.trimMargin()
                ScriptTemplateKind.EMPTY -> """
                    |def _ready():
                    |    log.info("$safeName StudioLang ready")
                """.trimMargin()
            }

            ScriptLanguage.KOTLIN -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |// Kotlin Godot Script attached to $safeName (${nodeType.label})
                    |val speed = 4.5
                    |
                    |fun _ready() {
                    |    println("Kotlin controller attached to $safeName")
                    |}
                    |
                    |fun _process(delta: Float) {
                    |    move(speed, 0.0)
                    |}
                """.trimMargin()
                ScriptTemplateKind.AI_PATROL -> """
                    |// Kotlin AI Patrol for $safeName
                    |fun _process(delta: Float) {
                    |    move(2.5, 0.0)
                    |}
                """.trimMargin()
                ScriptTemplateKind.TOUCH_INPUT -> """
                    |// Kotlin Touch Trigger for $safeName
                    |fun _ready() {
                    |    set_prop("tint", "#FBBF24")
                    |    println("Kotlin touch ready on $safeName")
                    |}
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |// Kotlin + Voice Command Script for $safeName
                    |fun _ready() {
                    |    voice_speak("Kotlin script running on $safeName")
                    |    move(5.0, 0.0)
                    |}
                """.trimMargin()
                ScriptTemplateKind.EMPTY -> """
                    |fun _ready() {
                    |    println("Kotlin script ready on $safeName")
                    |}
                """.trimMargin()
            }

            ScriptLanguage.JAVA -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |// Java Godot Script attached to $safeName
                    |double speed = 3.5;
                    |
                    |void _ready() {
                    |    System.out.println("Java ready on $safeName");
                    |}
                    |
                    |void _process(double delta) {
                    |    move(speed, 0.0);
                    |}
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |void _ready() {
                    |    voice_speak("Java node $safeName online");
                    |    set_prop("tint", "#38BDF8");
                    |}
                """.trimMargin()
                else -> """
                    |void _ready() {
                    |    System.out.println("Java handler on $safeName");
                    |    move(2.0, 0.0);
                    |}
                """.trimMargin()
            }

            ScriptLanguage.JAVASCRIPT -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |// JavaScript Godot Script for $safeName
                    |const speed = 4.0;
                    |
                    |function _ready() {
                    |  console.log("JS script ready on $safeName");
                    |}
                    |
                    |function _process(delta) {
                    |  move(speed, 0);
                    |}
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |function _ready() {
                    |  voice_speak("JavaScript node $safeName ready");
                    |  move(5, 0);
                    |}
                """.trimMargin()
                else -> """
                    |function _ready() {
                    |  console.log("JS attached to $safeName");
                    |  set_prop("tint", "#22D3EE");
                    |}
                """.trimMargin()
            }

            ScriptLanguage.CPP -> when (templateKind) {
                ScriptTemplateKind.MOVEMENT -> """
                    |// C++ Godot Native Script for $safeName
                    |double speed = 5.0;
                    |
                    |void _ready() {
                    |    print("C++ script ready on $safeName");
                    |}
                    |
                    |void _process(double delta) {
                    |    move(speed, 0.0);
                    |}
                """.trimMargin()
                ScriptTemplateKind.VOICE_REACTIVE -> """
                    |void _ready() {
                    |    voice_speak("C++ native script on $safeName");
                    |    move(6.0, 0.0);
                    |}
                """.trimMargin()
                else -> """
                    |void _ready() {
                    |    print("C++ node $safeName active");
                    |}
                """.trimMargin()
            }
        }
    }

    /**
     * Transpiles any supported language (GDScript, Python, StudioLang, Kotlin, Java, JS, C++)
     * into executable StudioLang bytecode-ready source code targeting [targetNodeName].
     */
    fun transpileToStudioLang(
        language: ScriptLanguage,
        source: String,
        targetNodeName: String
    ): String {
        val cleanedNode = targetNodeName.replace("\"", "\\\"").ifBlank { "Player" }
        if (language == ScriptLanguage.STUDIOLANG) {
            return expandShorthandHelpers(source, cleanedNode)
        }

        val declaredVars = mutableSetOf<String>()
        val out = StringBuilder()
        var braceIndent = 0
        val useBraceIndentation = language in setOf(
            ScriptLanguage.KOTLIN,
            ScriptLanguage.JAVA,
            ScriptLanguage.JAVASCRIPT,
            ScriptLanguage.CPP
        )

        val rawLines = source.replace("\r\n", "\n").split("\n")
        for (rawLine in rawLines) {
            var line = rawLine.trimEnd()
            if (line.isBlank()) {
                out.append('\n')
                continue
            }

            // Strip C-style line comments / preprocessor headers
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) {
                out.append("    ".repeat(braceIndent))
                    .append("# ")
                    .append(trimmed.removePrefix("//").trim())
                    .append('\n')
                continue
            }
            if (trimmed.startsWith("#include") || trimmed.startsWith("package ") ||
                trimmed.startsWith("import ") || trimmed.startsWith("using namespace") ||
                trimmed.startsWith("extends ") || trimmed.startsWith("class_name ") ||
                trimmed.startsWith("@")
            ) {
                out.append("# ").append(trimmed).append('\n')
                continue
            }
            if (trimmed.startsWith("#")) {
                out.append(line).append('\n')
                continue
            }

            if (useBraceIndentation) {
                var work = trimmed
                while (work.startsWith("}")) {
                    braceIndent = (braceIndent - 1).coerceAtLeast(0)
                    work = work.removePrefix("}").trim()
                }
                if (work.isEmpty()) continue
                val opensBlock = work.endsWith("{")
                if (opensBlock) {
                    work = work.removeSuffix("{").trim()
                }
                if (work.endsWith(";")) {
                    work = work.removeSuffix(";").trim()
                }
                val converted = convertSingleStatement(
                    stmt = work,
                    nodeName = cleanedNode,
                    declaredVars = declaredVars,
                    expectColon = opensBlock
                )
                out.append("    ".repeat(braceIndent)).append(converted).append('\n')
                if (opensBlock) braceIndent++
            } else {
                // Indentation-based (GDScript / Python)
                val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }
                    .replace("\t", "    ").length
                val level = leadingSpaces / 4
                var work = trimmed.removeSuffix(";").trim()
                if (work == "pass") {
                    out.append("    ".repeat(level)).append("let _pass_ = 0\n")
                    continue
                }
                val expectColon = work.endsWith(":")
                val bodyNoColon = if (expectColon) work.removeSuffix(":").trim() else work
                val converted = convertSingleStatement(
                    stmt = bodyNoColon,
                    nodeName = cleanedNode,
                    declaredVars = declaredVars,
                    expectColon = expectColon
                )
                out.append("    ".repeat(level)).append(converted).append('\n')
            }
        }
        return out.toString()
    }

    private fun convertSingleStatement(
        stmt: String,
        nodeName: String,
        declaredVars: MutableSet<String>,
        expectColon: Boolean
    ): String {
        var s = stmt.trim()
        if (s.isEmpty()) return ""

        // Function definitions across GDScript / Kotlin / JS / Java / C++ / Python
        val funcMatch = Regex(
            """^(?:public\s+|private\s+|protected\s+|static\s+|export\s+|override\s+|suspend\s+)*(?:func|def|fun|function|void|int|double|float|bool|auto|String|string)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)(?:\s*(?:->|:)\s*[A-Za-z0-9_<>,\s?]+)?$"""
        ).find(s)
        if (funcMatch != null) {
            val fnName = funcMatch.groupValues[1]
            val rawParams = funcMatch.groupValues[2].trim()
            val cleanedParams = if (rawParams.isEmpty()) "" else {
                rawParams.split(",").mapNotNull { p ->
                    val seg = p.trim()
                    if (seg.isEmpty()) null
                    else {
                        val beforeColon = seg.substringBefore(":").substringBefore("=").trim()
                        val ident = beforeColon.split(Regex("\\s+")).lastOrNull()?.trim().orEmpty()
                        if (ident.isNotEmpty()) declaredVars += ident
                        ident.ifEmpty { null }
                    }
                }.joinToString(", ")
            }
            return "def $fnName($cleanedParams):"
        }

        // Control structures: if / else if / elif / else / while
        if (s.startsWith("else if")) {
            val cond = s.removePrefix("else if").trim().removeSurrounding("(", ")")
            return "elif ${convertExpr(cond, nodeName)}:"
        }
        if (s.startsWith("elif ")) {
            val cond = s.removePrefix("elif").trim()
            return "elif ${convertExpr(cond, nodeName)}:"
        }
        if (s == "else") return "else:"
        if (s.startsWith("if ") || s.startsWith("if(")) {
            val cond = s.removePrefix("if").trim().removeSurrounding("(", ")")
            return "if ${convertExpr(cond, nodeName)}:"
        }
        if (s.startsWith("while ") || s.startsWith("while(")) {
            val cond = s.removePrefix("while").trim().removeSurrounding("(", ")")
            return "while ${convertExpr(cond, nodeName)}:"
        }

        // Typed/untyped variable declarations
        val varDeclRegex = Regex(
            """^(?:let|var|val|const|int|double|float|bool|boolean|auto|String|string)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[A-Za-z0-9_<>,?]+)?\s*=\s*(.+)$"""
        )
        val declMatch = varDeclRegex.find(s)
        if (declMatch != null) {
            val vName = declMatch.groupValues[1]
            val vExpr = declMatch.groupValues[2]
            declaredVars += vName
            return "let $vName = ${convertExpr(vExpr, nodeName)}"
        }

        // Plain Python assignment `x = expr`
        val plainAssignMatch = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^=].*)$""").find(s)
        if (plainAssignMatch != null && !s.contains("==")) {
            val vName = plainAssignMatch.groupValues[1]
            val vExpr = plainAssignMatch.groupValues[2]
            return if (vName !in declaredVars) {
                declaredVars += vName
                "let $vName = ${convertExpr(vExpr, nodeName)}"
            } else {
                "$vName = ${convertExpr(vExpr, nodeName)}"
            }
        }

        val exprConverted = convertExpr(s, nodeName)
        return if (expectColon && !exprConverted.endsWith(":")) "$exprConverted:" else exprConverted
    }

    private fun convertExpr(expr: String, nodeName: String): String {
        var r = expandShorthandHelpers(expr, nodeName)
        r = replaceOutsideQuotes(r, "&&", " and ")
        r = replaceOutsideQuotes(r, "||", " or ")
        r = r.replace(Regex("""(?<![A-Za-z_])(\d+(?:\.\d+)?)[fF]\b"""), "$1")
        r = r.replace(Regex("""\bTrue\b"""), "true")
            .replace(Regex("""\bFalse\b"""), "false")
            .replace(Regex("""\bNone\b"""), "none")
            .replace(Regex("""\bnull\b"""), "none")
            .replace(Regex("""!(?!=)"""), "not ")
        return r
    }

    private fun expandShorthandHelpers(source: String, nodeName: String): String {
        var r = source
        r = r.replace("System.out.println(", "log.info(")
            .replace("console.log(", "log.info(")
            .replace(Regex("""\bprintln\s*\("""), "log.info(")
            .replace(Regex("""\bprint\s*\("""), "log.info(")
            .replace(Regex("""(?<!godot\.)\bmove_and_slide\s*\("""), "godot.move(\"$nodeName\", ")
            .replace(Regex("""(?<!godot\.)\bmove\s*\("""), "godot.move(\"$nodeName\", ")
            .replace(Regex("""(?<!godot\.)\bset_prop\s*\("""), "godot.set_prop(\"$nodeName\", ")
            .replace(Regex("""(?<!godot\.)\bget_prop\s*\("""), "godot.get_prop(\"$nodeName\", ")
            .replace(Regex("""(?<!godot\.)\bspawn\s*\("""), "godot.spawn(")
            .replace(Regex("""(?<!godot\.)\bplay_scene\s*\("""), "godot.play(")
            .replace(Regex("""(?<!godot\.)\bpause_scene\s*\("""), "godot.pause(")
            .replace(Regex("""(?<!godot\.)\bstop_scene\s*\("""), "godot.stop(")
            .replace(Regex("""(?<!voice\.)\bvoice_speak\s*\("""), "voice.speak(")
        return r
    }

    private fun replaceOutsideQuotes(input: String, target: String, replacement: String): String {
        val sb = StringBuilder()
        var inQuote = false
        var quoteChar = '"'
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (inQuote) {
                sb.append(c)
                if (c == quoteChar && (i == 0 || input[i - 1] != '\\')) inQuote = false
                i++
            } else if (c == '"' || c == '\'') {
                inQuote = true
                quoteChar = c
                sb.append(c)
                i++
            } else if (input.startsWith(target, i)) {
                sb.append(replacement)
                i += target.length
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Compiles a Godot attached script in any of the 7 languages via transpilation + [StudioCompiler],
     * automatically appending invocation of `_ready()` and optionally `_process(delta)` if defined.
     */
    fun compileForExecution(
        language: ScriptLanguage,
        source: String,
        targetNodeName: String,
        invokeProcessTick: Boolean,
        deltaSec: Float = 0.016f,
        compiler: StudioCompiler = StudioCompiler()
    ): Pair<com.example.vm.CompilationOutput, String> {
        val baseStl = transpileToStudioLang(language, source, targetNodeName)
        val hasReady = Regex("""\bdef\s+_ready\s*\(""").containsMatchIn(baseStl)
        val hasProcess = Regex("""\bdef\s+_process\s*\(""").containsMatchIn(baseStl)
        val runnableStl = buildString {
            append(baseStl.trimEnd())
            append('\n')
            if (!invokeProcessTick && hasReady) {
                append("\n_ready()\n")
            }
            if (invokeProcessTick && hasProcess) {
                append("\n_process(").append(deltaSec).append(")\n")
            }
        }
        return compiler.compile(runnableStl, useCache = false) to runnableStl
    }

    /**
     * Offline diagnostics analyzer for VS Code Problems panel and Android Code Studio validation.
     */
    fun analyzeFileDiagnostics(
        file: IdeFileEntry,
        compiler: StudioCompiler = StudioCompiler()
    ): List<IdeDiagnostic> {
        val out = mutableListOf<IdeDiagnostic>()
        val pathLower = file.path.lowercase()
        val text = file.content
        if (text.isBlank()) {
            out += IdeDiagnostic(file.path, 1, "WARNING", "File is empty.")
            return out
        }

        // Balance check for brackets/braces/parens across code files
        if (!pathLower.endsWith(".md") && !pathLower.endsWith(".txt")) {
            var parens = 0
            var braces = 0
            var brackets = 0
            text.lines().forEachIndexed { idx, ln ->
                val clean = ln.substringBefore("//").substringBefore("#")
                clean.forEach { ch ->
                    when (ch) {
                        '(' -> parens++
                        ')' -> if (parens > 0) parens-- else out += IdeDiagnostic(file.path, idx + 1, "ERROR", "Unmatched closing ')'.")
                        '{' -> braces++
                        '}' -> if (braces > 0) braces-- else out += IdeDiagnostic(file.path, idx + 1, "ERROR", "Unmatched closing '}'.")
                        '[' -> brackets++
                        ']' -> if (brackets > 0) brackets-- else out += IdeDiagnostic(file.path, idx + 1, "ERROR", "Unmatched closing ']'.")
                    }
                }
            }
            if (parens > 0) out += IdeDiagnostic(file.path, text.lines().size, "ERROR", "$parens unclosed '(' parenthesis.")
            if (braces > 0) out += IdeDiagnostic(file.path, text.lines().size, "ERROR", "$braces unclosed '{' brace.")
            if (brackets > 0) out += IdeDiagnostic(file.path, text.lines().size, "ERROR", "$brackets unclosed '[' bracket.")
        }

        // AndroidManifest.xml Google Play Policy checks
        if (pathLower.endsWith("androidmanifest.xml")) {
            val bannedPerms = listOf(
                "READ_EXTERNAL_STORAGE",
                "WRITE_EXTERNAL_STORAGE",
                "READ_SMS",
                "RECEIVE_SMS",
                "REQUEST_INSTALL_PACKAGES"
            )
            text.lines().forEachIndexed { idx, ln ->
                bannedPerms.forEach { bp ->
                    if (ln.contains(bp)) {
                        out += IdeDiagnostic(
                            file.path,
                            idx + 1,
                            "WARNING",
                            "Play Policy alert: $bp requires declaration or Photo Picker / NotificationListener alternative."
                        )
                    }
                }
            }
            if (!text.contains("<application")) {
                out += IdeDiagnostic(file.path, 1, "ERROR", "AndroidManifest.xml missing <application> element.")
            }
        }

        // Polyglot / StudioLang script compile check
        val lang = ScriptLanguage.entries.firstOrNull { pathLower.endsWith(it.extension) }
        if (lang != null && !pathLower.endsWith(".kt") && !pathLower.endsWith(".java")) {
            val targetNode = file.attachedNodeName ?: "Player"
            val (comp, _) = compileForExecution(lang, text, targetNode, invokeProcessTick = false, compiler = compiler)
            comp.diagnostics.forEach { d ->
                val sev = when (d.severity) {
                    DiagnosticSeverity.ERROR -> "ERROR"
                    DiagnosticSeverity.WARNING -> "WARNING"
                    DiagnosticSeverity.INFO -> "INFO"
                }
                out += IdeDiagnostic(file.path, d.span.line.coerceAtLeast(1), sev, "[${lang.label}] ${d.message}")
            }
        } else if (pathLower.endsWith(".kt") || pathLower.endsWith(".java")) {
            // If it's a Godot script (not full Android Activity), validate via Polyglot compiler
            if (!text.contains("class MainActivity") && !text.contains("@Composable")) {
                val targetNode = file.attachedNodeName ?: "Player"
                val (comp, _) = compileForExecution(lang ?: ScriptLanguage.KOTLIN, text, targetNode, invokeProcessTick = false, compiler = compiler)
                comp.diagnostics.forEach { d ->
                    out += IdeDiagnostic(file.path, d.span.line.coerceAtLeast(1), "WARNING", d.message)
                }
            }
        }
        out += IdeAdvancedModulesEngine.analyzeAndroidApiDeprecations(file)
        return out
    }

    /**
     * Parses Compose UI calls, Android XML layout tags, or AppDesign into live interactive Android Code Studio preview widgets.
     */
    fun parseComposeWidgetsForPreview(
        source: String,
        fallbackDesign: AppDesign,
        resourceTable: AndroidCodeStudioKotlin.ResourceTable = AndroidCodeStudioKotlin.ResourceTable(emptyMap(), emptyMap(), emptyList(), emptyList())
    ): List<ComposePreviewElement> {
        val rawMatches = mutableListOf<Pair<Int, ComposePreviewElement>>()
        val consumedRanges = mutableListOf<IntRange>()
        var idx = 0

        if (source.contains("<LinearLayout") || source.contains("<ConstraintLayout") || source.contains("<TextView") || source.contains("<Button") || source.contains("<CheckBox") || source.contains("<EditText") || source.contains("<Switch")) {
            AndroidCodeStudioKotlin.XmlInflaterEngine.inflateXmlLayout(source, resourceTable).forEach { w ->
                val action = if (w.kind == "BUTTON") "XML ${w.tagName} tap" else ""
                rawMatches += (idx++) to ComposePreviewElement(w.id, w.kind, w.label, action)
            }
        } else {
            Regex("""Button\([^)]*\)\s*\{\s*Text\(\s*"([^"]+)"\s*\)""").findAll(source).forEach { m ->
                consumedRanges += m.range
                rawMatches += m.range.first to ComposePreviewElement("pv_${idx++}", "BUTTON", m.groupValues[1], "Interactive tap")
            }
            Regex("""OutlinedTextField\([^)]*label\s*=\s*\{\s*Text\(\s*"([^"]+)"\s*\)""").findAll(source).forEach { m ->
                consumedRanges += m.range
                rawMatches += m.range.first to ComposePreviewElement("pv_${idx++}", "INPUT", m.groupValues[1])
            }
            Regex("""Switch\(""").findAll(source).forEach { m ->
                consumedRanges += m.range
                rawMatches += m.range.first to ComposePreviewElement("pv_${idx++}", "SWITCH", "Toggle Setting #$idx")
            }
            Regex("""Text\(\s*"([^"]+)"\s*""").findAll(source).forEach { m ->
                val isInsideCompound = consumedRanges.any { r -> m.range.first >= r.first && m.range.last <= r.last }
                val label = m.groupValues[1]
                if (!isInsideCompound && !label.startsWith("package")) {
                    rawMatches += m.range.first to ComposePreviewElement("pv_${idx++}", "TEXT", label)
                }
            }
        }

        val found = rawMatches.sortedBy { it.first }.map { it.second }.toMutableList()
        if (found.isEmpty()) {
            fallbackDesign.screens.firstOrNull()?.elements?.forEach { el ->
                found += ComposePreviewElement(el.id, el.kind.name, el.label, el.onTapCode)
            }
        }
        return found
    }

    fun serializeTscn(sceneName: String, nodes: List<GodotSceneNode>): String = buildString {
        appendLine("[gd_scene format=3 uid=\"uid://automation_studio_main\"]")
        appendLine()
        var extId = 1
        val scriptIds = mutableMapOf<String, Int>()
        nodes.forEach { node ->
            val sp = node.scriptPath
            if (!sp.isNullOrBlank() && sp !in scriptIds) {
                scriptIds[sp] = extId
                appendLine("[ext_resource type=\"Script\" path=\"res://$sp\" id=\"${extId}_script\"]")
                extId++
            }
        }
        appendLine()
        appendLine("[node name=\"$sceneName\" type=\"Node2D\"]")
        nodes.forEach { n ->
            appendLine()
            appendLine("[node name=\"${n.name}\" type=\"${n.type.label}\" parent=\".\"]")
            appendLine("position = Vector2(${n.x.toInt()}, ${n.y.toInt()})")
            appendLine("size = Vector2(${n.width.toInt()}, ${n.height.toInt()})")
            if (n.vx != 0f || n.vy != 0f) {
                appendLine("velocity = Vector2(${n.vx}, ${n.vy})")
            }
            if (n.gravityScale != 0f) {
                appendLine("gravity_scale = ${n.gravityScale}")
            }
            if (n.labelText.isNotBlank()) {
                appendLine("text = \"${n.labelText.replace("\"", "\\\"")}\"")
            }
            val sid = n.scriptPath?.let { scriptIds[it] }
            if (sid != null) {
                appendLine("script = ExtResource(\"${sid}_script\")")
            }
        }
    }

    fun toAppDesignSnapshot(
        base: AppDesign,
        activeFile: IdeFileEntry?,
        appNameOverride: String? = null
    ): AppDesign {
        val text = activeFile?.content.orEmpty()
        return when {
            activeFile?.path?.endsWith(".kt") == true -> base.copy(
                appName = appNameOverride ?: base.appName,
                buildLanguage = AppBuildLanguage.KOTLIN_COMPOSE,
                customKotlinCode = text
            )
            activeFile?.path?.endsWith(".java") == true -> base.copy(
                appName = appNameOverride ?: base.appName,
                buildLanguage = AppBuildLanguage.JAVA_ANDROID,
                customJavaCode = text
            )
            activeFile?.path?.endsWith(".html") == true -> base.copy(
                appName = appNameOverride ?: base.appName,
                buildLanguage = AppBuildLanguage.HTML5_JS,
                customHtml5Code = text
            )
            activeFile?.path?.endsWith(".cpp") == true -> base.copy(
                appName = appNameOverride ?: base.appName,
                buildLanguage = AppBuildLanguage.CPP_NDK,
                customCppCode = text
            )
            else -> base.copy(appName = appNameOverride ?: base.appName)
        }
    }

    fun buildCommandCatalog(): List<IdeCommandItem> = listOf(
        IdeCommandItem("cmd.mode_dual", "Switch to Unified Dual Mode (VS Code + Godot + ACS)", "Workbench", "Ctrl+1"),
        IdeCommandItem("cmd.mode_acs", "Switch to Android Code Studio Override (App Builder)", "Workbench", "Ctrl+2"),
        IdeCommandItem("cmd.godot_play", "Godot: Play / Pause Live 2D Scene", "Godot", "F5"),
        IdeCommandItem("cmd.godot_stop", "Godot: Stop & Reset Scene", "Godot", "Shift+F5"),
        IdeCommandItem("cmd.godot_add_node", "Godot: Add Scene Node", "Godot", "Ctrl+A"),
        IdeCommandItem("cmd.godot_attach_script", "Godot: Attach Polyglot Script to Selected Node", "Godot", "Ctrl+Alt+S"),
        IdeCommandItem("cmd.run_compile", "Compile & Run Active Script / App Test", "Build", "F6"),
        IdeCommandItem("cmd.acs_export_zip", "Android Code Studio: Export Gradle APK Project ZIP", "Android", "Ctrl+Shift+B"),
        IdeCommandItem("cmd.voice_listen", "Voice Control: Speak Command to IDE", "Voice", "Alt+V"),
        IdeCommandItem("cmd.toggle_landscape", "Toggle Landscape Orientation Lock", "Window", "Alt+L"),
        IdeCommandItem("cmd.licenses", "View Open-Source MIT Attribution Notices", "Help", "F1")
    )

    fun extractJsonPackageName(manifestXml: String): String? =
        Regex("""package="([^"]+)"""").find(manifestXml)?.groupValues?.getOrNull(1)

    fun summarizeBundleJson(nodes: List<GodotSceneNode>, files: List<IdeFileEntry>): String {
        val root = JSONObject()
        root.put("node_count", nodes.size)
        root.put("file_count", files.size)
        return root.toString()
    }
}
