package com.example.macros

import com.example.lang.StudioCapability
import com.example.vm.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MacroRecord(
    val id: String,
    val name: String,
    val description: String,
    val sourceCode: String,
    val enabled: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
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

/**
 * Language-driven Macro Engine backed by StudioCompiler + StudioVm.
 * Zero external macro plugin required.
 */
class MacroEngine(
    private val workspaceDir: File,
    private val compiler: StudioCompiler,
    private val capabilityManager: CapabilityManager,
    private val hostDispatcher: HostApiDispatcher
) {
    private val macrosFile = File(workspaceDir, "macros_store.json")
    private val historyFile = File(workspaceDir, "macro_history.json")

    private val _macros = MutableStateFlow<List<MacroRecord>>(emptyList())
    val macros: StateFlow<List<MacroRecord>> = _macros.asStateFlow()

    private val _history = MutableStateFlow<List<MacroExecutionHistoryItem>>(emptyList())
    val history: StateFlow<List<MacroExecutionHistoryItem>> = _history.asStateFlow()

    private var activeVm: StudioVm? = null

    init {
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        loadAll()
    }

    private fun loadAll() {
        if (macrosFile.exists()) {
            runCatching {
                val arr = JSONArray(macrosFile.readText())
                val list = mutableListOf<MacroRecord>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += MacroRecord(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description", ""),
                        sourceCode = o.getString("sourceCode"),
                        enabled = o.optBoolean("enabled", true),
                        updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                }
                _macros.value = list
            }
        }
        if (_macros.value.isEmpty()) {
            _macros.value = defaultStarterMacros()
            saveMacros()
        }
        if (historyFile.exists()) {
            runCatching {
                val arr = JSONArray(historyFile.readText())
                val list = mutableListOf<MacroExecutionHistoryItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += MacroExecutionHistoryItem(
                        id = o.getString("id"),
                        macroName = o.getString("macroName"),
                        timestampMs = o.getLong("timestampMs"),
                        success = o.getBoolean("success"),
                        durationMs = o.getLong("durationMs"),
                        instructionsExecuted = o.getLong("instructionsExecuted"),
                        summary = o.getString("summary"),
                        logs = emptyList()
                    )
                }
                _history.value = list
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

    fun pauseActive() { activeVm?.pause() }
    fun resumeActive() { activeVm?.resume() }
    fun stopActive() { activeVm?.cancel() }

    suspend fun runMacro(
        record: MacroRecord,
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
        // Check capabilities before launching
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

        val vm = StudioVm(capabilityManager, hostDispatcher)
        activeVm = vm
        // If the program defines a `macro` block, check its `only if` constraint and run its `do:` body
        val macroMeta = bc.macros.firstOrNull()
        val result = if (macroMeta != null) {
            if (macroMeta.onlyIfFuncIndex != null) {
                val condRes = vm.execute(bc, macroMeta.onlyIfFuncIndex, listOf(MapVal()))
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
            val doRes = vm.execute(bc, macroMeta.doFuncIndex, listOf(MapVal()), debugger)
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
            id = "run_${System.currentTimeMillis()}",
            macroName = macroName,
            timestampMs = System.currentTimeMillis(),
            success = result.success,
            durationMs = result.durationMs,
            instructionsExecuted = result.instructionsExecuted,
            summary = if (result.success) "Result: ${result.returnValue.asDisplayString()}" else "Error: ${result.error}",
            logs = result.logs
        )
        _history.value = (listOf(item) + _history.value).take(50)
        saveHistory()
    }

    private fun saveMacros() {
        val arr = JSONArray()
        _macros.value.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("description", m.description)
                put("sourceCode", m.sourceCode)
                put("enabled", m.enabled)
                put("updatedAtMs", m.updatedAtMs)
            })
        }
        macrosFile.writeText(arr.toString(2))
    }

    private fun saveHistory() {
        val arr = JSONArray()
        _history.value.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("macroName", h.macroName)
                put("timestampMs", h.timestampMs)
                put("success", h.success)
                put("durationMs", h.durationMs)
                put("instructionsExecuted", h.instructionsExecuted)
                put("summary", h.summary)
            })
        }
        historyFile.writeText(arr.toString(2))
    }

    private fun defaultStarterMacros(): List<MacroRecord> = listOf(
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
            """.trimIndent()
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
