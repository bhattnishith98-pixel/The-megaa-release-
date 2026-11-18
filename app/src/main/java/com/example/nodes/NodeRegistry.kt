package com.example.nodes

import com.example.lang.StudioCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data-driven registry for all Node Studio node types + dynamic user-created
 * Custom Triggers, Custom Actions, and Action Blocks.
 */
object NodeRegistry {
    const val TARGET_TRIGGER_COUNT = 500
    const val TARGET_ACTION_COUNT = 400
    const val TARGET_CONSTRAINT_COUNT = 200
    const val TARGET_CONDITION_COUNT = 500

    private val baseBuiltInSpecs: List<NodeTypeSpec> = listOf(
        // ---- TRIGGERS ----
        NodeTypeSpec(
            id = "trigger.manual",
            title = "Manual / Shortcut",
            category = NodeCategory.TRIGGERS,
            summary = "Starts a macro immediately when run by the user or a quick shortcut.",
            inputs = emptyList(),
            outputs = listOf(
                NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("event", "Event", PortDirection.OUT, PortType.MAP)
            ),
            params = listOf(NodeParamSpec("macro_name", "Macro Name", PortType.TEXT, "MyMacro")),
            docSyntax = "macro <Name>:\n    on manual()",
            docExample = "macro QuickPing:\n    on manual()\n    do:\n        log.info(\"Ping!\")"
        ),
        NodeTypeSpec(
            id = "trigger.time",
            title = "Time / Schedule",
            category = NodeCategory.TRIGGERS,
            summary = "Triggers a macro at a scheduled HH:MM time.",
            inputs = emptyList(),
            outputs = listOf(
                NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("time", "At", PortDirection.OUT, PortType.TIME)
            ),
            params = listOf(
                NodeParamSpec("macro_name", "Macro Name", PortType.TEXT, "NightMode"),
                NodeParamSpec("at_time", "Time (HH:MM)", PortType.TEXT, "22:30")
            ),
            docSyntax = "on time.at(\"22:30\")",
            docExample = "macro NightMode:\n    on time.at(\"22:30\")\n    do:\n        log.info(\"Night mode\")"
        ),
        NodeTypeSpec(
            id = "trigger.battery",
            title = "Battery Changed",
            category = NodeCategory.TRIGGERS,
            summary = "Fires on battery level or charging state changes.",
            inputs = emptyList(),
            outputs = listOf(
                NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("level", "Level %", PortDirection.OUT, PortType.NUMBER),
                NodePortSpec("charging", "Charging", PortDirection.OUT, PortType.BOOL)
            ),
            params = listOf(NodeParamSpec("macro_name", "Macro Name", PortType.TEXT, "BatteryGuard")),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO),
            docSyntax = "on battery.changed",
            docExample = "trigger FullCharge:\n    source battery.changed\n    when event.level == 100"
        ),
        NodeTypeSpec(
            id = "trigger.screen_text",
            title = "Screen Text Appears",
            category = NodeCategory.TRIGGERS,
            summary = "Triggers when visible UI text matches via Accessibility.",
            inputs = emptyList(),
            outputs = listOf(
                NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("matches", "Matches", PortDirection.OUT, PortType.LIST)
            ),
            params = listOf(NodeParamSpec("query", "Match Text", PortType.TEXT, "Build")),
            requiredCapabilities = setOf(StudioCapability.ACCESSIBILITY_CONTROL)
        ),

        // ---- CONDITIONS ----
        NodeTypeSpec(
            id = "cond.compare",
            title = "Compare",
            category = NodeCategory.CONDITIONS,
            summary = "Compares two values using ==, !=, <, <=, >, >=.",
            inputs = listOf(
                NodePortSpec("a", "A", PortDirection.IN, PortType.ANY),
                NodePortSpec("b", "B", PortDirection.IN, PortType.ANY)
            ),
            outputs = listOf(NodePortSpec("result", "Result", PortDirection.OUT, PortType.BOOL)),
            params = listOf(
                NodeParamSpec("op", "Operator", PortType.TEXT, "==", listOf("==", "!=", "<", "<=", ">", ">=")),
                NodeParamSpec("a_val", "Default A", PortType.TEXT, "battery.level"),
                NodeParamSpec("b_val", "Default B", PortType.TEXT, "15")
            )
        ),
        NodeTypeSpec(
            id = "cond.battery_range",
            title = "Battery Above",
            category = NodeCategory.CONDITIONS,
            summary = "Returns true when device battery percentage exceeds threshold.",
            inputs = emptyList(),
            outputs = listOf(NodePortSpec("ok", "Ok", PortDirection.OUT, PortType.BOOL)),
            params = listOf(NodeParamSpec("min_pct", "Min %", PortType.NUMBER, "15")),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO)
        ),
        NodeTypeSpec(
            id = "cond.network",
            title = "Network Connected?",
            category = NodeCategory.CONDITIONS,
            summary = "Checks if device has active internet or Wi-Fi connectivity.",
            inputs = emptyList(),
            outputs = listOf(NodePortSpec("connected", "Connected", PortDirection.OUT, PortType.BOOL)),
            params = listOf(NodeParamSpec("wifi_only", "Wi-Fi Only", PortType.BOOL, "false")),
            requiredCapabilities = setOf(StudioCapability.NETWORK_ACCESS)
        ),
        NodeTypeSpec(
            id = "cond.screen_contains",
            title = "Screen Contains Text",
            category = NodeCategory.CONDITIONS,
            summary = "Checks if visible foreground screen contains specified text.",
            inputs = listOf(NodePortSpec("query", "Query", PortDirection.IN, PortType.TEXT)),
            outputs = listOf(NodePortSpec("found", "Found", PortDirection.OUT, PortType.BOOL)),
            params = listOf(NodeParamSpec("query", "Text", PortType.TEXT, "Export")),
            requiredCapabilities = setOf(StudioCapability.ACCESSIBILITY_CONTROL)
        ),

        // ---- ACTIONS ----
        NodeTypeSpec(
            id = "action.log",
            title = "Log Message",
            category = NodeCategory.ACTIONS,
            summary = "Writes an info/warn/error entry to the Studio runtime log.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("msg", "Message", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("level", "Level", PortType.TEXT, "info", listOf("info", "warn", "error", "debug")),
                NodeParamSpec("message", "Message", PortType.TEXT, "\"Automation step complete\"")
            ),
            docSyntax = "log.info(\"message\")",
            docExample = "log.info(\"Hello from Automation Studio\")"
        ),
        NodeTypeSpec(
            id = "action.notify",
            title = "Show Notification",
            category = NodeCategory.ACTIONS,
            summary = "Posts an Android notification (requires NOTIFICATIONS & POST_NOTIFICATIONS).",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("title", "Title", PortDirection.IN, PortType.TEXT),
                NodePortSpec("body", "Body", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("title", "Title", PortType.TEXT, "\"Automation Studio\""),
                NodeParamSpec("body", "Body", PortType.TEXT, "\"Macro completed\"")
            ),
            requiredCapabilities = setOf(StudioCapability.NOTIFICATIONS),
            minApiLevel = 24
        ),
        NodeTypeSpec(
            id = "action.vibrate",
            title = "Vibrate",
            category = NodeCategory.ACTIONS,
            summary = "Triggers a short haptic pulse on the device.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("ms", "Duration (ms)", PortType.NUMBER, "120")),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO)
        ),
        NodeTypeSpec(
            id = "action.tap",
            title = "Accessibility Tap",
            category = NodeCategory.ACTIONS,
            summary = "Taps screen coordinates (x, y) via StudioAccessibilityService.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("x", "X", PortDirection.IN, PortType.NUMBER),
                NodePortSpec("y", "Y", PortDirection.IN, PortType.NUMBER)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("ok", "Success", PortDirection.OUT, PortType.BOOL)
            ),
            params = listOf(
                NodeParamSpec("x", "X", PortType.NUMBER, "540"),
                NodeParamSpec("y", "Y", PortType.NUMBER, "960")
            ),
            requiredCapabilities = setOf(StudioCapability.ACCESSIBILITY_CONTROL)
        ),
        NodeTypeSpec(
            id = "action.type_text",
            title = "Accessibility Type",
            category = NodeCategory.ACTIONS,
            summary = "Types text into the currently focused editable field.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("text", "Text", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("text", "Text", PortType.TEXT, "\"Hello\"")),
            requiredCapabilities = setOf(StudioCapability.ACCESSIBILITY_CONTROL)
        ),
        NodeTypeSpec(
            id = "action.screenshot",
            title = "Capture Screen Frame",
            category = NodeCategory.ACTIONS,
            summary = "Captures a visible screen frame using active MediaProjection.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("frame", "Frame Info", PortDirection.OUT, PortType.MAP)
            ),
            requiredCapabilities = setOf(StudioCapability.SCREEN_CAPTURE)
        ),
        NodeTypeSpec(
            id = "action.http_get",
            title = "HTTP GET",
            category = NodeCategory.ACTIONS,
            summary = "Performs an HTTPS/HTTP GET request and returns status/body.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("url", "URL", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("response", "Response", PortDirection.OUT, PortType.MAP)
            ),
            params = listOf(NodeParamSpec("url", "URL", PortType.TEXT, "\"https://httpbin.org/get\"")),
            requiredCapabilities = setOf(StudioCapability.NETWORK_ACCESS)
        ),
        NodeTypeSpec(
            id = "action.clipboard_set",
            title = "Copy to Clipboard",
            category = NodeCategory.ACTIONS,
            summary = "Writes text to Android system clipboard.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("text", "Text", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("text", "Text", PortType.TEXT, "\"Copied from Studio\"")),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO)
        ),
        NodeTypeSpec(
            id = "action.clipboard_get",
            title = "Read Clipboard",
            category = NodeCategory.ACTIONS,
            summary = "Reads the latest plain text item from system clipboard.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("text", "Clipboard", PortDirection.OUT, PortType.TEXT)
            ),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO)
        ),
        NodeTypeSpec(
            id = "settings.open",
            title = "Open Settings Panel",
            category = NodeCategory.ACTIONS,
            summary = "Opens Android Wi-Fi, Bluetooth, Display, Battery, or Accessibility settings.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("panel", "Panel", PortType.TEXT, "\"wifi\"", listOf("\"wifi\"", "\"bluetooth\"", "\"display\"", "\"battery\"", "\"accessibility\"", "\"developer\""))
            ),
            requiredCapabilities = setOf(StudioCapability.SYSTEM_INFO)
        ),
        NodeTypeSpec(
            id = "shizuku.exec",
            title = "Shizuku Shell Command",
            category = NodeCategory.ACTIONS,
            summary = "Runs ADB-level Android controls via Shizuku (requires SHIZUKU_PRIVILEGED + confirm).",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("cmd", "Command", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("result", "Result", PortDirection.OUT, PortType.MAP)
            ),
            params = listOf(NodeParamSpec("cmd", "Shell Cmd", PortType.TEXT, "\"settings get global airplane_mode_on\"")),
            requiredCapabilities = setOf(StudioCapability.SHIZUKU_PRIVILEGED)
        ),
        NodeTypeSpec(
            id = "root.exec",
            title = "Root Superuser (su)",
            category = NodeCategory.ACTIONS,
            summary = "Runs superuser system edits via `su` (requires ROOT_SUPERUSER + confirm).",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("cmd", "Command", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("result", "Result", PortDirection.OUT, PortType.MAP)
            ),
            params = listOf(NodeParamSpec("cmd", "su Command", PortType.TEXT, "\"id\"")),
            requiredCapabilities = setOf(StudioCapability.ROOT_SUPERUSER)
        ),

        // ---- FLOW ----
        NodeTypeSpec(
            id = "flow.if",
            title = "If / Else",
            category = NodeCategory.FLOW,
            summary = "Branches execution based on a boolean condition.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("cond", "Condition", PortDirection.IN, PortType.BOOL)
            ),
            outputs = listOf(
                NodePortSpec("true_out", "True", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("false_out", "False", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("next_out", "After", PortDirection.OUT, PortType.FLOW)
            ),
            params = listOf(NodeParamSpec("expr", "Condition Expr", PortType.TEXT, "true"))
        ),
        NodeTypeSpec(
            id = "flow.repeat",
            title = "Repeat N Times",
            category = NodeCategory.FLOW,
            summary = "Repeats the loop body N times.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("count", "Count", PortDirection.IN, PortType.NUMBER)
            ),
            outputs = listOf(
                NodePortSpec("body_out", "Body", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("next_out", "Done", PortDirection.OUT, PortType.FLOW)
            ),
            params = listOf(NodeParamSpec("count", "Times", PortType.NUMBER, "3"))
        ),
        NodeTypeSpec(
            id = "flow.wait",
            title = "Wait Duration",
            category = NodeCategory.FLOW,
            summary = "Pauses execution safely for a bounded duration.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("duration", "Duration", PortType.DURATION, "500ms"))
        ),
        NodeTypeSpec(
            id = "flow.retry",
            title = "Retry With Wait",
            category = NodeCategory.FLOW,
            summary = "Retries a body block up to N times on error.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("body_out", "Attempt", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("next_out", "After", PortDirection.OUT, PortType.FLOW)
            ),
            params = listOf(
                NodeParamSpec("times", "Times", PortType.NUMBER, "3"),
                NodeParamSpec("wait", "Wait", PortType.DURATION, "1s")
            )
        ),
        NodeTypeSpec(
            id = "flow.confirm",
            title = "Confirm Prompt",
            category = NodeCategory.FLOW,
            summary = "Asks the user for explicit confirmation before destructive actions.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Approved", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("prompt", "Prompt", PortType.TEXT, "\"Proceed with action?\""))
        ),
        NodeTypeSpec(
            id = "flow.return",
            title = "Return Value",
            category = NodeCategory.FLOW,
            summary = "Returns a value from a function or action block.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("value", "Value", PortDirection.IN, PortType.ANY)
            ),
            outputs = emptyList(),
            params = listOf(NodeParamSpec("expr", "Value", PortType.TEXT, "true"))
        ),

        // ---- DATA ----
        NodeTypeSpec(
            id = "data.var_set",
            title = "Set Variable",
            category = NodeCategory.DATA,
            summary = "Declares or updates a variable (`let name = value`).",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("value", "Value", PortDirection.IN, PortType.ANY)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("val_out", "Var", PortDirection.OUT, PortType.ANY)
            ),
            params = listOf(
                NodeParamSpec("name", "Name", PortType.TEXT, "count"),
                NodeParamSpec("value", "Value", PortType.TEXT, "0")
            )
        ),
        NodeTypeSpec(
            id = "data.const_number",
            title = "Number Constant",
            category = NodeCategory.DATA,
            summary = "Outputs a numeric literal.",
            inputs = emptyList(),
            outputs = listOf(NodePortSpec("out", "Number", PortDirection.OUT, PortType.NUMBER)),
            params = listOf(NodeParamSpec("value", "Value", PortType.NUMBER, "42"))
        ),
        NodeTypeSpec(
            id = "data.const_text",
            title = "Text Constant",
            category = NodeCategory.DATA,
            summary = "Outputs a text literal.",
            inputs = emptyList(),
            outputs = listOf(NodePortSpec("out", "Text", PortDirection.OUT, PortType.TEXT)),
            params = listOf(NodeParamSpec("value", "Text", PortType.TEXT, "\"Hello\""))
        ),
        NodeTypeSpec(
            id = "data.math",
            title = "Math Op",
            category = NodeCategory.DATA,
            summary = "Computes +, -, *, /, % on two numbers.",
            inputs = listOf(
                NodePortSpec("a", "A", PortDirection.IN, PortType.NUMBER),
                NodePortSpec("b", "B", PortDirection.IN, PortType.NUMBER)
            ),
            outputs = listOf(NodePortSpec("out", "Result", PortDirection.OUT, PortType.NUMBER)),
            params = listOf(
                NodeParamSpec("op", "Operator", PortType.TEXT, "+", listOf("+", "-", "*", "/", "%")),
                NodeParamSpec("a_val", "A", PortType.NUMBER, "1"),
                NodeParamSpec("b_val", "B", PortType.NUMBER, "2")
            )
        ),

        // ---- FILES ----
        NodeTypeSpec(
            id = "files.write",
            title = "Write File",
            category = NodeCategory.FILES,
            summary = "Writes text to a sandboxed workspace file.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("content", "Content", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("path", "Path", PortType.FILE, "\"notes/log.txt\""),
                NodeParamSpec("content", "Content", PortType.TEXT, "\"Saved by Studio\"")
            ),
            requiredCapabilities = setOf(StudioCapability.FILE_ACCESS)
        ),
        NodeTypeSpec(
            id = "files.read",
            title = "Read File",
            category = NodeCategory.FILES,
            summary = "Reads text from a sandboxed workspace file.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("text", "Text", PortDirection.OUT, PortType.TEXT)
            ),
            params = listOf(NodeParamSpec("path", "Path", PortType.FILE, "\"notes/log.txt\"")),
            requiredCapabilities = setOf(StudioCapability.FILE_ACCESS)
        ),
        NodeTypeSpec(
            id = "files.delete",
            title = "Delete File",
            category = NodeCategory.FILES,
            summary = "Deletes a workspace file (requires `confirm` + FILE_ACCESS).",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("path", "Path", PortType.FILE, "\"temp.txt\"")),
            requiredCapabilities = setOf(StudioCapability.FILE_ACCESS)
        ),

        // ---- PERMISSIONS & CREDENTIALS ----
        NodeTypeSpec(
            id = "perms.check",
            title = "Check Android Permission",
            category = NodeCategory.PERMISSIONS,
            summary = "Checks if a specific Android permission is granted.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("granted", "Granted", PortDirection.OUT, PortType.BOOL)
            ),
            params = listOf(NodeParamSpec("permission", "Permission", PortType.TEXT, "\"android.permission.POST_NOTIFICATIONS\""))
        ),
        NodeTypeSpec(
            id = "creds.store",
            title = "Store Secret",
            category = NodeCategory.CREDENTIALS,
            summary = "Encrypts and stores a secret in Android Keystore.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("key", "Key", PortType.TEXT, "\"api_token\""),
                NodeParamSpec("value", "Secret", PortType.TEXT, "\"secret_value\"")
            ),
            requiredCapabilities = setOf(StudioCapability.CREDENTIAL_MANAGEMENT)
        ),
        NodeTypeSpec(
            id = "creds.get",
            title = "Get Secret (Masked)",
            category = NodeCategory.CREDENTIALS,
            summary = "Retrieves a secret from Android Keystore (masked in logs/UI).",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("secret", "Secret", PortDirection.OUT, PortType.TEXT)
            ),
            params = listOf(NodeParamSpec("key", "Key", PortType.TEXT, "\"api_token\"")),
            requiredCapabilities = setOf(StudioCapability.CREDENTIAL_MANAGEMENT)
        ),

        // ---- AGENT / AI ----
        NodeTypeSpec(
            id = "agent.ask",
            title = "Ask AI Agent",
            category = NodeCategory.AGENT,
            summary = "Queries your selected BYOK AI provider (GPT, Gemini, Claude, or Sarvam AI).",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("prompt", "Prompt", PortDirection.IN, PortType.TEXT)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("reply", "Reply", PortDirection.OUT, PortType.TEXT)
            ),
            params = listOf(NodeParamSpec("prompt", "Prompt", PortType.TEXT, "\"Summarize screen state\"")),
            requiredCapabilities = setOf(StudioCapability.AGENT_AI)
        ),

        // ---- CUSTOM DEFINITIONS & BLOCKS ----
        NodeTypeSpec(
            id = "block.call",
            title = "Call Block / Function",
            category = NodeCategory.BLOCKS,
            summary = "Calls a reusable Action Block or `def` function.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("arg0", "Arg", PortDirection.IN, PortType.ANY)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("result", "Result", PortDirection.OUT, PortType.ANY)
            ),
            params = listOf(
                NodeParamSpec("target", "Block Name", PortType.TEXT, "CleanDownloads"),
                NodeParamSpec("args", "Arguments", PortType.TEXT, "30")
            )
        ),
        NodeTypeSpec(
            id = "polyglot.trigger",
            title = "Polyglot Custom Trigger",
            category = NodeCategory.TRIGGERS,
            summary = "Rule/event trigger defined in JSON, JavaScript, Kotlin, HTML5, Python, or C++.",
            inputs = emptyList(),
            outputs = listOf(
                NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("event", "Event", PortDirection.OUT, PortType.MAP)
            ),
            params = listOf(
                NodeParamSpec("language", "Language", PortType.TEXT, "JSON", listOf("JSON", "JavaScript", "Kotlin", "HTML5", "Python", "C++")),
                NodeParamSpec("name", "Trigger Name", PortType.TEXT, "BatteryHighRule"),
                NodeParamSpec("rule", "Rule / Script", PortType.TEXT, "{\"when\":{\"level_gte\":80}}")
            )
        ),
        NodeTypeSpec(
            id = "polyglot.block",
            title = "Polyglot Action Block",
            category = NodeCategory.BLOCKS,
            summary = "Custom code block written in JSON, JavaScript, HTML5, Kotlin, Python, or C++.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("arg0", "Input", PortDirection.IN, PortType.ANY)
            ),
            outputs = listOf(
                NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW),
                NodePortSpec("result", "Result", PortDirection.OUT, PortType.ANY)
            ),
            params = listOf(
                NodeParamSpec("language", "Language", PortType.TEXT, "JavaScript", listOf("StudioLang", "JSON", "JavaScript", "HTML5", "Kotlin", "Python", "C++")),
                NodeParamSpec("name", "Block Name", PortType.TEXT, "CustomLogic"),
                NodeParamSpec("code", "Code", PortType.TEXT, "function runBlock(input){ return input; }")
            )
        ),

        // ---- APP BUILDING ----
        NodeTypeSpec(
            id = "app.screen",
            title = "App Screen",
            category = NodeCategory.APP_BUILDING,
            summary = "Defines a Compose screen in the visual App Builder.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Elements", PortDirection.OUT, PortType.FLOW)),
            params = listOf(NodeParamSpec("screen_name", "Screen Name", PortType.TEXT, "Home")),
            requiredCapabilities = setOf(StudioCapability.APP_CONFIGURATION)
        ),
        NodeTypeSpec(
            id = "app.ui_element",
            title = "UI Element",
            category = NodeCategory.APP_BUILDING,
            summary = "Adds a Text, Button, Input, Switch, or Card to an App Screen.",
            inputs = listOf(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW)),
            outputs = listOf(NodePortSpec("flow_out", "Next", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("kind", "Widget", PortType.TEXT, "Button", listOf("Text", "Button", "Input", "Switch", "Slider", "Card")),
                NodeParamSpec("label", "Text / Label", PortType.TEXT, "\"Run Action\"")
            ),
            requiredCapabilities = setOf(StudioCapability.APP_CONFIGURATION)
        ),

        // ---- DEBUG ----
        NodeTypeSpec(
            id = "debug.assert",
            title = "Assert Condition",
            category = NodeCategory.DEBUG,
            summary = "Raises a diagnostic error if the condition evaluates to false.",
            inputs = listOf(
                NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW),
                NodePortSpec("cond", "Condition", PortDirection.IN, PortType.BOOL)
            ),
            outputs = listOf(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW)),
            params = listOf(
                NodeParamSpec("cond", "Condition", PortType.TEXT, "true"),
                NodeParamSpec("message", "Failure Msg", PortType.TEXT, "\"Assertion failed\"")
            )
        )
    )

    private val builtInSpecs: List<NodeTypeSpec> by lazy {
        val baseTriggers = baseBuiltInSpecs.count { it.category == NodeCategory.TRIGGERS }
        val baseActions = baseBuiltInSpecs.count { it.category == NodeCategory.ACTIONS }
        val baseConstraints = baseBuiltInSpecs.count { it.category == NodeCategory.CONSTRAINTS }
        val baseConditions = baseBuiltInSpecs.count { it.category == NodeCategory.CONDITIONS }

        val merged = ArrayList<NodeTypeSpec>(
            baseBuiltInSpecs.size + TARGET_TRIGGER_COUNT + TARGET_ACTION_COUNT + TARGET_CONSTRAINT_COUNT + TARGET_CONDITION_COUNT
        )
        merged += baseBuiltInSpecs
        merged += AutomationCatalogTriggers.build((TARGET_TRIGGER_COUNT - baseTriggers).coerceAtLeast(0))
        merged += AutomationCatalogActions.build((TARGET_ACTION_COUNT - baseActions).coerceAtLeast(0))
        merged += AutomationCatalogConstraints.build((TARGET_CONSTRAINT_COUNT - baseConstraints).coerceAtLeast(0))
        merged += AutomationCatalogConditions.build((TARGET_CONDITION_COUNT - baseConditions).coerceAtLeast(0))
        merged
    }

    private val builtInMap: Map<String, NodeTypeSpec> by lazy {
        builtInSpecs.associateBy { it.id }
    }

    private val builtInByCategory: Map<NodeCategory, List<NodeTypeSpec>> by lazy {
        builtInSpecs.groupBy { it.category }
    }

    private val customDefs = linkedMapOf<String, CustomNodeDefinition>()
    private val _customDefinitions = MutableStateFlow<List<CustomNodeDefinition>>(emptyList())
    val customDefinitions: StateFlow<List<CustomNodeDefinition>> = _customDefinitions.asStateFlow()

    fun builtInSpecs(): List<NodeTypeSpec> = builtInSpecs

    fun specsForCategory(category: NodeCategory): List<NodeTypeSpec> =
        builtInByCategory[category].orEmpty() + customDefs.values.map { it.toNodeTypeSpec() }.filter { it.category == category }

    fun builtInCountFor(category: NodeCategory): Int = builtInByCategory[category]?.size ?: 0

    fun allSpecs(): List<NodeTypeSpec> = builtInSpecs + customDefs.values.map { it.toNodeTypeSpec() }

    fun getSpec(typeId: String): NodeTypeSpec? =
        builtInMap[typeId] ?: customDefs[typeId]?.toNodeTypeSpec()

    fun registerCustom(def: CustomNodeDefinition) {
        customDefs[def.id] = def
        _customDefinitions.value = customDefs.values.toList()
    }

    fun unregisterCustom(id: String) {
        customDefs.remove(id)
        _customDefinitions.value = customDefs.values.toList()
    }

    private fun CustomNodeDefinition.toNodeTypeSpec(): NodeTypeSpec {
        val cat = when (kind) {
            CustomDefinitionKind.TRIGGER -> NodeCategory.TRIGGERS
            CustomDefinitionKind.ACTION -> NodeCategory.ACTIONS
            CustomDefinitionKind.BLOCK -> NodeCategory.BLOCKS
        }
        val inPorts = buildList {
            if (kind != CustomDefinitionKind.TRIGGER) {
                add(NodePortSpec("flow_in", "In", PortDirection.IN, PortType.FLOW))
            }
            params.forEach { p ->
                add(NodePortSpec("arg_${p.key}", p.label, PortDirection.IN, p.type))
            }
        }
        val outPorts = buildList {
            add(NodePortSpec("flow_out", "Out", PortDirection.OUT, PortType.FLOW))
            if (returnType != PortType.FLOW) {
                add(NodePortSpec("result", "Result", PortDirection.OUT, returnType))
            }
        }
        return NodeTypeSpec(
            id = id,
            title = "$name [${language.label}]",
            category = cat,
            summary = "[${language.label} · ${language.runtimeMode}] $summary",
            inputs = inPorts,
            outputs = outPorts,
            params = params,
            requiredCapabilities = requiredCapabilities,
            docSyntax = sourceCode.lines().firstOrNull().orEmpty(),
            docExample = sourceCode,
            isCustom = true
        )
    }

    data class WireCheckResult(
        val allowed: Boolean,
        val autoConvert: Boolean = false,
        val reason: String? = null
    )

    fun validateConnection(
        graph: NodeGraph,
        fromNodeId: String,
        fromPortId: String,
        toNodeId: String,
        toPortId: String
    ): WireCheckResult {
        if (fromNodeId == toNodeId) {
            return WireCheckResult(false, reason = "Cannot connect a node to itself.")
        }
        val fromNode = graph.nodes.firstOrNull { it.id == fromNodeId }
            ?: return WireCheckResult(false, reason = "Source node not found.")
        val toNode = graph.nodes.firstOrNull { it.id == toNodeId }
            ?: return WireCheckResult(false, reason = "Target node not found.")
        val fromPort = getSpec(fromNode.typeId)?.outputs?.firstOrNull { it.id == fromPortId }
            ?: return WireCheckResult(false, reason = "Output port '$fromPortId' not found.")
        val toPort = getSpec(toNode.typeId)?.inputs?.firstOrNull { it.id == toPortId }
            ?: return WireCheckResult(false, reason = "Input port '$toPortId' not found.")

        if (fromPort.type == PortType.FLOW && toPort.type != PortType.FLOW) {
            return WireCheckResult(false, reason = "Flow port can only connect to a flow input port.")
        }
        if (fromPort.type != PortType.FLOW && toPort.type == PortType.FLOW) {
            return WireCheckResult(false, reason = "Data port cannot connect to a flow port.")
        }

        if (fromPort.type == PortType.FLOW) {
            if (wouldCreateFlowCycle(graph, fromNodeId, toNodeId)) {
                return WireCheckResult(false, reason = "Flow cycles are disallowed (use Repeat/While loop nodes).")
            }
            return WireCheckResult(true)
        }

        if (fromPort.type == toPort.type || fromPort.type == PortType.ANY || toPort.type == PortType.ANY) {
            return WireCheckResult(true)
        }

        // Safe auto-converters (e.g. number -> text, bool -> text)
        if (toPort.type == PortType.TEXT && fromPort.type in setOf(PortType.NUMBER, PortType.BOOL, PortType.DURATION, PortType.TIME)) {
            return WireCheckResult(true, autoConvert = true, reason = "Auto-converts ${fromPort.type.label} → text")
        }

        return WireCheckResult(false, reason = "Type mismatch: ${fromPort.type.label} cannot connect to ${toPort.type.label}.")
    }

    private fun wouldCreateFlowCycle(graph: NodeGraph, fromId: String, toId: String): Boolean {
        val adj = mutableMapOf<String, MutableList<String>>()
        for (w in graph.wires) {
            val fromNode = graph.nodes.firstOrNull { it.id == w.fromNodeId } ?: continue
            val port = getSpec(fromNode.typeId)?.outputs?.firstOrNull { it.id == w.fromPortId } ?: continue
            if (port.type == PortType.FLOW) {
                adj.getOrPut(w.fromNodeId) { mutableListOf() }.add(w.toNodeId)
            }
        }
        adj.getOrPut(fromId) { mutableListOf() }.add(toId)

        // BFS from toId to see if we reach fromId
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(toId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == fromId) return true
            if (!visited.add(cur)) continue
            adj[cur]?.forEach { queue.addLast(it) }
        }
        return false
    }

    fun validateGraph(graph: NodeGraph, grantedCaps: Set<StudioCapability>): List<NodeValidationIssue> {
        val issues = mutableListOf<NodeValidationIssue>()
        for (node in graph.nodes) {
            val spec = getSpec(node.typeId)
            if (spec == null) {
                issues += NodeValidationIssue(node.id, NodeValidationIssue.IssueSeverity.ERROR, "Unknown node type '${node.typeId}'.")
                continue
            }
            spec.requiredCapabilities.forEach { cap ->
                if (cap !in grantedCaps) {
                    issues += NodeValidationIssue(
                        node.id,
                        NodeValidationIssue.IssueSeverity.CAPABILITY,
                        "Needs capability ${cap.id}",
                        capability = cap
                    )
                }
            }
            if (node.typeId == "files.delete") {
                val hasConfirmBefore = graph.wires.any { w ->
                    w.toNodeId == node.id && graph.nodes.any { it.id == w.fromNodeId && it.typeId == "flow.confirm" }
                }
                if (!hasConfirmBefore) {
                    issues += NodeValidationIssue(
                        node.id,
                        NodeValidationIssue.IssueSeverity.WARNING,
                        "Add a Confirm Prompt node before Delete File."
                    )
                }
            }
        }
        return issues
    }
}
