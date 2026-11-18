package com.example.docs

import com.example.lang.StudioAnalyzer
import com.example.lang.StudioCapability
import com.example.lang.StudioLexer
import com.example.lang.StudioType
import com.example.nodes.NodeRegistry

enum class DocCategory(val label: String) {
    KEYWORDS("Keywords"),
    TYPES("Data Types"),
    STDLIB("Standard Library & Android APIs"),
    NODES("Node Studio"),
    CAPABILITIES("Capabilities & Permissions"),
    ERRORS("Error Codes"),
    CUSTOM("Custom Triggers / Actions / Blocks")
}

data class DocEntry(
    val id: String,
    val title: String,
    val category: DocCategory,
    val summary: String,
    val syntax: String,
    val parameters: String = "None",
    val returnType: String = "none",
    val requiredCapabilities: String = "None",
    val androidPermissions: String = "None",
    val apiCompatibility: String = "Android 7.0+ (API 24+)",
    val securityNotes: String = "Runs inside StudioVm sandbox.",
    val errorsInfo: String = "See error code reference.",
    val exampleCode: String = ""
)

/**
 * Built-in searchable documentation synchronized directly with StudioLexer,
 * StudioAnalyzer, NodeRegistry, and user-created custom definitions.
 * Only documents features that actually exist in the runtime.
 */
object DocsRegistry {

    private val staticEntries: List<DocEntry> by lazy {
        val list = ArrayList<DocEntry>(1800)
        list += keywordDocs()
        list += typeDocs()
        list += stdlibDocs()
        list += nodeDocs()
        list += capabilityDocs()
        list += errorDocs()
        list
    }

    fun allEntries(): List<DocEntry> {
        val custom = customDocs()
        return if (custom.isEmpty()) staticEntries else staticEntries + custom
    }

    fun search(query: String, category: DocCategory? = null): List<DocEntry> {
        val q = query.trim().lowercase()
        return allEntries().filter { entry ->
            (category == null || entry.category == category) &&
                (q.isEmpty() ||
                    entry.title.lowercase().contains(q) ||
                    entry.summary.lowercase().contains(q) ||
                    entry.syntax.lowercase().contains(q) ||
                    entry.requiredCapabilities.lowercase().contains(q))
        }
    }

    private fun keywordDocs(): List<DocEntry> = StudioLexer.DEFAULT_KEYWORDS.keys.sorted().map { kw ->
        val (desc, syn, ex) = when (kw) {
            "let" -> Triple("Declares a mutable variable.", "let name[: type] = expr", "let count = 0\ncount += 1")
            "const" -> Triple("Declares an immutable constant.", "const NAME = expr", "const MAX_TRIES = 3")
            "def" -> Triple("Defines a reusable function.", "def name(a, b = 1) -> type:", "def add(a: number, b: number) -> number:\n    return a + b")
            "macro" -> Triple("Defines a complete automation workflow with trigger, constraint, body, and error handler.", "macro Name:\n    on <trigger>\n    only if <cond>\n    do:\n        ...", "macro Ping:\n    on manual()\n    do:\n        log.info(\"Hi\")")
            "trigger" -> Triple("Defines a custom reusable trigger.", "trigger Name:\n    source ...\n    when ...\n    emit ...", "trigger FullCharge:\n    source battery.changed\n    when event.level == 100")
            "action" -> Triple("Defines a custom action with declared capabilities.", "action Name(params):\n    needs ...", "action Notify(title: text):\n    needs NOTIFICATIONS\n    notify.show(title, \"Done\")")
            "block" -> Triple("Defines a reusable Action Block returning a value.", "block Name(params) -> type:", "block Double(x: number) -> number:\n    return x * 2")
            "condition" -> Triple("Defines a reusable boolean condition.", "condition Name(params) -> bool:", "condition HasPower(min = 15) -> bool:\n    return device.battery_level() > min")
            "constraint" -> Triple("Defines a reusable execution constraint.", "constraint Name() -> bool:", "constraint Online() -> bool:\n    return net.is_connected()")
            "needs" -> Triple("Declares required Automation Studio capabilities for the file or construct.", "needs CAP_A, CAP_B", "needs FILE_ACCESS, SYSTEM_INFO")
            "confirm" -> Triple("Pauses execution and prompts the user before destructive operations.", "confirm \"message\"", "confirm \"Delete temp.txt?\"\nfiles.delete(\"temp.txt\")")
            "retry" -> Triple("Retries a block N times with an optional wait duration on error.", "retry N times, wait D:", "retry 3 times, wait 500ms:\n    log.info(\"Attempt\")")
            "timeout" -> Triple("Enforces a time limit around a block.", "timeout 5s:\n    ...", "timeout 5s:\n    time.sleep(100ms)")
            "try" -> Triple("Begins an error-handling block paired with `on error e:`.", "try:\n    ...\non error e:\n    ...", "try:\n    raise \"Oops\"\non error e:\n    log.warn(e.message)")
            "repeat" -> Triple("Repeats a block a fixed number of times.", "repeat N times:", "repeat 3 times:\n    log.info(\"Tick\")")
            "app" -> Triple("Declares a custom Android application definition.", "app AppName:", "app Counter:\n    screen Home:\n        log.info(\"Home\")")
            "screen" -> Triple("Declares a UI screen inside an app definition.", "screen ScreenName:", "screen Home:\n    log.info(\"Open\")")
            else -> Triple("StudioLang reserved keyword '$kw'.", kw, "# Keyword: $kw")
        }
        DocEntry(
            id = "kw_$kw",
            title = kw,
            category = DocCategory.KEYWORDS,
            summary = desc,
            syntax = syn,
            exampleCode = ex
        )
    }

    private fun typeDocs(): List<DocEntry> = StudioType.entries.map { t ->
        DocEntry(
            id = "type_${t.label}",
            title = t.label,
            category = DocCategory.TYPES,
            summary = "Built-in StudioLang type '${t.label}'.",
            syntax = "let x: ${t.label} = ...",
            returnType = t.label
        )
    }

    private fun stdlibDocs(): List<DocEntry> = StudioAnalyzer.DEFAULT_HOST_SIGNATURES.values.sortedBy { it.qualifiedName }.map { sig ->
        val cap = sig.requiredCapability?.id ?: "None"
        val androidPerm = when (sig.qualifiedName) {
            "notify.show", "notify.cancel" -> "android.permission.POST_NOTIFICATIONS (Android 13+)"
            "net.http_get", "net.http_post", "net.is_connected", "net.is_wifi" -> "INTERNET, ACCESS_NETWORK_STATE"
            "screen.capture_frame" -> "User-consented MediaProjection session"
            "screen.tap", "screen.type_text", "screen.scroll", "screen.back", "screen.home", "screen.find_text", "screen.ui_tree" ->
                "User-enabled StudioAccessibilityService"
            "device.vibrate" -> "VIBRATE"
            "shizuku.is_available", "shizuku.exec" -> "Shizuku service (moe.shizuku.privileged.api) + user grant"
            "root.is_available", "root.exec" -> "Root binary (su) + superuser approval"
            "clipboard.get", "clipboard.set" -> "Android ClipboardManager (foreground app focus on Android 10+)"
            else -> "None"
        }
        val minApi = when (sig.qualifiedName) {
            "notify.show" -> "API 24+ (runtime POST_NOTIFICATIONS on API 33+)"
            "screen.tap", "screen.scroll" -> "API 24+ (GestureDescription)"
            "shizuku.exec", "shizuku.is_available" -> "API 24+ (Android 11+ wireless ADB for Shizuku)"
            else -> "API 24+"
        }
        DocEntry(
            id = "api_${sig.qualifiedName}",
            title = sig.qualifiedName,
            category = DocCategory.STDLIB,
            summary = "Built-in runtime API `${sig.qualifiedName}` returning `${sig.returnType.label}`." +
                if (sig.isDestructive) " Destructive operation: pair with `confirm`." else "",
            syntax = "${sig.qualifiedName}(...)",
            returnType = sig.returnType.label,
            requiredCapabilities = cap,
            androidPermissions = androidPerm,
            apiCompatibility = minApi,
            securityNotes = if (sig.qualifiedName.startsWith("creds.")) {
                "Encrypted with Android Keystore AES-256 GCM. Returned secret values are redacted in logs and watch panels."
            } else if (sig.qualifiedName.startsWith("files.")) {
                "Sandboxed to studio workspace or user-picked SAF trees. Path traversal ('..') is blocked."
            } else "Enforced by BytecodeVerifier allowlist + CapabilityManager.",
            exampleCode = if (cap != "None") "needs $cap\n${sig.qualifiedName}()" else "${sig.qualifiedName}()"
        )
    }

    private fun nodeDocs(): List<DocEntry> = NodeRegistry.allSpecs().filter { !it.isCustom }.map { spec ->
        DocEntry(
            id = "node_${spec.id}",
            title = "${spec.title} (${spec.id})",
            category = DocCategory.NODES,
            summary = spec.summary,
            syntax = spec.docSyntax.ifBlank { "Node: ${spec.id}" },
            parameters = spec.params.joinToString(", ") { "${it.key}: ${it.type.label}" }.ifBlank { "None" },
            requiredCapabilities = spec.requiredCapabilities.joinToString { it.id }.ifBlank { "None" },
            apiCompatibility = "API ${spec.minApiLevel}+",
            exampleCode = spec.docExample
        )
    }

    private fun capabilityDocs(): List<DocEntry> = StudioCapability.entries.map { cap ->
        DocEntry(
            id = "cap_${cap.id}",
            title = cap.id,
            category = DocCategory.CAPABILITIES,
            summary = cap.description,
            syntax = "needs ${cap.id}",
            requiredCapabilities = cap.id,
            securityNotes = "Must be granted by user inside Automation Studio AND backed by corresponding Android OS permission/consent where applicable."
        )
    }

    private fun errorDocs(): List<DocEntry> = listOf(
        DocEntry("err_lex", "LEX001–LEX005", DocCategory.ERRORS, "Lexer errors: tab indentation (LEX001), misaligned indent (LEX002/003), unterminated string (LEX004), unexpected char (LEX005).", "Use 4 spaces per indent level."),
        DocEntry("err_par", "PAR001–PAR007", DocCategory.ERRORS, "Parser errors: unexpected token, missing identifier, missing ':', missing indent, missing 'on error'.", "Check block ':' and 4-space indentation."),
        DocEntry("err_sem", "SEM001–SEM008", DocCategory.ERRORS, "Semantic errors: unknown capability, undeclared variable, const reassignment, break/continue outside loop.", "Declare variables with `let` or `const`."),
        DocEntry("err_ver", "VER001–VER013", DocCategory.ERRORS, "Bytecode verifier errors: bad magic/version, out-of-bounds slots/jumps, unlisted host API, missing capability.", "Recompile cleanly via StudioCompiler."),
        DocEntry("err_vm", "VM001–VM013", DocCategory.ERRORS, "Runtime VM errors: stack bounds, division by zero, execution cancelled, timeout, instruction/recursion/allocation limits.", "Use bounded loops or `try ... on error`."),
        DocEntry("err_sec", "SEC001–SEC011", DocCategory.ERRORS, "Security sandbox errors: missing Studio capability, declined `confirm` prompt, or path traversal blocked.", "Declare `needs` and grant capability in Security panel.")
    )

    private fun customDocs(): List<DocEntry> = NodeRegistry.customDefinitions.value.map { def ->
        DocEntry(
            id = "custom_${def.id}",
            title = "${def.kind.name}: ${def.name} [${def.language.label}]",
            category = DocCategory.CUSTOM,
            summary = def.summary,
            syntax = "[${def.language.label}] ${def.kind.name.lowercase()} ${def.name}(${def.params.joinToString { it.key }})",
            parameters = def.params.joinToString { "${it.key}: ${it.type.label}" }.ifBlank { "None" },
            returnType = def.returnType.label,
            requiredCapabilities = def.requiredCapabilities.joinToString { it.id }.ifBlank { "None" },
            exampleCode = def.sourceCode
        )
    }
}
