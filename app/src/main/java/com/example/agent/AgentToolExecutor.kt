package com.example.agent

import com.example.appbuilder.AppDesign
import com.example.appbuilder.AppScreenDesign
import com.example.appbuilder.UiElementDesign
import com.example.appbuilder.UiElementKind
import com.example.lang.StudioCapability
import com.example.nodes.*
import com.example.vm.CapabilityManager
import com.example.vm.SafePathValidator
import com.example.vm.StudioCompiler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Validated, undoable Agent Tool API executor (Part A4.3).
 * Every graph/code/app mutation snapshots state onto the undo stack
 * and logs redacted tool arguments and outcomes to the Agent Action Log.
 */
class AgentToolExecutor(
    private val workspaceRoot: File,
    private val compiler: StudioCompiler,
    private val capabilityManager: CapabilityManager
) {
    data class WorkspaceSnapshot(
        val graph: NodeGraph,
        val sourceCode: String,
        val appDesign: AppDesign
    )

    private val undoStack = ArrayDeque<WorkspaceSnapshot>()
    private val _actionLog = MutableStateFlow<List<AgentToolLogEntry>>(emptyList())
    val actionLog: StateFlow<List<AgentToolLogEntry>> = _actionLog.asStateFlow()

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun pushUndoSnapshot(snapshot: WorkspaceSnapshot) {
        undoStack.addLast(snapshot)
        if (undoStack.size > 50) undoStack.removeFirst()
    }

    fun popUndoSnapshot(): WorkspaceSnapshot? = if (undoStack.isNotEmpty()) undoStack.removeLast() else null

    fun executeTool(
        call: AgentToolCallSpec,
        current: WorkspaceSnapshot,
        onUpdate: (WorkspaceSnapshot) -> Unit,
        onAskUser: (String) -> Unit = {},
        onRequestCapability: (StudioCapability) -> Unit = {}
    ): Pair<Boolean, String> {
        val args = call.args
        val outcome: Pair<Boolean, String> = when (call.toolName) {
            "create_node" -> {
                val typeId = args["typeId"] ?: return logResult(call, false, "Missing typeId")
                if (NodeRegistry.getSpec(typeId) == null) return logResult(call, false, "Unknown node type '$typeId'")
                pushUndoSnapshot(current)
                val spec = NodeRegistry.getSpec(typeId)!!
                val id = args["id"] ?: "n_${System.currentTimeMillis() % 100000}"
                val x = args["x"]?.toFloatOrNull() ?: (100f + current.graph.nodes.size * 220f)
                val y = args["y"]?.toFloatOrNull() ?: 140f
                val params = args.filterKeys { it.startsWith("param.") }.mapKeys { it.key.removePrefix("param.") }
                val node = GraphNode(id, typeId, args["label"] ?: spec.title, x, y, spec.params.associate { it.key to it.defaultValue } + params)
                val newGraph = current.graph.copy(nodes = current.graph.nodes + node)
                val newSrc = GraphAstConverter.graphToSource(newGraph)
                onUpdate(current.copy(graph = newGraph, sourceCode = newSrc))
                true to "Created node ${node.label} ($id)"
            }
            "delete_node" -> {
                val nodeId = args["nodeId"] ?: return logResult(call, false, "Missing nodeId")
                if (current.graph.nodes.none { it.id == nodeId }) return logResult(call, false, "Node '$nodeId' not found")
                pushUndoSnapshot(current)
                val newGraph = current.graph.copy(
                    nodes = current.graph.nodes.filterNot { it.id == nodeId },
                    wires = current.graph.wires.filterNot { it.fromNodeId == nodeId || it.toNodeId == nodeId }
                )
                onUpdate(current.copy(graph = newGraph, sourceCode = GraphAstConverter.graphToSource(newGraph)))
                true to "Deleted node $nodeId"
            }
            "set_param" -> {
                val nodeId = args["nodeId"] ?: return logResult(call, false, "Missing nodeId")
                val key = args["key"] ?: return logResult(call, false, "Missing key")
                val value = args["value"] ?: ""
                pushUndoSnapshot(current)
                val newGraph = current.graph.copy(
                    nodes = current.graph.nodes.map {
                        if (it.id == nodeId) it.copy(params = it.params + (key to value)) else it
                    }
                )
                onUpdate(current.copy(graph = newGraph, sourceCode = GraphAstConverter.graphToSource(newGraph)))
                true to "Set $nodeId.$key"
            }
            "connect" -> {
                val fromId = args["fromNodeId"] ?: return logResult(call, false, "Missing fromNodeId")
                val fromPort = args["fromPortId"] ?: "flow_out"
                val toId = args["toNodeId"] ?: return logResult(call, false, "Missing toNodeId")
                val toPort = args["toPortId"] ?: "flow_in"
                val check = NodeRegistry.validateConnection(current.graph, fromId, fromPort, toId, toPort)
                if (!check.allowed) return logResult(call, false, check.reason ?: "Connection rejected")
                pushUndoSnapshot(current)
                val wire = GraphWire("w_${System.currentTimeMillis() % 100000}", fromId, fromPort, toId, toPort, check.autoConvert)
                val newGraph = current.graph.copy(wires = current.graph.wires + wire)
                onUpdate(current.copy(graph = newGraph, sourceCode = GraphAstConverter.graphToSource(newGraph)))
                true to "Connected $fromId.$fromPort -> $toId.$toPort"
            }
            "disconnect" -> {
                val wireId = args["wireId"] ?: return logResult(call, false, "Missing wireId")
                pushUndoSnapshot(current)
                val newGraph = current.graph.copy(wires = current.graph.wires.filterNot { it.id == wireId })
                onUpdate(current.copy(graph = newGraph, sourceCode = GraphAstConverter.graphToSource(newGraph)))
                true to "Disconnected wire $wireId"
            }
            "define_trigger", "define_action", "make_block",
            "define_polyglot_trigger", "define_polyglot_block" -> {
                val name = args["name"] ?: return logResult(call, false, "Missing name")
                val kind = when (call.toolName) {
                    "define_trigger", "define_polyglot_trigger" -> CustomDefinitionKind.TRIGGER
                    "define_action" -> CustomDefinitionKind.ACTION
                    else -> CustomDefinitionKind.BLOCK
                }
                val lang = runCatching {
                    BlockLanguage.valueOf(args["language"].orEmpty().uppercase())
                }.getOrDefault(BlockLanguage.STUDIOLANG)
                val code = args["sourceCode"] ?: ""
                val def = CustomNodeDefinition(
                    id = "custom.${kind.name.lowercase()}.$name",
                    name = name,
                    kind = kind,
                    summary = args["summary"] ?: "Agent-created ${lang.label} ${kind.name.lowercase()}",
                    params = emptyList(),
                    returnType = PortType.ANY,
                    requiredCapabilities = emptySet(),
                    sourceCode = code,
                    language = lang
                )
                NodeRegistry.registerCustom(def)
                true to "Registered ${lang.label} ${kind.name.lowercase()} '$name'"
            }
            "write_app_code" -> {
                val code = args["code"] ?: return logResult(call, false, "Missing code")
                val lang = runCatching {
                    BlockLanguage.valueOf(args["language"].orEmpty().uppercase())
                }.getOrDefault(BlockLanguage.KOTLIN)
                pushUndoSnapshot(current)
                val updatedApp = when (lang) {
                    BlockLanguage.KOTLIN -> current.appDesign.copy(
                        buildLanguage = com.example.appbuilder.AppBuildLanguage.KOTLIN_COMPOSE,
                        customKotlinCode = code
                    )
                    BlockLanguage.HTML5 -> current.appDesign.copy(
                        buildLanguage = com.example.appbuilder.AppBuildLanguage.HTML5_JS,
                        customHtml5Code = code
                    )
                    BlockLanguage.JAVASCRIPT -> current.appDesign.copy(
                        buildLanguage = com.example.appbuilder.AppBuildLanguage.HTML5_JS,
                        customJsCode = code
                    )
                    BlockLanguage.CPP -> current.appDesign.copy(customCppCode = code)
                    BlockLanguage.PYTHON -> current.appDesign.copy(customPythonCode = code)
                    else -> current.appDesign.copy(
                        buildLanguage = com.example.appbuilder.AppBuildLanguage.STUDIOLANG
                    )
                }
                val nextSource = if (lang == BlockLanguage.STUDIOLANG) code else current.sourceCode
                onUpdate(current.copy(appDesign = updatedApp, sourceCode = nextSource))
                true to "Updated Custom App Builder with ${lang.label} source code"
            }
            "create_screen" -> {
                val name = args["name"] ?: return logResult(call, false, "Missing screen name")
                pushUndoSnapshot(current)
                val screen = AppScreenDesign("scr_${System.currentTimeMillis() % 10000}", name)
                onUpdate(current.copy(appDesign = current.appDesign.copy(screens = current.appDesign.screens + screen)))
                true to "Created screen '$name'"
            }
            "add_ui_element" -> {
                val screenName = args["screenName"] ?: current.appDesign.screens.firstOrNull()?.name ?: "Home"
                val kind = runCatching { UiElementKind.valueOf((args["kind"] ?: "TEXT").uppercase()) }.getOrDefault(UiElementKind.TEXT)
                val label = args["label"] ?: "Item"
                pushUndoSnapshot(current)
                val updatedScreens = current.appDesign.screens.map { s ->
                    if (s.name.equals(screenName, ignoreCase = true)) {
                        s.copy(elements = s.elements + UiElementDesign("el_${System.currentTimeMillis() % 10000}", kind, label, args["stateKey"].orEmpty(), args["onTapCode"].orEmpty()))
                    } else s
                }
                onUpdate(current.copy(appDesign = current.appDesign.copy(screens = updatedScreens)))
                true to "Added ${kind.label} '$label' to $screenName"
            }
            "write_source" -> {
                val code = args["code"] ?: return logResult(call, false, "Missing code")
                pushUndoSnapshot(current)
                val newGraph = GraphAstConverter.sourceToGraph(code, current.graph)
                onUpdate(current.copy(sourceCode = code, graph = newGraph))
                true to "Updated StudioLang source & synced NodeGraph (${newGraph.nodes.size} nodes)"
            }
            "apply_patch" -> {
                val find = args["find"] ?: return logResult(call, false, "Missing find")
                val replace = args["replace"] ?: ""
                if (!current.sourceCode.contains(find)) return logResult(call, false, "Patch target not found")
                pushUndoSnapshot(current)
                val updated = current.sourceCode.replaceFirst(find, replace)
                onUpdate(current.copy(sourceCode = updated, graph = GraphAstConverter.sourceToGraph(updated, current.graph)))
                true to "Applied patch to source"
            }
            "read_file" -> {
                if (!capabilityManager.isGranted(StudioCapability.FILE_ACCESS)) {
                    return logResult(call, false, "FILE_ACCESS capability not granted")
                }
                val path = args["path"] ?: return logResult(call, false, "Missing path")
                val f = SafePathValidator.resolveInsideWorkspace(workspaceRoot, path)
                if (!f.exists()) false to "File not found: $path"
                else true to AttachmentValidator.redactSecretsOnly(f.readText().take(4000))
            }
            "list_files" -> {
                if (!capabilityManager.isGranted(StudioCapability.FILE_ACCESS)) {
                    return logResult(call, false, "FILE_ACCESS capability not granted")
                }
                val sub = args["path"].orEmpty()
                val dir = SafePathValidator.resolveInsideWorkspace(workspaceRoot, sub)
                val items = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
                true to items.joinToString(", ")
            }
            "validate", "compile" -> {
                val res = compiler.compile(current.sourceCode, useCache = false)
                if (res.success) true to "Compiled & verified (${res.bytecode?.instructions?.size ?: 0} instructions, ${res.compileTimeUs} us)"
                else false to res.diagnostics.joinToString("; ") { it.format() }
            }
            "ask_user" -> {
                val q = args["question"] ?: "Need your confirmation"
                onAskUser(q)
                true to "Asked user: $q"
            }
            "request_capability" -> {
                val cap = args["capability"]?.let { StudioCapability.fromId(it) }
                    ?: return logResult(call, false, "Unknown capability")
                onRequestCapability(cap)
                true to "Requested capability review for ${cap.id} (never auto-granted)"
            }
            else -> false to "Unknown or unsupported tool '${call.toolName}'"
        }
        return logResult(call, outcome.first, outcome.second)
    }

    private fun logResult(call: AgentToolCallSpec, success: Boolean, output: String): Pair<Boolean, String> {
        val redactedArgs = AttachmentValidator.redactSecretsOnly(call.args.toString())
        val entry = AgentToolLogEntry(
            id = "tl_${System.currentTimeMillis()}_${_actionLog.value.size}",
            timestampMs = System.currentTimeMillis(),
            toolName = call.toolName,
            argsRedacted = redactedArgs,
            success = success,
            output = AttachmentValidator.redactSecretsOnly(output)
        )
        _actionLog.value = (listOf(entry) + _actionLog.value).take(200)
        return success to output
    }
}
