package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.*
import com.example.appbuilder.*
import com.example.bridge.TermuxEnvironmentStatus
import com.example.bridge.TermuxToolchainBridge
import com.example.docs.ExamplesLibrary
import com.example.docs.StudioExample
import com.example.lang.*
import com.example.macros.MacroEngine
import com.example.macros.MacroRecord
import com.example.monetization.AdMobRewardedController
import com.example.monetization.MonetizationManager
import com.example.monetization.MonetizationProfile
import com.example.nodes.*
import com.example.runtime.*
import com.example.services.AutomationRunnerService
import com.example.services.FloatingAgentOverlayService
import com.example.services.ScreenCaptureService
import com.example.services.StudioAccessibilityService
import com.example.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

enum class StudioDestination(val label: String) {
    STUDIO("Studio"),
    IDE("IDE"),
    NODES("Nodes"),
    AGENT("Agent"),
    APPS("Apps"),
    DOCS("Lang Docs")
}

enum class EditorViewMode(val label: String) {
    NODE("Node"),
    TEXT("Text"),
    SPLIT("Split")
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    val workspaceDir = File(appContext.filesDir, "studio_workspace").apply { mkdirs() }
    val generatedAppDir = File(appContext.filesDir, "generated_apps/current").apply { mkdirs() }
    val appBackupsDir = File(appContext.filesDir, "app_backups").apply { mkdirs() }

    val capabilityManager = CapabilityManager(
        setOf(
            StudioCapability.MACRO_EXECUTION,
            StudioCapability.SYSTEM_INFO,
            StudioCapability.FILE_ACCESS,
            StudioCapability.NOTIFICATIONS,
            StudioCapability.NETWORK_ACCESS,
            StudioCapability.APP_CONFIGURATION,
            StudioCapability.BUILD_MANAGEMENT,
            StudioCapability.CREDENTIAL_MANAGEMENT,
            StudioCapability.AGENT_AI
        )
    )
    val credentialVault = CredentialVault(appContext)
    val compiler = StudioCompiler(context = appContext, workspaceRoot = workspaceDir)
    val aiProvider = MultiProviderAgentAi(appContext, credentialVault)
    val monetization = MonetizationManager(appContext)
    val adMobController = AdMobRewardedController(appContext)
    val networkShareManager = NetworkShareManager(workspaceDir)
    val voiceController = VoiceAgentController(appContext)
    val stdLib = StudioStdLib(
        context = appContext,
        workspaceRoot = workspaceDir,
        credentialVault = credentialVault,
        aiAskCallback = { prompt ->
            val plan = aiProvider.planFromBrief(prompt, emptyList(), _sourceCode.value)
            plan.summary
        },
        networkShareManager = networkShareManager,
        requestPermissionCallback = { perm ->
            val def = CompletableDeferred<Boolean>()
            permissionRequestDeferred = def
            _pendingPermissionRequest.value = perm
            def.await()
        },
        voiceSpeakCallback = { msg ->
            viewModelScope.launch {
                voiceController.initTts()
                voiceController.speakAndAwait(msg)
            }
        }
    )
    val macroEngine = MacroEngine(workspaceDir, compiler, capabilityManager, stdLib)
    val toolExecutor = AgentToolExecutor(workspaceDir, compiler, capabilityManager)
    val godotObserve = GodotObserveController(aiProvider)
    val unifiedIde = com.example.ide.UnifiedIdeController(
        scope = viewModelScope,
        compiler = compiler,
        capabilityManager = capabilityManager,
        hostDispatcher = stdLib,
        workspaceRoot = workspaceDir,
        initialAppDesign = AppDesign(),
        voiceSpeakCallback = { msg ->
            viewModelScope.launch {
                voiceController.initTts()
                voiceController.speakAndAwait(msg)
            }
        }
    )

    private val _identityReport = MutableStateFlow(
        RuntimeIdentityReport(
            packageName = appContext.packageName,
            expectedPackageName = "com.aistudio.automationstudio.xqvmkp",
            signingSha256 = "Verifying...",
            isExclusiveSessionValid = true
        )
    )
    val identityReportState: StateFlow<RuntimeIdentityReport> = _identityReport.asStateFlow()
    val identityReport: RuntimeIdentityReport get() = _identityReport.value

    private val _binaryTarget = MutableStateFlow(BinaryTarget.STUDIO_VM_BIN)
    val binaryTarget: StateFlow<BinaryTarget> = _binaryTarget.asStateFlow()

    private val _selfHostResult = MutableStateFlow<SelfHostBootstrapResult?>(null)
    val selfHostResult: StateFlow<SelfHostBootstrapResult?> = _selfHostResult.asStateFlow()

    private val _availableLibraries = MutableStateFlow<List<String>>(listOf("studio.h"))
    val availableLibraries: StateFlow<List<String>> = _availableLibraries.asStateFlow()

    private val _aiTestResult = MutableStateFlow<String?>(null)
    val aiTestResult: StateFlow<String?> = _aiTestResult.asStateFlow()

    private val _androidTweaks = MutableStateFlow(
        DeviceTweaksSnapshot(
            brightness = 128,
            screenTimeoutMs = 30_000,
            mediaVolume = 0,
            maxMediaVolume = 15,
            ringVolume = 0,
            canWriteSettings = false,
            canDrawOverlays = false,
            fontScale = 1.0f
        )
    )
    val androidTweaks: StateFlow<DeviceTweaksSnapshot> = _androidTweaks.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppSummary>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppSummary>> = _installedApps.asStateFlow()

    private val _selectedAppPackage = MutableStateFlow(appContext.packageName)
    val selectedAppPackage: StateFlow<String> = _selectedAppPackage.asStateFlow()

    private val _selectedAppPerms = MutableStateFlow<List<AppPermissionInfo>>(emptyList())
    val selectedAppPerms: StateFlow<List<AppPermissionInfo>> = _selectedAppPerms.asStateFlow()

    private val _privilegeCommandOutput = MutableStateFlow<String?>(null)
    val privilegeCommandOutput: StateFlow<String?> = _privilegeCommandOutput.asStateFlow()

    private val starterCode = ExamplesLibrary.examples.first().code
    private val _activeFileName = MutableStateFlow("main.stl")
    val activeFileName: StateFlow<String> = _activeFileName.asStateFlow()

    private val _sourceCode = MutableStateFlow(starterCode)
    val sourceCode: StateFlow<String> = _sourceCode.asStateFlow()

    private val _nodeGraph = MutableStateFlow(NodeGraph(name = "MainWorkflow"))
    val nodeGraph: StateFlow<NodeGraph> = _nodeGraph.asStateFlow()

    private val _compileOutput = MutableStateFlow(
        CompilationOutput(
            success = true,
            program = null,
            analysis = null,
            irStats = null,
            bytecode = null,
            diagnostics = emptyList(),
            compileTimeUs = 0L
        )
    )
    val compileOutput: StateFlow<CompilationOutput> = _compileOutput.asStateFlow()

    private val _editorMode = MutableStateFlow(EditorViewMode.NODE)
    val editorMode: StateFlow<EditorViewMode> = _editorMode.asStateFlow()

    private val undoGraphs = ArrayDeque<Pair<NodeGraph, String>>()
    private val redoGraphs = ArrayDeque<Pair<NodeGraph, String>>()

    private val _vmRunning = MutableStateFlow(false)
    val vmRunning: StateFlow<Boolean> = _vmRunning.asStateFlow()

    private val _vmPaused = MutableStateFlow(false)
    val vmPaused: StateFlow<Boolean> = _vmPaused.asStateFlow()

    private val _vmLogs = MutableStateFlow<List<VmLogEntry>>(emptyList())
    val vmLogs: StateFlow<List<VmLogEntry>> = _vmLogs.asStateFlow()

    private val _activeDebugLine = MutableStateFlow<Int?>(null)
    val activeDebugLine: StateFlow<Int?> = _activeDebugLine.asStateFlow()

    private val _watchLocals = MutableStateFlow<Map<String, String>>(emptyMap())
    val watchLocals: StateFlow<Map<String, String>> = _watchLocals.asStateFlow()

    private val _pendingConfirmPrompt = MutableStateFlow<String?>(null)
    val pendingConfirmPrompt: StateFlow<String?> = _pendingConfirmPrompt.asStateFlow()
    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    private val _pendingPermissionRequest = MutableStateFlow<String?>(null)
    val pendingPermissionRequest: StateFlow<String?> = _pendingPermissionRequest.asStateFlow()
    private var permissionRequestDeferred: CompletableDeferred<Boolean>? = null

    private val _recentNodeIds = MutableStateFlow(listOf("trigger.manual", "action.log", "flow.if", "data.var_set"))
    val recentNodeIds: StateFlow<List<String>> = _recentNodeIds.asStateFlow()

    private val _favouriteNodeIds = MutableStateFlow(setOf("trigger.manual", "action.log", "action.notify", "flow.confirm"))
    val favouriteNodeIds: StateFlow<Set<String>> = _favouriteNodeIds.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<String>>(emptyList())
    val workspaceFiles: StateFlow<List<String>> = _workspaceFiles.asStateFlow()

    // App Builder state
    private val _appDesign = MutableStateFlow(AppDesign())
    val appDesign: StateFlow<AppDesign> = _appDesign.asStateFlow()

    private val _appBuildOutput = MutableStateFlow<AppBuildOutput?>(null)
    val appBuildOutput: StateFlow<AppBuildOutput?> = _appBuildOutput.asStateFlow()

    private val _appPreviewLog = MutableStateFlow<List<String>>(emptyList())
    val appPreviewLog: StateFlow<List<String>> = _appPreviewLog.asStateFlow()

    // Agent Briefing state
    private val _agentStatus = MutableStateFlow(AgentStatus.IDLE)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _briefAttachments = MutableStateFlow<List<BriefAttachment>>(emptyList())
    val briefAttachments: StateFlow<List<BriefAttachment>> = _briefAttachments.asStateFlow()

    private val _agentMessages = MutableStateFlow<List<AgentMessage>>(
        listOf(
            AgentMessage(
                id = "welcome",
                isUser = false,
                text = "Send an idea, screenshot, file, or folder. I will inspect attachments safely (redacting secrets & isolating untrusted instructions), propose an editable plan, and generate validated StudioLang nodes/code."
            )
        )
    )
    val agentMessages: StateFlow<List<AgentMessage>> = _agentMessages.asStateFlow()

    private val _pendingPlan = MutableStateFlow<AgentPlan?>(null)
    val pendingPlan: StateFlow<AgentPlan?> = _pendingPlan.asStateFlow()

    private val _speakingOnlyMode = MutableStateFlow(false)
    val speakingOnlyMode: StateFlow<Boolean> = _speakingOnlyMode.asStateFlow()

    private val onboardingPrefs = appContext.getSharedPreferences("studio_onboarding_prefs", Context.MODE_PRIVATE)
    private val _showFirstLaunchPermissionsDialog = MutableStateFlow(
        !onboardingPrefs.getBoolean("first_launch_perms_prompted", false)
    )
    val showFirstLaunchPermissionsDialog: StateFlow<Boolean> = _showFirstLaunchPermissionsDialog.asStateFlow()

    private val _benchmarkReport = MutableStateFlow<BenchmarkReport?>(null)
    val benchmarkReport: StateFlow<BenchmarkReport?> = _benchmarkReport.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _editingMacroId = MutableStateFlow<String?>(null)
    val editingMacroId: StateFlow<String?> = _editingMacroId.asStateFlow()

    private val _editingMacroName = MutableStateFlow<String?>(null)
    val editingMacroName: StateFlow<String?> = _editingMacroName.asStateFlow()

    private val _isCompiling = MutableStateFlow(false)
    val isCompiling: StateFlow<Boolean> = _isCompiling.asStateFlow()

    private val _showNodeQuickStart = MutableStateFlow(true)
    val showNodeQuickStart: StateFlow<Boolean> = _showNodeQuickStart.asStateFlow()

    private var activeVm: StudioVm? = null
    private var executionJob: Job? = null
    private var compileDebounceJob: Job? = null
    private var lastTextUndoAtMs: Long = 0L
    private var stepGate: CompletableDeferred<Unit>? = null
    private var stepModeEnabled = false

    init {
        AutomationRunnerService.onEmergencyStop = { emergencyStopAll() }
        FloatingAgentOverlayService.onStopClicked = { emergencyStopAll() }
        FloatingAgentOverlayService.onMicClicked = { triggerOverlayVoiceCommand() }
        monetization.startForegroundTicker(viewModelScope)
        viewModelScope.launch(Dispatchers.Default) {
            val graph = GraphAstConverter.sourceToGraph(starterCode, graphName = "MainWorkflow")
            val compiled = compiler.compile(starterCode, target = BinaryTarget.STUDIO_VM_BIN)
            _nodeGraph.value = graph
            _compileOutput.value = compiled
        }
        viewModelScope.launch(Dispatchers.IO) {
            _identityReport.value = RuntimeIdentity.verifyAppIdentity(appContext)
            _androidTweaks.value = AndroidCustomizer.readDeviceTweaks(appContext)
            saveActiveWorkspaceFiles()
            File(workspaceDir, "monetization_profile.json").delete()
            refreshWorkspaceFilesSuspend()
        }
        unifiedIde.scanBundledAssetArchives { assetPath -> appContext.assets.open(assetPath) }
        refreshAvailableLibraries()
        refreshInstalledApps()
    }

    override fun onCleared() {
        super.onCleared()
        networkShareManager.stopReceiverServer()
        voiceController.release()
    }

    fun initAdsSdk() {
        adMobController.initializeSdk(monetization.state.value.adUnitId)
    }

    fun playRewardedAd(activity: Activity?) {
        val state = monetization.state.value
        adMobController.showRewardedAdOrFallback(
            activity = activity,
            adUnitId = state.adUnitId,
            useSdkWhenAvailable = state.profile.admob.useSdkWhenAvailable,
            onRewardEarned = {
                monetization.completeTestRewardedAd()
                setBanner("Rewarded Ad completed — foreground timer reset.")
            },
            onFallbackToTestPlayer = {
                monetization.startTestRewardedAd(viewModelScope)
            }
        )
    }

    fun saveMonetizationProfileJson(rawJson: String): Result<MonetizationProfile> {
        val res = monetization.updateProfileFromJson(rawJson)
        res.onSuccess { prof ->
            adMobController.preloadRewardedAd(prof.effectiveRewardedAdUnitId())
        }
        return res
    }

    fun resetMonetizationProfileToTest(): MonetizationProfile {
        val def = monetization.resetProfileToTestDefaults()
        adMobController.preloadRewardedAd(def.effectiveRewardedAdUnitId())
        return def
    }

    fun clearBanner() { _statusBanner.value = null }
    fun setBanner(msg: String) { _statusBanner.value = msg }
    fun setEditorMode(mode: EditorViewMode) { _editorMode.value = mode }
    fun setShowNodeQuickStart(show: Boolean) { _showNodeQuickStart.value = show }

    /**
     * Fast, non-blocking editor keystroke handler. Keeps IME typing smooth on UI thread
     * and offloads compilation, AST-to-Graph sync, and disk writes to background dispatchers.
     */
    fun onEditorTextChanged(newCode: String) {
        if (newCode == _sourceCode.value) return
        val now = System.currentTimeMillis()
        if (now - lastTextUndoAtMs > 1200L) {
            pushUndo()
            lastTextUndoAtMs = now
        }
        _sourceCode.value = newCode
        updateSourceAsync(newCode, syncToGraph = true, debounceMs = 280L)
    }

    private fun updateSourceAsync(newCode: String, syncToGraph: Boolean, debounceMs: Long) {
        compileDebounceJob?.cancel()
        val fileBase = _activeFileName.value.removeSuffix(".stl")
        val prevGraph = _nodeGraph.value
        compileDebounceJob = viewModelScope.launch {
            _isCompiling.value = true
            try {
                if (debounceMs > 0L) delay(debounceMs)
                val target = _binaryTarget.value
                val (compiled, newGraph) = withContext(Dispatchers.Default) {
                    val out = compiler.compile(newCode, target = target)
                    val g = if (syncToGraph) GraphAstConverter.sourceToGraph(newCode, prevGraph, fileBase) else null
                    out to g
                }
                _compileOutput.value = compiled
                if (newGraph != null) _nodeGraph.value = newGraph
                withContext(Dispatchers.IO) {
                    saveActiveWorkspaceFiles()
                }
            } finally {
                _isCompiling.value = false
            }
        }
    }

    fun selectBinaryTarget(target: BinaryTarget) {
        _binaryTarget.value = target
        viewModelScope.launch {
            _isCompiling.value = true
            try {
                val out = withContext(Dispatchers.Default) {
                    compiler.compile(_sourceCode.value, useCache = false, target = target)
                }
                _compileOutput.value = out
                setBanner("Binary target switched to ${target.label}.")
            } finally {
                _isCompiling.value = false
            }
        }
    }

    fun runSelfHostedBootstrap() {
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                SelfHostedCompiler.bootstrapCompile(
                    targetSource = _sourceCode.value,
                    compiler = compiler,
                    capabilityManager = capabilityManager,
                    hostDispatcher = stdLib,
                    workspaceDir = workspaceDir
                )
            }
            _selfHostResult.value = res
            setBanner(
                if (res.success) "Self-Hosted Compiler Stage 1->2 built ${res.stage2ByteCount} bytes in ${res.durationMs}ms."
                else "Self-Host error: ${res.error}"
            )
        }
    }

    fun refreshAvailableLibraries() {
        viewModelScope.launch(Dispatchers.IO) {
            _availableLibraries.value = LibraryImporter.listAvailableLibraries(appContext, workspaceDir)
        }
    }

    fun saveStlLibrary(rawName: String, authorLang: String, content: String) {
        viewModelScope.launch {
            val (fname, loc) = withContext(Dispatchers.IO) {
                LibraryImporter.saveLibraryToDownloads(appContext, workspaceDir, rawName, authorLang, content)
            }
            refreshAvailableLibraries()
            refreshWorkspaceFilesSuspend()
            _compileOutput.value = withContext(Dispatchers.Default) {
                compiler.compile(_sourceCode.value, useCache = false, target = _binaryTarget.value)
            }
            setBanner("Saved library '$fname' ($loc).")
        }
    }

    fun insertLibraryInclude(libName: String) {
        val normalized = LibraryImporter.normalizeLibraryFilename(libName)
        val includeLine = "#include <$normalized>"
        val cur = _sourceCode.value
        if (!cur.contains(includeLine)) {
            val updated = "$includeLine\n$cur"
            updateSource(updated, syncToGraph = false)
            setBanner("Added $includeLine")
        }
    }

    fun exportBinaryToDownloads() {
        val art = _compileOutput.value.binaryArtifact ?: return
        val base = _activeFileName.value.substringBeforeLast('.').ifBlank { "program" }
        val fileName = "$base${art.target.extension}"
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val outDir = File(workspaceDir, "bin").apply { mkdirs() }
                val binFile = File(outDir, fileName)
                binFile.writeBytes(art.bytes)
                File(outDir, "$base.hex.txt").writeText(art.hexDump)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    runCatching {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = appContext.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            appContext.contentResolver.openOutputStream(uri)?.use { it.write(art.bytes) }
                            values.clear()
                            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            appContext.contentResolver.update(uri, values, null, null)
                        }
                    }
                }
            }
            refreshWorkspaceFilesSuspend()
            setBanner("Exported ${art.bytes.size} bytes to bin/$fileName & Downloads.")
        }
    }

    fun openMacroInEditor(macro: MacroRecord) {
        pushUndo()
        _editingMacroId.value = macro.id
        _editingMacroName.value = macro.name
        _activeFileName.value = "${macro.name}.stl"
        _editorMode.value = EditorViewMode.TEXT
        _sourceCode.value = macro.sourceCode
        updateSourceAsync(macro.sourceCode, syncToGraph = true, debounceMs = 0L)
        setBanner("Editing macro '${macro.name}' in full-width Text mode.")
    }

    fun saveCurrentToMacro() {
        val code = _sourceCode.value
        val existingId = _editingMacroId.value
        val existing = existingId?.let { id -> macroEngine.macros.value.firstOrNull { it.id == id } }
        val inferredName = Regex("""macro\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .find(code)?.groupValues?.getOrNull(1)
            ?: _editingMacroName.value
            ?: _activeFileName.value.removeSuffix(".stl").ifBlank { "CustomMacro" }

        val updated = existing?.copy(name = inferredName, sourceCode = code)
            ?: MacroRecord(
                id = "macro_${System.currentTimeMillis()}",
                name = inferredName,
                description = "Saved from StudioLang editor",
                sourceCode = code
            )
        macroEngine.upsertMacro(updated)
        _editingMacroId.value = updated.id
        _editingMacroName.value = updated.name
        setBanner("Saved changes to macro '${updated.name}'.")
    }

    fun updateSource(newCode: String, syncToGraph: Boolean = true) {
        pushUndo()
        _sourceCode.value = newCode
        updateSourceAsync(newCode, syncToGraph = syncToGraph, debounceMs = 0L)
    }

    fun formatCode() {
        val snapshot = _sourceCode.value
        viewModelScope.launch {
            val formatted = withContext(Dispatchers.Default) {
                StudioFormatter.formatSource(snapshot)
            }
            updateSource(formatted, syncToGraph = true)
            setBanner("Formatted with StudioLang canonical style.")
        }
    }

    fun updateGraph(newGraph: NodeGraph, syncToSource: Boolean = true) {
        pushUndo()
        _nodeGraph.value = newGraph
        viewModelScope.launch {
            _isCompiling.value = true
            try {
                if (syncToSource) {
                    val (code, compiled) = withContext(Dispatchers.Default) {
                        val c = GraphAstConverter.graphToSource(newGraph)
                        c to compiler.compile(c, target = _binaryTarget.value)
                    }
                    _sourceCode.value = code
                    _compileOutput.value = compiled
                }
                withContext(Dispatchers.IO) {
                    saveActiveWorkspaceFiles()
                }
            } finally {
                _isCompiling.value = false
            }
        }
    }

    fun recordNodeUsed(typeId: String) {
        _recentNodeIds.value = (listOf(typeId) + _recentNodeIds.value.filterNot { it == typeId }).take(8)
    }

    fun toggleFavouriteNode(typeId: String) {
        _favouriteNodeIds.value = if (typeId in _favouriteNodeIds.value) {
            _favouriteNodeIds.value - typeId
        } else {
            _favouriteNodeIds.value + typeId
        }
    }

    fun undoGraphOrCode() {
        if (undoGraphs.isEmpty()) return
        redoGraphs.addLast(_nodeGraph.value to _sourceCode.value)
        val (g, s) = undoGraphs.removeLast()
        _nodeGraph.value = g
        _sourceCode.value = s
        updateSourceAsync(s, syncToGraph = false, debounceMs = 0L)
    }

    fun redoGraphOrCode() {
        if (redoGraphs.isEmpty()) return
        undoGraphs.addLast(_nodeGraph.value to _sourceCode.value)
        val (g, s) = redoGraphs.removeLast()
        _nodeGraph.value = g
        _sourceCode.value = s
        updateSourceAsync(s, syncToGraph = false, debounceMs = 0L)
    }

    private fun pushUndo() {
        undoGraphs.addLast(_nodeGraph.value to _sourceCode.value)
        if (undoGraphs.size > 120) undoGraphs.removeFirst()
        redoGraphs.clear()
    }

    fun loadExample(ex: StudioExample) {
        _editingMacroId.value = null
        _editingMacroName.value = null
        _activeFileName.value = "${ex.id}.stl"
        updateSource(ex.code, syncToGraph = true)
        setBanner("Loaded example '${ex.title}'.")
    }

    fun runCurrent(stepByStep: Boolean = false) {
        executionJob?.cancel()
        stepModeEnabled = stepByStep
        val codeToRun = _sourceCode.value
        _vmLogs.value = emptyList()
        _vmRunning.value = true
        _vmPaused.value = false

        executionJob = viewModelScope.launch {
            val out = withContext(Dispatchers.Default) { compiler.compile(codeToRun) }
            _compileOutput.value = out
            if (!out.success || out.bytecode == null) {
                _vmLogs.value = out.diagnostics.map { VmLogEntry("ERROR", it.format(), it.span.line) }
                _vmRunning.value = false
                return@launch
            }
            val bc = out.bytecode
            val vm = StudioVm(capabilityManager, stdLib)
            activeVm = vm
            val debugger = object : ExecutionDebugger {
                override suspend fun onStep(pc: Int, line: Int, funcName: String, localsSnapshot: Map<String, String>) {
                    _activeDebugLine.value = line
                    _watchLocals.value = localsSnapshot
                    if (stepModeEnabled) {
                        _vmPaused.value = true
                        val gate = CompletableDeferred<Unit>()
                        stepGate = gate
                        gate.await()
                        _vmPaused.value = false
                    }
                }

                override suspend fun requestConfirm(prompt: String): Boolean {
                    val def = CompletableDeferred<Boolean>()
                    confirmDeferred = def
                    _pendingConfirmPrompt.value = prompt
                    return def.await()
                }
            }

            val entryIdx = bc.macros.firstOrNull()?.doFuncIndex ?: 0
            val initialArgs = if (entryIdx != 0) listOf(MapVal()) else emptyList()
            val result = vm.execute(bc, entryIdx, initialArgs, debugger)

            _vmLogs.value = result.logs + VmLogEntry(
                if (result.success) "INFO" else "ERROR",
                if (result.success) {
                    "Finished in ${result.durationMs}ms (${result.instructionsExecuted} ops) -> ${result.returnValue.asDisplayString()}"
                } else {
                    "Failed: ${result.error}"
                }
            )
            _vmRunning.value = false
            _vmPaused.value = false
            _activeDebugLine.value = null
            activeVm = null
        }
    }

    fun stepNext() {
        stepModeEnabled = true
        stepGate?.complete(Unit)
        stepGate = null
    }

    fun togglePauseVm() {
        if (_vmPaused.value) {
            stepModeEnabled = false
            stepGate?.complete(Unit)
            activeVm?.resume()
            _vmPaused.value = false
        } else {
            activeVm?.pause()
            _vmPaused.value = true
        }
    }

    fun stopVm() {
        stepGate?.cancel()
        confirmDeferred?.complete(false)
        _pendingConfirmPrompt.value = null
        permissionRequestDeferred?.complete(false)
        _pendingPermissionRequest.value = null
        activeVm?.cancel()
        executionJob?.cancel()
        _vmRunning.value = false
        _vmPaused.value = false
        _activeDebugLine.value = null
    }

    fun resolveConfirm(approved: Boolean) {
        _pendingConfirmPrompt.value = null
        confirmDeferred?.complete(approved)
        confirmDeferred = null
    }

    fun resolvePermissionRequest(granted: Boolean) {
        _pendingPermissionRequest.value = null
        permissionRequestDeferred?.complete(granted)
        permissionRequestDeferred = null
    }

    fun registerCustomDefinition(
        kind: CustomDefinitionKind,
        name: String,
        summary: String,
        paramName: String,
        caps: Set<StudioCapability>,
        bodyCode: String,
        language: BlockLanguage = BlockLanguage.STUDIOLANG
    ) {
        val cleanName = name.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").ifBlank { "CustomItem" }
        val fullCode = if (language != BlockLanguage.STUDIOLANG) {
            bodyCode.ifBlank { PolyglotEngine.starterTemplate(kind, language, cleanName) }
        } else {
            when (kind) {
                CustomDefinitionKind.TRIGGER -> bodyCode.ifBlank {
                    "trigger $cleanName:\n    source manual\n    when true\n    emit {name: \"$cleanName\"}"
                }
                CustomDefinitionKind.ACTION -> "action $cleanName(${paramName.ifBlank { "arg" }}: text):\n" +
                    (if (caps.isNotEmpty()) "    needs ${caps.joinToString { it.id }}\n" else "") +
                    "    ${bodyCode.ifBlank { "log.info(\"Action $cleanName\")" }}"
                CustomDefinitionKind.BLOCK -> "block $cleanName(${paramName.ifBlank { "x" }} = 1) -> any:\n" +
                    "    ${bodyCode.ifBlank { "return x" }}"
            }
        }
        val params = if (kind == CustomDefinitionKind.TRIGGER || paramName.isBlank()) emptyList()
        else listOf(NodeParamSpec(paramName.trim(), paramName.trim(), PortType.ANY, "1"))

        val def = CustomNodeDefinition(
            id = "custom.${kind.name.lowercase()}.$cleanName",
            name = cleanName,
            kind = kind,
            summary = summary.ifBlank { "Custom ${language.label} ${kind.name.lowercase()} $cleanName" },
            params = params,
            returnType = if (kind == CustomDefinitionKind.BLOCK) PortType.ANY else PortType.FLOW,
            requiredCapabilities = caps,
            sourceCode = fullCode,
            language = language
        )
        NodeRegistry.registerCustom(def)
        setBanner("Registered ${language.label} ${kind.name.lowercase()} '$cleanName' into Palette & Docs.")
    }

    fun autoWireSelectedNodes(selectedNodeIds: Set<String>) {
        val ordered = _nodeGraph.value.nodes.filter { it.id in selectedNodeIds }
        if (ordered.size < 2) return
        val newWires = _nodeGraph.value.wires.toMutableList()
        var wiredCount = 0
        for (i in 0 until ordered.size - 1) {
            val from = ordered[i]
            val to = ordered[i + 1]
            val alreadyExists = newWires.any { it.fromNodeId == from.id && it.toNodeId == to.id }
            if (!alreadyExists) {
                val testGraph = _nodeGraph.value.copy(wires = newWires)
                val check = NodeRegistry.validateConnection(testGraph, from.id, "flow_out", to.id, "flow_in")
                if (check.allowed) {
                    newWires += GraphWire("w_${System.currentTimeMillis()}_$i", from.id, "flow_out", to.id, "flow_in")
                    wiredCount++
                }
            }
        }
        if (wiredCount > 0) {
            updateGraph(_nodeGraph.value.copy(wires = newWires))
            setBanner("Auto-wired $wiredCount node connection(s).")
        } else {
            setBanner("Selected nodes have no compatible flow_out -> flow_in ports to wire.")
        }
    }

    fun inspectPrivilegeStatus(): PrivilegeEnvironmentStatus = PrivilegeBridge.inspectEnvironment(appContext)

    fun makeBlockFromSelection(selectedNodeIds: Set<String>, blockName: String) {
        val selectedNodes = _nodeGraph.value.nodes.filter { it.id in selectedNodeIds }
        if (selectedNodes.isEmpty()) return
        val subGraph = NodeGraph(
            name = blockName,
            nodes = selectedNodes,
            wires = _nodeGraph.value.wires.filter { it.fromNodeId in selectedNodeIds && it.toNodeId in selectedNodeIds }
        )
        val generated = GraphAstConverter.graphToSource(subGraph)
        val cleanName = blockName.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").ifBlank { "CustomBlock" }
        val blockCode = "block $cleanName():\n" + generated.lines().filter { it.isNotBlank() }.joinToString("\n") { "    $it" }
        val caps = selectedNodes.mapNotNull { NodeRegistry.getSpec(it.typeId) }.flatMap { it.requiredCapabilities }.toSet()
        NodeRegistry.registerCustom(
            CustomNodeDefinition(
                id = "custom.block.$cleanName",
                name = cleanName,
                kind = CustomDefinitionKind.BLOCK,
                summary = "Action Block converted from ${selectedNodes.size} nodes.",
                params = emptyList(),
                returnType = PortType.ANY,
                requiredCapabilities = caps,
                sourceCode = blockCode
            )
        )
        setBanner("Created Action Block '$cleanName' from ${selectedNodes.size} nodes.")
    }

    fun exportCustomPackageToWorkspace(): String {
        val defs = NodeRegistry.customDefinitions.value
        val bytes = StlPackageManager.exportPackage("StudioCustomPack", "1.0.0", "Automation Studio User", defs)
        val pkgDir = File(workspaceDir, "packages").apply { mkdirs() }
        val outFile = File(pkgDir, "StudioCustomPack.stlpkg")
        outFile.writeBytes(bytes)
        refreshWorkspaceFiles()
        return outFile.relativeTo(workspaceDir).path
    }

    fun inspectPackageBytes(bytes: ByteArray): StlPackageManifest =
        StlPackageManager.inspectPackageForReview(bytes)

    fun importReviewedPackage(manifest: StlPackageManifest) {
        manifest.definitions.forEach { NodeRegistry.registerCustom(it) }
        setBanner("Imported ${manifest.definitions.size} custom items from ${manifest.packageName}. Review capabilities before running.")
    }

    // ---- APP BUILDER ----
    fun updateAppDesign(design: AppDesign) { _appDesign.value = design }

    fun syncAppDesignToUnifiedIde() {
        unifiedIde.syncAppDesignIntoAndroidIde(_appDesign.value)
    }

    fun switchAcsBuildLanguage(lang: AppBuildLanguage) {
        val updated = _appDesign.value.copy(buildLanguage = lang)
        _appDesign.value = updated
        unifiedIde.switchAndroidBuildLanguage(lang, updated)
    }

    fun buildAppProject() {
        if (unifiedIde.isAcsBuilding.value) {
            setBanner("Gradle build already in progress...")
            return
        }
        viewModelScope.launch {
            runCatching {
                val designSnapshot = _appDesign.value
                val sourceSnapshot = _sourceCode.value
                unifiedIde.executeAndroidGradleBuildPipeline(designSnapshot)
                val out = withContext(Dispatchers.IO) {
                    val compiledLogic = withContext(Dispatchers.Default) {
                        compiler.compile(sourceSnapshot).bytecode
                    }
                    AndroidProjectGenerator.buildAndExport(designSnapshot, generatedAppDir, appBackupsDir, compiledLogic)
                }
                _appBuildOutput.value = out
                refreshWorkspaceFilesSuspend()
                setBanner(
                    if (out.success) "Built Gradle project (${out.generatedFiles.size} files)."
                    else "Build validation warnings: ${out.validation.errors.joinToString("; ")}"
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                setBanner("Build failed: ${err.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun exportUnifiedAndroidZip() {
        if (unifiedIde.isAcsBuilding.value) {
            setBanner("Gradle build already in progress...")
            return
        }
        viewModelScope.launch {
            runCatching {
                val mergedDesign = unifiedIde.applyAppDesignSnapshot(_appDesign.value)
                _appDesign.value = mergedDesign
                val sourceSnapshot = _sourceCode.value
                unifiedIde.executeAndroidGradleBuildPipeline(mergedDesign)
                val out = withContext(Dispatchers.IO) {
                    val compiledLogic = withContext(Dispatchers.Default) {
                        compiler.compile(sourceSnapshot).bytecode
                    }
                    AndroidProjectGenerator.buildAndExport(mergedDesign, generatedAppDir, appBackupsDir, compiledLogic)
                }
                _appBuildOutput.value = out
                refreshWorkspaceFilesSuspend()
                setBanner(
                    if (out.success) "Android Code Studio built & exported Gradle APK CI ZIP (${out.generatedFiles.size} files)!"
                    else "ACS Export warnings: ${out.validation.errors.joinToString("; ")}"
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                setBanner("Export failed: ${err.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun triggerAppPreviewAction(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            val macroMatch = Regex("""macro\.run\(\s*["']([^"']+)["']\s*\)""").find(code)
            if (macroMatch != null) {
                val target = macroMatch.groupValues[1]
                val rec = macroEngine.macros.value.firstOrNull {
                    it.name.equals(target, ignoreCase = true) || it.id == target
                }
                if (rec != null) {
                    val res = withContext(Dispatchers.Default) { macroEngine.runMacro(rec) }
                    val msg = res.logs.joinToString(" | ") { it.message }.ifBlank { res.returnValue.asDisplayString() }
                    _appPreviewLog.value = (listOf("[Macro:${rec.name}] $msg") + _appPreviewLog.value).take(20)
                } else {
                    _appPreviewLog.value = (listOf("[Error] Macro '$target' not found.") + _appPreviewLog.value).take(20)
                }
                return@launch
            }

            val referencedDefs = NodeRegistry.customDefinitions.value
                .filter { def -> code.contains(def.name) }
            val extraCaps = linkedSetOf<StudioCapability>().apply {
                referencedDefs.forEach { addAll(it.requiredCapabilities) }
                if (code.contains("notify.")) add(StudioCapability.NOTIFICATIONS)
                if (code.contains("files.")) add(StudioCapability.FILE_ACCESS)
                if (code.contains("device.")) add(StudioCapability.SYSTEM_INFO)
                if (code.contains("net.")) add(StudioCapability.NETWORK_ACCESS)
            }
            val needsLine = if (extraCaps.isNotEmpty() && !code.contains("needs ")) {
                "needs ${extraCaps.joinToString { it.id }}\n"
            } else ""
            val prefixDefs = referencedDefs.joinToString("\n\n") { it.sourceCode }
            val fullProgram = buildString {
                append(needsLine)
                if (prefixDefs.isNotBlank()) {
                    append(prefixDefs)
                    append("\n\n")
                }
                append(code)
            }

            val out = withContext(Dispatchers.Default) { compiler.compile(fullProgram) }
            if (out.success && out.bytecode != null) {
                val vm = StudioVm(capabilityManager, stdLib)
                val entryIdx = out.bytecode.macros.firstOrNull()?.doFuncIndex ?: 0
                val args = if (entryIdx != 0) listOf(MapVal()) else emptyList()
                val res = vm.execute(out.bytecode, entryIdx, args)
                val msg = res.logs.joinToString(" | ") { it.message }.ifBlank { res.returnValue.asDisplayString() }
                _appPreviewLog.value = (listOf("[AppAction] $msg") + _appPreviewLog.value).take(20)
            } else {
                _appPreviewLog.value = (listOf("[Error] ${out.diagnostics.firstOrNull()?.message}") + _appPreviewLog.value).take(20)
            }
        }
    }

    // ---- AGENT BRIEFING ----
    fun attachImageUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size > AttachmentValidator.MAX_IMAGE_BYTES) {
                        setBanner("Image exceeds 10 MB limit.")
                        return@launch
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                    val scaled = scaleBitmapIfNeeded(bmp, 1280)
                    val att = BriefAttachment(
                        id = "att_${System.currentTimeMillis()}",
                        kind = AttachmentKind.IMAGE,
                        displayName = queryDisplayName(uri),
                        bitmap = scaled
                    )
                    _briefAttachments.value = (_briefAttachments.value + att).take(AttachmentValidator.MAX_ATTACHMENTS_PER_BRIEF)
                }
            }
        }
    }

    fun attachCurrentScreenCapture() {
        val bmp = ScreenCaptureService.instance?.captureLatestBitmap()
        if (bmp == null) {
            setBanner("Start MediaProjection screen capture first to capture current screen.")
            return
        }
        val att = BriefAttachment(
            id = "att_scr_${System.currentTimeMillis()}",
            kind = AttachmentKind.SCREENSHOT,
            displayName = "CapturedScreen.png",
            bitmap = scaleBitmapIfNeeded(bmp, 1280)
        )
        _briefAttachments.value = (_briefAttachments.value + att).take(AttachmentValidator.MAX_ATTACHMENTS_PER_BRIEF)
    }

    fun attachFileUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(uri)
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val res = if (name.lowercase().endsWith(".zip") || name.lowercase().endsWith(".stlpkg")) {
                    AttachmentValidator.validateZipAttachment(name, bytes)
                } else {
                    AttachmentValidator.validateTextAttachment(name, bytes)
                }
                if (!res.accepted) {
                    setBanner(res.rejectionReason ?: "Attachment rejected.")
                    return@launch
                }
                val att = BriefAttachment(
                    id = "att_${System.currentTimeMillis()}",
                    kind = AttachmentKind.FILE,
                    displayName = name,
                    sanitizedTextContent = res.sanitizedText,
                    injectionWarnings = res.injectionWarnings,
                    redactedSecretsCount = res.redactedSecretsCount
                )
                _briefAttachments.value = (_briefAttachments.value + att).take(AttachmentValidator.MAX_ATTACHMENTS_PER_BRIEF)
            }
        }
    }

    fun attachWorkspaceReference(label: String, text: String) {
        val res = AttachmentValidator.sanitizeContent(label, text)
        val att = BriefAttachment(
            id = "att_ref_${System.currentTimeMillis()}",
            kind = AttachmentKind.IN_APP_REF,
            displayName = label,
            sanitizedTextContent = res.sanitizedText,
            injectionWarnings = res.injectionWarnings,
            redactedSecretsCount = res.redactedSecretsCount
        )
        _briefAttachments.value = (_briefAttachments.value + att).take(AttachmentValidator.MAX_ATTACHMENTS_PER_BRIEF)
    }

    fun toggleAttachmentIncluded(id: String) {
        _briefAttachments.value = _briefAttachments.value.map {
            if (it.id == id) it.copy(includeInSend = !it.includeInSend) else it
        }
    }

    fun removeAttachment(id: String) {
        _briefAttachments.value = _briefAttachments.value.filterNot { it.id == id }
    }

    fun setSpeakingOnlyMode(enabled: Boolean) {
        _speakingOnlyMode.value = enabled
        if (enabled) {
            voiceController.initTts()
            setBanner("Only Speaking Mode ON: AI reads everything you send and replies by voice only.")
        } else {
            voiceController.stopSpeaking()
            setBanner("Switched back to Action Plan Mode.")
        }
    }

    fun speakMessageAloud(text: String) {
        viewModelScope.launch {
            voiceController.initTts()
            voiceController.speakAndAwait(text)
        }
    }

    fun markFirstLaunchPermissionsPromptShown() {
        onboardingPrefs.edit().putBoolean("first_launch_perms_prompted", true).apply()
        _showFirstLaunchPermissionsDialog.value = false
    }

    fun reopenFirstLaunchPermissionsDialog() {
        _showFirstLaunchPermissionsDialog.value = true
    }

    fun getAllRuntimePermissionsToRequest(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun submitBriefToAgent(ideaText: String) {
        if (ideaText.isBlank() && _briefAttachments.value.none { it.includeInSend }) return
        val atts = _briefAttachments.value.filter { it.includeInSend }
        val userMsg = AgentMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = ideaText.ifBlank { "(Sent ${atts.size} attachment(s))" },
            attachmentNames = atts.map { it.displayName }
        )
        _agentMessages.value = _agentMessages.value + userMsg
        _briefAttachments.value = emptyList()
        _agentStatus.value = AgentStatus.PLANNING

        val speakOnly = _speakingOnlyMode.value
        val historySnapshot = _agentMessages.value.map { it.isUser to it.text }

        viewModelScope.launch {
            try {
                if (speakOnly) {
                    _pendingPlan.value = null
                    val reply = aiProvider.chatOnlyReply(
                        userMessage = ideaText,
                        attachments = atts,
                        currentSource = _sourceCode.value,
                        conversationHistory = historySnapshot
                    )
                    _agentStatus.value = AgentStatus.IDLE
                    _agentMessages.value = _agentMessages.value + AgentMessage(
                        id = "a_${System.currentTimeMillis()}",
                        isUser = false,
                        text = reply
                    )
                    voiceController.initTts()
                    voiceController.speakAndAwait(reply)
                } else {
                    val plan = aiProvider.planFromBrief(ideaText, atts, _sourceCode.value)
                    _pendingPlan.value = plan
                    _agentStatus.value = AgentStatus.WAITING_APPROVAL
                    _agentMessages.value = _agentMessages.value + AgentMessage(
                        id = "a_${System.currentTimeMillis()}",
                        isUser = false,
                        text = plan.summary,
                        plan = plan
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _agentStatus.value = AgentStatus.ERROR
                val errMsg = "Agent error: ${e.localizedMessage ?: "Unknown failure"}"
                _agentMessages.value = _agentMessages.value + AgentMessage(
                    id = "err_${System.currentTimeMillis()}",
                    isUser = false,
                    text = errMsg
                )
                if (speakOnly) {
                    voiceController.speakAndAwait(errMsg)
                }
            }
        }
    }

    fun approvePendingPlan() {
        val plan = _pendingPlan.value ?: return
        _pendingPlan.value = null
        _agentStatus.value = AgentStatus.ACTING
        var snapshot = AgentToolExecutor.WorkspaceSnapshot(_nodeGraph.value, _sourceCode.value, _appDesign.value)
        viewModelScope.launch {
            val results = mutableListOf<String>()
            var lastCompile = _compileOutput.value
            for (call in plan.toolCalls) {
                val (ok, msg) = withContext(Dispatchers.Default) {
                    toolExecutor.executeTool(
                        call = call,
                        current = snapshot,
                        onUpdate = { updated ->
                            snapshot = updated
                            lastCompile = compiler.compile(updated.sourceCode)
                        }
                    )
                }
                _nodeGraph.value = snapshot.graph
                _sourceCode.value = snapshot.sourceCode
                _compileOutput.value = lastCompile
                _appDesign.value = snapshot.appDesign
                results += "${if (ok) "OK" else "FAIL"}: $msg"
            }
            withContext(Dispatchers.IO) {
                saveActiveWorkspaceFiles()
            }
            _agentStatus.value = AgentStatus.VERIFYING
            val verifyMsg = if (lastCompile.success) {
                "Verified: compiled with 0 errors (${lastCompile.bytecode?.instructions?.size ?: 0} bytecode ops)."
            } else {
                "Validation issues: ${lastCompile.diagnostics.joinToString("; ") { it.message }}"
            }
            _agentMessages.value = _agentMessages.value + AgentMessage(
                id = "res_${System.currentTimeMillis()}",
                isUser = false,
                text = (results + verifyMsg).joinToString("\n")
            )
            _agentStatus.value = AgentStatus.IDLE
        }
    }

    fun cancelPendingPlan() {
        _pendingPlan.value = null
        _agentStatus.value = AgentStatus.IDLE
    }

    fun undoLastAgentChange() {
        val prev = toolExecutor.popUndoSnapshot() ?: return
        _nodeGraph.value = prev.graph
        _sourceCode.value = prev.sourceCode
        _appDesign.value = prev.appDesign
        viewModelScope.launch {
            val compiled = withContext(Dispatchers.Default) {
                compiler.compile(prev.sourceCode)
            }
            _compileOutput.value = compiled
            withContext(Dispatchers.IO) {
                saveActiveWorkspaceFiles()
            }
            setBanner("Rolled back last Agent change.")
        }
    }

    private val _ideAiBusy = MutableStateFlow(false)
    val ideAiBusy: StateFlow<Boolean> = _ideAiBusy.asStateFlow()

    private val _ideAiLastReply = MutableStateFlow<String?>(null)
    val ideAiLastReply: StateFlow<String?> = _ideAiLastReply.asStateFlow()

    fun testAiConnection() {
        viewModelScope.launch {
            _aiTestResult.value = "Testing connection..."
            try {
                val msg = aiProvider.testConnection()
                _aiTestResult.value = "OK: $msg"
                setBanner("OK: $msg")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = "FAIL: ${e.localizedMessage ?: "Connection failed"}"
                _aiTestResult.value = err
                setBanner(err)
            }
        }
    }

    fun askActiveAiForIde(userPrompt: String, applyToActiveFile: Boolean = true) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isEmpty()) return
        val cfg = aiProvider.config.value
        if (!cfg.hasApiKey && cfg.provider != AiProviderKind.CUSTOM_OPENAI) {
            val missingMsg = "FAIL: Missing ${cfg.provider.displayName} API key. Tap 'Open AI Key Setup' below or configure your key in the Agent tab."
            _ideAiLastReply.value = missingMsg
            setBanner(missingMsg)
            return
        }
        viewModelScope.launch {
            _ideAiBusy.value = true
            _ideAiLastReply.value = "Asking ${aiProvider.providerLabel}..."
            try {
                val activePath = unifiedIde.activeTabPath.value
                val activeContent = unifiedIde.files.value.firstOrNull { it.path == activePath }?.content.orEmpty()
                val sys = buildString {
                    appendLine("You are an expert coding assistant inside Automation Studio Tri-Pane IDE (VS Code + Godot + Android Code Studio).")
                    if (applyToActiveFile) {
                        appendLine("Return ONLY raw updated source code for '$activePath' with NO markdown fences.")
                    } else {
                        appendLine("Provide a concise answer and code snippet.")
                    }
                }
                val usr = buildString {
                    appendLine("ACTIVE FILE: $activePath")
                    appendLine("CURRENT CODE:")
                    appendLine(activeContent.take(6000))
                    appendLine()
                    appendLine("REQUEST: $cleanPrompt")
                }
                val rawReply = aiProvider.askDirectPrompt(sys, usr).trim()
                val cleaned = rawReply
                    .replace(Regex("^```[a-zA-Z0-9_-]*\\s*\\n?"), "")
                    .replace(Regex("\\n?```\\s*$"), "")
                    .trim()
                if (applyToActiveFile && cleaned.isNotBlank()) {
                    unifiedIde.updateActiveFileContent(cleaned)
                    _ideAiLastReply.value = "Updated $activePath with ${aiProvider.providerLabel}"
                    setBanner("AI (${aiProvider.providerLabel}) updated $activePath")
                } else {
                    _ideAiLastReply.value = cleaned.take(400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = "FAIL: ${e.localizedMessage ?: "AI request failed"}"
                _ideAiLastReply.value = err
                setBanner(err)
            } finally {
                _ideAiBusy.value = false
            }
        }
    }

    fun startUniversalObserve(goal: String, speakFirst: Boolean = true, withFloatingOverlay: Boolean = false) {
        if (speakFirst) voiceController.initTts()
        if (withFloatingOverlay) {
            if (!Settings.canDrawOverlays(appContext)) {
                setBanner("Grant Overlay permission in Android Settings to show the floating AI window.")
                AndroidCustomizer.openOverlayConsent(appContext)
            } else {
                FloatingAgentOverlayService.updateStatus("Goal: $goal")
                if (!FloatingAgentOverlayService.start(appContext, "Goal: $goal")) {
                    setBanner("Could not start Floating AI Overlay service (check notification/battery settings).")
                }
            }
        }
        if (!StudioAccessibilityService.isEnabledInSettings(appContext) && StudioAccessibilityService.instance == null) {
            setBanner("Accessibility Service is Off — enable Automation Studio in Android Accessibility Settings to control foreground apps.")
        }
        godotObserve.start(
            scope = viewModelScope,
            goal = goal,
            speakBeforeAct = if (speakFirst) { msg ->
                FloatingAgentOverlayService.updateStatus(msg)
                voiceController.speakAndAwait(msg)
            } else null
        )
    }

    fun triggerOverlayVoiceCommand() {
        voiceController.initTts()
        voiceController.startListening { spokenCommand ->
            viewModelScope.launch {
                val planNotice = "Heard command: $spokenCommand. Inspecting foreground screen and executing now."
                FloatingAgentOverlayService.updateStatus(planNotice)
                voiceController.speakAndAwait(planNotice)
                startUniversalObserve(goal = spokenCommand, speakFirst = true, withFloatingOverlay = true)
            }
        }
    }

    fun startFloatingEditorMode() {
        if (!Settings.canDrawOverlays(appContext)) {
            setBanner("Grant Overlay permission first — opening Android Settings...")
            AndroidCustomizer.openOverlayConsent(appContext)
            return
        }
        voiceController.initTts()
        FloatingAgentOverlayService.updateStatus("Editor Mode Ready. Listening on Mic...")
        val started = FloatingAgentOverlayService.start(appContext, "Editor Mode Active — Tap Mic or Speak")
        if (!started) {
            setBanner("Could not start Floating AI Overlay service (foreground service blocked by Android).")
            return
        }
        viewModelScope.launch {
            voiceController.speakAndAwait("Floating AI Editor activated. Listening for your command.")
            triggerOverlayVoiceCommand()
        }
    }

    fun stopFloatingEditorMode() {
        voiceController.stopListening()
        FloatingAgentOverlayService.stop(appContext)
        godotObserve.stop()
    }

    fun refreshAndroidTweaks() {
        viewModelScope.launch(Dispatchers.IO) {
            _androidTweaks.value = AndroidCustomizer.readDeviceTweaks(appContext)
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = AndroidCustomizer.listInstalledAppsWithPerms(appContext)
            _installedApps.value = apps
            selectAppForPermissions(_selectedAppPackage.value)
        }
    }

    fun selectAppForPermissions(pkg: String) {
        _selectedAppPackage.value = pkg
        viewModelScope.launch(Dispatchers.IO) {
            _selectedAppPerms.value = AndroidCustomizer.getPackagePermissions(appContext, pkg)
        }
    }

    fun runPrivilegedAction(label: String, block: suspend () -> PrivilegedCommandResult) {
        viewModelScope.launch {
            val res = block()
            val msg = if (res.ok) {
                "[$label via ${res.backend}] OK ${res.stdout.trim()}".trim()
            } else {
                "[$label via ${res.backend}] ${res.stderr.ifBlank { "Exit ${res.exitCode}" }}"
            }
            _privilegeCommandOutput.value = msg
            setBanner(msg)
            refreshAndroidTweaks()
            selectAppForPermissions(_selectedAppPackage.value)
        }
    }

    fun emergencyStopAll() {
        stopVm()
        macroEngine.stopActive()
        godotObserve.stop()
        voiceController.stopListening()
        voiceController.stopSpeaking()
        FloatingAgentOverlayService.stop(appContext)
        AutomationRunnerService.stop(appContext)
        _agentStatus.value = AgentStatus.IDLE
        setBanner("Emergency Stop executed: all VM, macro, voice, overlay, and Agent loops halted.")
    }

    fun runBenchmarks() {
        viewModelScope.launch(Dispatchers.Default) {
            _benchmarkReport.value = BenchmarkSuite.runAll(compiler, capabilityManager, stdLib)
        }
    }

    fun inspectTermuxStatus(): TermuxEnvironmentStatus = TermuxToolchainBridge.inspectStatus(appContext)

    fun refreshWorkspaceFiles() {
        viewModelScope.launch {
            refreshWorkspaceFilesSuspend()
        }
    }

    private suspend fun refreshWorkspaceFilesSuspend() {
        val list = withContext(Dispatchers.IO) {
            workspaceDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(workspaceDir).path }
                .sorted()
                .toList()
        }
        _workspaceFiles.value = list
    }

    private fun saveActiveWorkspaceFiles() {
        val name = _activeFileName.value
        val code = _sourceCode.value
        val graph = _nodeGraph.value
        File(workspaceDir, name).writeText(code)
        File(workspaceDir, "$name.layout.json").writeText(LayoutSidecarSerializer.toJson(graph))
    }

    private fun queryDisplayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "attachment"
    }

    private fun scaleBitmapIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}
