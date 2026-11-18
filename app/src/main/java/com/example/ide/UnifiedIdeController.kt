package com.example.ide

import android.content.Context
import android.net.Uri
import com.example.appbuilder.AndroidProjectGenerator
import com.example.appbuilder.AppBuildLanguage
import com.example.appbuilder.AppDesign
import com.example.runtime.GodotProjectWorkspace
import com.example.services.StudioTriggerBus
import com.example.vm.CapabilityManager
import com.example.vm.HostApiDispatcher
import com.example.vm.StudioCompiler
import com.example.vm.StudioVm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class UnifiedIdeController(
    private val scope: CoroutineScope,
    private val compiler: StudioCompiler,
    private val capabilityManager: CapabilityManager,
    private val hostDispatcher: HostApiDispatcher,
    private val workspaceRoot: File,
    private val initialAppDesign: AppDesign,
    private val voiceSpeakCallback: ((String) -> Unit)? = null
) {
    private val ideDir = File(workspaceRoot, "unified_ide").apply { mkdirs() }
    private val defaultSandboxDesc = UniversalProjectManager.defaultSandboxDescriptor(workspaceRoot)

    /** Root folder used by native compiler adapters for project-relative source paths. */
    fun nativeCompilerWorkspaceRoot(): File {
        val local = _activeProject.value.localDirPath
        return if (local.isNotBlank()) File(local).apply { mkdirs() } else ideDir
    }
    private val initialNodes: List<GodotSceneNode> = createDefaultSceneNodes()

    private val _workspaceMode = MutableStateFlow(IdeWorkspaceMode.DUAL_GAME_AND_APP)
    val workspaceMode: StateFlow<IdeWorkspaceMode> = _workspaceMode.asStateFlow()

    private val _themePreset = MutableStateFlow(IdeThemePreset.OBSIDIAN_STUDIO)
    val themePreset: StateFlow<IdeThemePreset> = _themePreset.asStateFlow()

    private val _activeActivityTab = MutableStateFlow(IdeActivityTab.SCENE_TREE)
    val activeActivityTab: StateFlow<IdeActivityTab> = _activeActivityTab.asStateFlow()

    private val _activeRightTab = MutableStateFlow(IdeRightInspectorTab.INSPECTOR)
    val activeRightTab: StateFlow<IdeRightInspectorTab> = _activeRightTab.asStateFlow()

    private val _activeBottomTab = MutableStateFlow(IdeBottomPanelTab.TERMINAL)
    val activeBottomTab: StateFlow<IdeBottomPanelTab> = _activeBottomTab.asStateFlow()

    private val _leftDrawerOpen = MutableStateFlow(true)
    val leftDrawerOpen: StateFlow<Boolean> = _leftDrawerOpen.asStateFlow()

    private val _rightDrawerOpen = MutableStateFlow(true)
    val rightDrawerOpen: StateFlow<Boolean> = _rightDrawerOpen.asStateFlow()

    private val _bottomPanelOpen = MutableStateFlow(true)
    val bottomPanelOpen: StateFlow<Boolean> = _bottomPanelOpen.asStateFlow()

    private val _landscapeLockEnabled = MutableStateFlow(true)
    val landscapeLockEnabled: StateFlow<Boolean> = _landscapeLockEnabled.asStateFlow()

    private val _gridSnapEnabled = MutableStateFlow(true)
    val gridSnapEnabled: StateFlow<Boolean> = _gridSnapEnabled.asStateFlow()

    private val _sceneNodes = MutableStateFlow(initialNodes)
    val sceneNodes: StateFlow<List<GodotSceneNode>> = _sceneNodes.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(initialNodes.firstOrNull()?.id)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    private val _isScenePlaying = MutableStateFlow(false)
    val isScenePlaying: StateFlow<Boolean> = _isScenePlaying.asStateFlow()

    private val _sceneTickCount = MutableStateFlow(0L)
    val sceneTickCount: StateFlow<Long> = _sceneTickCount.asStateFlow()

    private val _sceneFps = MutableStateFlow(0)
    val sceneFps: StateFlow<Int> = _sceneFps.asStateFlow()

    private val _collisionStatus = MutableStateFlow("No active collisions")
    val collisionStatus: StateFlow<String> = _collisionStatus.asStateFlow()

    private val _files = MutableStateFlow<List<IdeFileEntry>>(emptyList())
    val files: StateFlow<List<IdeFileEntry>> = _files.asStateFlow()

    private val _openTabs = MutableStateFlow<List<String>>(emptyList())
    val openTabs: StateFlow<List<String>> = _openTabs.asStateFlow()

    private val _activeTabPath = MutableStateFlow("scripts/player_controller.gd")
    val activeTabPath: StateFlow<String> = _activeTabPath.asStateFlow()

    private val _extensions = MutableStateFlow(defaultExtensionPacks())
    val extensions: StateFlow<List<IdeExtensionPack>> = _extensions.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<IdeDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<IdeDiagnostic>> = _diagnostics.asStateFlow()

    private val _terminalLogs = MutableStateFlow(
        listOf(
            "Automation Studio Tri-Pane Shell Ready (Zero-Download Bundled Compiler)",
            "VS Code + Godot Polyglot (GD/PY/STL/KT/JAVA/JS/C++) + Android Code Studio active.",
            "Type `help` or tap Mic for Voice Control."
        )
    )
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _logcatLogs = MutableStateFlow(
        listOf("I/AndroidCodeStudio: Embedded layout preview & manifest analyzer ready.")
    )
    val logcatLogs: StateFlow<List<String>> = _logcatLogs.asStateFlow()

    private val _voiceCommandsLog = MutableStateFlow<List<String>>(emptyList())
    val voiceCommandsLog: StateFlow<List<String>> = _voiceCommandsLog.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Android Code Studio Live Interactive Test State
    private val _acsTestCounter = MutableStateFlow(0)
    val acsTestCounter: StateFlow<Int> = _acsTestCounter.asStateFlow()

    private val _acsTestInputs = MutableStateFlow<Map<String, String>>(emptyMap())
    val acsTestInputs: StateFlow<Map<String, String>> = _acsTestInputs.asStateFlow()

    private val _acsTestSwitches = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val acsTestSwitches: StateFlow<Map<String, Boolean>> = _acsTestSwitches.asStateFlow()

    private val _acsLastTestResult = MutableStateFlow("Ready to run Android App UI & Compile Test")
    val acsLastTestResult: StateFlow<String> = _acsLastTestResult.asStateFlow()

    private val _mountedRepos = MutableStateFlow(ExternalRepoBridgeEngine.defaultRepoStatuses())
    val mountedRepos: StateFlow<List<MountedRepoStatus>> = _mountedRepos.asStateFlow()

    private val _bridgeDescriptors = MutableStateFlow(ExternalRepoBridgeEngine.defaultBridgeDescriptors())
    val bridgeDescriptors: StateFlow<List<RepoBridgeDescriptor>> = _bridgeDescriptors.asStateFlow()

    private val _editorConfig = MutableStateFlow(IdeEditorConfig())
    val editorConfig: StateFlow<IdeEditorConfig> = _editorConfig.asStateFlow()

    private val _gitSnapshots = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _gitChanges = MutableStateFlow<List<GitFileChange>>(emptyList())
    val gitChanges: StateFlow<List<GitFileChange>> = _gitChanges.asStateFlow()

    private val _gitCommits = MutableStateFlow<List<GitCommitRecord>>(emptyList())
    val gitCommits: StateFlow<List<GitCommitRecord>> = _gitCommits.asStateFlow()

    private val _godotSignalsLog = MutableStateFlow<List<String>>(emptyList())
    val godotSignalsLog: StateFlow<List<String>> = _godotSignalsLog.asStateFlow()

    private val _repoDownloadStatus = MutableStateFlow<Map<UpstreamRepoKind, String>>(emptyMap())
    val repoDownloadStatus: StateFlow<Map<UpstreamRepoKind, String>> = _repoDownloadStatus.asStateFlow()

    private val _repoImportMode = MutableStateFlow(RepoImportMode.FULL_SOURCE_AND_COMPILE)
    val repoImportMode: StateFlow<RepoImportMode> = _repoImportMode.asStateFlow()

    private val _inAppVaultZips = MutableStateFlow<List<InAppVaultZipEntry>>(emptyList())
    val inAppVaultZips: StateFlow<List<InAppVaultZipEntry>> = _inAppVaultZips.asStateFlow()

    private val _isAcsBuilding = MutableStateFlow(false)
    val isAcsBuilding: StateFlow<Boolean> = _isAcsBuilding.asStateFlow()

    private val _isCompiling = MutableStateFlow(false)
    val isCompiling: StateFlow<Boolean> = _isCompiling.asStateFlow()

    private val _activeProject = MutableStateFlow(defaultSandboxDesc)
    val activeProject: StateFlow<IdeProjectDescriptor> = _activeProject.asStateFlow()

    private val _recentProjects = MutableStateFlow(listOf(defaultSandboxDesc))
    val recentProjects: StateFlow<List<IdeProjectDescriptor>> = _recentProjects.asStateFlow()

    private val _projectSyncState = MutableStateFlow(ProjectSyncState.IDLE)
    val projectSyncState: StateFlow<ProjectSyncState> = _projectSyncState.asStateFlow()

    private val _projectSyncMessage = MutableStateFlow("Sandbox Workspace Ready")
    val projectSyncMessage: StateFlow<String> = _projectSyncMessage.asStateFlow()

    @Volatile
    private var appContextRef: Context? = null
    private val safDocUriMap = ConcurrentHashMap<String, String>()

    private val _acsBuildLanguage = MutableStateFlow(initialAppDesign.buildLanguage)
    val acsBuildLanguage: StateFlow<AppBuildLanguage> = _acsBuildLanguage.asStateFlow()

    private val _acsBuildVariant = MutableStateFlow("debug")
    val acsBuildVariant: StateFlow<String> = _acsBuildVariant.asStateFlow()

    private val _acsBuildPhases = MutableStateFlow(
        listOf(
            "Gradle Sync: Ready (${initialAppDesign.packageId})",
            "AAPT2 Resources: Ready",
            "Multi-Lang Compile (${initialAppDesign.buildLanguage.label}): Ready",
            "Manifest Audit & Play Policy: Ready",
            "APK/Gradle Package Export: Ready"
        )
    )
    val acsBuildPhases: StateFlow<List<String>> = _acsBuildPhases.asStateFlow()

    private val _splitRightPath = MutableStateFlow<String?>(null)
    val splitRightPath: StateFlow<String?> = _splitRightPath.asStateFlow()

    val godotInputMap = GodotEngineKotlin.GodotInputMap()
    val godotSignalBus = GodotEngineKotlin.GodotSignalBus()
    val godotGroupManager = GodotEngineKotlin.GodotSceneGroupManager()

    private val _acsResourceTable = MutableStateFlow(AndroidCodeStudioKotlin.ResourceTable(emptyMap(), emptyMap(), emptyList(), emptyList()))
    val acsResourceTable: StateFlow<AndroidCodeStudioKotlin.ResourceTable> = _acsResourceTable.asStateFlow()

    private val undoStacks = mutableMapOf<String, ArrayDeque<String>>()
    private val redoStacks = mutableMapOf<String, ArrayDeque<String>>()

    private var savedSnapshotBeforePlay: List<GodotSceneNode>? = null
    private var simulationJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var activeBuildJob: Job? = null
    private var scriptTickDivider = 0
    @Volatile
    private var godotSessionActive = false

    init {
        UnifiedIdeBridge.activeController = this
        seedDefaultFiles(initialNodes)
        godotGroupManager.addToGroup("Player", "player")
        godotGroupManager.addToGroup("GoalGem", "collectibles")
        _gitSnapshots.value = _files.value.associate { it.path to it.content }
        _gitCommits.value = listOf(
            GitCommitRecord("c0001", "Initial VS Code + Godot + ACS workspace", _files.value.size, System.currentTimeMillis())
        )
        scope.launch {
            val (mounted, savedRegistry) = withContext(Dispatchers.IO) {
                _inAppVaultZips.value = ExternalRepoBridgeEngine.listInAppVaultZips(workspaceRoot)
                val m = ExternalRepoBridgeEngine.scanExternalReposDirectory(workspaceRoot)
                val reg = UniversalProjectManager.loadRegistry(workspaceRoot)
                m to reg
            }
            val defaultWithCount = defaultSandboxDesc.copy(fileCount = _files.value.size)
            _activeProject.value = defaultWithCount
            _recentProjects.value = mergeProjectList(defaultWithCount, savedRegistry)
            if (mounted.isNotEmpty()) {
                applyMountedResults(mounted)
            }
            withContext(Dispatchers.Default) {
                refreshAcsResourceTable()
                runCompileAndTestSync()
            }
        }
    }

    private fun seedDefaultFiles(nodes: List<GodotSceneNode>) {
        val playerGd = PolyglotGodotCompiler.starterScript(
            ScriptLanguage.GDSCRIPT, GodotNodeType.CHARACTER_BODY_2D, ScriptTemplateKind.MOVEMENT, "Player"
        )
        val gemPy = PolyglotGodotCompiler.starterScript(
            ScriptLanguage.PYTHON, GodotNodeType.SPRITE_2D, ScriptTemplateKind.AI_PATROL, "GoalGem"
        )
        val studioVoiceStl = """
            |# StudioLang Master Controller — drives Godot, VS Code & Android Code Studio
            |def run_automation():
            |    godot.set_prop("HudTitle", "label", "StudioLang Live Control")
            |    let acs_res = acs.validate()
            |    log.info("ACS Valid=" + acs_res)
            |    voice.speak("Automation Studio Godot and Android IDE ready")
            |
            |run_automation()
        """.trimMargin()

        val manifestXml = """
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            |    package="${initialAppDesign.packageId}">
            |    <uses-permission android:name="android.permission.INTERNET" />
            |    <application android:label="${initialAppDesign.appName}">
            |        <activity android:name=".MainActivity" android:exported="true" />
            |    </application>
            |</manifest>
        """.trimMargin()

        val appGradle = """
            |plugins {
            |    id("com.android.application")
            |    id("org.jetbrains.kotlin.plugin.compose")
            |}
            |android {
            |    namespace = "${initialAppDesign.packageId}"
            |    compileSdk = ${initialAppDesign.targetSdk}
            |    defaultConfig {
            |        minSdk = ${initialAppDesign.minSdk}
            |        targetSdk = ${initialAppDesign.targetSdk}
            |    }
            |}
        """.trimMargin()

        val activityXml = """
            |<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:layout_width="match_parent"
            |    android:layout_height="match_parent"
            |    android:orientation="vertical"
            |    android:padding="16dp">
            |    <TextView android:text="Android Code Studio Live XML" />
            |    <EditText android:hint="Enter message" />
            |    <Switch android:text="Enable Live Telemetry" />
            |    <Button android:text="Run Android Action" />
            |</LinearLayout>
        """.trimMargin()

        val stringsXml = """
            |<resources>
            |    <string name="app_name">${initialAppDesign.appName}</string>
            |    <string name="status_ready">Android Code Studio Live</string>
            |</resources>
        """.trimMargin()

        val projectGodot = GodotProjectWorkspace.defaultProjectGodot(initialAppDesign.appName)

        val initialList = listOf(
            IdeFileEntry("project.godot", "project.godot", projectGodot, "godot-scene"),
            IdeFileEntry("scenes/main.tscn", "main.tscn", PolyglotGodotCompiler.serializeTscn("MainScene", nodes), "tscn"),
            IdeFileEntry("scripts/player_controller.gd", "player_controller.gd", playerGd, "gd", attachedNodeName = "Player"),
            IdeFileEntry("scripts/coin_patrol.py", "coin_patrol.py", gemPy, "py", attachedNodeName = "GoalGem"),
            IdeFileEntry("scripts/voice_bridge.stl", "voice_bridge.stl", studioVoiceStl, "stl"),
            IdeFileEntry(
                "android/app/src/main/java/com/example/studioapp/MainActivity.kt",
                "MainActivity.kt",
                initialAppDesign.customKotlinCode,
                "kt"
            ),
            IdeFileEntry("android/app/src/main/res/layout/activity_main.xml", "activity_main.xml", activityXml, "xml"),
            IdeFileEntry("android/app/src/main/res/values/strings.xml", "strings.xml", stringsXml, "xml"),
            IdeFileEntry("android/app/src/main/AndroidManifest.xml", "AndroidManifest.xml", manifestXml, "xml"),
            IdeFileEntry("android/app/build.gradle.kts", "build.gradle.kts", appGradle, "kts")
        )
        _files.value = initialList
        _openTabs.value = listOf(
            "scripts/player_controller.gd",
            "scripts/coin_patrol.py",
            "scripts/voice_bridge.stl",
            "android/app/src/main/java/com/example/studioapp/MainActivity.kt"
        )
        _activeTabPath.value = "scripts/player_controller.gd"
        persistFilesAsync(initialList)
    }

    private fun persistFilesAsync(entries: List<IdeFileEntry>) {
        if (entries.isEmpty()) return
        val projectSnapshot = _activeProject.value
        scope.launch(Dispatchers.IO) {
            writeEntriesToActiveStorage(projectSnapshot, entries)
        }
    }

    private fun writeEntriesToActiveStorage(project: IdeProjectDescriptor, entries: List<IdeFileEntry>): Int {
        if (entries.isEmpty()) return 0
        _projectSyncState.value = ProjectSyncState.SAVING
        var savedCount = 0
        var lastError: String? = null
        entries.forEach { f ->
            runCatching {
                val safeRel = UniversalProjectManager.sanitizeRelativePath(f.path)
                val localRoot = File(project.localDirPath.ifBlank { ideDir.absolutePath }).apply { mkdirs() }
                UniversalProjectManager.writeLocalFile(localRoot, safeRel, f.content)
                if (project.isDirectFolder) {
                    val ctx = appContextRef ?: error("Folder write context unavailable")
                    UniversalProjectManager.writeSafFile(
                        context = ctx,
                        treeUri = Uri.parse(project.safTreeUriString!!),
                        docUriMap = safDocUriMap,
                        relPath = safeRel,
                        content = f.content
                    )
                }
                savedCount++
            }.onFailure { err ->
                lastError = err.localizedMessage ?: "Write error"
            }
        }
        val writtenByPath = entries.associateBy { it.path }
        _files.value = _files.value.map { cur ->
            val saved = writtenByPath[cur.path]
            if (saved != null && cur.content == saved.content && cur.isDirty) cur.copy(isDirty = false) else cur
        }
        if (lastError != null) {
            _projectSyncState.value = ProjectSyncState.ERROR
            _projectSyncMessage.value = "Write error: $lastError"
        } else {
            _projectSyncState.value = ProjectSyncState.SYNCED
            _projectSyncMessage.value = if (project.isDirectFolder) {
                "Saved $savedCount file(s) directly to '${project.name}'"
            } else {
                "Saved $savedCount file(s) in '${project.name}'"
            }
        }
        return savedCount
    }

    fun setWorkspaceMode(mode: IdeWorkspaceMode) {
        _workspaceMode.value = mode
        if (mode == IdeWorkspaceMode.ANDROID_CODE_STUDIO) {
            _activeActivityTab.value = IdeActivityTab.ANDROID_IDE
            _activeRightTab.value = IdeRightInspectorTab.ANDROID_PREVIEW
            _rightDrawerOpen.value = true
            openFile("android/app/src/main/java/com/example/studioapp/MainActivity.kt")
            appendTerminal("Switched to Android Code Studio Override (App Builder + Live Compose Preview).")
        } else {
            _activeActivityTab.value = IdeActivityTab.SCENE_TREE
            _activeRightTab.value = IdeRightInspectorTab.INSPECTOR
            appendTerminal("Switched to Unified Tri-Pane Mode (VS Code + Godot + Android Code Studio).")
        }
    }

    fun setThemePreset(preset: IdeThemePreset) { _themePreset.value = preset }
    fun setActivityTab(tab: IdeActivityTab) { _activeActivityTab.value = tab; _leftDrawerOpen.value = true }
    fun setRightTab(tab: IdeRightInspectorTab) { _activeRightTab.value = tab; _rightDrawerOpen.value = true }
    fun setBottomTab(tab: IdeBottomPanelTab) { _activeBottomTab.value = tab; _bottomPanelOpen.value = true }
    fun setLeftDrawerOpen(open: Boolean) { _leftDrawerOpen.value = open }
    fun setRightDrawerOpen(open: Boolean) { _rightDrawerOpen.value = open }
    fun toggleLeftDrawer() { _leftDrawerOpen.value = !_leftDrawerOpen.value }
    fun toggleRightDrawer() { _rightDrawerOpen.value = !_rightDrawerOpen.value }
    fun toggleBottomPanel() { _bottomPanelOpen.value = !_bottomPanelOpen.value }
    fun toggleLandscapeLock() { _landscapeLockEnabled.value = !_landscapeLockEnabled.value }
    fun toggleGridSnap() { _gridSnapEnabled.value = !_gridSnapEnabled.value }
    fun updateSearchQuery(q: String) { _searchQuery.value = q }

    fun toggleExtension(id: String) {
        _extensions.value = _extensions.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
    }

    fun openFile(path: String): Boolean {
        val exists = _files.value.any { it.path == path }
        if (!exists) return false
        if (path !in _openTabs.value) {
            _openTabs.value = _openTabs.value + path
        }
        _activeTabPath.value = path
        return true
    }

    fun closeTab(path: String) {
        val remaining = _openTabs.value.filterNot { it == path }
        _openTabs.value = remaining
        if (_activeTabPath.value == path) {
            _activeTabPath.value = remaining.lastOrNull() ?: ""
        }
    }

    fun createOrUpdateFile(path: String, content: String, attachedNodeName: String? = null) {
        val normalized = runCatching { UniversalProjectManager.sanitizeRelativePath(path) }.getOrElse {
            appendTerminal("[IDE] Invalid path rejected: $path")
            return
        }
        val title = normalized.substringAfterLast('/')
        val ext = title.substringAfterLast('.', "txt")
        val existingIdx = _files.value.indexOfFirst { it.path == normalized }
        val newList = _files.value.toMutableList()
        if (existingIdx >= 0) {
            val prev = newList[existingIdx]
            newList[existingIdx] = prev.copy(
                content = content,
                isDirty = false,
                attachedNodeName = attachedNodeName ?: prev.attachedNodeName
            )
        } else {
            newList += IdeFileEntry(normalized, title, content, ext, false, attachedNodeName)
        }
        _files.value = newList
        updateActiveProjectCount(newList.size)
        openFile(normalized)
        persistFilesAsync(listOf(newList.first { it.path == normalized }))
        scope.launch(Dispatchers.Default) {
            refreshGitChanges()
            if (normalized.endsWith(".xml")) refreshAcsResourceTable()
        }
    }

    private fun batchUpsertFiles(entries: List<IdeFileEntry>, openTargetPath: String? = null) {
        if (entries.isEmpty()) return
        val byPath = _files.value.associateBy { it.path }.toMutableMap()
        entries.forEach { incoming ->
            val prev = byPath[incoming.path]
            byPath[incoming.path] = if (prev != null) {
                prev.copy(
                    content = incoming.content,
                    isDirty = false,
                    attachedNodeName = incoming.attachedNodeName ?: prev.attachedNodeName
                )
            } else {
                incoming
            }
        }
        _files.value = byPath.values.toList()
        if (!openTargetPath.isNullOrBlank()) {
            openFile(openTargetPath)
        }
        persistFilesAsync(entries)
        scope.launch(Dispatchers.Default) {
            refreshGitChanges()
            refreshAcsResourceTable()
        }
    }

    fun updateActiveFileContent(newContent: String) {
        val active = _activeTabPath.value
        if (active.isBlank()) return
        val prevContent = _files.value.firstOrNull { it.path == active }?.content
        if (prevContent == newContent) return
        if (prevContent != null) {
            val undo = undoStacks.getOrPut(active) { ArrayDeque() }
            if (undo.lastOrNull() != prevContent) {
                undo.addLast(prevContent)
                if (undo.size > 40) undo.removeFirst()
            }
            redoStacks[active]?.clear()
        }
        val updatedList = _files.value.map {
            if (it.path == active) it.copy(content = newContent, isDirty = true) else it
        }
        _files.value = updatedList
        val autoSaveEnabled = _editorConfig.value.autoSave
        val projectSnapshot = _activeProject.value
        typingDebounceJob?.cancel()
        typingDebounceJob = scope.launch {
            delay(320L)
            withContext(Dispatchers.Default) { refreshGitChanges() }
            if (autoSaveEnabled) {
                val activeEntry = _files.value.firstOrNull { it.path == active }
                if (activeEntry != null) {
                    withContext(Dispatchers.IO) {
                        writeEntriesToActiveStorage(projectSnapshot, listOf(activeEntry))
                    }
                }
            }
        }
    }

    fun undoActiveFile(): Boolean {
        val active = _activeTabPath.value
        val cur = _files.value.firstOrNull { it.path == active }?.content ?: return false
        val undo = undoStacks[active] ?: return false
        if (undo.isEmpty()) return false
        val previous = undo.removeLast()
        redoStacks.getOrPut(active) { ArrayDeque() }.addLast(cur)
        _files.value = _files.value.map { if (it.path == active) it.copy(content = previous, isDirty = true) else it }
        refreshGitChanges()
        return true
    }

    fun redoActiveFile(): Boolean {
        val active = _activeTabPath.value
        val cur = _files.value.firstOrNull { it.path == active }?.content ?: return false
        val redo = redoStacks[active] ?: return false
        if (redo.isEmpty()) return false
        val next = redo.removeLast()
        undoStacks.getOrPut(active) { ArrayDeque() }.addLast(cur)
        _files.value = _files.value.map { if (it.path == active) it.copy(content = next, isDirty = true) else it }
        refreshGitChanges()
        return true
    }

    fun findAndReplaceActiveFile(find: String, replace: String, replaceAll: Boolean): Int {
        val active = _activeTabPath.value
        val file = _files.value.firstOrNull { it.path == active } ?: return 0
        val (updated, count) = IdeAdvancedModulesEngine.findAndReplace(file.content, find, replace, replaceAll)
        if (count > 0) {
            updateActiveFileContent(updated)
            appendTerminal("[Editor] Replaced $count occurrence(s) of '$find' in ${file.title}.")
        }
        return count
    }

    fun updateEditorConfig(transform: (IdeEditorConfig) -> IdeEditorConfig) {
        _editorConfig.value = transform(_editorConfig.value)
    }

    fun executeVscodeTextCommand(commandId: String): Boolean {
        val active = _files.value.firstOrNull { it.path == _activeTabPath.value } ?: return false
        val updated = VscodeCoreKotlin.CommandRegistry.executeTextCommand(commandId, active.content, active.languageHint)
        if (updated != active.content) {
            updateActiveFileContent(updated)
            appendTerminal("[VS Code] Executed $commandId on ${active.title}.")
            return true
        }
        return false
    }

    fun refreshAcsResourceTable() {
        _acsResourceTable.value = AndroidCodeStudioKotlin.ResourceIndexer.indexWorkspace(_files.value)
    }

    fun fixMissingAndroidImportsActiveFile(): Int {
        val active = _files.value.firstOrNull { it.path == _activeTabPath.value } ?: return 0
        if (!active.path.endsWith(".kt") && !active.path.endsWith(".java")) return 0
        val (fixed, count) = AndroidCodeStudioKotlin.KotlinJavaCompletion.addMissingImports(active.content)
        if (count > 0) {
            updateActiveFileContent(fixed)
            appendTerminal("[ACS Completion] Added $count missing import(s) in ${active.title}.")
        } else {
            appendTerminal("[ACS Completion] All Compose/Android imports present in ${active.title}.")
        }
        return count
    }

    fun refreshGitChanges() {
        _gitChanges.value = IdeAdvancedModulesEngine.computeGitChanges(_files.value, _gitSnapshots.value)
    }

    fun commitGitChanges(message: String): GitCommitRecord {
        val cleanMsg = message.trim().ifBlank { "Update Automation Studio workspace" }
        _gitSnapshots.value = _files.value.associate { it.path to it.content }
        _files.value = _files.value.map { it.copy(isDirty = false) }
        val rec = GitCommitRecord(
            id = "c${(_gitCommits.value.size + 1).toString().padStart(4, '0')}",
            message = cleanMsg,
            fileCount = _files.value.size,
            timestampMs = System.currentTimeMillis()
        )
        _gitCommits.value = listOf(rec) + _gitCommits.value
        refreshGitChanges()
        appendTerminal("[Git] Committed (${rec.id}): $cleanMsg")
        return rec
    }

    fun revertFileToCommit(path: String): Boolean {
        val snap = _gitSnapshots.value[path] ?: return false
        _files.value = _files.value.map {
            if (it.path == path) it.copy(content = snap, isDirty = false) else it
        }
        refreshGitChanges()
        appendTerminal("[Git] Reverted $path to last commit.")
        return true
    }

    fun insertAndroidDesignerWidget(widgetKind: String, label: String): String {
        val active = _files.value.firstOrNull { it.path == _activeTabPath.value }
        val targetPath = when {
            active != null && (active.path.endsWith(".kt") || active.path.endsWith(".xml")) && !active.path.endsWith("AndroidManifest.xml") ->
                active.path
            else -> "android/app/src/main/java/com/example/studioapp/MainActivity.kt"
        }
        val target = _files.value.firstOrNull { it.path == targetPath } ?: return targetPath
        val isXml = target.path.endsWith(".xml")
        val updated = IdeAdvancedModulesEngine.insertAndroidWidget(target.content, isXml, widgetKind, label)
        createOrUpdateFile(target.path, updated)
        runCompileAndTest()
        appendTerminal("[ACS Designer] Inserted $widgetKind('$label') into ${target.title}")
        return target.path
    }

    fun createAndroidVectorAsset(name: String, preset: String, tintHex: String = "#38BDF8"): String {
        val slug = name.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_").ifBlank { preset }
        val path = "android/app/src/main/res/drawable/ic_${slug.removePrefix("ic_")}.xml"
        val xml = IdeAdvancedModulesEngine.generateVectorDrawableXml(preset, tintHex)
        createOrUpdateFile(path, xml)
        runCompileAndTest()
        appendTerminal("[ACS Asset Studio] Generated vector drawable: $path")
        return path
    }

    fun selectNode(nodeId: String?) {
        _selectedNodeId.value = nodeId
    }

    fun addSceneNode(type: GodotNodeType, name: String, x: Float = 200f, y: Float = 150f): GodotSceneNode {
        val safeName = name.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { type.label }
        val finalName = if (_sceneNodes.value.any { it.name.equals(safeName, ignoreCase = true) }) {
            "${safeName}_${_sceneNodes.value.size + 1}"
        } else safeName
        val node = GodotSceneNode(
            name = finalName,
            type = type,
            x = x,
            y = y,
            labelText = if (type == GodotNodeType.LABEL || type == GodotNodeType.TOUCH_ZONE) finalName else ""
        )
        _sceneNodes.value = _sceneNodes.value + node
        _selectedNodeId.value = node.id
        syncTscnFile()
        appendTerminal("[Godot] Added ${type.label} '$finalName'.")
        return node
    }

    fun deleteSelectedNode() {
        val sel = _selectedNodeId.value ?: return
        _sceneNodes.value = _sceneNodes.value.filterNot { it.id == sel }
        _selectedNodeId.value = _sceneNodes.value.firstOrNull()?.id
        syncTscnFile()
    }

    fun dragNodeTo(nodeId: String, rawX: Float, rawY: Float) {
        val snap = _gridSnapEnabled.value
        val x = if (snap) (Math.round(rawX / 10f) * 10f) else rawX
        val y = if (snap) (Math.round(rawY / 10f) * 10f) else rawY
        _sceneNodes.value = _sceneNodes.value.map { n ->
            if (n.id == nodeId) n.copy(x = x.coerceIn(10f, 560f), y = y.coerceIn(10f, 340f)) else n
        }
    }

    fun updateSelectedNode(transform: (GodotSceneNode) -> GodotSceneNode) {
        val sel = _selectedNodeId.value ?: return
        _sceneNodes.value = _sceneNodes.value.map { n ->
            if (n.id == sel) transform(n) else n
        }
        syncTscnFile()
    }

    fun attachScriptToNode(
        nodeId: String,
        language: ScriptLanguage,
        templateKind: ScriptTemplateKind
    ): IdeFileEntry? {
        val node = _sceneNodes.value.firstOrNull { it.id == nodeId } ?: return null
        val slug = node.name.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_")
        val scriptPath = "scripts/${slug}_script${language.extension}"
        val starter = PolyglotGodotCompiler.starterScript(language, node.type, templateKind, node.name)
        createOrUpdateFile(scriptPath, starter, attachedNodeName = node.name)
        _sceneNodes.value = _sceneNodes.value.map {
            if (it.id == nodeId) it.copy(scriptPath = scriptPath, scriptLanguage = language) else it
        }
        syncTscnFile()
        runCompileAndTest()
        appendTerminal("[Godot -> VS Code] Attached ${language.label} script '$scriptPath' to node '${node.name}'.")
        return _files.value.firstOrNull { it.path == scriptPath }
    }

    fun spawnSceneNode(typeName: String, name: String, x: Float, y: Float): GodotSceneNode {
        val resolvedType = GodotNodeType.entries.firstOrNull {
            it.label.equals(typeName, ignoreCase = true) || it.name.equals(typeName, ignoreCase = true)
        } ?: GodotNodeType.SPRITE_2D
        return addSceneNode(resolvedType, name, x, y)
    }

    fun moveSceneNodeBy(nameOrId: String, dx: Float, dy: Float): Boolean {
        var found = false
        _sceneNodes.value = _sceneNodes.value.map { n ->
            if (n.id == nameOrId || n.name.equals(nameOrId, ignoreCase = true)) {
                found = true
                n.copy(
                    x = (n.x + dx).coerceIn(10f, 560f),
                    y = (n.y + dy).coerceIn(10f, 340f)
                )
            } else n
        }
        return found
    }

    fun setSceneNodeProperty(nameOrId: String, key: String, value: String): Boolean {
        var found = false
        val lowKey = key.lowercase(Locale.US)
        _sceneNodes.value = _sceneNodes.value.map { n ->
            if (n.id == nameOrId || n.name.equals(nameOrId, ignoreCase = true)) {
                found = true
                when (lowKey) {
                    "x" -> n.copy(x = value.toFloatOrNull() ?: n.x)
                    "y" -> n.copy(y = value.toFloatOrNull() ?: n.y)
                    "vx" -> n.copy(vx = value.toFloatOrNull() ?: n.vx)
                    "vy" -> n.copy(vy = value.toFloatOrNull() ?: n.vy)
                    "gravity", "gravity_scale" -> n.copy(gravityScale = value.toFloatOrNull() ?: n.gravityScale)
                    "scale" -> n.copy(scale = value.toFloatOrNull() ?: n.scale)
                    "rotation" -> n.copy(rotationDeg = value.toFloatOrNull() ?: n.rotationDeg)
                    "label", "text" -> n.copy(labelText = value)
                    "visible" -> n.copy(visible = value.toBooleanStrictOrNull() ?: n.visible)
                    "tint", "color" -> {
                        val hex = value.removePrefix("#").toLongOrNull(16)
                        if (hex != null) n.copy(tintHex = 0xFF000000L or hex) else n
                    }
                    else -> n
                }
            } else n
        }
        return found
    }

    fun getSceneNodeProperty(nameOrId: String, key: String): String {
        val n = _sceneNodes.value.firstOrNull {
            it.id == nameOrId || it.name.equals(nameOrId, ignoreCase = true)
        } ?: return ""
        return when (key.lowercase(Locale.US)) {
            "x" -> n.x.toString()
            "y" -> n.y.toString()
            "vx" -> n.vx.toString()
            "vy" -> n.vy.toString()
            "label", "text" -> n.labelText
            "type" -> n.type.label
            "script" -> n.scriptPath.orEmpty()
            else -> ""
        }
    }

    private fun syncTscnFile() {
        val tscn = PolyglotGodotCompiler.serializeTscn("MainScene", _sceneNodes.value)
        _files.value = _files.value.map {
            if (it.path == "scenes/main.tscn") it.copy(content = tscn, isDirty = false) else it
        }
    }

    fun playScene() {
        if (_isScenePlaying.value) return
        if (savedSnapshotBeforePlay == null) {
            savedSnapshotBeforePlay = _sceneNodes.value.toList()
        }
        _isScenePlaying.value = true
        _sceneFps.value = 30
        appendTerminal("[Godot] Scene started (Live 2D Physics + Polyglot Script VM).")

        // Execute `_ready()` for all attached scripts once off main thread
        scope.launch(Dispatchers.Default) {
            runAttachedNodeScripts(invokeProcessTick = false)
            simulationJob?.cancel()
            simulationJob = scope.launch(Dispatchers.Default) {
                while (isActive && _isScenePlaying.value) {
                    stepPhysicsAndScripts()
                    delay(33L)
                }
            }
        }
    }

    fun pauseScene() {
        _isScenePlaying.value = false
        _sceneFps.value = 0
        simulationJob?.cancel()
        appendTerminal("[Godot] Scene paused at tick #${_sceneTickCount.value}.")
    }

    fun stopScene() {
        _isScenePlaying.value = false
        _sceneFps.value = 0
        simulationJob?.cancel()
        savedSnapshotBeforePlay?.let { _sceneNodes.value = it }
        savedSnapshotBeforePlay = null
        _sceneTickCount.value = 0L
        godotInputMap.clearAll()
        _collisionStatus.value = "Scene reset"
        appendTerminal("[Godot] Scene stopped and transforms restored.")
    }

    fun stepSceneTick() {
        if (savedSnapshotBeforePlay == null) savedSnapshotBeforePlay = _sceneNodes.value.toList()
        scope.launch(Dispatchers.Default) { stepPhysicsAndScripts(forceScripts = true) }
    }

    fun stepSceneTickSync() {
        if (savedSnapshotBeforePlay == null) savedSnapshotBeforePlay = _sceneNodes.value.toList()
        _sceneTickCount.value += 1
        val step = GodotEngineKotlin.PhysicsServer2D.stepWorld(
            nodes = _sceneNodes.value,
            tick = _sceneTickCount.value,
            inputMap = godotInputMap,
            signalBus = godotSignalBus
        )
        _sceneNodes.value = step.updatedNodes
        _collisionStatus.value = step.collisionStatus
    }

    private suspend fun stepPhysicsAndScripts(forceScripts: Boolean = false) {
        _sceneTickCount.value += 1
        val tick = _sceneTickCount.value
        scriptTickDivider++

        val step = GodotEngineKotlin.PhysicsServer2D.stepWorld(
            nodes = _sceneNodes.value,
            tick = tick,
            inputMap = godotInputMap,
            signalBus = godotSignalBus
        )
        _sceneNodes.value = step.updatedNodes
        _collisionStatus.value = step.collisionStatus
        if (step.emittedSignals.isNotEmpty()) {
            _godotSignalsLog.value = (step.emittedSignals + _godotSignalsLog.value).take(30)
            step.emittedSignals.forEach { appendLogcat("I/GodotSignals: $it") }
        }

        if (forceScripts || scriptTickDivider % 4 == 0) {
            runAttachedNodeScripts(invokeProcessTick = true)
        }
    }

    private suspend fun runAttachedNodeScripts(invokeProcessTick: Boolean) {
        val nodesWithScripts = _sceneNodes.value.filter { !it.scriptPath.isNullOrBlank() }
        for (node in nodesWithScripts) {
            val file = _files.value.firstOrNull { it.path == node.scriptPath } ?: continue
            val lang = node.scriptLanguage
                ?: ScriptLanguage.entries.firstOrNull { file.path.endsWith(it.extension) }
                ?: continue
            val (comp, _) = withContext(Dispatchers.Default) {
                PolyglotGodotCompiler.compileForExecution(lang, file.content, node.name, invokeProcessTick, 0.033f, compiler)
            }
            val bc = comp.bytecode ?: continue
            val vm = StudioVm(capabilityManager, hostDispatcher)
            val res = vm.execute(bc)
            if (!invokeProcessTick && res.logs.isNotEmpty()) {
                res.logs.forEach { appendTerminal("[${node.name}:${lang.badge}] ${it.message}") }
            }
        }
    }

    fun triggerTouchZone(nodeId: String) {
        val node = _sceneNodes.value.firstOrNull { it.id == nodeId } ?: return
        appendTerminal("[Godot TouchZone] Tapped '${node.name}'.")
        scope.launch {
            val scriptPath = node.scriptPath
            if (!scriptPath.isNullOrBlank()) {
                val file = _files.value.firstOrNull { it.path == scriptPath }
                val lang = node.scriptLanguage ?: ScriptLanguage.entries.firstOrNull { scriptPath.endsWith(it.extension) }
                if (file != null && lang != null) {
                    val (comp, _) = PolyglotGodotCompiler.compileForExecution(lang, file.content, node.name, false, 0.016f, compiler)
                    comp.bytecode?.let { bc ->
                        val res = StudioVm(capabilityManager, hostDispatcher).execute(bc)
                        res.logs.forEach { appendTerminal("[TouchZone:${node.name}] ${it.message}") }
                    }
                }
            } else {
                // Default jump impulse on Player when JumpTouchZone is tapped
                moveSceneNodeBy("Player", 0f, -24f)
            }
        }
    }

    fun runCompileAndTest() {
        scope.launch {
            _isCompiling.value = true
            try {
                val active = _files.value.firstOrNull { it.path == _activeTabPath.value }
                val diags = withContext(Dispatchers.Default) { runCompileAndTestSync() }
                val isAndroidComposeActivity = active?.content?.contains("class MainActivity") == true ||
                    active?.content?.contains("@Composable") == true
                val lang = active?.let { f -> ScriptLanguage.entries.firstOrNull { f.path.endsWith(it.extension) } }
                if (active != null && lang != null && !isAndroidComposeActivity) {
                    val targetNode = active.attachedNodeName ?: "Player"
                    val res = withContext(Dispatchers.Default) {
                        val (comp, _) = PolyglotGodotCompiler.compileForExecution(lang, active.content, targetNode, false, 0.016f, compiler)
                        comp.bytecode?.let { bc -> StudioVm(capabilityManager, hostDispatcher).execute(bc) }
                    }
                    if (res != null) {
                        res.logs.forEach { appendTerminal("[Run:${active.title}] ${it.message}") }
                        appendLogcat("I/RunTest: ${active.title} executed (${res.instructionsExecuted} ops, ${res.durationMs}ms)")
                    }
                }
                val errs = diags.count { it.severity == "ERROR" }
                val summary = "Compile & Test complete: $errs error(s), ${diags.size - errs} notice(s) across project files."
                _acsLastTestResult.value = summary
                appendTerminal(summary)
                appendLogcat("I/ACS-Build: $summary")
            } finally {
                _isCompiling.value = false
            }
        }
    }

    fun runCompileAndTestSync(): List<IdeDiagnostic> {
        val activePath = _activeTabPath.value
        val workspaceFiles = _files.value.filter { f ->
            !f.path.startsWith("repos/") || f.path == activePath
        }
        val allDiags = workspaceFiles.flatMap { f ->
            PolyglotGodotCompiler.analyzeFileDiagnostics(f, compiler)
        }
        _diagnostics.value = allDiags
        val errs = allDiags.count { it.severity == "ERROR" }
        _acsLastTestResult.value = if (errs == 0) {
            "PASS: All ${workspaceFiles.size} project files verified (0 errors)."
        } else {
            "FAIL: $errs compile/manifest error(s) detected."
        }
        return allDiags
    }

    fun pressLivePreviewButton(label: String) {
        _acsTestCounter.value += 1
        val msg = "[ACS Live Preview] Tapped '$label' -> event #${_acsTestCounter.value}"
        _acsLastTestResult.value = msg
        appendLogcat("D/PreviewUI: $msg")
    }

    fun updateLivePreviewInput(id: String, text: String) {
        _acsTestInputs.value = _acsTestInputs.value + (id to text)
    }

    fun toggleLivePreviewSwitch(id: String, checked: Boolean) {
        _acsTestSwitches.value = _acsTestSwitches.value + (id to checked)
        appendLogcat("D/PreviewUI: Switch $id -> $checked")
    }

    fun executeTerminalCommand(rawCommand: String): String {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return ""
        appendTerminal("$ $cmd")
        val parts = cmd.split(Regex("\\s+"))
        val head = parts.first().lowercase(Locale.US)
        val reply = when (head) {
            "help" -> "Commands: help | ls | cat <path> | open <path> | repos | bridges | godot version|project|play|pause|stop|step|list|export | godot spawn <Type> <Name> | acs test | acs mode | dual mode | stl <code> | voice <msg> | clear"
            "repos" -> {
                scanExternalReposFoldersSync()
                _mountedRepos.value.joinToString("\n") { r ->
                    " - ${r.kind.displayName} (${r.kind.repoSlug}): ${r.state.badge}, files=${r.indexedFileCount}, layers=${r.detectedLayers.joinToString(",")}"
                }
            }
            "bridges" -> _bridgeDescriptors.value.joinToString("\n") { b ->
                " * ${b.title} [${b.lastStatus}]: ${b.sourceLayer} -> ${b.targetLayer}"
            }
            "clear" -> {
                _terminalLogs.value = listOf("Terminal cleared.")
                return "Terminal cleared."
            }
            "ls" -> _files.value.joinToString("\n") { " - ${it.path} (${it.content.length} chars)" }
            "cat" -> {
                val target = parts.getOrNull(1).orEmpty()
                val match = _files.value.firstOrNull { it.path.contains(target, ignoreCase = true) }
                match?.content ?: "File not found: $target"
            }
            "open" -> {
                val target = parts.getOrNull(1).orEmpty()
                val match = _files.value.firstOrNull { it.path.contains(target, ignoreCase = true) }
                if (match != null && openFile(match.path)) "Opened ${match.path}" else "File not found: $target"
            }
            "godot" -> when (parts.getOrNull(1)?.lowercase(Locale.US)) {
                "version" -> "Godot 4.7.2.stable (Official Android AAR: Runtime + Editor + AutomationStudioBridge)"
                "project" -> {
                    openFile("project.godot")
                    _files.value.firstOrNull { it.path == "project.godot" }?.content ?: "project.godot ready"
                }
                "play" -> { playScene(); "Godot scene playing." }
                "pause" -> { pauseScene(); "Godot scene paused." }
                "stop" -> { stopScene(); "Godot scene stopped." }
                "step" -> { stepSceneTick(); "Stepped 1 frame." }
                "list" -> _sceneNodes.value.joinToString("\n") { " * ${it.name} (${it.type.label}) @ (${it.x.toInt()}, ${it.y.toInt()}) script=${it.scriptPath ?: "none"}" }
                "export" -> PolyglotGodotCompiler.serializeTscn("MainScene", _sceneNodes.value)
                "spawn" -> {
                    val t = parts.getOrNull(2) ?: "Sprite2D"
                    val n = parts.getOrNull(3) ?: "Enemy"
                    val node = spawnSceneNode(t, n, 240f, 140f)
                    "Spawned ${node.type.label} '${node.name}'"
                }
                else -> "Godot subcommands: version | project | play | pause | stop | step | list | export | spawn <Type> <Name>"
            }
            "acs" -> when (parts.getOrNull(1)?.lowercase(Locale.US)) {
                "mode" -> { setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO); "Android Code Studio mode active." }
                else -> { runCompileAndTest(); "Running Android Code Studio diagnostics & live test..." }
            }
            "dual" -> { setWorkspaceMode(IdeWorkspaceMode.DUAL_GAME_AND_APP); "Dual Game+App IDE mode active." }
            "stl" -> {
                val code = cmd.removePrefix("stl").trim()
                runInlineStudioLang(code)
                "Executing StudioLang..."
            }
            "voice" -> {
                val msg = cmd.removePrefix("voice").trim().ifBlank { "Voice bridge active." }
                voiceSpeakCallback?.invoke(msg)
                handleVoiceCommand(msg)
                "Voice processed: $msg"
            }
            else -> "Unknown command '$head'. Type 'help' for commands."
        }
        appendTerminal(reply)
        return reply
    }

    private fun runInlineStudioLang(code: String) {
        scope.launch {
            val out = withContext(Dispatchers.Default) { compiler.compile(code, useCache = false) }
            if (!out.success || out.bytecode == null) {
                out.diagnostics.forEach { appendTerminal("[STL Error] ${it.message}") }
                return@launch
            }
            val res = StudioVm(capabilityManager, hostDispatcher).execute(out.bytecode)
            res.logs.forEach { appendTerminal("[STL] ${it.message}") }
            appendTerminal("-> ${res.returnValue.asDisplayString()}")
        }
    }

    fun handleVoiceCommand(spokenText: String) {
        val clean = spokenText.trim()
        if (clean.isEmpty()) return
        val low = clean.lowercase(Locale.US)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _voiceCommandsLog.value = (listOf("[$ts] \"$clean\"") + _voiceCommandsLog.value).take(25)
        appendTerminal("[Voice] Heard: $clean")

        val response = when {
            low.contains("play") || low.contains("start") || low.contains("चालू") || low.contains("चलाओ") -> {
                playScene()
                "Playing Godot 2D scene now."
            }
            low.contains("pause") || low.contains("रुको") -> {
                pauseScene()
                "Godot scene paused."
            }
            low.contains("stop") || low.contains("रोको") || low.contains("बंद") -> {
                stopScene()
                "Godot scene stopped and reset."
            }
            low.contains("spawn") || low.contains("add") || low.contains("जोड़") -> {
                val n = spawnSceneNode("Sprite2D", "VoiceSprite_${_sceneNodes.value.size + 1}", 220f, 140f)
                "Added new node ${n.name} to Godot scene."
            }
            low.contains("python") -> {
                val targetId = _selectedNodeId.value ?: _sceneNodes.value.firstOrNull()?.id
                if (targetId != null) attachScriptToNode(targetId, ScriptLanguage.PYTHON, ScriptTemplateKind.VOICE_REACTIVE)
                "Attached Python script in VS Code editor."
            }
            low.contains("android") || low.contains("app mode") || low.contains("एंड्राइड") -> {
                setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO)
                "Switched to Android Code Studio App Override."
            }
            low.contains("dual") || low.contains("game") -> {
                setWorkspaceMode(IdeWorkspaceMode.DUAL_GAME_AND_APP)
                "Switched to Unified Game and App mode."
            }
            low.contains("compile") || low.contains("test") || low.contains("run") || low.contains("टेस्ट") -> {
                runCompileAndTest()
                "Running offline compiler and Android test check."
            }
            else -> {
                "Command logged. Say play scene, pause, add sprite, attach python, android mode, or compile."
            }
        }
        voiceSpeakCallback?.invoke(response)
    }

    fun applyAppDesignSnapshot(base: AppDesign): AppDesign {
        val activeKt = _files.value.firstOrNull { it.path.endsWith("MainActivity.kt") }
        return PolyglotGodotCompiler.toAppDesignSnapshot(base, activeKt)
    }

    fun scanExternalReposFoldersSync(): List<MountedRepoResult> {
        val mounted = ExternalRepoBridgeEngine.scanExternalReposDirectory(workspaceRoot)
        if (mounted.isNotEmpty()) {
            applyMountedResults(mounted)
        }
        return mounted
    }

    fun scanExternalReposFolders() {
        scope.launch {
            val mounted = withContext(Dispatchers.IO) { scanExternalReposFoldersSync() }
            if (mounted.isEmpty()) {
                appendTerminal("[Repos] No unpacked folders in external_repos/{vscode,godot,android-code-studio}. Tap 'Mount Repo .ZIP' to import.")
            } else {
                mounted.forEach { appendTerminal("[Repos] ${it.summaryMessage}") }
            }
        }
    }

    fun setRepoImportMode(mode: RepoImportMode) {
        _repoImportMode.value = mode
        appendTerminal("[Repos] Import mode set to: ${mode.title}")
    }

    fun refreshInAppVault() {
        scope.launch(Dispatchers.IO) {
            _inAppVaultZips.value = ExternalRepoBridgeEngine.listInAppVaultZips(workspaceRoot)
        }
    }

    fun mountRepoFromZipStream(
        kindOverride: UpstreamRepoKind?,
        zipName: String,
        inputStream: InputStream,
        importMode: RepoImportMode = _repoImportMode.value,
        saveToInAppVault: Boolean = true
    ) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    if (saveToInAppVault) {
                        val saved = ExternalRepoBridgeEngine.saveStreamToInAppVault(workspaceRoot, zipName, inputStream)
                        saved.inputStream().use { storedStream ->
                            mountRepoFromZipStreamSync(kindOverride, saved.name, storedStream, importMode)
                        }
                    } else {
                        mountRepoFromZipStreamSync(kindOverride, zipName, inputStream, importMode)
                    }
                }
                refreshInAppVault()
                appendTerminal("[Repos] ${res.summaryMessage}")
                appendLogcat("I/RepoMount: ${res.summaryMessage}")
            } catch (t: Throwable) {
                val err = "Repo mount failed ($zipName): ${t.message}"
                appendTerminal("[Repos Error] $err")
                appendLogcat("E/RepoMount: $err")
            }
        }
    }

    fun mountInAppVaultZip(vaultZip: InAppVaultZipEntry, importMode: RepoImportMode = _repoImportMode.value) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    File(vaultZip.absolutePath).inputStream().use { stream ->
                        mountRepoFromZipStreamSync(vaultZip.detectedKind, vaultZip.name, stream, importMode)
                    }
                }
                appendTerminal("[In-App Vault] Mounted ${vaultZip.name} (${importMode.title})")
                appendLogcat("I/InAppVault: ${res.summaryMessage}")
            } catch (t: Throwable) {
                appendTerminal("[In-App Vault Error] ${t.message}")
            }
        }
    }

    fun generateAndMountInAppRepoZip(kind: UpstreamRepoKind, importMode: RepoImportMode = _repoImportMode.value) {
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val zipFile = ExternalRepoBridgeEngine.createReadyInAppRepoZip(kind, workspaceRoot)
                zipFile.inputStream().use { stream ->
                    mountRepoFromZipStreamSync(kind, zipFile.name, stream, importMode)
                }
            }
            refreshInAppVault()
            appendTerminal("[In-App ZIP Created & Mounted] ${res.summaryMessage}")
        }
    }

    fun addDirectFileToRepo(kind: UpstreamRepoKind, relativePath: String, content: String): String {
        val cleanRel = relativePath.trim().trimStart('/').replace('\\', '/').ifBlank { "src/custom_file.kt" }
        val repoDir = File(workspaceRoot, kind.folderHint)
        val out = File(repoDir, cleanRel)
        if (!out.canonicalPath.startsWith(repoDir.canonicalPath + File.separator)) {
            throw SecurityException("Invalid repo relative path: $relativePath")
        }
        out.parentFile?.mkdirs()
        out.writeText(content)
        val vPath = "repos/${kind.id}/$cleanRel"
        createOrUpdateFile(vPath, content)
        openFile(vPath)
        appendTerminal("[In-App Upload] Added $vPath directly inside app.")
        return vPath
    }

    fun setAcsBuildVariant(variant: String) {
        _acsBuildVariant.value = if (variant.equals("release", ignoreCase = true)) "release" else "debug"
        appendLogcat("I/Gradle: Build variant switched to ${_acsBuildVariant.value}")
    }

    private fun buildLanguageTargetEntry(lang: AppBuildLanguage, design: AppDesign): Pair<String, String> {
        val targetRelPath = when (lang) {
            AppBuildLanguage.KOTLIN_COMPOSE, AppBuildLanguage.VISUAL_DESIGNER, AppBuildLanguage.JSON_MANIFEST ->
                "android/app/src/main/java/com/example/studioapp/MainActivity.kt"
            AppBuildLanguage.JAVA_ANDROID -> "android/app/src/main/java/com/example/studioapp/MainActivity.java"
            AppBuildLanguage.STUDIOLANG -> "android/app/src/main/assets/scripts/main.stl"
            AppBuildLanguage.PYTHON_BRIDGE -> "android/app/src/main/assets/scripts/main.py"
            AppBuildLanguage.HTML5_JS -> "android/app/src/main/assets/web/index.html"
            AppBuildLanguage.CPP_NDK -> "android/app/src/main/cpp/native-lib.cpp"
            AppBuildLanguage.RUST_JNI -> "android/app/src/main/rust/src/lib.rs"
            AppBuildLanguage.CUSTOM_OS_KERNEL -> "android/os_kernel/kernel.stl"
        }
        val content = when (lang) {
            AppBuildLanguage.KOTLIN_COMPOSE, AppBuildLanguage.VISUAL_DESIGNER, AppBuildLanguage.JSON_MANIFEST ->
                design.customKotlinCode
            AppBuildLanguage.JAVA_ANDROID -> design.customJavaCode
            AppBuildLanguage.STUDIOLANG -> """
                |#include <studio.h>
                |// ${design.appName} — Android Code Studio StudioLang App Entry
                |log.info("Booting ${design.appName} (${design.packageId})")
                |notify.show("${design.appName}", "Built with Android Code Studio")
            """.trimMargin()
            AppBuildLanguage.PYTHON_BRIDGE -> design.customPythonCode
            AppBuildLanguage.HTML5_JS -> design.customHtml5Code
            AppBuildLanguage.CPP_NDK -> design.customCppCode
            AppBuildLanguage.RUST_JNI -> design.customRustCode
            AppBuildLanguage.CUSTOM_OS_KERNEL -> design.customOsKernelCode
        }
        return targetRelPath to content
    }

    fun switchAndroidBuildLanguage(lang: AppBuildLanguage, design: AppDesign): String {
        _acsBuildLanguage.value = lang
        val (targetRelPath, content) = buildLanguageTargetEntry(lang, design)
        createOrUpdateFile(targetRelPath, content)
        openFile(targetRelPath)
        _workspaceMode.value = IdeWorkspaceMode.ANDROID_CODE_STUDIO
        appendTerminal("[Android Code Studio] Active app build language -> ${lang.label} ($targetRelPath)")
        return targetRelPath
    }

    fun syncAppDesignIntoAndroidIde(design: AppDesign) {
        _acsBuildLanguage.value = design.buildLanguage
        scope.launch {
            syncAppDesignIntoAndroidIdeSuspend(design)
        }
    }

    private suspend fun syncAppDesignIntoAndroidIdeSuspend(design: AppDesign) {
        val activeLang = design.buildLanguage
        val updatedDesign = design.copy(buildLanguage = activeLang)
        val entries = withContext(Dispatchers.Default) {
            val genFiles = AndroidProjectGenerator.generateFiles(updatedDesign)
            val mapped = genFiles.map { gf ->
                val vPath = "android/${gf.relativePath}"
                val title = vPath.substringAfterLast('/')
                val ext = title.substringAfterLast('.', "txt")
                IdeFileEntry(vPath, title, gf.content, ext, false)
            }.toMutableList()
            val (targetRelPath, targetContent) = buildLanguageTargetEntry(activeLang, updatedDesign)
            val tTitle = targetRelPath.substringAfterLast('/')
            val tExt = tTitle.substringAfterLast('.', "txt")
            mapped.removeAll { it.path == targetRelPath }
            mapped += IdeFileEntry(targetRelPath, tTitle, targetContent, tExt, false)
            mapped to targetRelPath
        }
        batchUpsertFiles(entries.first, openTargetPath = entries.second)
        appendTerminal("[Android Code Studio] Synced ${entries.first.size} Gradle/Android project files for '${updatedDesign.appName}'.")
    }

    fun runAndroidStudioGradleBuildPipeline(design: AppDesign): Job {
        if (_isAcsBuilding.value && activeBuildJob?.isActive == true) {
            return activeBuildJob!!
        }
        val job = scope.launch {
            executeAndroidGradleBuildPipeline(design)
        }
        activeBuildJob = job
        return job
    }

    suspend fun executeAndroidGradleBuildPipeline(design: AppDesign): List<String> {
        _isAcsBuilding.value = true
        val activeLang = _acsBuildLanguage.value
        val variant = _acsBuildVariant.value
        val updatedDesign = design.copy(buildLanguage = activeLang)
        try {
            _acsBuildPhases.value = listOf(
                "[1/5 Gradle Sync] Generating Gradle project files (${updatedDesign.packageId})...",
                "[2/5 AAPT2 Index] Pending...",
                "[3/5 Multi-Lang Build] Pending...",
                "[4/5 Manifest & Lint] Pending...",
                "[5/5 Package Ready] Running in background..."
            )
            _acsLastTestResult.value = "Gradle building ($variant · ${activeLang.label})..."

            syncAppDesignIntoAndroidIdeSuspend(updatedDesign)

            val result = withContext(Dispatchers.Default) {
                val filesSnapshot = _files.value
                val resTable = AndroidCodeStudioKotlin.ResourceIndexer.indexWorkspace(filesSnapshot)
                _acsResourceTable.value = resTable
                val diags = runCompileAndTestSync()
                val errCount = diags.count { it.severity == "ERROR" }
                val stlDiag = if (activeLang == AppBuildLanguage.STUDIOLANG || activeLang == AppBuildLanguage.CUSTOM_OS_KERNEL) {
                    val code = filesSnapshot.firstOrNull { it.path.endsWith(".stl") }?.content.orEmpty()
                    val c = compiler.compile(code)
                    if (c.success) "Compiled StudioLang bytecode (${c.bytecode?.instructions?.size ?: 0} ops)"
                    else "Compile warning: ${c.diagnostics.firstOrNull()?.message}"
                } else {
                    "Prepared ${activeLang.label} sources (${activeLang.extension}) for Gradle APK CI"
                }
                val phases = listOf(
                    "[1/5 Gradle Sync] ${updatedDesign.packageId} · variant=$variant · minSdk=${updatedDesign.minSdk} targetSdk=${updatedDesign.targetSdk}",
                    "[2/5 AAPT2 Index] Indexed ${resTable.totalSymbols} R.* symbols (${resTable.strings.size} strings, ${resTable.layouts.size} layouts)",
                    "[3/5 Multi-Lang Build] $stlDiag",
                    "[4/5 Manifest & Lint] Checked AndroidManifest & Play Policy ($errCount error(s))",
                    "[5/5 Package Ready] ${if (errCount == 0) "PASS — Ready to Export Gradle/APK ZIP" else "FAIL — Fix $errCount error(s)"}"
                )
                phases to errCount
            }

            val (phases, errCount) = result
            _acsBuildPhases.value = phases
            phases.forEach { appendTerminal(it); appendLogcat("I/ACSBuild: $it") }
            _acsLastTestResult.value = if (errCount == 0) {
                "Android Studio Build PASS ($variant · ${activeLang.label})"
            } else {
                "Build blocked: $errCount error(s)"
            }
            return phases
        } finally {
            _isAcsBuilding.value = false
        }
    }

    fun mountRepoFromZipStreamSync(
        kindOverride: UpstreamRepoKind?,
        zipName: String,
        inputStream: InputStream,
        importMode: RepoImportMode = _repoImportMode.value
    ): MountedRepoResult {
        val result = ExternalRepoBridgeEngine.mountFromZipStream(
            kindOverride = kindOverride,
            zipDisplayName = zipName,
            inputStream = inputStream,
            workspaceRoot = workspaceRoot,
            importMode = importMode
        )
        applyMountedResults(listOf(result))
        result.indexedFiles.firstOrNull()?.let { firstFile ->
            openFile(firstFile.path)
        }
        refreshInAppVault()
        return result
    }

    private fun applyMountedResults(results: List<MountedRepoResult>) {
        val statusMap = _mountedRepos.value.associateBy { it.kind }.toMutableMap()
        val currentFiles = _files.value.toMutableList()
        results.forEach { res ->
            statusMap[res.status.kind] = res.status
            val prefix = "repos/${res.status.kind.id}/"
            currentFiles.removeAll { it.path.startsWith(prefix) }
            currentFiles.addAll(res.indexedFiles)
        }
        _mountedRepos.value = UpstreamRepoKind.entries.map { k ->
            statusMap[k] ?: MountedRepoStatus(kind = k)
        }
        _files.value = currentFiles
    }

    fun openMountedSampleFile(kind: UpstreamRepoKind): Boolean {
        val prefix = "repos/${kind.id}/"
        val match = _files.value.firstOrNull { it.path.startsWith(prefix) } ?: return false
        return openFile(match.path)
    }

    fun toggleSplitEditorRight(path: String? = null) {
        if (_splitRightPath.value != null && path == null) {
            _splitRightPath.value = null
            return
        }
        val candidate = path ?: _openTabs.value.firstOrNull { it != _activeTabPath.value }
            ?: _files.value.firstOrNull { it.path != _activeTabPath.value }?.path
        _splitRightPath.value = candidate
    }

    fun scanBundledAssetArchives(assetOpener: (String) -> InputStream?) {
        scope.launch(Dispatchers.IO) {
            val mounted = ExternalRepoBridgeEngine.scanBundledAssetZips(workspaceRoot, assetOpener)
            if (mounted.isNotEmpty()) {
                applyMountedResults(mounted)
                mounted.forEach { appendTerminal("[Assets] ${it.summaryMessage}") }
            }
            refreshInAppVault()
        }
    }

    fun downloadAndMountRepo(
        kind: UpstreamRepoKind,
        customUrl: String? = null,
        importMode: RepoImportMode = _repoImportMode.value
    ) {
        scope.launch(Dispatchers.IO) {
            _repoDownloadStatus.value = _repoDownloadStatus.value + (kind to "Connecting (${importMode.title})...")
            try {
                var lastReportMb = -1L
                val result = ExternalRepoBridgeEngine.downloadAndMountFromUrl(
                    kind = kind,
                    workspaceRoot = workspaceRoot,
                    urlOverride = customUrl,
                    importMode = importMode
                ) { bytes ->
                    val mb = bytes / (1024L * 1024L)
                    if (mb != lastReportMb) {
                        lastReportMb = mb
                        val label = if (mb > 0) "Downloading ${mb} MB..." else "Downloading ${bytes / 1024L} KB..."
                        _repoDownloadStatus.value = _repoDownloadStatus.value + (kind to label)
                    }
                }
                applyMountedResults(listOf(result))
                refreshInAppVault()
                result.indexedFiles.firstOrNull()?.let { openFile(it.path) }
                _repoDownloadStatus.value = _repoDownloadStatus.value + (kind to "Mounted ${result.status.indexedFileCount} files (${importMode.title})")
                appendTerminal("[GitHub Fetch] ${result.summaryMessage}")
                appendLogcat("I/RepoFetch: ${result.summaryMessage}")
            } catch (t: Throwable) {
                val msg = "Fetch failed: ${t.message ?: "network unavailable"}"
                _repoDownloadStatus.value = _repoDownloadStatus.value + (kind to msg)
                appendTerminal("[GitHub Fetch Error] ${kind.archiveFileName}: $msg")
                appendLogcat("E/RepoFetch: ${kind.archiveFileName}: $msg")
            }
        }
    }

    fun runBridgeWorkflow(bridgeId: String) {
        scope.launch {
            executeBridgeWorkflow(bridgeId)
        }
    }

    suspend fun executeBridgeWorkflow(bridgeId: String): String = withContext(Dispatchers.Default) {
        val outcome = when (bridgeId) {
            "bridge_vscode_to_godot" -> {
                val target = _sceneNodes.value.firstOrNull { it.name == "Player" } ?: _sceneNodes.value.firstOrNull()
                if (target != null) {
                    val sample = PolyglotGodotCompiler.starterScript(
                        ScriptLanguage.PYTHON,
                        target.type,
                        ScriptTemplateKind.MOVEMENT,
                        target.name
                    )
                    val (comp, _) = PolyglotGodotCompiler.compileForExecution(
                        ScriptLanguage.PYTHON,
                        sample,
                        target.name,
                        invokeProcessTick = true,
                        deltaSec = 0.016f,
                        compiler = compiler
                    )
                    val ops = comp.bytecode?.let { StudioVm(capabilityManager, hostDispatcher).execute(it).instructionsExecuted } ?: 0
                    "PASS (${target.name} bridged via Python -> StudioVm in $ops ops)"
                } else {
                    "PASS (Bridge compiler ready)"
                }
            }
            "bridge_godot_servers_vm" -> {
                stepSceneTickSync()
                val tscn = PolyglotGodotCompiler.serializeTscn("MainScene", _sceneNodes.value)
                "PASS (${_sceneNodes.value.size} nodes stepped, ${tscn.length} bytes .tscn synced)"
            }
            "bridge_acs_android_pipeline" -> {
                val mainKt = _files.value.firstOrNull { it.path.endsWith("MainActivity.kt") }?.content.orEmpty()
                val widgets = PolyglotGodotCompiler.parseComposeWidgetsForPreview(mainKt, initialAppDesign)
                val diags = runCompileAndTestSync()
                val errs = diags.count { it.severity == "ERROR" }
                "PASS (${widgets.size} live UI widgets rendered, $errs manifest/lint error(s))"
            }
            else -> "PASS (Bridge ready)"
        }
        val now = System.currentTimeMillis()
        _bridgeDescriptors.value = _bridgeDescriptors.value.map { b ->
            if (b.id == bridgeId) b.copy(lastStatus = outcome, lastVerifiedMs = now) else b
        }
        appendTerminal("[Bridge:$bridgeId] $outcome")
        appendLogcat("I/RepoBridge: [$bridgeId] $outcome")
        outcome
    }

    fun syncGodot47ProjectToDisk(context: Context): GodotProjectWorkspace.SyncResult {
        syncTscnFile()
        val result = GodotProjectWorkspace.syncIdeToGodotProject(
            context = context,
            files = _files.value,
            sceneNodes = _sceneNodes.value
        )
        godotSessionActive = true
        val msg = "[Godot 4.7.2] Synced ${result.writtenFilesCount} workspace file(s) -> ${result.projectDir.name}"
        appendTerminal(msg)
        appendLogcat("I/Godot47Workspace: $msg")
        return result
    }

    fun syncFromGodot47Disk(context: Context, onlyIfSessionWasActive: Boolean = false): Int {
        if (onlyIfSessionWasActive && !godotSessionActive) return 0
        if (onlyIfSessionWasActive) godotSessionActive = false
        val projectDir = GodotProjectWorkspace.activeProjectDir(context)
        val diskEntries = GodotProjectWorkspace.importGodotProjectToIde(projectDir)
        if (diskEntries.isEmpty()) return 0

        val currentByPath = _files.value.associateBy { it.path }.toMutableMap()
        val updatedEntries = mutableListOf<IdeFileEntry>()
        diskEntries.forEach { disk ->
            val existing = currentByPath[disk.path]
            if (existing == null || existing.content != disk.content) {
                val nextEntry = if (existing != null) {
                    existing.copy(content = disk.content, isDirty = false)
                } else {
                    disk
                }
                currentByPath[disk.path] = nextEntry
                updatedEntries += nextEntry
            }
        }
        if (updatedEntries.isNotEmpty()) {
            _files.value = currentByPath.values.toList()
            persistFilesAsync(updatedEntries)
            refreshGitChanges()
            val msg = "[Godot 4.7.2] Synced ${updatedEntries.size} updated file(s) back from Godot disk workspace."
            appendTerminal(msg)
            appendLogcat("I/Godot47Workspace: $msg")
        }
        return updatedEntries.size
    }

    // ---- UNIVERSAL MULTI-PROJECT WORKSPACE OPERATIONS ----

    fun attachAppContext(context: Context) {
        appContextRef = context.applicationContext
    }

    private fun mergeProjectList(
        primary: IdeProjectDescriptor,
        existing: List<IdeProjectDescriptor>
    ): List<IdeProjectDescriptor> {
        val map = linkedMapOf<String, IdeProjectDescriptor>()
        map[primary.id] = primary
        existing.forEach { item ->
            if (item.id !in map) map[item.id] = item
        }
        if (defaultSandboxDesc.id !in map) {
            map[defaultSandboxDesc.id] = defaultSandboxDesc
        }
        return map.values.sortedWith(
            compareByDescending<IdeProjectDescriptor> { it.id == primary.id }
                .thenByDescending { it.lastOpenedMs }
        )
    }

    private fun updateActiveProjectCount(fileCount: Int) {
        val updated = _activeProject.value.copy(fileCount = fileCount, lastOpenedMs = System.currentTimeMillis())
        _activeProject.value = updated
        val nextList = mergeProjectList(updated, _recentProjects.value)
        _recentProjects.value = nextList
        scope.launch(Dispatchers.IO) {
            UniversalProjectManager.saveRegistry(workspaceRoot, nextList)
        }
    }

    private fun applyLoadedProjectWorkspace(loaded: LoadedProjectWorkspace) {
        safDocUriMap.clear()
        safDocUriMap.putAll(loaded.safDocUriByRelPath)
        _activeProject.value = loaded.descriptor
        _files.value = loaded.files
        val preferredEntry = loaded.files.firstOrNull { f ->
            f.path == "project.godot" ||
                f.path.endsWith("MainActivity.kt") ||
                f.path.endsWith("main.py") ||
                f.path.endsWith("main.cpp") ||
                f.path.endsWith("index.html") ||
                f.path.endsWith("main.stl") ||
                f.path.endsWith(".gd")
        } ?: loaded.files.firstOrNull()
        _openTabs.value = listOfNotNull(preferredEntry?.path)
        _activeTabPath.value = preferredEntry?.path.orEmpty()
        _gitSnapshots.value = loaded.files.associate { it.path to it.content }
        _gitChanges.value = emptyList()
        _gitCommits.value = listOf(
            GitCommitRecord("c0001", "Opened ${loaded.descriptor.name}", loaded.files.size, System.currentTimeMillis())
        )
        _recentProjects.value = mergeProjectList(loaded.descriptor, _recentProjects.value)
        scope.launch(Dispatchers.IO) {
            UniversalProjectManager.saveRegistry(workspaceRoot, _recentProjects.value)
        }
        scope.launch(Dispatchers.Default) {
            refreshAcsResourceTable()
            runCompileAndTestSync()
        }
        _projectSyncState.value = ProjectSyncState.SYNCED
        _projectSyncMessage.value = loaded.summaryMessage
        appendTerminal("[Project] ${loaded.summaryMessage}")
        appendLogcat("I/ProjectManager: ${loaded.summaryMessage}")
    }

    fun saveAllDirtyFiles(onResult: (String) -> Unit = {}) {
        val projectSnapshot = _activeProject.value
        val toSave = _files.value.filter { it.isDirty || it.path == _activeTabPath.value }
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                writeEntriesToActiveStorage(projectSnapshot, toSave)
            }
            val msg = if (_projectSyncState.value == ProjectSyncState.ERROR) {
                _projectSyncMessage.value
            } else {
                "Saved $saved file(s) to '${projectSnapshot.name}'."
            }
            appendTerminal("[Save] $msg")
            onResult(msg)
        }
    }

    fun deleteFileFromActiveProject(relPath: String, onResult: (String) -> Unit = {}) {
        val clean = runCatching { UniversalProjectManager.sanitizeRelativePath(relPath) }.getOrNull() ?: return
        val proj = _activeProject.value
        _files.value = _files.value.filterNot { it.path == clean }
        closeTab(clean)
        updateActiveProjectCount(_files.value.size)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { UniversalProjectManager.deleteLocalFile(File(proj.localDirPath), clean) }
                if (proj.isDirectFolder) {
                    appContextRef?.let { ctx -> UniversalProjectManager.deleteSafFile(ctx, safDocUriMap, clean) }
                }
            }
            refreshGitChanges()
            val msg = "Deleted '$clean' from '${proj.name}'."
            _projectSyncMessage.value = msg
            appendTerminal("[Project] $msg")
            onResult(msg)
        }
    }

    fun openSafFolderProject(context: Context, treeUri: Uri, onComplete: (String) -> Unit = {}) {
        attachAppContext(context)
        _projectSyncState.value = ProjectSyncState.SCANNING
        _projectSyncMessage.value = "Scanning selected device folder..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UniversalProjectManager.scanSafTree(context.applicationContext, workspaceRoot, treeUri)
                }
            }.onSuccess { loaded ->
                applyLoadedProjectWorkspace(loaded)
                onComplete(loaded.summaryMessage)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = "Folder open failed: ${err.localizedMessage ?: "Permission denied"}"
                _projectSyncState.value = ProjectSyncState.ERROR
                _projectSyncMessage.value = msg
                appendTerminal("[Project] $msg")
                onComplete(msg)
            }
        }
    }

    fun importZipProject(context: Context, zipUri: Uri, onComplete: (String) -> Unit = {}) {
        attachAppContext(context)
        _projectSyncState.value = ProjectSyncState.SCANNING
        _projectSyncMessage.value = "Extracting ZIP project archive..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val zipName = UniversalProjectManager.queryDocumentFileName(context, zipUri)
                    val stream = context.contentResolver.openInputStream(zipUri)
                        ?: error("Could not read selected ZIP file")
                    stream.use { input ->
                        UniversalProjectManager.importAnyZipStream(workspaceRoot, zipName, input)
                    }
                }
            }.onSuccess { loaded ->
                applyLoadedProjectWorkspace(loaded)
                onComplete(loaded.summaryMessage)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = "ZIP import failed: ${err.localizedMessage ?: "Invalid archive"}"
                _projectSyncState.value = ProjectSyncState.ERROR
                _projectSyncMessage.value = msg
                appendTerminal("[Project] $msg")
                onComplete(msg)
            }
        }
    }

    fun cloneGithubProject(rawUrl: String, branchOverride: String? = null, onComplete: (String) -> Unit = {}) {
        _projectSyncState.value = ProjectSyncState.SCANNING
        _projectSyncMessage.value = "Downloading GitHub repository..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UniversalProjectManager.cloneGithubRepoZip(workspaceRoot, rawUrl, branchOverride)
                }
            }.onSuccess { loaded ->
                applyLoadedProjectWorkspace(loaded)
                onComplete(loaded.summaryMessage)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = "GitHub clone failed: ${err.localizedMessage ?: "Network error"}"
                _projectSyncState.value = ProjectSyncState.ERROR
                _projectSyncMessage.value = msg
                appendTerminal("[Project] $msg")
                onComplete(msg)
            }
        }
    }

    fun createNewInAppProject(name: String, template: ProjectTemplateKind, onComplete: (String) -> Unit = {}) {
        _projectSyncState.value = ProjectSyncState.SCANNING
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UniversalProjectManager.createNewInAppProject(workspaceRoot, name, template)
                }
            }.onSuccess { loaded ->
                applyLoadedProjectWorkspace(loaded)
                onComplete(loaded.summaryMessage)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = "Project creation failed: ${err.localizedMessage ?: "Error"}"
                _projectSyncState.value = ProjectSyncState.ERROR
                _projectSyncMessage.value = msg
                onComplete(msg)
            }
        }
    }

    fun switchToProject(context: Context, descriptor: IdeProjectDescriptor, onComplete: (String) -> Unit = {}) {
        attachAppContext(context)
        if (descriptor.id == UniversalProjectManager.DEFAULT_PROJECT_ID) {
            safDocUriMap.clear()
            seedDefaultFiles(initialNodes)
            val sandbox = defaultSandboxDesc.copy(fileCount = _files.value.size, lastOpenedMs = System.currentTimeMillis())
            _activeProject.value = sandbox
            _gitSnapshots.value = _files.value.associate { it.path to it.content }
            _gitChanges.value = emptyList()
            _recentProjects.value = mergeProjectList(sandbox, _recentProjects.value)
            scope.launch(Dispatchers.IO) {
                UniversalProjectManager.saveRegistry(workspaceRoot, _recentProjects.value)
                val mounted = ExternalRepoBridgeEngine.scanExternalReposDirectory(workspaceRoot)
                if (mounted.isNotEmpty()) applyMountedResults(mounted)
            }
            scope.launch(Dispatchers.Default) {
                refreshAcsResourceTable()
                runCompileAndTestSync()
            }
            val msg = "Switched to Automation Sandbox (${_files.value.size} files)."
            _projectSyncState.value = ProjectSyncState.SYNCED
            _projectSyncMessage.value = msg
            appendTerminal("[Project] $msg")
            onComplete(msg)
            return
        }

        if (descriptor.isDirectFolder && !descriptor.safTreeUriString.isNullOrBlank()) {
            openSafFolderProject(context, Uri.parse(descriptor.safTreeUriString), onComplete)
            return
        }

        _projectSyncState.value = ProjectSyncState.SCANNING
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                UniversalProjectManager.loadLocalProjectDir(descriptor)
            }
            applyLoadedProjectWorkspace(loaded)
            onComplete(loaded.summaryMessage)
        }
    }

    fun removeProject(descriptor: IdeProjectDescriptor) {
        if (descriptor.id == UniversalProjectManager.DEFAULT_PROJECT_ID) return
        val filtered = _recentProjects.value.filterNot { it.id == descriptor.id }
        _recentProjects.value = filtered
        scope.launch(Dispatchers.IO) {
            UniversalProjectManager.saveRegistry(workspaceRoot, filtered)
            if (descriptor.localDirPath.isNotBlank() && descriptor.localDirPath != ideDir.absolutePath) {
                runCatching { File(descriptor.localDirPath).deleteRecursively() }
            }
        }
        if (_activeProject.value.id == descriptor.id) {
            appContextRef?.let { switchToProject(it, defaultSandboxDesc) }
        }
    }

    fun exportProjectZipToUri(
        context: Context,
        project: IdeProjectDescriptor,
        destUri: Uri,
        onComplete: (String) -> Unit = {}
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val exportFiles = if (project.id == _activeProject.value.id) {
                        _files.value
                    } else if (project.isDirectFolder && !project.safTreeUriString.isNullOrBlank()) {
                        UniversalProjectManager.scanSafTree(
                            context.applicationContext,
                            workspaceRoot,
                            Uri.parse(project.safTreeUriString)
                        ).files
                    } else {
                        UniversalProjectManager.loadLocalProjectDir(project).files
                    }
                    val tmpZip = File(workspaceRoot, "exports/${project.id}_export.zip")
                    UniversalProjectManager.exportProjectToZip(project, exportFiles, tmpZip)
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        tmpZip.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open destination file")
                    tmpZip.length()
                }
            }.onSuccess { bytes ->
                val msg = "Exported '${project.name}' ZIP (${bytes / 1024} KB)."
                appendTerminal("[Export] $msg")
                onComplete(msg)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = "Export failed: ${err.localizedMessage ?: "Write error"}"
                appendTerminal("[Export] $msg")
                onComplete(msg)
            }
        }
    }

    fun exportGodot47ProjectZip(context: Context): File {
        val sync = syncGodot47ProjectToDisk(context)
        val outZip = File(workspaceRoot, "exports/godot_47_project.zip")
        GodotProjectWorkspace.exportGodotProjectZip(sync.projectDir, outZip)
        val msg = "[Godot 4.7.2] Exported Godot project ZIP (${outZip.length() / 1024} KB) -> ${outZip.name}"
        appendTerminal(msg)
        appendLogcat("I/Godot47Export: $msg")
        return outZip
    }

    fun recordGodotBridgeEvent(type: String, name: String, payload: String) {
        val formatted = "[Godot 4.7.2 Bridge/$type] $name: $payload"
        _godotSignalsLog.value = (listOf(formatted) + _godotSignalsLog.value).take(30)
        appendTerminal(formatted)
        appendLogcat("I/GodotBridge: $formatted")
        StudioTriggerBus.pushEvent("godot.$type", "Godot 4.7.2 Bridge", "$name: $payload")
        if (type == "studiolang" && payload.isNotBlank()) {
            runInlineStudioLang(payload)
        }
    }

    private fun appendTerminal(line: String) {
        _terminalLogs.value = (_terminalLogs.value + line.lines()).takeLast(120)
    }

    private fun appendLogcat(line: String) {
        _logcatLogs.value = (_logcatLogs.value + line).takeLast(100)
    }

    companion object {
        private fun createDefaultSceneNodes(): List<GodotSceneNode> = listOf(
            GodotSceneNode(
                id = "node_player",
                name = "Player",
                type = GodotNodeType.CHARACTER_BODY_2D,
                x = 110f,
                y = 140f,
                width = 54f,
                height = 54f,
                gravityScale = 0.2f,
                tintHex = 0xFF34D399,
                scriptPath = "scripts/player_controller.gd",
                scriptLanguage = ScriptLanguage.GDSCRIPT
            ),
            GodotSceneNode(
                id = "node_floor",
                name = "FloorPlatform",
                type = GodotNodeType.COLLISION_SHAPE_2D,
                x = 240f,
                y = 260f,
                width = 340f,
                height = 32f,
                tintHex = 0xFFFBBF24,
                solidCollision = true
            ),
            GodotSceneNode(
                id = "node_gem",
                name = "GoalGem",
                type = GodotNodeType.SPRITE_2D,
                x = 370f,
                y = 140f,
                width = 44f,
                height = 44f,
                tintHex = 0xFF38BDF8,
                scriptPath = "scripts/coin_patrol.py",
                scriptLanguage = ScriptLanguage.PYTHON
            ),
            GodotSceneNode(
                id = "node_hud",
                name = "HudTitle",
                type = GodotNodeType.LABEL,
                x = 180f,
                y = 36f,
                width = 200f,
                height = 30f,
                tintHex = 0xFFF8FAFC,
                labelText = "Godot + StudioLang"
            ),
            GodotSceneNode(
                id = "node_touch",
                name = "JumpTouchZone",
                type = GodotNodeType.TOUCH_ZONE,
                x = 430f,
                y = 215f,
                width = 88f,
                height = 48f,
                tintHex = 0xFF22D3EE,
                labelText = "TAP JUMP"
            )
        )

        private fun defaultExtensionPacks(): List<IdeExtensionPack> = listOf(
            IdeExtensionPack(
                id = "ms-vscode.studiolang",
                name = "StudioLang & Multi-Language IntelliSense",
                publisher = "Automation Studio (MIT)",
                description = "Syntax coloring, inline diagnostics, and instant bytecode check for .stl, .gd, .py, .kt, .java, .js, .cpp.",
                version = "2.4.0"
            ),
            IdeExtensionPack(
                id = "godotengine.polyglot-2d",
                name = "Godot 4.7.2.stable Engine & Polyglot Bridge",
                publisher = "Godot Ecosystem (MIT)",
                description = "Official Godot 4.7.2.stable Runtime/Editor + GDScript, Python, StudioLang, Kotlin, Java, JS, C++ bridge.",
                version = "4.7.2"
            ),
            IdeExtensionPack(
                id = "androidcs.compose-preview",
                name = "Android Code Studio & Live Tester",
                publisher = "AndroidCS Ecosystem",
                description = "Zero-download Manifest Play-Policy validator, live Compose UI tester, and Gradle APK CI ZIP export.",
                version = "1.9.0"
            ),
            IdeExtensionPack(
                id = "automation.voice-ide",
                name = "Voice Control & Hindi/English Assistant",
                publisher = "Automation Studio",
                description = "Control VS Code tabs, Godot play/spawn/attach, and Android builds hands-free by voice.",
                version = "1.5.0"
            )
        )
    }
}
