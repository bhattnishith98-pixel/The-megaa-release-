package com.example.ide

/**
 * Live runtime bridge allowing StudioLang (`godot.*`, `ide.*`, `acs.*`, `voice.*`)
 * executed inside StudioVm to inspect and mutate the active Godot Scene, VS Code workbench tabs,
 * Android Code Studio state, and Voice announcer in real time.
 */
object UnifiedIdeBridge {

    @Volatile
    var activeController: UnifiedIdeController? = null

    fun spawnNode(typeName: String, name: String, x: Float, y: Float): Map<String, Any> {
        val ctrl = activeController
            ?: return mapOf("ok" to false, "error" to "Unified IDE session not initialized")
        val node = ctrl.spawnSceneNode(typeName, name, x, y)
        return mapOf(
            "ok" to true,
            "id" to node.id,
            "name" to node.name,
            "type" to node.type.label,
            "x" to node.x.toDouble(),
            "y" to node.y.toDouble()
        )
    }

    fun moveNode(nameOrId: String, dx: Float, dy: Float): Boolean {
        val ctrl = activeController ?: return false
        return ctrl.moveSceneNodeBy(nameOrId, dx, dy)
    }

    fun setNodeProp(nameOrId: String, key: String, value: String): Boolean {
        val ctrl = activeController ?: return false
        return ctrl.setSceneNodeProperty(nameOrId, key, value)
    }

    fun getNodeProp(nameOrId: String, key: String): String {
        val ctrl = activeController ?: return ""
        return ctrl.getSceneNodeProperty(nameOrId, key)
    }

    fun setScenePlaying(playing: Boolean, paused: Boolean = false): Boolean {
        val ctrl = activeController ?: return false
        if (playing) ctrl.playScene() else if (paused) ctrl.pauseScene() else ctrl.stopScene()
        return true
    }

    fun listSceneNodes(): List<Map<String, Any>> {
        val ctrl = activeController ?: return emptyList()
        return ctrl.sceneNodes.value.map { n ->
            mapOf(
                "id" to n.id,
                "name" to n.name,
                "type" to n.type.label,
                "x" to n.x.toDouble(),
                "y" to n.y.toDouble(),
                "script" to (n.scriptPath ?: "")
            )
        }
    }

    fun exportTscn(): String {
        val ctrl = activeController ?: return ""
        return PolyglotGodotCompiler.serializeTscn("MainScene", ctrl.sceneNodes.value)
    }

    fun openEditorFile(path: String): Boolean {
        val ctrl = activeController ?: return false
        return ctrl.openFile(path)
    }

    fun writeEditorFile(path: String, content: String): Boolean {
        val ctrl = activeController ?: return false
        ctrl.createOrUpdateFile(path, content)
        return true
    }

    fun runTerminal(command: String): String {
        val ctrl = activeController ?: return "Unified IDE unavailable"
        return ctrl.executeTerminalCommand(command)
    }

    fun validateAndroidProject(): Map<String, Any> {
        val ctrl = activeController ?: return mapOf("ok" to false, "errors" to listOf("IDE unavailable"))
        val diags = ctrl.runCompileAndTestSync()
        val errCount = diags.count { it.severity == "ERROR" }
        return mapOf(
            "ok" to (errCount == 0),
            "error_count" to errCount,
            "diagnostics" to diags.map { "${it.filePath}:${it.line} [${it.severity}] ${it.message}" }
        )
    }
}
