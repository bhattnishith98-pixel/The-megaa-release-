package com.example.macros

import com.example.lang.StudioCapability
import com.example.services.StudioTriggerBus
import com.example.services.TriggerEventRecord
import com.example.vm.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class StudioVarType(val label: String) {
    BOOLEAN("Boolean"),
    INTEGER("Integer"),
    DECIMAL("Decimal"),
    STRING("String"),
    ARRAY("Array"),
    DICTIONARY("Dictionary")
}

data class StudioVariable(
    val name: String,
    val type: StudioVarType,
    val value: String,
    val comment: String = "",
    val isSecure: Boolean = false
)

enum class ConstraintLogicGate(val label: String, val description: String) {
    AND("ALL (AND)", "All constraints must pass"),
    OR("ANY (OR)", "Any constraint passes"),
    XOR("ONE (XOR)", "Exactly one constraint passes"),
    NOT("NONE (NOT)", "No constraints may pass")
}

data class MacroTriggerRule(
    val id: String,
    val title: String,
    val category: String = "Device Events",
    val param: String = ""
)

data class MacroActionStep(
    val id: String,
    val title: String,
    val target: String = "",
    val value: String = ""
)

data class MacroConstraintRule(
    val id: String,
    val title: String,
    val param: String = "",
    val expected: String = ""
)

data class MacroRecord(
    val id: String,
    val name: String,
    val description: String,
    val sourceCode: String,
    val enabled: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val triggers: List<MacroTriggerRule> = emptyList(),
    val actionSteps: List<MacroActionStep> = emptyList(),
    val constraints: List<MacroConstraintRule> = emptyList(),
    val constraintGate: ConstraintLogicGate = ConstraintLogicGate.AND
)

data class MacroExecutionHistoryItem(
    val id: String,
    val macroName: String,
    val timestampMs: Long,
    val success: Boolean,
    val durationMs: Long,
    val instructionsExecuted: Long,
    val summary: String,
    val logs: List<VmLogEntry>
)

object MacroSourceBuilder {
    fun buildSourceFromBlocks(
        name: String,
        triggers: List<MacroTriggerRule>,
        actionSteps: List<MacroActionStep>,
        constraints: List<MacroConstraintRule>,
        gate: ConstraintLogicGate = ConstraintLogicGate.AND,
        variables: List<StudioVariable> = emptyList()
    ): String {
        val cleanName = name.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "CustomMacro" }
            .let { if (it.first().isDigit()) "M_$it" else it }

        val firstTrigger = triggers.firstOrNull()
        val triggerExpr = if (firstTrigger != null) {
            "catalog.fire_trigger(${q(firstTrigger.id)}, ${q(firstTrigger.title)}, ${q(firstTrigger.category)}, ${q(firstTrigger.param)})"
        } else {
            "manual()"
        }

        val constraintExpr: String? = if (constraints.isEmpty()) {
            null
        } else {
            val checks = constraints.map { c ->
                "catalog.check_constraint(${q(c.id)}, ${q(c.param)}, ${q(c.expected)})"
            }
            when (gate) {
                ConstraintLogicGate.AND -> checks.joinToString(" and ")
                ConstraintLogicGate.OR -> checks.joinToString(" or ")
                ConstraintLogicGate.NOT -> "not (${checks.joinToString(" or ")})"
                ConstraintLogicGate.XOR -> if (checks.size == 1) checks.first()
                else checks.joinToString(" + ") { "(1 if $it else 0)" } + " == 1"
            }
        }

        val sb = StringBuilder()
        sb.appendLine("needs SYSTEM_INFO, FILE_ACCESS, NOTIFICATIONS, NETWORK_ACCESS")
        sb.appendLine("macro $cleanName:")
        sb.appendLine("    on $triggerExpr")
        if (constraintExpr != null) {
            sb.appendLine("    only if $constraintExpr")
        }
        sb.appendLine("    do:")
        if (variables.isNotEmpty()) {
            variables.take(24).forEach { v ->
                val vName = v.name.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { return@forEach }
                val literal = when (v.type) {
                    StudioVarType.BOOLEAN -> if (v.value.lowercase() in setOf("true", "1", "yes", "on")) "True" else "False"
                    StudioVarType.INTEGER -> (v.value.toLongOrNull() ?: 0L).toString()
                    StudioVarType.DECIMAL -> (v.value.toDoubleOrNull() ?: 0.0).toString()
                    StudioVarType.ARRAY -> if (v.value.trim().startsWith("[")) v.value.trim() else "[]"
                    StudioVarType.DICTIONARY -> if (v.value.trim().startsWith("{")) v.value.trim() else "{}"
                    StudioVarType.STRING -> q(v.value)
                }
                sb.appendLine("        let $vName = $literal")
            }
        }
        if (actionSteps.isEmpty()) {
            sb.appendLine("        log.info(${q("Macro '$cleanName' executed (no actions configured).")})")
        } else {
            actionSteps.forEach { a ->
                sb.appendLine("        catalog.run_action(${q(a.id)}, ${q(a.title)}, ${q(a.target)}, ${q(a.value)})")
            }
            sb.appendLine("        log.info(${q("Macro '$cleanName' finished ${actionSteps.size} action(s).")})")
        }
        sb.appendLine("    on error e:")
        sb.appendLine("        log.warn(e.message)")
        return sb.toString().trimEnd()
    }

    private fun q(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> append(ch)
            }
        }
        append('"')
    }
}

/**
 * Language-driven Reactive Macro Engine backed by StudioCompiler + StudioVm.
 * Supports PDF-style Triggers (Red), Actions (Blue), Constraints (Green with AND/OR/XOR/NOT),
 * typed Global Variables with Secure export clearing, and automatic background trigger dispatch.
 */
class MacroEngine(
    private val workspaceDir: File,
    private val compiler: StudioCompiler,
    private val capabilityManager: CapabilityManager,
    private val hostDispatcher: HostApiDispatcher
) {
    private val macrosFile = File(workspaceDir, "macros_store.json")
    private val historyFile = File(workspaceDir, "macro_history.json")
    private val variablesFile = File(workspaceDir, "variables_store.json")

    private val _macros = MutableStateFlow<List<MacroRecord>>(emptyList())
    val macros: StateFlow<List<MacroRecord>> = _macros.asStateFlow()

    private val _history = MutableStateFlow<List<MacroExecutionHistoryItem>>(emptyList())
    val history: StateFlow<List<MacroExecutionHistoryItem>> = _history.asStateFlow()

    private val _variables = MutableStateFlow<List<StudioVariable>>(emptyList())
    val variables: StateFlow<List<StudioVariable>> = _variables.asStateFlow()

    private var activeVm: StudioVm? = null
    private val dispatchMutex = Mutex()
    private val lastAutoTriggeredAtMs = ConcurrentHashMap<String, Long>()

    init {
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        loadAll()
    }

    private fun loadAll() {
        if (macrosFile.exists()) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val arr = StudioMiniJson.parse(macrosFile.readText()) as? List<Any?> ?: emptyList()
                _macros.value = arr.mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? Map<String, Any?>)?.let { macroFromMap(it) }
                }
            }
        }
        if (_macros.value.isEmpty()) {
            _macros.value = defaultStarterMacros()
            saveMacros()
        }
        if (variablesFile.exists()) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val arr = StudioMiniJson.parse(variablesFile.readText()) as? List<Any?> ?: emptyList()
                _variables.value = arr.mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    val o = item as? Map<String, Any?> ?: return@mapNotNull null
                    val name = o["name"] as? String ?: return@mapNotNull null
                    val t = StudioVarType.entries.firstOrNull { it.name == (o["type"] as? String) } ?: StudioVarType.STRING
                    StudioVariable(
                        name = name,
                        type = t,
                        value = o["value"] as? String ?: "",
                        comment = o["comment"] as? String ?: "",
                        isSecure = o["isSecure"] as? Boolean ?: false
                    )
                }
            }
        }
        if (historyFile.exists()) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val arr = StudioMiniJson.parse(historyFile.readText()) as? List<Any?> ?: emptyList()
                _history.value = arr.mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    val o = item as? Map<String, Any?> ?: return@mapNotNull null
                    MacroExecutionHistoryItem(
                        id = o["id"] as? String ?: return@mapNotNull null,
                        macroName = o["macroName"] as? String ?: "",
                        timestampMs = (o["timestampMs"] as? Number)?.toLong() ?: 0L,
                        success = o["success"] as? Boolean ?: false,
                        durationMs = (o["durationMs"] as? Number)?.toLong() ?: 0L,
                        instructionsExecuted = (o["instructionsExecuted"] as? Number)?.toLong() ?: 0L,
                        summary = o["summary"] as? String ?: "",
                        logs = emptyList()
                    )
                }
            }
        }
    }

    fun upsertMacro(record: MacroRecord) {
        val current = _macros.value.toMutableList()
        val idx = current.indexOfFirst { it.id == record.id }
        val updated = record.copy(updatedAtMs = System.currentTimeMillis())
        if (idx >= 0) current[idx] = updated else current.add(0, updated)
        _macros.value = current
        saveMacros()
    }

    fun deleteMacro(id: String) {
        _macros.value = _macros.value.filterNot { it.id == id }
        saveMacros()
    }

    fun toggleEnabled(id: String) {
        _macros.value = _macros.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        saveMacros()
    }

    fun upsertVariable(variable: StudioVariable) {
        val cleanName = variable.name.trim()
        if (cleanName.isEmpty()) return
        val normalized = variable.copy(name = cleanName)
        val current = _variables.value.toMutableList()
        val idx = current.indexOfFirst { it.name == cleanName }
        if (idx >= 0) current[idx] = normalized else current.add(normalized)
        _variables.value = current
        saveVariables()
    }

    fun deleteVariable(name: String) {
        _variables.value = _variables.value.filterNot { it.name == name }
        saveVariables()
    }

    /**
     * Exports a macro + global variables bundle as JSON.
     * Any variable with `isSecure == true` has its value cleared before export/sharing.
     */
    fun exportMacroBundleJson(macro: MacroRecord, includeVariables: Boolean = true): String {
        val root = linkedMapOf<String, Any?>(
            "format" to "automation_studio_macro_v2",
            "macro" to macroToMap(macro)
        )
        if (includeVariables) {
            root["variables"] = _variables.value.map { v ->
                linkedMapOf<String, Any?>(
                    "name" to v.name,
                    "type" to v.type.name,
                    "value" to if (v.isSecure) "" else v.value,
                    "comment" to v.comment,
                    "isSecure" to v.isSecure
                )
            }
        }
        return StudioMiniJson.stringify(root)
    }

    fun matchesTriggerEvent(record: MacroRecord, event: TriggerEventRecord): Boolean {
        if (!record.enabled) return false
        // Prevent self-trigger loops from generic catalog action trace logs
        if (event.category == "Actions" && record.triggers.none { it.id == event.triggerTypeId }) {
            return false
        }
        if (record.triggers.any { t -> StudioTriggerBus.matchesTrigger(t.id, t.param, event) }) {
            return true
        }
        // Also parse `on <...>` clause from StudioLang source code for text/code macros
        val onMatch = Regex("""^\s*on\s+(.+)$""", RegexOption.MULTILINE).find(record.sourceCode)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (onMatch.isNotEmpty() && !onMatch.startsWith("manual") && !onMatch.startsWith("error")) {
            val triggerId = Regex("""["']([^"']+)["']""").find(onMatch)?.groupValues?.getOrNull(1)
                ?: onMatch.substringBefore("(").trim()
            if (StudioTriggerBus.matchesTrigger(triggerId, "", event)) return true
        }
        return false
    }

    /**
     * Automatically evaluates all enabled macros matching the incoming trigger event.
     * If a macro's constraints / `only if` pass (AND/OR/XOR/NOT), runs its actions automatically.
     */
    suspend fun dispatchTriggerEvent(event: TriggerEventRecord): List<Pair<MacroRecord, VmExecutionResult>> {
        val snapshot = _macros.value.filter { it.enabled }
        val executed = mutableListOf<Pair<MacroRecord, VmExecutionResult>>()
        val now = System.currentTimeMillis()
        dispatchMutex.withLock {
            for (macro in snapshot) {
                if (!matchesTriggerEvent(macro, event)) continue
                val lastAt = lastAutoTriggeredAtMs[macro.id] ?: 0L
                if (now - lastAt < 500L) continue
                lastAutoTriggeredAtMs[macro.id] = now
                val res = runMacro(macro, triggerEvent = event)
                val skipped = res.logs.any { it.message.contains("skipped: constraint evaluated to false") }
                val outcome = when {
                    skipped -> "Skipped (Constraint false)"
                    res.success -> "Ran (${res.durationMs}ms)"
                    else -> "Error: ${res.error}"
                }
                StudioTriggerBus.recordMacroAutoRun(macro.name, outcome)
                executed += macro to res
            }
        }
        return executed
    }

    fun pauseActive() { activeVm?.pause() }
    fun resumeActive() { activeVm?.resume() }
    fun stopActive() { activeVm?.cancel() }

    suspend fun runMacro(
        record: MacroRecord,
        triggerEvent: TriggerEventRecord? = null,
        ignoreConstraints: Boolean = false,
        debugger: ExecutionDebugger? = null
    ): VmExecutionResult {
        val compiled = compiler.compile(record.sourceCode)
        if (!compiled.success || compiled.bytecode == null) {
            val errMsg = compiled.diagnostics.joinToString("\n") { it.format() }
            val failResult = VmExecutionResult(
                success = false,
                returnValue = NoneVal,
                logs = compiled.diagnostics.map { VmLogEntry("ERROR", it.format(), it.span.line) },
                instructionsExecuted = 0,
                allocationsCount = 0,
                durationMs = 0,
                error = errMsg
            )
            recordHistory(record.name, failResult)
            return failResult
        }

        val bc = compiled.bytecode
        val missingCaps = bc.requiredCapabilities.filter { !capabilityManager.isGranted(it) }
        if (missingCaps.isNotEmpty()) {
            val msg = "Missing Studio capability authorization: ${missingCaps.joinToString { it.id }}"
            val failResult = VmExecutionResult(
                success = false,
                returnValue = NoneVal,
                logs = listOf(VmLogEntry("ERROR", msg)),
                instructionsExecuted = 0,
                allocationsCount = 0,
                durationMs = 0,
                error = msg
            )
            recordHistory(record.name, failResult)
            return failResult
        }

        val eventPayload = MapVal(
            linkedMapOf<String, StudioValue>(
                "id" to TextVal(triggerEvent?.id ?: "manual"),
                "type" to TextVal(triggerEvent?.triggerTypeId ?: "trigger.manual"),
                "category" to TextVal(triggerEvent?.category ?: "Manual"),
                "summary" to TextVal(triggerEvent?.summary ?: "Manual execution"),
                "timestamp_ms" to NumVal((triggerEvent?.timestampMs ?: System.currentTimeMillis()).toDouble()),
                "vars" to MapVal(
                    _variables.value.associateTo(LinkedHashMap()) { v ->
                        v.name to TextVal(v.value, isSecret = v.isSecure)
                    }
                )
            )
        )

        val vm = StudioVm(capabilityManager, hostDispatcher)
        activeVm = vm
        val macroMeta = bc.macros.firstOrNull()
        val result = if (macroMeta != null) {
            if (!ignoreConstraints && macroMeta.onlyIfFuncIndex != null) {
                val condRes = vm.execute(bc, macroMeta.onlyIfFuncIndex, listOf(eventPayload))
                if (!condRes.success || !condRes.returnValue.asBool()) {
                    val skipped = VmExecutionResult(
                        success = condRes.success,
                        returnValue = NoneVal,
                        logs = condRes.logs + VmLogEntry("INFO", "Macro '${macroMeta.name}' skipped: constraint evaluated to false."),
                        instructionsExecuted = condRes.instructionsExecuted,
                        allocationsCount = condRes.allocationsCount,
                        durationMs = condRes.durationMs,
                        error = condRes.error
                    )
                    activeVm = null
                    recordHistory(record.name, skipped)
                    return skipped
                }
            }
            val doRes = vm.execute(bc, macroMeta.doFuncIndex, listOf(eventPayload), debugger)
            if (!doRes.success && macroMeta.errorFuncIndex != null) {
                val errMap = MapVal(linkedMapOf("message" to TextVal(doRes.error ?: "Error")))
                val errRes = vm.execute(bc, macroMeta.errorFuncIndex, listOf(errMap), debugger)
                doRes.copy(logs = doRes.logs + errRes.logs)
            } else doRes
        } else {
            vm.execute(bc, 0, emptyList(), debugger)
        }

        activeVm = null
        recordHistory(record.name, result)
        return result
    }

    private fun recordHistory(macroName: String, result: VmExecutionResult) {
        val item = MacroExecutionHistoryItem(
            id = "run_${System.nanoTime()}",
            macroName = macroName,
            timestampMs = System.currentTimeMillis(),
            success = result.success,
            durationMs = result.durationMs,
            instructionsExecuted = result.instructionsExecuted,
            summary = if (result.success) {
                val skipMsg = result.logs.firstOrNull { it.message.contains("skipped:") }?.message
                skipMsg ?: "Result: ${result.returnValue.asDisplayString()}"
            } else "Error: ${result.error}",
            logs = result.logs
        )
        _history.value = (listOf(item) + _history.value).take(50)
        saveHistory()
    }

    private fun saveMacros() {
        macrosFile.writeText(StudioMiniJson.stringify(_macros.value.map { macroToMap(it) }))
    }

    private fun saveVariables() {
        val list = _variables.value.map { v ->
            linkedMapOf<String, Any?>(
                "name" to v.name,
                "type" to v.type.name,
                "value" to v.value,
                "comment" to v.comment,
                "isSecure" to v.isSecure
            )
        }
        variablesFile.writeText(StudioMiniJson.stringify(list))
    }

    private fun macroToMap(m: MacroRecord): Map<String, Any?> = linkedMapOf(
        "id" to m.id,
        "name" to m.name,
        "description" to m.description,
        "sourceCode" to m.sourceCode,
        "enabled" to m.enabled,
        "updatedAtMs" to m.updatedAtMs,
        "constraintGate" to m.constraintGate.name,
        "triggers" to m.triggers.map { t ->
            linkedMapOf<String, Any?>("id" to t.id, "title" to t.title, "category" to t.category, "param" to t.param)
        },
        "actionSteps" to m.actionSteps.map { a ->
            linkedMapOf<String, Any?>("id" to a.id, "title" to a.title, "target" to a.target, "value" to a.value)
        },
        "constraints" to m.constraints.map { c ->
            linkedMapOf<String, Any?>("id" to c.id, "title" to c.title, "param" to c.param, "expected" to c.expected)
        }
    )

    @Suppress("UNCHECKED_CAST")
    private fun macroFromMap(o: Map<String, Any?>): MacroRecord {
        val triggers = (o["triggers"] as? List<Any?>).orEmpty().mapNotNull { item ->
            val t = item as? Map<String, Any?> ?: return@mapNotNull null
            MacroTriggerRule(
                id = t["id"] as? String ?: "",
                title = t["title"] as? String ?: "",
                category = t["category"] as? String ?: "Device Events",
                param = t["param"] as? String ?: ""
            )
        }
        val actions = (o["actionSteps"] as? List<Any?>).orEmpty().mapNotNull { item ->
            val a = item as? Map<String, Any?> ?: return@mapNotNull null
            MacroActionStep(
                id = a["id"] as? String ?: "",
                title = a["title"] as? String ?: "",
                target = a["target"] as? String ?: "",
                value = a["value"] as? String ?: ""
            )
        }
        val constraints = (o["constraints"] as? List<Any?>).orEmpty().mapNotNull { item ->
            val c = item as? Map<String, Any?> ?: return@mapNotNull null
            MacroConstraintRule(
                id = c["id"] as? String ?: "",
                title = c["title"] as? String ?: "",
                param = c["param"] as? String ?: "",
                expected = c["expected"] as? String ?: ""
            )
        }
        val gate = ConstraintLogicGate.entries.firstOrNull { it.name == (o["constraintGate"] as? String) }
            ?: ConstraintLogicGate.AND

        return MacroRecord(
            id = o["id"] as? String ?: "",
            name = o["name"] as? String ?: "",
            description = o["description"] as? String ?: "",
            sourceCode = o["sourceCode"] as? String ?: "",
            enabled = o["enabled"] as? Boolean ?: true,
            updatedAtMs = (o["updatedAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            triggers = triggers,
            actionSteps = actions,
            constraints = constraints,
            constraintGate = gate
        )
    }

    private fun saveHistory() {
        val list = _history.value.map { h ->
            linkedMapOf<String, Any?>(
                "id" to h.id,
                "macroName" to h.macroName,
                "timestampMs" to h.timestampMs,
                "success" to h.success,
                "durationMs" to h.durationMs,
                "instructionsExecuted" to h.instructionsExecuted,
                "summary" to h.summary
            )
        }
        historyFile.writeText(StudioMiniJson.stringify(list))
    }

    private fun defaultStarterMacros(): List<MacroRecord> {
        val battTriggers = listOf(MacroTriggerRule("trigger.battery_changed", "Battery Level Changed", "Battery/Power", ""))
        val battActions = listOf(MacroActionStep("action.vibrate", "Vibrate Alert", "device", "150"))
        val battConstraints = listOf(MacroConstraintRule("constraint.battery_below", "Battery <= 20%", "battery", "20"))

        val shakeTriggers = listOf(MacroTriggerRule("trigger.shake", "Shake Device", "Sensors", ""))
        val shakeActions = listOf(MacroActionStep("action.torch_toggle", "Flashlight On", "torch", "true"))
        val shakeConstraints = listOf(MacroConstraintRule("constraint.screen_on", "Screen On", "screen", "true"))

        return listOf(
            MacroRecord(
                id = "macro_auto_battery_guard",
                name = "BatteryLowGuard",
                description = "Auto-triggers on battery change; vibrates only if battery <= 20%.",
                sourceCode = MacroSourceBuilder.buildSourceFromBlocks("BatteryLowGuard", battTriggers, battActions, battConstraints, ConstraintLogicGate.AND),
                enabled = true,
                triggers = battTriggers,
                actionSteps = battActions,
                constraints = battConstraints,
                constraintGate = ConstraintLogicGate.AND
            ),
            MacroRecord(
                id = "macro_shake_torch",
                name = "ShakeFlashlight",
                description = "Auto-triggers on device shake when screen is ON to enable flashlight.",
                sourceCode = MacroSourceBuilder.buildSourceFromBlocks("ShakeFlashlight", shakeTriggers, shakeActions, shakeConstraints, ConstraintLogicGate.AND),
                enabled = true,
                triggers = shakeTriggers,
                actionSteps = shakeActions,
                constraints = shakeConstraints,
                constraintGate = ConstraintLogicGate.AND
            ),
            MacroRecord(
                id = "macro_battery_audit",
                name = "DeviceHealthCheck",
                description = "Inspects battery, SDK level, and network state cleanly.",
                sourceCode = """
                    needs SYSTEM_INFO, NETWORK_ACCESS
                    macro DeviceHealthCheck:
                        on manual()
                        only if device.battery_level() > 5
                        do:
                            let lvl = device.battery_level()
                            let net_ok = net.is_connected()
                            log.info("Model: " + device.model())
                            log.info("Battery: " + lvl + "% | Online: " + net_ok)
                        on error e:
                            log.warn(e.message)
                """.trimIndent(),
                triggers = listOf(MacroTriggerRule("trigger.manual", "Manual Run", "Macro", "")),
                actionSteps = listOf(MacroActionStep("action.log_health", "Log Device Health", "device", "status")),
                constraints = listOf(MacroConstraintRule("constraint.battery_above", "Battery > 5%", "battery", "5"))
            ),
            MacroRecord(
                id = "macro_workspace_note",
                name = "WorkspaceNoteBackup",
                description = "Writes a timestamped note in the sandboxed studio workspace.",
                sourceCode = """
                    needs FILE_ACCESS
                    macro WorkspaceNoteBackup:
                        on manual()
                        do:
                            let ts = time.now()
                            files.write("notes/session.txt", "Automation Studio run at " + ts)
                            let saved = files.read("notes/session.txt")
                            log.info("Saved file content -> " + saved)
                """.trimIndent()
            )
        )
    }
}

/**
 * Lightweight zero-dependency JSON reader/writer so MacroEngine persists and exports cleanly
 * on both Android runtime and pure-JVM unit tests without org.json stub issues.
 */
object StudioMiniJson {
    fun stringify(value: Any?, indent: Int = 0): String = buildString {
        writeValue(value, indent, this)
    }

    private fun writeValue(v: Any?, indent: Int, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long, is Double, is Float -> sb.append(v.toString())
            is String -> writeString(v, sb)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val entries = v.entries.toList()
                entries.forEachIndexed { idx, (k, child) ->
                    sb.append(" ".repeat(indent + 2))
                    writeString(k.toString(), sb)
                    sb.append(": ")
                    writeValue(child, indent + 2, sb)
                    if (idx < entries.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append(" ".repeat(indent)).append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                items.forEachIndexed { idx, child ->
                    sb.append(" ".repeat(indent + 2))
                    writeValue(child, indent + 2, sb)
                    if (idx < items.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append(" ".repeat(indent)).append(']')
            }
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? = Parser(text).parseVal()

    private class Parser(private val src: String) {
        private var pos = 0

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseVal(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> parseNum()
            }
        }

        private fun parseObj(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            pos++ // '{'
            skipWs()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (pos < src.length) {
                skipWs()
                val k = parseStr()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                val v = parseVal()
                map[k] = v
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun parseArr(): List<Any?> {
            val list = mutableListOf<Any?>()
            pos++ // '['
            skipWs()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (pos < src.length) {
                list += parseVal()
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun parseStr(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> if (pos + 4 <= src.length) {
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNum(): Number {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in setOf('-', '+', '.', 'e', 'E'))) pos++
            val slice = src.substring(start, pos)
            return if (slice.contains('.') || slice.contains('e', ignoreCase = true)) {
                slice.toDoubleOrNull() ?: 0.0
            } else {
                slice.toLongOrNull() ?: 0L
            }
        }
    }
}

