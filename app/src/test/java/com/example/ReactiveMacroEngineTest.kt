package com.example

import com.example.lang.StudioCapability
import com.example.macros.*
import com.example.runtime.PythonStdLibBridge
import com.example.services.TriggerEventRecord
import com.example.vm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReactiveMacroEngineTest {

    private class FakePredicateDispatcher(
        private val tempDir: java.io.File,
        private val predicateValues: MutableMap<String, Boolean>,
        private val ranActions: MutableList<String>
    ) : HostApiDispatcher {
        override suspend fun invokeHost(
            qualifiedName: String,
            args: List<StudioValue>,
            line: Int,
            logSink: (level: String, message: String) -> Unit
        ): StudioValue = when (qualifiedName) {
            "log.info", "log.warn" -> {
                logSink("INFO", args.joinToString(" ") { it.asDisplayString() })
                NoneVal
            }
            "catalog.fire_trigger" -> {
                MapVal(linkedMapOf("id" to TextVal(args.getOrNull(0)?.asDisplayString().orEmpty())))
            }
            "catalog.check_constraint", "catalog.check_condition" -> {
                val id = args.getOrNull(0)?.asDisplayString().orEmpty()
                BoolVal(predicateValues[id] ?: false)
            }
            "catalog.run_action" -> {
                val id = args.getOrNull(0)?.asDisplayString().orEmpty()
                ranActions += id
                MapVal(linkedMapOf("ok" to BoolVal(true), "id" to TextVal(id)))
            }
            else -> PythonStdLibBridge.dispatchOrNull(qualifiedName, args, line, tempDir, logSink)
                ?: NoneVal
        }
    }

    @Test
    fun reactiveTriggerAutoRunsActionOnlyWhenConstraintPassesAcrossAllGates() = runBlocking {
        val tempDir = Files.createTempDirectory("macro_reactive_test").toFile()
        try {
            val predicates = mutableMapOf(
                "c_screen_on" to false,
                "c_battery_ok" to true
            )
            val ranActions = mutableListOf<String>()
            val compiler = StudioCompiler(workspaceRoot = tempDir)
            val engine = MacroEngine(
                workspaceDir = tempDir,
                compiler = compiler,
                capabilityManager = CapabilityManager(StudioCapability.entries.toSet()),
                hostDispatcher = FakePredicateDispatcher(tempDir, predicates, ranActions)
            )

            // Disable default macros so only our test macro responds
            engine.macros.value.forEach { engine.deleteMacro(it.id) }

            val triggers = listOf(MacroTriggerRule("trigger.shake", "Shake Device", "Sensors"))
            val actions = listOf(MacroActionStep("action.torch_toggle", "Flashlight On", "torch", "true"))
            val constraints = listOf(
                MacroConstraintRule("c_screen_on", "Screen On"),
                MacroConstraintRule("c_battery_ok", "Battery Ok")
            )

            // 1. AND gate: c_screen_on is FALSE -> skipped
            val andMacro = MacroRecord(
                id = "m_and",
                name = "ShakeAndMacro",
                description = "AND gate test",
                sourceCode = MacroSourceBuilder.buildSourceFromBlocks("ShakeAndMacro", triggers, actions, constraints, ConstraintLogicGate.AND),
                enabled = true,
                triggers = triggers,
                actionSteps = actions,
                constraints = constraints,
                constraintGate = ConstraintLogicGate.AND
            )
            engine.upsertMacro(andMacro)

            val evt1 = TriggerEventRecord("e1", "trigger.shake", "Sensors", "Shake 1")
            val r1 = engine.dispatchTriggerEvent(evt1)
            assertEquals(1, r1.size)
            assertTrue(ranActions.isEmpty()) // Skipped because c_screen_on is false!

            // 2. Flip c_screen_on to TRUE -> actions MUST auto-run!
            predicates["c_screen_on"] = true
            Thread.sleep(520) // Past 500ms anti-feedback cooldown
            val evt2 = TriggerEventRecord("e2", "trigger.shake", "Sensors", "Shake 2")
            val r2 = engine.dispatchTriggerEvent(evt2)
            assertEquals(1, r2.size)
            assertEquals(listOf("action.torch_toggle"), ranActions)

            // 3. XOR gate: both TRUE -> skipped; one FALSE -> runs!
            ranActions.clear()
            val xorMacro = andMacro.copy(
                id = "m_and",
                constraintGate = ConstraintLogicGate.XOR,
                sourceCode = MacroSourceBuilder.buildSourceFromBlocks("ShakeAndMacro", triggers, actions, constraints, ConstraintLogicGate.XOR)
            )
            engine.upsertMacro(xorMacro)
            Thread.sleep(520)
            engine.dispatchTriggerEvent(TriggerEventRecord("e3", "trigger.shake", "Sensors", "Shake 3"))
            assertTrue("XOR with both true should skip", ranActions.isEmpty())

            predicates["c_screen_on"] = false
            Thread.sleep(520)
            engine.dispatchTriggerEvent(TriggerEventRecord("e4", "trigger.shake", "Sensors", "Shake 4"))
            assertEquals(listOf("action.torch_toggle"), ranActions)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun secureVariablesHaveContentsClearedWhenExportingMacroBundle() {
        val tempDir = Files.createTempDirectory("macro_vars_test").toFile()
        try {
            val engine = MacroEngine(
                workspaceDir = tempDir,
                compiler = StudioCompiler(workspaceRoot = tempDir),
                capabilityManager = CapabilityManager(StudioCapability.entries.toSet()),
                hostDispatcher = FakePredicateDispatcher(tempDir, mutableMapOf(), mutableListOf())
            )
            engine.upsertVariable(StudioVariable("public_count", StudioVarType.INTEGER, "42", "Non-secret counter", isSecure = false))
            engine.upsertVariable(StudioVariable("api_token", StudioVarType.STRING, "super_secret_xyz", "Private key", isSecure = true))

            val macro = engine.macros.value.first()
            val exportedJson = engine.exportMacroBundleJson(macro, includeVariables = true)
            @Suppress("UNCHECKED_CAST")
            val root = StudioMiniJson.parse(exportedJson) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val varsArr = root["variables"] as List<Map<String, Any?>>

            val exportedMap = varsArr.associate { obj ->
                (obj["name"] as String) to (obj["value"] as String)
            }
            assertEquals("42", exportedMap["public_count"])
            assertEquals("", exportedMap["api_token"]) // Redacted per PDF p.41!
            // In-memory store retains original value for local macro execution
            assertEquals("super_secret_xyz", engine.variables.value.first { it.name == "api_token" }.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
