package com.example.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.io.File

/**
 * Official Godot 4.7.2.stable Android [GodotPlugin] registered as `AutomationStudioBridge`.
 * GDScript inside GodotRuntimeActivity / GodotEditorActivity can invoke:
 *   var bridge = Engine.get_singleton("AutomationStudioBridge")
 *   bridge.logToIde("Player spawned")
 *   bridge.emitStudioEvent("level_clear", "score=100")
 *   bridge.getLaunchMode() / bridge.readWorkspaceFile("scenes/main.tscn")
 * Events cross process boundaries cleanly via package-scoped Broadcast Intents.
 */
class AutomationStudioGodotPlugin(
    godot: Godot,
    private val launchConfigProvider: () -> GodotCommandLineSanitizer.LaunchConfig = {
        GodotCommandLineSanitizer.LaunchConfig(
            mode = GodotCommandLineSanitizer.MODE_DEMO_3D,
            projectPath = "",
            mainScene = ""
        )
    },
    private val onOpenRuntimeRequested: ((projectPath: String, mainScene: String) -> Unit)? = null
) : GodotPlugin(godot) {

    override fun getPluginName(): String = PLUGIN_NAME

    override fun getPluginSignals(): Set<SignalInfo> = setOf(
        SignalInfo("studio_event", String::class.java, String::class.java),
        SignalInfo("ide_command", String::class.java)
    )

    @UsedByGodot
    fun getEngineVersion(): String = "4.7.2.stable"

    @UsedByGodot
    fun getLaunchMode(): String = launchConfigProvider().mode

    @UsedByGodot
    fun getLaunchProjectPath(): String = launchConfigProvider().projectPath

    @UsedByGodot
    fun getLaunchMainScene(): String = launchConfigProvider().mainScene

    @UsedByGodot
    fun listWorkspaceFiles(): String {
        val projectPath = launchConfigProvider().projectPath
        if (projectPath.isBlank()) return ""
        val rootDir = File(projectPath)
        if (!rootDir.isDirectory) return ""
        val canonicalBase = rootDir.canonicalPath
        val skipDirs = setOf(".godot", ".import", ".git", "build")
        val paths = mutableListOf<String>()
        rootDir.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs }
            .filter { it.isFile }
            .forEach { file ->
                val canonical = file.canonicalPath
                if (canonical.startsWith(canonicalBase + File.separator)) {
                    val rel = canonical.removePrefix(canonicalBase + File.separator)
                        .replace('\\', '/')
                    if (rel.isNotBlank()) paths += rel
                }
            }
        return paths.sorted().joinToString("\n")
    }

    @UsedByGodot
    fun readWorkspaceFile(relPath: String): String {
        val target = resolveSafeWorkspaceFile(launchConfigProvider().projectPath, relPath) ?: return ""
        if (!target.isFile) return ""
        return runCatching { target.readText() }.getOrDefault("")
    }

    @UsedByGodot
    fun writeWorkspaceFile(relPath: String, content: String): Boolean {
        val target = resolveSafeWorkspaceFile(launchConfigProvider().projectPath, relPath) ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
            sendBridgeBroadcast("workspace_saved", relPath, "${content.length} bytes")
            true
        }.getOrDefault(false)
    }

    @UsedByGodot
    fun openProjectInRuntime(projectPath: String, mainScene: String) {
        val targetPath = projectPath.ifBlank { launchConfigProvider().projectPath }
        val targetScene = mainScene.ifBlank { launchConfigProvider().mainScene }
        val host = activity
        val handler = onOpenRuntimeRequested
        if (handler != null) {
            if (host != null) {
                host.runOnUiThread { handler(targetPath, targetScene) }
            } else {
                handler(targetPath, targetScene)
            }
        }
    }

    @UsedByGodot
    fun logToIde(message: String) {
        sendBridgeBroadcast("log", "GodotLog", message)
    }

    @UsedByGodot
    fun emitStudioEvent(eventName: String, payload: String) {
        sendBridgeBroadcast("event", eventName, payload)
        emitSignal("studio_event", eventName, payload)
    }

    @UsedByGodot
    fun runStudioLang(code: String): String {
        sendBridgeBroadcast("studiolang", "RunStudioLang", code)
        return "Dispatched StudioLang snippet (${code.length} chars) to Automation Studio"
    }

    fun dispatchIdeCommand(command: String) {
        emitSignal("ide_command", command)
        sendBridgeBroadcast("ide_command", "OverlayAction", command)
    }

    private fun sendBridgeBroadcast(type: String, name: String, payload: String) {
        val ctx = activity?.applicationContext ?: context ?: return
        val intent = Intent(ACTION_GODOT_BRIDGE_EVENT).apply {
            setPackage(ctx.packageName)
            putExtra(EXTRA_EVENT_TYPE, type)
            putExtra(EXTRA_EVENT_NAME, name)
            putExtra(EXTRA_EVENT_PAYLOAD, payload)
        }
        runCatching { ctx.sendBroadcast(intent) }
    }

    companion object {
        const val PLUGIN_NAME = "AutomationStudioBridge"
        const val ACTION_GODOT_BRIDGE_EVENT = "com.example.action.GODOT_BRIDGE_EVENT"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_EVENT_PAYLOAD = "event_payload"

        fun resolveSafeWorkspaceFile(projectPath: String, relPath: String): File? {
            if (projectPath.isBlank() || relPath.isBlank()) return null
            val root = runCatching { File(projectPath).canonicalFile }.getOrNull() ?: return null
            val cleanRel = relPath.removePrefix("res://").trimStart('/', '\\')
            if (cleanRel.isBlank()) return null
            val target = runCatching { File(root, cleanRel).canonicalFile }.getOrNull() ?: return null
            return if (target.path.startsWith(root.path + File.separator)) target else null
        }
    }
}

/**
 * Receives cross-process events emitted by [AutomationStudioGodotPlugin] inside `:godot_runtime`
 * or `:godot_editor` and delivers them to the main Automation Studio IDE process.
 */
class GodotBridgeReceiver(
    private val onBridgeEvent: (type: String, name: String, payload: String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != AutomationStudioGodotPlugin.ACTION_GODOT_BRIDGE_EVENT) return
        val type = intent.getStringExtra(AutomationStudioGodotPlugin.EXTRA_EVENT_TYPE).orEmpty()
        val name = intent.getStringExtra(AutomationStudioGodotPlugin.EXTRA_EVENT_NAME).orEmpty()
        val payload = intent.getStringExtra(AutomationStudioGodotPlugin.EXTRA_EVENT_PAYLOAD).orEmpty()
        onBridgeEvent(type, name, payload)
    }

    companion object {
        fun register(
            context: Context,
            onBridgeEvent: (type: String, name: String, payload: String) -> Unit
        ): GodotBridgeReceiver {
            val receiver = GodotBridgeReceiver(onBridgeEvent)
            val filter = IntentFilter(AutomationStudioGodotPlugin.ACTION_GODOT_BRIDGE_EVENT)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            return receiver
        }
    }
}
