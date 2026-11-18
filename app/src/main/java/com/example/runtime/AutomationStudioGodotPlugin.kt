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

/**
 * Official Godot 4.7.2.stable Android [GodotPlugin] registered as `AutomationStudioBridge`.
 * GDScript inside GodotRuntimeActivity / GodotEditorActivity can invoke:
 *   var bridge = Engine.get_singleton("AutomationStudioBridge")
 *   bridge.logToIde("Player spawned")
 *   bridge.emitStudioEvent("level_clear", "score=100")
 * Events cross process boundaries cleanly via package-scoped Broadcast Intents.
 */
class AutomationStudioGodotPlugin(godot: Godot) : GodotPlugin(godot) {

    override fun getPluginName(): String = PLUGIN_NAME

    override fun getPluginSignals(): Set<SignalInfo> = setOf(
        SignalInfo("studio_event", String::class.java, String::class.java),
        SignalInfo("ide_command", String::class.java)
    )

    @UsedByGodot
    fun getEngineVersion(): String = "4.7.2.stable"

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
