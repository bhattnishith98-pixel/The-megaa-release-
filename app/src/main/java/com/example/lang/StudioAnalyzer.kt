package com.example.lang

/**
 * Semantic analyzer & type checker for StudioLang.
 * Validates variable scopes, const immutability, loop control flow,
 * capability declarations (`needs`), and inferred expression types.
 */
class StudioAnalyzer(
    private val hostSignatures: Map<String, HostFunctionSignature> = DEFAULT_HOST_SIGNATURES
) {
    data class HostFunctionSignature(
        val qualifiedName: String,
        val returnType: StudioType,
        val requiredCapability: StudioCapability? = null,
        val isDestructive: Boolean = false
    )

    data class SymbolInfo(
        val name: String,
        val type: StudioType,
        val isConst: Boolean
    )

    data class AnalysisResult(
        val diagnostics: List<StudioDiagnostic>,
        val inferredTypes: Map<Expr, StudioType>,
        val requiredCapabilities: Set<StudioCapability>,
        val hasConfirmForDestructive: Boolean
    )

    private val diagnostics = mutableListOf<StudioDiagnostic>()
    private val inferredTypes = IdentityHashMapCompat<Expr, StudioType>()
    private val requiredCapabilities = linkedSetOf<StudioCapability>()
    private val scopes = ArrayDeque<MutableMap<String, SymbolInfo>>()
    private val definedCallables = mutableSetOf<String>()
    private var loopDepth = 0
    private var functionDepth = 0
    private var sawConfirm = false
    private var sawDestructiveWithoutConfirm = false

    fun analyze(program: ProgramNode): AnalysisResult {
        scopes.clear()
        scopes.addLast(mutableMapOf())

        // Pre-register built-in namespaces & top-level declarations
        for (ns in BUILTIN_NAMESPACES) {
            scopes.first()[ns] = SymbolInfo(ns, StudioType.ANY, isConst = true)
        }
        program.declaredNeeds.forEach { capRaw ->
            val cap = StudioCapability.fromId(capRaw)
            if (cap == null) {
                diagnostics += StudioDiagnostic(
                    "SEM001",
                    DiagnosticSeverity.ERROR,
                    "Unknown capability '$capRaw' in 'needs' declaration.",
                    SourceSpan(1, 1),
                    hint = "Valid capabilities: ${StudioCapability.entries.joinToString { it.id }}"
                )
            } else {
                requiredCapabilities += cap
            }
        }

        // First pass: register function, trigger, condition, constraint, action, block names
        for (stmt in program.statements) {
            when (stmt) {
                is FuncDefStmt -> definedCallables += stmt.name
                is ConditionDefStmt -> definedCallables += stmt.name
                is ConstraintDefStmt -> definedCallables += stmt.name
                is ActionDefStmt -> definedCallables += stmt.name
                is BlockDefStmt -> definedCallables += stmt.name
                is TriggerDefStmt -> definedCallables += stmt.name
                is MacroDefStmt -> definedCallables += stmt.name
                else -> Unit
            }
        }

        for (stmt in program.statements) {
            analyzeStmt(stmt)
        }

        if (sawDestructiveWithoutConfirm) {
            diagnostics += StudioDiagnostic(
                "SEC002",
                DiagnosticSeverity.WARNING,
                "Destructive API call detected without an explicit `confirm \"...\"` prompt.",
                SourceSpan(1, 1),
                hint = "Add `confirm \"Are you sure?\"` before deleting files or credentials."
            )
        }

        return AnalysisResult(
            diagnostics = diagnostics.toList(),
            inferredTypes = inferredTypes.toMap(),
            requiredCapabilities = requiredCapabilities.toSet(),
            hasConfirmForDestructive = !sawDestructiveWithoutConfirm
        )
    }

    private fun analyzeStmt(stmt: Stmt) {
        when (stmt) {
            is UseStmt -> {
                val alias = stmt.alias ?: stmt.modulePath.last()
                defineSymbol(alias, StudioType.ANY, isConst = true, stmt.span)
            }
            is NeedsStmt -> {
                stmt.capabilities.forEach { raw ->
                    val cap = StudioCapability.fromId(raw)
                    if (cap == null) {
                        diagnostics += StudioDiagnostic(
                            "SEM001",
                            DiagnosticSeverity.ERROR,
                            "Unknown capability '$raw'.",
                            stmt.span
                        )
                    } else {
                        requiredCapabilities += cap
                    }
                }
            }
            is VarDeclStmt -> {
                val initType = inferExpr(stmt.initializer)
                if (stmt.typeHint != null && initType != StudioType.ANY && stmt.typeHint != initType) {
                    if (!stmt.typeHint.isAssignableFrom(initType)) {
                        diagnostics += StudioDiagnostic(
                            "TYP001",
                            DiagnosticSeverity.ERROR,
                            "Cannot assign '${initType.label}' to '${stmt.name}: ${stmt.typeHint.label}'.",
                            stmt.span
                        )
                    }
                }
                defineSymbol(stmt.name, stmt.typeHint ?: initType, stmt.isConst, stmt.span)
            }
            is AssignStmt -> {
                val rhsType = inferExpr(stmt.value)
                when (val t = stmt.target) {
                    is VarRef -> {
                        val existing = lookupSymbol(t.name)
                        if (existing == null) {
                            diagnostics += StudioDiagnostic(
                                "SEM002",
                                DiagnosticSeverity.ERROR,
                                "Variable '${t.name}' is not declared. Use `let ${t.name} = ...` first.",
                                stmt.span
                            )
                        } else if (existing.isConst) {
                            diagnostics += StudioDiagnostic(
                                "SEM003",
                                DiagnosticSeverity.ERROR,
                                "Cannot reassign constant '${t.name}'.",
                                stmt.span
                            )
                        } else if (stmt.op != AssignOp.SET && existing.type != StudioType.ANY &&
                            existing.type != StudioType.NUMBER && existing.type != StudioType.TEXT
                        ) {
                            diagnostics += StudioDiagnostic(
                                "TYP002",
                                DiagnosticSeverity.ERROR,
                                "Compound assignment not supported on type '${existing.type.label}'.",
                                stmt.span
                            )
                        }
                        if (existing != null && existing.type != StudioType.ANY && rhsType != StudioType.ANY &&
                            stmt.op == AssignOp.SET && !existing.type.isAssignableFrom(rhsType)
                        ) {
                            diagnostics += StudioDiagnostic(
                                "TYP003",
                                DiagnosticSeverity.WARNING,
                                "Variable '${t.name}' changed type from ${existing.type.label} to ${rhsType.label}.",
                                stmt.span
                            )
                        }
                    }
                    is MemberAccess, is IndexAccess -> {
                        inferExpr(t)
                    }
                    else -> {
                        diagnostics += StudioDiagnostic(
                            "SEM004",
                            DiagnosticSeverity.ERROR,
                            "Invalid assignment target.",
                            stmt.span
                        )
                    }
                }
            }
            is FuncDefStmt -> analyzeCallable(stmt.params, stmt.body)
            is ConditionDefStmt -> analyzeCallable(stmt.params, stmt.body)
            is ConstraintDefStmt -> analyzeCallable(stmt.params, stmt.body)
            is ActionDefStmt -> {
                stmt.needs.mapNotNull { StudioCapability.fromId(it) }.forEach { requiredCapabilities += it }
                analyzeCallable(stmt.params, stmt.body)
            }
            is BlockDefStmt -> {
                stmt.needs.mapNotNull { StudioCapability.fromId(it) }.forEach { requiredCapabilities += it }
                analyzeCallable(stmt.params, stmt.body)
            }
            is TriggerDefStmt -> {
                stmt.needs.mapNotNull { StudioCapability.fromId(it) }.forEach { requiredCapabilities += it }
                pushScope()
                defineSymbol("event", StudioType.MAP, isConst = true, stmt.span)
                stmt.sourceExpr?.let { inferExpr(it) }
                stmt.whenExpr?.let { inferExpr(it) }
                stmt.emitExpr?.let { inferExpr(it) }
                stmt.body.forEach { analyzeStmt(it) }
                popScope()
            }
            is MacroDefStmt -> {
                stmt.needs.mapNotNull { StudioCapability.fromId(it) }.forEach { requiredCapabilities += it }
                stmt.onTrigger?.let { inferExpr(it) }
                stmt.onlyIf?.let { inferExpr(it) }
                pushScope()
                defineSymbol("event", StudioType.MAP, isConst = true, stmt.span)
                stmt.doBody.forEach { analyzeStmt(it) }
                popScope()
                if (stmt.errorBody.isNotEmpty()) {
                    pushScope()
                    defineSymbol(stmt.errorVar ?: "e", StudioType.MAP, isConst = true, stmt.span)
                    stmt.errorBody.forEach { analyzeStmt(it) }
                    popScope()
                }
            }
            is AppDefStmt -> {
                pushScope()
                stmt.body.forEach { analyzeStmt(it) }
                popScope()
            }
            is ScreenDefStmt -> {
                pushScope()
                stmt.body.forEach { analyzeStmt(it) }
                popScope()
            }
            is IfStmt -> {
                stmt.branches.forEach { br ->
                    inferExpr(br.condition)
                    pushScope()
                    br.body.forEach { analyzeStmt(it) }
                    popScope()
                }
                if (stmt.elseBody.isNotEmpty()) {
                    pushScope()
                    stmt.elseBody.forEach { analyzeStmt(it) }
                    popScope()
                }
            }
            is ForStmt -> {
                inferExpr(stmt.iterable)
                pushScope()
                defineSymbol(stmt.itemName, StudioType.ANY, isConst = false, stmt.span)
                loopDepth++
                stmt.body.forEach { analyzeStmt(it) }
                loopDepth--
                popScope()
            }
            is WhileStmt -> {
                inferExpr(stmt.condition)
                pushScope()
                loopDepth++
                stmt.body.forEach { analyzeStmt(it) }
                loopDepth--
                popScope()
            }
            is RepeatStmt -> {
                val cType = inferExpr(stmt.countExpr)
                if (cType != StudioType.NUMBER && cType != StudioType.ANY) {
                    diagnostics += StudioDiagnostic(
                        "TYP004",
                        DiagnosticSeverity.ERROR,
                        "`repeat` count must be a number, got ${cType.label}.",
                        stmt.span
                    )
                }
                pushScope()
                loopDepth++
                stmt.body.forEach { analyzeStmt(it) }
                loopDepth--
                popScope()
            }
            is BreakStmt -> {
                if (loopDepth <= 0) {
                    diagnostics += StudioDiagnostic("SEM005", DiagnosticSeverity.ERROR, "`break` used outside of a loop.", stmt.span)
                }
            }
            is ContinueStmt -> {
                if (loopDepth <= 0) {
                    diagnostics += StudioDiagnostic("SEM006", DiagnosticSeverity.ERROR, "`continue` used outside of a loop.", stmt.span)
                }
            }
            is ReturnStmt -> {
                if (functionDepth <= 0) {
                    diagnostics += StudioDiagnostic("SEM007", DiagnosticSeverity.WARNING, "`return` at top-level halts script execution.", stmt.span)
                }
                stmt.value?.let { inferExpr(it) }
            }
            is TryStmt -> {
                pushScope()
                stmt.tryBody.forEach { analyzeStmt(it) }
                popScope()
                pushScope()
                defineSymbol(stmt.errorVar ?: "e", StudioType.MAP, isConst = true, stmt.span)
                stmt.catchBody.forEach { analyzeStmt(it) }
                popScope()
            }
            is RaiseStmt -> inferExpr(stmt.messageExpr)
            is RetryStmt -> {
                inferExpr(stmt.countExpr)
                stmt.waitExpr?.let { inferExpr(it) }
                pushScope()
                stmt.body.forEach { analyzeStmt(it) }
                popScope()
            }
            is TimeoutStmt -> {
                inferExpr(stmt.durationExpr)
                pushScope()
                stmt.body.forEach { analyzeStmt(it) }
                popScope()
            }
            is ConfirmStmt -> {
                inferExpr(stmt.promptExpr)
                sawConfirm = true
            }
            is ExprStmt -> inferExpr(stmt.expr)
        }
    }

    private fun analyzeCallable(params: List<ParamSpec>, body: List<Stmt>) {
        pushScope()
        functionDepth++
        params.forEach { p ->
            val defaultType = p.defaultValue?.let { inferExpr(it) }
            defineSymbol(p.name, p.typeHint ?: defaultType ?: StudioType.ANY, isConst = false, p.span)
        }
        body.forEach { analyzeStmt(it) }
        functionDepth--
        popScope()
    }

    private fun inferExpr(expr: Expr): StudioType {
        val t = when (expr) {
            is NumberLit -> StudioType.NUMBER
            is TextLit -> StudioType.TEXT
            is BoolLit -> StudioType.BOOL
            is NoneLit -> StudioType.NONE
            is DurationLit -> StudioType.DURATION
            is ListLit -> {
                expr.elements.forEach { inferExpr(it) }
                StudioType.LIST
            }
            is MapLit -> {
                expr.entries.forEach { inferExpr(it.value) }
                StudioType.MAP
            }
            is VarRef -> {
                val sym = lookupSymbol(expr.name)
                if (sym != null) sym.type
                else if (expr.name in definedCallables) StudioType.ANY
                else {
                    diagnostics += StudioDiagnostic(
                        "SEM008",
                        DiagnosticSeverity.ERROR,
                        "Undefined identifier '${expr.name}'.",
                        expr.span
                    )
                    StudioType.ANY
                }
            }
            is MemberAccess -> {
                inferExpr(expr.target)
                StudioType.ANY
            }
            is IndexAccess -> {
                inferExpr(expr.target)
                inferExpr(expr.index)
                StudioType.ANY
            }
            is CallExpr -> {
                expr.args.forEach { inferExpr(it.value) }
                val qual = extractQualifiedName(expr.callee)
                val sig = if (qual != null) hostSignatures[qual] else null
                if (sig != null) {
                    sig.requiredCapability?.let { cap ->
                        requiredCapabilities += cap
                    }
                    if (sig.isDestructive && !sawConfirm) {
                        sawDestructiveWithoutConfirm = true
                    }
                    sig.returnType
                } else {
                    if (expr.callee !is MemberAccess) {
                        inferExpr(expr.callee)
                    } else {
                        inferExpr(expr.callee.target)
                    }
                    StudioType.ANY
                }
            }
            is UnaryExpr -> {
                val opT = inferExpr(expr.operand)
                when (expr.op) {
                    UnaryOp.NEG -> StudioType.NUMBER
                    UnaryOp.NOT -> StudioType.BOOL
                }.also {
                    if (expr.op == UnaryOp.NEG && opT != StudioType.NUMBER && opT != StudioType.ANY) {
                        diagnostics += StudioDiagnostic("TYP005", DiagnosticSeverity.ERROR, "Unary '-' requires a number.", expr.span)
                    }
                }
            }
            is BinaryExpr -> {
                val l = inferExpr(expr.left)
                val r = inferExpr(expr.right)
                when (expr.op) {
                    BinaryOp.ADD -> if (l == StudioType.TEXT || r == StudioType.TEXT) StudioType.TEXT else StudioType.NUMBER
                    BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD, BinaryOp.POW -> StudioType.NUMBER
                    BinaryOp.EQ, BinaryOp.NEQ, BinaryOp.LT, BinaryOp.LTE, BinaryOp.GT, BinaryOp.GTE,
                    BinaryOp.AND, BinaryOp.OR, BinaryOp.IN, BinaryOp.IS -> StudioType.BOOL
                }
            }
        }
        inferredTypes[expr] = t
        return t
    }

    private fun extractQualifiedName(expr: Expr): String? = when (expr) {
        is VarRef -> expr.name
        is MemberAccess -> {
            val base = extractQualifiedName(expr.target)
            if (base != null) "$base.${expr.member}" else null
        }
        else -> null
    }

    private fun defineSymbol(name: String, type: StudioType, isConst: Boolean, span: SourceSpan) {
        scopes.last()[name] = SymbolInfo(name, type, isConst)
    }

    private fun lookupSymbol(name: String): SymbolInfo? {
        for (i in scopes.size - 1 downTo 0) {
            scopes[i][name]?.let { return it }
        }
        return null
    }

    private fun pushScope() { scopes.addLast(mutableMapOf()) }
    private fun popScope() { if (scopes.size > 1) scopes.removeLast() }

    private class IdentityHashMapCompat<K, V> {
        private val map = java.util.IdentityHashMap<K, V>()
        operator fun set(k: K, v: V) { map[k] = v }
        fun toMap(): Map<K, V> = map
    }

    companion object {
        val BUILTIN_NAMESPACES = setOf(
            "log", "math", "text", "list", "json", "time", "device", "battery", "manual",
            "net", "notify", "files", "creds", "perms", "screen", "apps", "settings", "wifi",
            "build", "ui", "agent", "shizuku", "root", "clipboard", "intent",
            "mem", "bin", "hw", "os", "android",
            "pin", "sms", "mobile", "touch", "catalog",
            "godot", "ide", "acs", "voice"
        )

        val DEFAULT_HOST_SIGNATURES: Map<String, HostFunctionSignature> = listOf(
            HostFunctionSignature("log.info", StudioType.NONE),
            HostFunctionSignature("log.warn", StudioType.NONE),
            HostFunctionSignature("log.error", StudioType.NONE),
            HostFunctionSignature("log.debug", StudioType.NONE),
            HostFunctionSignature("math.abs", StudioType.NUMBER),
            HostFunctionSignature("math.min", StudioType.NUMBER),
            HostFunctionSignature("math.max", StudioType.NUMBER),
            HostFunctionSignature("math.round", StudioType.NUMBER),
            HostFunctionSignature("math.floor", StudioType.NUMBER),
            HostFunctionSignature("math.ceil", StudioType.NUMBER),
            HostFunctionSignature("math.sqrt", StudioType.NUMBER),
            HostFunctionSignature("math.pow", StudioType.NUMBER),
            HostFunctionSignature("math.random", StudioType.NUMBER),
            HostFunctionSignature("math.clamp", StudioType.NUMBER),
            HostFunctionSignature("text.len", StudioType.NUMBER),
            HostFunctionSignature("text.upper", StudioType.TEXT),
            HostFunctionSignature("text.lower", StudioType.TEXT),
            HostFunctionSignature("text.trim", StudioType.TEXT),
            HostFunctionSignature("text.split", StudioType.LIST),
            HostFunctionSignature("text.join", StudioType.TEXT),
            HostFunctionSignature("text.contains", StudioType.BOOL),
            HostFunctionSignature("text.replace", StudioType.TEXT),
            HostFunctionSignature("text.starts_with", StudioType.BOOL),
            HostFunctionSignature("text.ends_with", StudioType.BOOL),
            HostFunctionSignature("list.len", StudioType.NUMBER),
            HostFunctionSignature("list.push", StudioType.LIST),
            HostFunctionSignature("list.pop", StudioType.ANY),
            HostFunctionSignature("list.contains", StudioType.BOOL),
            HostFunctionSignature("list.sort", StudioType.LIST),
            HostFunctionSignature("list.reverse", StudioType.LIST),
            HostFunctionSignature("json.parse", StudioType.ANY),
            HostFunctionSignature("json.stringify", StudioType.TEXT),
            HostFunctionSignature("time.now", StudioType.TEXT),
            HostFunctionSignature("time.timestamp_ms", StudioType.NUMBER),
            HostFunctionSignature("time.sleep", StudioType.NONE),
            HostFunctionSignature("time.at", StudioType.MAP),
            HostFunctionSignature("device.model", StudioType.TEXT, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("device.sdk_int", StudioType.NUMBER, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("device.battery_level", StudioType.NUMBER, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("device.is_charging", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("device.vibrate", StudioType.NONE, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("battery.level", StudioType.NUMBER, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("net.is_connected", StudioType.BOOL, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.is_wifi", StudioType.BOOL, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.http_get", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.http_post", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.local_ips", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.ipv4_send", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.ipv6_send", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.share_start", StudioType.BOOL, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.share_stop", StudioType.BOOL, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("net.share_file", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("notify.show", StudioType.NONE, StudioCapability.NOTIFICATIONS),
            HostFunctionSignature("notify.cancel", StudioType.NONE, StudioCapability.NOTIFICATIONS),
            HostFunctionSignature("files.list", StudioType.LIST, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.read", StudioType.TEXT, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.write", StudioType.BOOL, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.copy", StudioType.BOOL, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.rename", StudioType.BOOL, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.delete", StudioType.BOOL, StudioCapability.FILE_ACCESS, isDestructive = true),
            HostFunctionSignature("files.exists", StudioType.BOOL, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("files.mkdir", StudioType.BOOL, StudioCapability.FILE_ACCESS),
            HostFunctionSignature("creds.store", StudioType.BOOL, StudioCapability.CREDENTIAL_MANAGEMENT),
            HostFunctionSignature("creds.get", StudioType.TEXT, StudioCapability.CREDENTIAL_MANAGEMENT),
            HostFunctionSignature("creds.delete", StudioType.BOOL, StudioCapability.CREDENTIAL_MANAGEMENT, isDestructive = true),
            HostFunctionSignature("creds.list_keys", StudioType.LIST, StudioCapability.CREDENTIAL_MANAGEMENT),
            HostFunctionSignature("perms.check", StudioType.BOOL),
            HostFunctionSignature("perms.request", StudioType.BOOL),
            HostFunctionSignature("perms.list_declared", StudioType.LIST),
            HostFunctionSignature("perms.is_accessibility_enabled", StudioType.BOOL),
            HostFunctionSignature("perms.is_screen_capture_active", StudioType.BOOL),
            HostFunctionSignature("perms.open_app_settings", StudioType.NONE),
            HostFunctionSignature("perms.open_accessibility_settings", StudioType.NONE),
            HostFunctionSignature("screen.capture_frame", StudioType.MAP, StudioCapability.SCREEN_CAPTURE),
            HostFunctionSignature("screen.tap", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.long_press", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.double_tap", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.custom_swipe", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.custom_touch", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.gesture_path", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.click_text", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.type_text", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.scroll", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.back", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.home", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.find_text", StudioType.LIST, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("screen.ui_tree", StudioType.LIST, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("touch.last_event", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("pin.is_locked", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("pin.is_secure", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("pin.set_code", StudioType.BOOL, StudioCapability.CREDENTIAL_MANAGEMENT),
            HostFunctionSignature("pin.verify", StudioType.BOOL, StudioCapability.CREDENTIAL_MANAGEMENT),
            HostFunctionSignature("pin.failed_attempts", StudioType.NUMBER, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("pin.lock_now", StudioType.BOOL, StudioCapability.ACCESSIBILITY_CONTROL),
            HostFunctionSignature("pin.last_unlock_ms", StudioType.NUMBER, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_wifi", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("mobile.set_data", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("mobile.set_bluetooth", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_flashlight", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_airplane_mode", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_hotspot", StudioType.MAP, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("mobile.set_dnd", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_auto_rotate", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_screen", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.power_action", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_brightness", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mobile.set_volume", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.has_notification_access", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.open_notification_access", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.last_received", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.extract_otp", StudioType.TEXT, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.matches_filter", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("sms.open_composer", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("catalog.fire_trigger", StudioType.MAP),
            HostFunctionSignature("catalog.run_action", StudioType.MAP),
            HostFunctionSignature("catalog.check_condition", StudioType.BOOL),
            HostFunctionSignature("catalog.check_constraint", StudioType.BOOL),
            HostFunctionSignature("apps.launch", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("apps.open_url", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("apps.installed", StudioType.LIST, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("clipboard.get", StudioType.TEXT, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("clipboard.set", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("intent.broadcast", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("settings.open", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("settings.dnd", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("wifi.set", StudioType.BOOL, StudioCapability.NETWORK_ACCESS),
            HostFunctionSignature("shizuku.is_available", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("shizuku.exec", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED, isDestructive = true),
            HostFunctionSignature("root.is_available", StudioType.BOOL, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("root.exec", StudioType.MAP, StudioCapability.ROOT_SUPERUSER, isDestructive = true),
            HostFunctionSignature("android.grant_perm", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.revoke_perm", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.appops", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.set_setting", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.wm_density", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.wm_size", StudioType.MAP, StudioCapability.SHIZUKU_PRIVILEGED),
            HostFunctionSignature("android.package_perms", StudioType.LIST, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("android.device_tweaks", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("mem.arena_create", StudioType.NUMBER),
            HostFunctionSignature("mem.arena_free", StudioType.BOOL),
            HostFunctionSignature("mem.write_u8", StudioType.BOOL),
            HostFunctionSignature("mem.read_u8", StudioType.NUMBER),
            HostFunctionSignature("mem.write_u32", StudioType.BOOL),
            HostFunctionSignature("mem.read_u32", StudioType.NUMBER),
            HostFunctionSignature("mem.stats", StudioType.MAP),
            HostFunctionSignature("bin.to_hex_u32", StudioType.TEXT),
            HostFunctionSignature("bin.from_hex", StudioType.LIST),
            HostFunctionSignature("bin.to_bits", StudioType.TEXT),
            HostFunctionSignature("hw.cpu_info", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("hw.mem_info", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("hw.syscall", StudioType.MAP, StudioCapability.SYSTEM_INFO),
            HostFunctionSignature("os.kernel_log", StudioType.NONE),
            HostFunctionSignature("os.scheduler_tick", StudioType.NUMBER),
            HostFunctionSignature("build.validate_manifest", StudioType.MAP, StudioCapability.BUILD_MANAGEMENT),
            HostFunctionSignature("godot.spawn", StudioType.MAP),
            HostFunctionSignature("godot.move", StudioType.BOOL),
            HostFunctionSignature("godot.set_prop", StudioType.BOOL),
            HostFunctionSignature("godot.get_prop", StudioType.TEXT),
            HostFunctionSignature("godot.play", StudioType.BOOL),
            HostFunctionSignature("godot.pause", StudioType.BOOL),
            HostFunctionSignature("godot.stop", StudioType.BOOL),
            HostFunctionSignature("godot.list_nodes", StudioType.LIST),
            HostFunctionSignature("godot.export_tscn", StudioType.TEXT),
            HostFunctionSignature("ide.open_file", StudioType.BOOL),
            HostFunctionSignature("ide.write_file", StudioType.BOOL),
            HostFunctionSignature("ide.run_terminal", StudioType.TEXT),
            HostFunctionSignature("acs.validate", StudioType.MAP),
            HostFunctionSignature("voice.speak", StudioType.NONE),
            HostFunctionSignature("agent.ask", StudioType.TEXT, StudioCapability.AGENT_AI)
        ).associateBy { it.qualifiedName }
    }
}
