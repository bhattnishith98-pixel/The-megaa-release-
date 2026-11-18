package com.example.nodes

import com.example.lang.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sidecar layout persistence (`Name.stl.layout.json`) so visual coordinates,
 * comments, and color tags stay out of `.stl` text diffs.
 */
object LayoutSidecarSerializer {
    fun toJson(graph: NodeGraph): String {
        val root = JSONObject()
        root.put("schemaVersion", graph.schemaVersion)
        root.put("name", graph.name)
        val nodesArr = JSONArray()
        graph.nodes.forEach { n ->
            nodesArr.put(JSONObject().apply {
                put("id", n.id)
                put("typeId", n.typeId)
                put("label", n.label)
                put("x", n.x.toDouble())
                put("y", n.y.toDouble())
                put("collapsed", n.collapsed)
                put("hasBreakpoint", n.hasBreakpoint)
                n.colorTagHex?.let { put("colorTagHex", it) }
                val pObj = JSONObject()
                n.params.forEach { (k, v) -> pObj.put(k, v) }
                put("params", pObj)
            })
        }
        root.put("nodes", nodesArr)

        val wiresArr = JSONArray()
        graph.wires.forEach { w ->
            wiresArr.put(JSONObject().apply {
                put("id", w.id)
                put("fromNodeId", w.fromNodeId)
                put("fromPortId", w.fromPortId)
                put("toNodeId", w.toNodeId)
                put("toPortId", w.toPortId)
                put("autoConverted", w.autoConverted)
            })
        }
        root.put("wires", wiresArr)
        return root.toString(2)
    }

    fun fromJson(raw: String): NodeGraph {
        val root = JSONObject(raw)
        val version = root.optInt("schemaVersion", 1)
        val name = root.optString("name", "Workflow")
        val nodes = mutableListOf<GraphNode>()
        val nArr = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nArr.length()) {
            val o = nArr.getJSONObject(i)
            val params = mutableMapOf<String, String>()
            val pObj = o.optJSONObject("params")
            pObj?.keys()?.forEach { k -> params[k] = pObj.optString(k) }
            nodes += GraphNode(
                id = o.getString("id"),
                typeId = o.getString("typeId"),
                label = o.optString("label", o.getString("typeId")),
                x = o.optDouble("x", 80.0).toFloat(),
                y = o.optDouble("y", 80.0).toFloat(),
                params = params,
                colorTagHex = if (o.has("colorTagHex")) o.getLong("colorTagHex") else null,
                collapsed = o.optBoolean("collapsed", false),
                hasBreakpoint = o.optBoolean("hasBreakpoint", false)
            )
        }
        val wires = mutableListOf<GraphWire>()
        val wArr = root.optJSONArray("wires") ?: JSONArray()
        for (i in 0 until wArr.length()) {
            val o = wArr.getJSONObject(i)
            wires += GraphWire(
                id = o.getString("id"),
                fromNodeId = o.getString("fromNodeId"),
                fromPortId = o.getString("fromPortId"),
                toNodeId = o.getString("toNodeId"),
                toPortId = o.getString("toPortId"),
                autoConverted = o.optBoolean("autoConverted", false)
            )
        }
        return NodeGraph(schemaVersion = version, name = name, nodes = nodes, wires = wires)
    }
}

/**
 * Bidirectional lossless translator between NodeGraph and StudioLang AST / source text.
 * Graph -> AST -> Source Text
 * Source Text -> AST -> Graph (preserving coordinates from existing layout when available)
 */
object GraphAstConverter {

    fun graphToSource(graph: NodeGraph): String {
        if (graph.nodes.isEmpty()) return "# Empty StudioLang workflow\n"

        val requiredCaps = linkedSetOf<StudioCapability>()
        graph.nodes.forEach { n ->
            NodeRegistry.getSpec(n.typeId)?.requiredCapabilities?.let { requiredCaps += it }
        }

        val sb = StringBuilder()
        if (requiredCaps.isNotEmpty()) {
            sb.append("needs ").append(requiredCaps.joinToString(", ") { it.id }).append('\n')
        }

        val triggers = graph.nodes.filter {
            NodeRegistry.getSpec(it.typeId)?.category == NodeCategory.TRIGGERS
        }

        val visited = mutableSetOf<String>()

        if (triggers.isNotEmpty()) {
            triggers.forEach { trig ->
                val macroName = trig.params["macro_name"]?.takeIf { it.isNotBlank() }
                    ?: trig.params["name"]?.takeIf { it.isNotBlank() }
                    ?: "Workflow"
                val onClause = when (trig.typeId) {
                    "trigger.time" -> "time.at(\"${trig.params["at_time"] ?: "22:30"}\")"
                    "trigger.battery" -> "battery.changed"
                    "trigger.screen_text" -> "screen.find_text(\"${trig.params["query"] ?: ""}\")"
                    "polyglot.trigger" -> "manual()"
                    else -> "manual()"
                }
                sb.append("macro ").append(sanitizeIdent(macroName)).append(":\n")
                sb.append("    on ").append(onClause).append('\n')
                sb.append("    do:\n")
                if (trig.typeId == "polyglot.trigger") {
                    val lang = trig.params["language"] ?: "JSON"
                    val tName = trig.params["name"] ?: "Rule"
                    sb.append("        log.info(\"Polyglot trigger [$lang]: $tName\")\n")
                }
                emitFlowChain(graph, trig.id, "flow_out", 2, sb, visited)
                sb.append('\n')
            }
        }

        // Any disconnected flow roots (non-trigger nodes with no incoming flow wire)
        val hasIncomingFlow = graph.wires.filter { w ->
            val targetSpec = graph.nodes.firstOrNull { it.id == w.toNodeId }?.let { NodeRegistry.getSpec(it.typeId) }
            targetSpec?.inputs?.any { it.id == w.toPortId && it.type == PortType.FLOW } == true
        }.map { it.toNodeId }.toSet()

        val looseRoots = graph.nodes.filter {
            it.id !in visited &&
                it.id !in hasIncomingFlow &&
                NodeRegistry.getSpec(it.typeId)?.inputs?.any { p -> p.type == PortType.FLOW } == true
        }
        looseRoots.forEach { root ->
            emitFlowNode(graph, root, 0, sb, visited)
        }

        return sb.toString().trimEnd() + "\n"
    }

    private fun emitFlowChain(
        graph: NodeGraph,
        fromNodeId: String,
        fromPortId: String,
        indentLevel: Int,
        sb: StringBuilder,
        visited: MutableSet<String>
    ) {
        val nextWires = graph.wires.filter { it.fromNodeId == fromNodeId && it.fromPortId == fromPortId }
        for (w in nextWires) {
            val target = graph.nodes.firstOrNull { it.id == w.toNodeId } ?: continue
            emitFlowNode(graph, target, indentLevel, sb, visited)
        }
    }

    private fun emitFlowNode(
        graph: NodeGraph,
        node: GraphNode,
        indentLevel: Int,
        sb: StringBuilder,
        visited: MutableSet<String>
    ) {
        if (!visited.add(node.id)) return
        val pad = "    ".repeat(indentLevel)

        fun resolveData(portId: String, fallbackParam: String): String {
            val wire = graph.wires.firstOrNull { it.toNodeId == node.id && it.toPortId == portId }
            if (wire != null) {
                val src = graph.nodes.firstOrNull { it.id == wire.fromNodeId }
                if (src != null) {
                    return exprForDataNode(graph, src, wire.fromPortId)
                }
            }
            return node.params[fallbackParam] ?: "none"
        }

        when (node.typeId) {
            "action.log" -> {
                val lvl = node.params["level"] ?: "info"
                val msg = ensureQuotedOrExpr(resolveData("msg", "message"))
                sb.append("${pad}log.$lvl($msg)\n")
            }
            "action.notify" -> {
                val t = ensureQuotedOrExpr(resolveData("title", "title"))
                val b = ensureQuotedOrExpr(resolveData("body", "body"))
                sb.append("${pad}notify.show($t, $b)\n")
            }
            "action.vibrate" -> sb.append("${pad}device.vibrate(${node.params["ms"] ?: "120"})\n")
            "action.tap" -> sb.append("${pad}screen.tap(${resolveData("x", "x")}, ${resolveData("y", "y")})\n")
            "action.type_text" -> sb.append("${pad}screen.type_text(${ensureQuotedOrExpr(resolveData("text", "text"))})\n")
            "action.screenshot" -> sb.append("${pad}let frame = screen.capture_frame()\n")
            "action.http_get" -> sb.append("${pad}let resp = net.http_get(${ensureQuotedOrExpr(resolveData("url", "url"))})\n")
            "action.clipboard_set" -> sb.append("${pad}clipboard.set(${ensureQuotedOrExpr(resolveData("text", "text"))})\n")
            "action.clipboard_get" -> sb.append("${pad}let clip = clipboard.get()\n")
            "settings.open" -> sb.append("${pad}settings.open(${ensureQuotedOrExpr(node.params["panel"] ?: "\"wifi\"")})\n")
            "shizuku.exec" -> sb.append("${pad}let shizuku_out = shizuku.exec(${ensureQuotedOrExpr(resolveData("cmd", "cmd"))})\n")
            "root.exec" -> sb.append("${pad}let root_out = root.exec(${ensureQuotedOrExpr(resolveData("cmd", "cmd"))})\n")
            "data.var_set" -> {
                val name = sanitizeIdent(node.params["name"] ?: "x")
                val v = resolveData("value", "value")
                sb.append("${pad}let $name = $v\n")
            }
            "files.write" -> sb.append("${pad}files.write(${ensureQuotedOrExpr(node.params["path"] ?: "\"out.txt\"")}, ${ensureQuotedOrExpr(resolveData("content", "content"))})\n")
            "files.read" -> sb.append("${pad}let file_text = files.read(${ensureQuotedOrExpr(node.params["path"] ?: "\"out.txt\"")})\n")
            "files.delete" -> sb.append("${pad}files.delete(${ensureQuotedOrExpr(node.params["path"] ?: "\"temp.txt\"")})\n")
            "perms.check" -> sb.append("${pad}let granted = perms.check(${ensureQuotedOrExpr(node.params["permission"] ?: "\"android.permission.POST_NOTIFICATIONS\"")})\n")
            "creds.store" -> sb.append("${pad}creds.store(${ensureQuotedOrExpr(node.params["key"] ?: "\"token\"")}, ${ensureQuotedOrExpr(node.params["value"] ?: "\"\"")})\n")
            "creds.get" -> sb.append("${pad}let secret_val = creds.get(${ensureQuotedOrExpr(node.params["key"] ?: "\"token\"")})\n")
            "agent.ask" -> sb.append("${pad}let ai_reply = agent.ask(${ensureQuotedOrExpr(resolveData("prompt", "prompt"))})\n")
            "block.call" -> {
                val target = sanitizeIdent(node.params["target"] ?: "MyBlock")
                val args = node.params["args"].orEmpty()
                sb.append("${pad}$target($args)\n")
            }
            "debug.assert" -> {
                val cond = resolveData("cond", "cond")
                val msg = ensureQuotedOrExpr(node.params["message"] ?: "\"Assertion failed\"")
                sb.append("${pad}if not ($cond):\n${pad}    raise $msg\n")
            }
            "flow.wait" -> sb.append("${pad}time.sleep(${node.params["duration"] ?: "500ms"})\n")
            "flow.confirm" -> sb.append("${pad}confirm ${ensureQuotedOrExpr(node.params["prompt"] ?: "\"Proceed?\"")}\n")
            "flow.return" -> sb.append("${pad}return ${resolveData("value", "expr")}\n")
            "flow.if" -> {
                val cond = resolveData("cond", "expr")
                sb.append("${pad}if $cond:\n")
                val beforeLen = sb.length
                emitFlowChain(graph, node.id, "true_out", indentLevel + 1, sb, visited)
                if (sb.length == beforeLen) sb.append("${pad}    none\n")
                val hasElse = graph.wires.any { it.fromNodeId == node.id && it.fromPortId == "false_out" }
                if (hasElse) {
                    sb.append("${pad}else:\n")
                    emitFlowChain(graph, node.id, "false_out", indentLevel + 1, sb, visited)
                }
                emitFlowChain(graph, node.id, "next_out", indentLevel, sb, visited)
                return
            }
            "flow.repeat" -> {
                val times = resolveData("count", "count")
                sb.append("${pad}repeat $times times:\n")
                val beforeLen = sb.length
                emitFlowChain(graph, node.id, "body_out", indentLevel + 1, sb, visited)
                if (sb.length == beforeLen) sb.append("${pad}    none\n")
                emitFlowChain(graph, node.id, "next_out", indentLevel, sb, visited)
                return
            }
            "flow.retry" -> {
                val times = node.params["times"] ?: "3"
                val wait = node.params["wait"] ?: "1s"
                sb.append("${pad}retry $times times, wait $wait:\n")
                val beforeLen = sb.length
                emitFlowChain(graph, node.id, "body_out", indentLevel + 1, sb, visited)
                if (sb.length == beforeLen) sb.append("${pad}    none\n")
                emitFlowChain(graph, node.id, "next_out", indentLevel, sb, visited)
                return
            }
            "polyglot.block" -> {
                val lang = node.params["language"] ?: "JavaScript"
                val bName = node.params["name"] ?: "CustomLogic"
                sb.append("${pad}log.info(\"Polyglot block [$lang]: $bName\")\n")
            }
            "action.mobile_wifi_on" -> sb.append("${pad}mobile.set_wifi(true)\n")
            "action.mobile_wifi_off" -> sb.append("${pad}mobile.set_wifi(false)\n")
            "action.mobile_data_on" -> sb.append("${pad}mobile.set_data(true)\n")
            "action.mobile_data_off" -> sb.append("${pad}mobile.set_data(false)\n")
            "action.mobile_bt_on" -> sb.append("${pad}mobile.set_bluetooth(true)\n")
            "action.mobile_bt_off" -> sb.append("${pad}mobile.set_bluetooth(false)\n")
            "action.mobile_flash_on" -> sb.append("${pad}mobile.set_flashlight(true)\n")
            "action.mobile_flash_off" -> sb.append("${pad}mobile.set_flashlight(false)\n")
            "action.mobile_airplane_on" -> sb.append("${pad}mobile.set_airplane_mode(true)\n")
            "action.mobile_airplane_off" -> sb.append("${pad}mobile.set_airplane_mode(false)\n")
            "action.mobile_hotspot_on" -> sb.append("${pad}mobile.set_hotspot(true)\n")
            "action.mobile_hotspot_off" -> sb.append("${pad}mobile.set_hotspot(false)\n")
            "action.mobile_dnd_on" -> sb.append("${pad}mobile.set_dnd(true)\n")
            "action.mobile_dnd_off" -> sb.append("${pad}mobile.set_dnd(false)\n")
            "action.mobile_rotate_on" -> sb.append("${pad}mobile.set_auto_rotate(true)\n")
            "action.mobile_rotate_off" -> sb.append("${pad}mobile.set_auto_rotate(false)\n")
            "action.mobile_screen_on" -> sb.append("${pad}mobile.set_screen(true)\n")
            "action.mobile_screen_off" -> sb.append("${pad}mobile.set_screen(false)\n")
            "action.mobile_power_dialog" -> sb.append("${pad}mobile.power_action(\"power_dialog\")\n")
            "action.mobile_reboot" -> sb.append("${pad}mobile.power_action(\"reboot\")\n")
            "action.mobile_shutdown" -> sb.append("${pad}mobile.power_action(\"shutdown\")\n")
            "action.mobile_reboot_recovery" -> sb.append("${pad}mobile.power_action(\"recovery\")\n")
            "action.touch_custom", "action.touch_long_press", "action.touch_double_tap", "action.touch_custom_swipe" -> {
                val nums = (node.params["coords"] ?: "").replace("\"", "").split(",").mapNotNull { it.trim().toIntOrNull() }
                when (node.typeId) {
                    "action.touch_custom" -> {
                        val x = nums.getOrElse(0) { 540 }
                        val y = nums.getOrElse(1) { 960 }
                        val hold = nums.getOrElse(2) { 120 }
                        val reps = nums.getOrElse(3) { 1 }
                        sb.append("${pad}screen.custom_touch($x, $y, $hold, $reps)\n")
                    }
                    "action.touch_long_press" -> {
                        val x = nums.getOrElse(0) { 540 }
                        val y = nums.getOrElse(1) { 960 }
                        val hold = nums.getOrElse(2) { 750 }
                        sb.append("${pad}screen.long_press($x, $y, $hold)\n")
                    }
                    "action.touch_double_tap" -> {
                        val x = nums.getOrElse(0) { 540 }
                        val y = nums.getOrElse(1) { 960 }
                        sb.append("${pad}screen.double_tap($x, $y)\n")
                    }
                    else -> {
                        val x1 = nums.getOrElse(0) { 540 }
                        val y1 = nums.getOrElse(1) { 1500 }
                        val x2 = nums.getOrElse(2) { 540 }
                        val y2 = nums.getOrElse(3) { 400 }
                        val dur = nums.getOrElse(4) { 320 }
                        sb.append("${pad}screen.custom_swipe($x1, $y1, $x2, $y2, $dur)\n")
                    }
                }
            }
            "action.touch_gesture_path" -> sb.append("${pad}screen.gesture_path(${ensureQuotedOrExpr(node.params["coords"] ?: "\"300,900;540,600;800,900\"")}, 450)\n")
            "action.touch_click_text" -> sb.append("${pad}screen.click_text(${ensureQuotedOrExpr(node.params["coords"] ?: "\"Continue\"")})\n")
            "action.pin_set_code" -> sb.append("${pad}pin.set_code(${ensureQuotedOrExpr(node.params["pin"] ?: "\"1234\"")})\n")
            "action.pin_verify" -> sb.append("${pad}let pin_ok = pin.verify(${ensureQuotedOrExpr(node.params["pin"] ?: "\"1234\"")})\n")
            "action.pin_lock_now" -> sb.append("${pad}pin.lock_now()\n")
            "action.pin_check_state" -> sb.append("${pad}let pin_locked = pin.is_locked()\n")
            "action.sms_open_composer" -> sb.append("${pad}sms.open_composer(\"\", ${ensureQuotedOrExpr(node.params["sms_arg"] ?: "\"Hello from Studio\"")})\n")
            "action.sms_extract_otp" -> sb.append("${pad}let sms_otp = sms.extract_otp()\n")
            "action.sms_copy_last" -> sb.append("${pad}let last_sms = sms.last_received()\n")
            "action.sms_open_access" -> sb.append("${pad}sms.open_notification_access()\n")
            else -> {
                val spec = NodeRegistry.getSpec(node.typeId)
                if (spec != null && spec.isCustom) {
                    val fnName = sanitizeIdent(spec.title.substringBefore(" ["))
                    val argsStr = spec.params.joinToString(", ") { p -> resolveData("arg_${p.key}", p.key) }
                    sb.append("${pad}${fnName}($argsStr)\n")
                } else if (spec != null && spec.category == NodeCategory.CONSTRAINTS) {
                    val expected = ensureQuotedOrExpr(node.params["expected"] ?: "\"true\"")
                    sb.append("${pad}if not (catalog.check_constraint(\"${spec.id}\", \"guard\", $expected)):\n${pad}    return false\n")
                } else if (spec != null && spec.category == NodeCategory.ACTIONS) {
                    val pKey = spec.params.firstOrNull()?.key ?: "value"
                    val argVal = ensureQuotedOrExpr(resolveData("arg", pKey))
                    val safeTitle = spec.title.replace("\"", "'")
                    val safeSub = spec.subCategory.replace("\"", "'")
                    sb.append("${pad}catalog.run_action(\"${spec.id}\", \"$safeTitle\", \"$safeSub\", $argVal)\n")
                }
            }
        }
        emitFlowChain(graph, node.id, "flow_out", indentLevel, sb, visited)
    }

    private fun exprForDataNode(graph: NodeGraph, node: GraphNode, portId: String): String = when (node.typeId) {
        "data.const_number" -> node.params["value"] ?: "0"
        "data.const_text" -> ensureQuotedOrExpr(node.params["value"] ?: "\"\"")
        "data.var_set" -> sanitizeIdent(node.params["name"] ?: "x")
        "data.math" -> {
            val a = graph.wires.firstOrNull { it.toNodeId == node.id && it.toPortId == "a" }
                ?.let { w -> graph.nodes.firstOrNull { it.id == w.fromNodeId }?.let { exprForDataNode(graph, it, w.fromPortId) } }
                ?: (node.params["a_val"] ?: "1")
            val b = graph.wires.firstOrNull { it.toNodeId == node.id && it.toPortId == "b" }
                ?.let { w -> graph.nodes.firstOrNull { it.id == w.fromNodeId }?.let { exprForDataNode(graph, it, w.fromPortId) } }
                ?: (node.params["b_val"] ?: "2")
            "($a ${node.params["op"] ?: "+"} $b)"
        }
        "cond.compare" -> {
            val a = node.params["a_val"] ?: "battery.level"
            val b = node.params["b_val"] ?: "15"
            "($a ${node.params["op"] ?: "=="} $b)"
        }
        "cond.battery_range" -> "(battery.level > ${node.params["min_pct"] ?: "15"})"
        "cond.network" -> if (node.params["wifi_only"] == "true") "net.is_wifi()" else "net.is_connected()"
        "cond.pin_is_locked" -> "pin.is_locked()"
        "cond.pin_is_unlocked" -> "(not pin.is_locked())"
        "cond.pin_is_secure" -> "pin.is_secure()"
        "cond.sms_has_otp" -> "(sms.extract_otp() != \"\")"
        "perms.check" -> "granted"
        "files.read" -> "file_text"
        "creds.get" -> "secret_val"
        "agent.ask" -> "ai_reply"
        "action.http_get" -> "resp"
        "action.clipboard_get" -> "clip"
        "shizuku.exec" -> "shizuku_out"
        "root.exec" -> "root_out"
        else -> {
            val spec = NodeRegistry.getSpec(node.typeId)
            when (spec?.category) {
                NodeCategory.CONDITIONS -> {
                    val pKey = spec.params.firstOrNull()?.key ?: "target"
                    val expected = ensureQuotedOrExpr(node.params[pKey] ?: "\"true\"")
                    "catalog.check_condition(\"${spec.id}\", \"cond\", $expected)"
                }
                NodeCategory.CONSTRAINTS -> {
                    val expected = ensureQuotedOrExpr(node.params["expected"] ?: "\"true\"")
                    "catalog.check_constraint(\"${spec.id}\", \"guard\", $expected)"
                }
                else -> portId
            }
        }
    }

    /**
     * Converts StudioLang source text into a structured NodeGraph, preserving coordinates
     * from [previousGraph] if nodes match by id or sequence.
     */
    fun sourceToGraph(source: String, previousGraph: NodeGraph? = null, graphName: String = "Workflow"): NodeGraph {
        val lex = StudioLexer(source).tokenize()
        val parse = StudioParser(lex.tokens).parse()
        val nodes = mutableListOf<GraphNode>()
        val wires = mutableListOf<GraphWire>()
        var idSeq = 1
        fun nextId(prefix: String) = "${prefix}_${idSeq++}"

        val savedCoords = previousGraph?.nodes?.associate { it.id to (it.x to it.y) }.orEmpty()
        var cursorX = 80f
        var cursorY = 100f

        fun addNode(typeId: String, label: String, params: Map<String, String>, x: Float = cursorX, y: Float = cursorY): GraphNode {
            val id = nextId("n")
            val pos = savedCoords[id] ?: (x to y)
            val node = GraphNode(id = id, typeId = typeId, label = label, x = pos.first, y = pos.second, params = params)
            nodes += node
            cursorX += 260f
            return node
        }

        fun connect(fromId: String, fromPort: String, toId: String, toPort: String) {
            wires += GraphWire(nextId("w"), fromId, fromPort, toId, toPort)
        }

        fun mapStmtList(stmts: List<Stmt>, prevNodeId: String?, prevPort: String) {
            var curPrevId = prevNodeId
            var curPort = prevPort
            for (s in stmts) {
                val createdId = when (s) {
                    is NeedsStmt, is UseStmt -> null
                    is VarDeclStmt -> {
                        val n = addNode(
                            "data.var_set",
                            "Set ${s.name}",
                            mapOf("name" to s.name, "value" to StudioFormatter.formatExpr(s.initializer))
                        )
                        n.id
                    }
                    is ConfirmStmt -> {
                        addNode("flow.confirm", "Confirm", mapOf("prompt" to StudioFormatter.formatExpr(s.promptExpr))).id
                    }
                    is ReturnStmt -> {
                        addNode("flow.return", "Return", mapOf("expr" to (s.value?.let { StudioFormatter.formatExpr(it) } ?: "none"))).id
                    }
                    is RepeatStmt -> {
                        val rep = addNode("flow.repeat", "Repeat", mapOf("count" to StudioFormatter.formatExpr(s.countExpr)))
                        val savedX = cursorX
                        cursorY += 160f
                        mapStmtList(s.body, rep.id, "body_out")
                        cursorY -= 160f
                        cursorX = savedX
                        curPrevId?.let { connect(it, curPort, rep.id, "flow_in") }
                        curPrevId = rep.id
                        curPort = "next_out"
                        null
                    }
                    is RetryStmt -> {
                        val ret = addNode(
                            "flow.retry",
                            "Retry",
                            mapOf(
                                "times" to StudioFormatter.formatExpr(s.countExpr),
                                "wait" to (s.waitExpr?.let { StudioFormatter.formatExpr(it) } ?: "1s")
                            )
                        )
                        cursorY += 160f
                        mapStmtList(s.body, ret.id, "body_out")
                        cursorY -= 160f
                        curPrevId?.let { connect(it, curPort, ret.id, "flow_in") }
                        curPrevId = ret.id
                        curPort = "next_out"
                        null
                    }
                    is IfStmt -> {
                        val br = s.branches.firstOrNull()
                        val ifNode = addNode(
                            "flow.if",
                            "If",
                            mapOf("expr" to (br?.let { StudioFormatter.formatExpr(it.condition) } ?: "true"))
                        )
                        cursorY -= 110f
                        br?.let { mapStmtList(it.body, ifNode.id, "true_out") }
                        cursorY += 220f
                        if (s.elseBody.isNotEmpty()) {
                            mapStmtList(s.elseBody, ifNode.id, "false_out")
                        }
                        cursorY -= 110f
                        curPrevId?.let { connect(it, curPort, ifNode.id, "flow_in") }
                        curPrevId = ifNode.id
                        curPort = "next_out"
                        null
                    }
                    is ExprStmt -> mapExprToNode(s.expr, ::addNode)
                    is MacroDefStmt -> {
                        val trigText = s.onTrigger?.let { StudioFormatter.formatExpr(it) } ?: "manual()"
                        val trigNode = when {
                            trigText.startsWith("time.at") -> {
                                val arg = trigText.substringAfter("\"").substringBefore("\"", "22:30")
                                addNode("trigger.time", "Schedule: ${s.name}", mapOf("macro_name" to s.name, "at_time" to arg))
                            }
                            trigText.contains("battery") -> addNode("trigger.battery", "Battery: ${s.name}", mapOf("macro_name" to s.name))
                            else -> addNode("trigger.manual", "Macro: ${s.name}", mapOf("macro_name" to s.name))
                        }
                        mapStmtList(s.doBody, trigNode.id, "flow_out")
                        cursorX = 80f
                        cursorY += 240f
                        null
                    }
                    else -> null
                }
                if (createdId != null) {
                    if (curPrevId != null) {
                        connect(curPrevId, curPort, createdId, "flow_in")
                    }
                    curPrevId = createdId
                    curPort = "flow_out"
                }
            }
        }

        mapStmtList(parse.program.statements, null, "flow_out")
        return NodeGraph(schemaVersion = 1, name = graphName, nodes = nodes, wires = wires)
    }

    private fun mapExprToNode(
        expr: Expr,
        addNode: (String, String, Map<String, String>) -> GraphNode
    ): String? {
        if (expr !is CallExpr) return null
        val qual = when (val c = expr.callee) {
            is VarRef -> c.name
            is MemberAccess -> "${StudioFormatter.formatExpr(c.target)}.${c.member}"
            else -> return null
        }
        val arg0 = expr.args.getOrNull(0)?.let { StudioFormatter.formatExpr(it.value) }.orEmpty()
        val arg1 = expr.args.getOrNull(1)?.let { StudioFormatter.formatExpr(it.value) }.orEmpty()
        return when {
            qual.startsWith("log.") -> addNode("action.log", "Log (${qual.substringAfter('.')})", mapOf("level" to qual.substringAfter('.'), "message" to arg0)).id
            qual == "notify.show" -> addNode("action.notify", "Notify", mapOf("title" to arg0, "body" to arg1)).id
            qual == "device.vibrate" -> addNode("action.vibrate", "Vibrate", mapOf("ms" to arg0.ifEmpty { "120" })).id
            qual == "screen.tap" -> addNode("action.tap", "Tap", mapOf("x" to arg0, "y" to arg1)).id
            qual == "screen.type_text" -> addNode("action.type_text", "Type Text", mapOf("text" to arg0)).id
            qual == "screen.capture_frame" -> addNode("action.screenshot", "Capture Screen", emptyMap()).id
            qual == "net.http_get" -> addNode("action.http_get", "HTTP GET", mapOf("url" to arg0)).id
            qual == "files.write" -> addNode("files.write", "Write File", mapOf("path" to arg0, "content" to arg1)).id
            qual == "files.read" -> addNode("files.read", "Read File", mapOf("path" to arg0)).id
            qual == "files.delete" -> addNode("files.delete", "Delete File", mapOf("path" to arg0)).id
            qual == "creds.store" -> addNode("creds.store", "Store Secret", mapOf("key" to arg0, "value" to arg1)).id
            qual == "creds.get" -> addNode("creds.get", "Get Secret", mapOf("key" to arg0)).id
            qual == "time.sleep" -> addNode("flow.wait", "Wait", mapOf("duration" to arg0)).id
            qual == "agent.ask" -> addNode("agent.ask", "Ask AI Agent", mapOf("prompt" to arg0)).id
            qual == "clipboard.set" -> addNode("action.clipboard_set", "Copy Clipboard", mapOf("text" to arg0)).id
            qual == "clipboard.get" -> addNode("action.clipboard_get", "Read Clipboard", emptyMap()).id
            qual == "settings.open" -> addNode("settings.open", "Open Settings", mapOf("panel" to arg0)).id
            qual == "shizuku.exec" -> addNode("shizuku.exec", "Shizuku Exec", mapOf("cmd" to arg0)).id
            qual == "root.exec" -> addNode("root.exec", "Root Exec", mapOf("cmd" to arg0)).id
            else -> addNode("block.call", "Call $qual", mapOf("target" to qual, "args" to expr.args.joinToString(", ") { StudioFormatter.formatExpr(it.value) })).id
        }
    }

    private fun sanitizeIdent(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").ifBlank { "Item" }
            .let { if (it.first().isDigit()) "_$it" else it }

    private fun ensureQuotedOrExpr(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("\"") || t.startsWith("'") || t.startsWith("(") || t.matches(Regex("[a-zA-Z_][a-zA-Z0-9_.]*")) || t.toDoubleOrNull() != null) {
            return t
        }
        return "\"${t.replace("\"", "\\\"")}\""
    }
}
