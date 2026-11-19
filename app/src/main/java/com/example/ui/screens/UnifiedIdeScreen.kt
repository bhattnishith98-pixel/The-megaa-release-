package com.example.ui.screens

import android.provider.OpenableColumns
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.StudioViewModel
import com.example.ide.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Custom Automation Studio Unified Landscape Workbench combining:
 * 1. Microsoft VS Code (Explorer, Multi-Tab Editor, Symbol Quick Bar, Problems, Extensions, Terminal)
 * 2. Godot Engine (2D Scene Tree, Interactive Canvas Viewport, Gizmos, Physics, Multi-Language Attach Script)
 * 3. Android Code Studio (App Override Mode, Live Interactive Preview & Tester, Manifest Audit, Gradle APK ZIP Builder)
 */
@Composable
fun UnifiedIdeScreen(
    vm: StudioViewModel,
    onOpenAgentConfig: () -> Unit = {}
) {
    val ide = vm.unifiedIde
    val workspaceMode by ide.workspaceMode.collectAsStateWithLifecycle()
    val themePreset by ide.themePreset.collectAsStateWithLifecycle()
    val activeActivityTab by ide.activeActivityTab.collectAsStateWithLifecycle()
    val leftOpen by ide.leftDrawerOpen.collectAsStateWithLifecycle()
    val rightOpen by ide.rightDrawerOpen.collectAsStateWithLifecycle()
    val bottomOpen by ide.bottomPanelOpen.collectAsStateWithLifecycle()
    val landscapeLock by ide.landscapeLockEnabled.collectAsStateWithLifecycle()
    val files by ide.files.collectAsStateWithLifecycle()
    val openTabs by ide.openTabs.collectAsStateWithLifecycle()
    val activeTabPath by ide.activeTabPath.collectAsStateWithLifecycle()
    val sceneNodes by ide.sceneNodes.collectAsStateWithLifecycle()
    val selectedNodeId by ide.selectedNodeId.collectAsStateWithLifecycle()
    val extensions by ide.extensions.collectAsStateWithLifecycle()
    val diagnostics by ide.diagnostics.collectAsStateWithLifecycle()
    val terminalLogs by ide.terminalLogs.collectAsStateWithLifecycle()
    val logcatLogs by ide.logcatLogs.collectAsStateWithLifecycle()
    val activeBottomTab by ide.activeBottomTab.collectAsStateWithLifecycle()
    val lastTestResult by ide.acsLastTestResult.collectAsStateWithLifecycle()
    val searchQuery by ide.searchQuery.collectAsStateWithLifecycle()
    val appDesign by vm.appDesign.collectAsStateWithLifecycle()
    val isListening by vm.voiceController.isListening.collectAsStateWithLifecycle()
    val mountedRepos by ide.mountedRepos.collectAsStateWithLifecycle()
    val repoBridges by ide.bridgeDescriptors.collectAsStateWithLifecycle()
    val gitChanges by ide.gitChanges.collectAsStateWithLifecycle()
    val gitCommits by ide.gitCommits.collectAsStateWithLifecycle()
    val editorConfig by ide.editorConfig.collectAsStateWithLifecycle()
    val repoDownloadStatus by ide.repoDownloadStatus.collectAsStateWithLifecycle()
    val splitRightPath by ide.splitRightPath.collectAsStateWithLifecycle()
    val repoImportMode by ide.repoImportMode.collectAsStateWithLifecycle()
    val inAppVaultZips by ide.inAppVaultZips.collectAsStateWithLifecycle()
    val acsBuildLang by ide.acsBuildLanguage.collectAsStateWithLifecycle()
    val aiConfig by vm.aiProvider.config.collectAsStateWithLifecycle()
    val ideAiBusy by vm.ideAiBusy.collectAsStateWithLifecycle()
    val ideAiReply by vm.ideAiLastReply.collectAsStateWithLifecycle()
    val isAcsBuilding by ide.isAcsBuilding.collectAsStateWithLifecycle()
    val isCompiling by ide.isCompiling.collectAsStateWithLifecycle()
    val activeProject by ide.activeProject.collectAsStateWithLifecycle()
    val recentProjects by ide.recentProjects.collectAsStateWithLifecycle()
    val projectSyncState by ide.projectSyncState.collectAsStateWithLifecycle()
    val projectSyncMessage by ide.projectSyncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ide.attachAppContext(context)
    }

    val safFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.setBanner("Scanning folder for project files...")
            ide.openSafFolderProject(context, uri) { msg -> vm.setBanner(msg) }
        }
    }

    val anyZipProjectPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.setBanner("Extracting project ZIP...")
            ide.importZipProject(context, uri) { msg -> vm.setBanner(msg) }
        }
    }

    var pendingExportProject by remember { mutableStateOf<IdeProjectDescriptor?>(null) }
    val exportProjectZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val target = pendingExportProject ?: activeProject
            ide.exportProjectZipToUri(context, target, uri) { msg -> vm.setBanner(msg) }
        }
        pendingExportProject = null
    }

    val nativeToolchainPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                vm.setBanner("Installing Android ARM64 Clang toolchain…")
                val result = withContext(Dispatchers.IO) {
                    com.example.runtime.NativeCompilerRuntime.installFromZip(context, uri)
                }
                vm.setBanner(result.message)
            }
        }
    }

    var pendingRepoKindForZip by remember { mutableStateOf<UpstreamRepoKind?>(null) }
    val zipRepoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: (uri.lastPathSegment ?: "repo.zip")
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (stream != null) {
                ide.mountRepoFromZipStream(
                    kindOverride = pendingRepoKindForZip,
                    zipName = fileName,
                    inputStream = stream,
                    importMode = repoImportMode,
                    saveToInAppVault = true
                )
                vm.setBanner("Saved $fileName into In-App Vault & mounting (${repoImportMode.title})...")
            } else {
                vm.setBanner("Could not open selected archive.")
            }
        }
        pendingRepoKindForZip = null
    }

    var attachScriptTarget by remember { mutableStateOf<GodotSceneNode?>(null) }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var repoSetupDialogKind by remember { mutableStateOf<UpstreamRepoKind?>(null) }
    var showRepoSetupDialog by remember { mutableStateOf(false) }
    var showProjectManagerDialog by remember { mutableStateOf(false) }

    var compactDrawerOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("unified_ide_screen")
    ) {
        val isLandscapeWide = maxWidth >= 600.dp
        val drawerWidth = if (maxWidth >= 820.dp) 200.dp else 160.dp
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Custom Automation Studio Landscape Workbench Top Bar
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateSurface)
                .border(1.dp, SlateBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                FilledTonalButton(
                    onClick = { showProjectManagerDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_project_chip"),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SlateElevated,
                        contentColor = CyberCyan
                    )
                ) {
                    Icon(
                        imageVector = if (activeProject.isDirectFolder) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                        contentDescription = "Open or switch project",
                        modifier = Modifier.size(16.dp),
                        tint = if (activeProject.isDirectFolder) EmeraldRun else CyberCyan
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${activeProject.name} · ${activeProject.detectedType.label}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (files.any { it.isDirty } || activeProject.isDirectFolder) {
                item {
                    OutlinedButton(
                        onClick = { ide.saveAllDirtyFiles { msg -> vm.setBanner(msg) } },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_save_all_btn")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save all project files", modifier = Modifier.size(15.dp), tint = EmeraldRun)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (projectSyncState == ProjectSyncState.SAVING) "Saving..." else "Save",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldRun
                        )
                    }
                }
            }
            item {
                FilterChip(
                    selected = workspaceMode == IdeWorkspaceMode.DUAL_GAME_AND_APP,
                    onClick = { ide.setWorkspaceMode(IdeWorkspaceMode.DUAL_GAME_AND_APP) },
                    label = { Text("VS + Godot + ACS", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("mode_dual_chip")
                )
            }
            item {
                FilterChip(
                    selected = workspaceMode == IdeWorkspaceMode.ANDROID_CODE_STUDIO,
                    onClick = { ide.setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO) },
                    label = { Text("Android App Override", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("mode_acs_chip")
                )
            }
            item {
                Button(
                    onClick = { ide.runCompileAndTest() },
                    enabled = !isCompiling && !isAcsBuilding,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_run_all_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isCompiling) "Testing..." else "Run / Test", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                OutlinedButton(
                    onClick = { nativeToolchainPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_install_native_compiler_btn")
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (com.example.runtime.NativeCompilerRuntime.isInstalled(context)) "Clang Ready" else "Install C/C++",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        val sourcePath = activeTabPath
                        val source = java.io.File(ide.nativeCompilerWorkspaceRoot(), sourcePath)
                        val extension = source.extension.lowercase()
                        if (extension != "c" && extension !in setOf("cc", "cpp", "cxx")) {
                            vm.setBanner("Open a .c, .cc, .cpp or .cxx source file first.")
                        } else {
                            coroutineScope.launch {
                                vm.setBanner("Compiling ${source.name} with on-device Clang…")
                                val result = withContext(Dispatchers.IO) {
                                    if (extension == "c") {
                                        com.example.runtime.NativeCompilerRuntime.compileC(
                                            context, ide.nativeCompilerWorkspaceRoot(), source
                                        )
                                    } else {
                                        com.example.runtime.NativeCompilerRuntime.compileCpp(
                                            context, ide.nativeCompilerWorkspaceRoot(), source
                                        )
                                    }
                                }
                                vm.setBanner(
                                    if (result.success) "Compiled ${source.name}: ${result.outputFile?.relativeTo(ide.nativeCompilerWorkspaceRoot())}"
                                    else "Compile failed: ${result.output.take(240)}"
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_compile_native_btn")
                ) {
                    Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Compile C/C++", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val sync = withContext(Dispatchers.IO) { ide.syncGodot47ProjectToDisk(context) }
                            context.startActivity(
                                com.example.runtime.GodotRuntimeActivity.createIntent(
                                    context = context,
                                    projectPath = sync.projectDir.absolutePath,
                                    mainScene = sync.mainSceneResPath
                                )
                            )
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_run_godot_47_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                ) {
                    Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run Godot 4.7", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(com.example.runtime.GodotRuntimeActivity.createIntent(context))
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_real_godot_runtime_btn")
                ) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Godot 3D", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (BuildConfig.GODOT_EDITOR_BUILD) {
                item {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val sync = withContext(Dispatchers.IO) { ide.syncGodot47ProjectToDisk(context) }
                                context.startActivity(
                                    com.example.runtime.GodotEditorActivity.createIntent(
                                        context = context,
                                        projectPath = sync.projectDir.absolutePath
                                    )
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_godot_editor_btn")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Godot 4.7 Editor", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                com.example.runtime.GodotEditorActivity.createIntent(
                                    context = context,
                                    openProjectManager = true
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_godot_project_manager_btn")
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Project Mgr", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        vm.voiceController.initTts()
                        vm.voiceController.startListening { spoken ->
                            ide.handleVoiceCommand(spoken)
                        }
                        if (!vm.voiceController.hasMicPermission()) {
                            ide.setActivityTab(IdeActivityTab.VOICE_AI)
                            vm.setBanner("Grant Mic permission or run quick voice commands in Voice tab.")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_voice_btn")
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice Command",
                        tint = if (isListening) RoseError else CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isListening) "Listening..." else "Voice", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                OutlinedButton(
                    onClick = { showCommandPalette = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp).testTag("ide_palette_btn")
                ) {
                    Text("Palette", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }
            item {
                FilterChip(
                    selected = if (isLandscapeWide) leftOpen else compactDrawerOpen,
                    onClick = {
                        if (isLandscapeWide) ide.toggleLeftDrawer()
                        else compactDrawerOpen = !compactDrawerOpen
                    },
                    label = { Text("Sidebar", style = MaterialTheme.typography.labelSmall) }
                )
            }
            item {
                FilterChip(
                    selected = bottomOpen,
                    onClick = { ide.toggleBottomPanel() },
                    label = { Text("Terminal", style = MaterialTheme.typography.labelSmall) }
                )
            }
            item {
                FilterChip(
                    selected = rightOpen,
                    onClick = { ide.toggleRightDrawer() },
                    label = { Text("Preview/Scene", style = MaterialTheme.typography.labelSmall) }
                )
            }
            item {
                FilterChip(
                    selected = landscapeLock,
                    onClick = { ide.toggleLandscapeLock() },
                    label = { Text(if (landscapeLock) "Landscape" else "Auto-Orient", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("ide_landscape_toggle")
                )
            }
            item {
                TextButton(
                    onClick = { showLicensesDialog = true },
                    modifier = Modifier.testTag("ide_licenses_btn")
                ) {
                    Text("MIT", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        // Main Unified Responsive Workbench:
        // Landscape (>= 600dp): Activity Rail + Left Drawer | Center VS Code + Terminal | Right Godot/Android
        // Compact (< 600dp): Activity Rail | (On-demand Drawer) + Vertical split Editor / Godot&Android
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxSize()) {
                VsCodeActivityRail(
                    activeTab = activeActivityTab,
                    leftDrawerOpen = if (isLandscapeWide) leftOpen else compactDrawerOpen,
                    onSelectTab = { tab ->
                        if (isLandscapeWide) {
                            if (leftOpen && activeActivityTab == tab) ide.toggleLeftDrawer()
                            else ide.setActivityTab(tab)
                        } else {
                            if (compactDrawerOpen && activeActivityTab == tab) compactDrawerOpen = false
                            else {
                                ide.setActivityTab(tab)
                                compactDrawerOpen = true
                            }
                        }
                    }
                )

                if ((isLandscapeWide && leftOpen) || (!isLandscapeWide && compactDrawerOpen)) {
                    VsCodeLeftDrawerPane(
                        activeTab = activeActivityTab,
                        files = files,
                        activeFilePath = activeTabPath,
                        sceneNodes = sceneNodes,
                        selectedNodeId = selectedNodeId,
                        extensions = extensions,
                        themePreset = themePreset,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { ide.updateSearchQuery(it) },
                        onOpenFile = { ide.openFile(it); if (!isLandscapeWide) compactDrawerOpen = false },
                        onNewFileClick = { showNewFileDialog = true },
                        onSelectNode = { ide.selectNode(it) },
                        onAddNodeClick = { showAddNodeDialog = true },
                        onAttachScriptClick = { node -> attachScriptTarget = node },
                        onToggleExtension = { ide.toggleExtension(it) },
                        onSelectTheme = { ide.setThemePreset(it) },
                        onSwitchAndroidMode = { ide.setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO) },
                        onRunVoiceText = { ide.handleVoiceCommand(it) },
                        mountedRepos = mountedRepos,
                        repoBridges = repoBridges,
                        selectedImportMode = repoImportMode,
                        inAppVaultZips = inAppVaultZips,
                        onSelectImportMode = { ide.setRepoImportMode(it) },
                        onMountRepoZipClick = { kind ->
                            repoSetupDialogKind = kind
                            ide.refreshInAppVault()
                            showRepoSetupDialog = true
                        },
                        onRescanReposClick = { ide.scanExternalReposFolders() },
                        onOpenMountedSampleFile = { kind ->
                            if (ide.openMountedSampleFile(kind) && !isLandscapeWide) compactDrawerOpen = false
                        },
                        onRunBridgeWorkflow = { bridgeId ->
                            ide.runBridgeWorkflow(bridgeId)
                            vm.setBanner("Verified workflow bridge: $bridgeId")
                        },
                        repoDownloadStatus = repoDownloadStatus,
                        onDownloadRepoZipClick = { kind ->
                            ide.downloadAndMountRepo(kind, importMode = repoImportMode)
                            vm.setBanner("Fetching ${kind.archiveFileName} (${repoImportMode.title})...")
                        },
                        selectedAppBuildLang = acsBuildLang,
                        onSelectAppBuildLang = { lang ->
                            vm.switchAcsBuildLanguage(lang)
                            ide.setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO)
                            vm.setBanner("Switched Android Code Studio build language to ${lang.label}.")
                        },
                        onRunAcsGradleBuild = {
                            ide.setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO)
                            ide.runAndroidStudioGradleBuildPipeline(appDesign.copy(buildLanguage = acsBuildLang))
                            if (!isLandscapeWide) compactDrawerOpen = false
                            vm.setBanner("Running Android Code Studio Gradle build (${acsBuildLang.label})...")
                        },
                        gitChanges = gitChanges,
                        gitCommits = gitCommits,
                        onCommitGit = { msg ->
                            val rec = ide.commitGitChanges(msg)
                            vm.setBanner("Committed ${rec.id}: ${rec.message}")
                        },
                        onRevertGitFile = { path ->
                            ide.revertFileToCommit(path)
                            vm.setBanner("Reverted $path")
                        },
                        aiProviderLabel = "${aiConfig.provider.displayName} · ${aiConfig.model}",
                        ideAiBusy = ideAiBusy,
                        ideAiReply = ideAiReply,
                        onAskIdeAi = { prompt -> vm.askActiveAiForIde(prompt, applyToActiveFile = true) },
                        isAcsBuilding = isAcsBuilding,
                        onOpenAgentConfig = onOpenAgentConfig,
                        activeProjectName = activeProject.name,
                        projectSyncMessage = projectSyncMessage,
                        onOpenProjectsManager = { showProjectManagerDialog = true },
                        onSaveAllFiles = { ide.saveAllDirtyFiles { msg -> vm.setBanner(msg) } },
                        onDeleteFile = { path -> ide.deleteFileFromActiveProject(path) { msg -> vm.setBanner(msg) } },
                        onExportProjectZip = {
                            pendingExportProject = activeProject
                            val safeZip = activeProject.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" }
                            runCatching { exportProjectZipLauncher.launch("$safeZip.zip") }
                                .onFailure { err -> vm.setBanner("Document saver unavailable: ${err.localizedMessage ?: "No handler"}") }
                        },
                        modifier = Modifier.width(if (isLandscapeWide) drawerWidth else 155.dp)
                    )
                }

                if (isLandscapeWide) {
                    VsCodeEditorAndTerminalPane(
                        theme = themePreset,
                        files = files,
                        openTabs = openTabs,
                        activeTabPath = activeTabPath,
                        bottomPanelOpen = bottomOpen,
                        activeBottomTab = activeBottomTab,
                        terminalLogs = terminalLogs,
                        diagnostics = diagnostics,
                        logcatLogs = logcatLogs,
                        lastTestResult = lastTestResult,
                        onSelectTab = { ide.openFile(it) },
                        onCloseTab = { ide.closeTab(it) },
                        onContentChange = { ide.updateActiveFileContent(it) },
                        onSelectBottomTab = { ide.setBottomTab(it) },
                        onToggleBottomPanel = { ide.toggleBottomPanel() },
                        onExecuteTerminal = { ide.executeTerminalCommand(it) },
                        onRunActiveScript = { ide.runCompileAndTest() },
                        editorConfig = editorConfig,
                        splitRightPath = splitRightPath,
                        onUndo = { ide.undoActiveFile() },
                        onRedo = { ide.redoActiveFile() },
                        onFindReplace = { q, r, all -> ide.findAndReplaceActiveFile(q, r, all) },
                        onFontSizeChange = { sz -> ide.updateEditorConfig { it.copy(fontSizeSp = sz) } },
                        onToggleSplit = { ide.toggleSplitEditorRight() },
                        onToggleWordWrap = { ide.updateEditorConfig { it.copy(wordWrap = !it.wordWrap) } },
                        onToggleAutoSave = { ide.updateEditorConfig { it.copy(autoSave = !it.autoSave) } },
                        onRunVscodeCommand = { cmd -> ide.executeVscodeTextCommand(cmd) },
                        onToggleSoraAar = {
                            ide.updateEditorConfig { cfg ->
                                val next = when (cfg.backend) {
                                    IdeEditorBackend.SORA_AAR -> IdeEditorBackend.VSCODROID_AAR
                                    IdeEditorBackend.VSCODROID_AAR -> IdeEditorBackend.COMPOSE
                                    IdeEditorBackend.COMPOSE -> IdeEditorBackend.SORA_AAR
                                }
                                cfg.copy(backend = next)
                            }
                        },
                        modifier = Modifier.weight(1.1f)
                    )

                    if (rightOpen) {
                        GodotAndAndroidRightPane(
                            ide = ide,
                            appDesign = appDesign,
                            onAttachScriptClick = { node -> attachScriptTarget = node },
                            onExportAndroidZip = { vm.exportUnifiedAndroidZip() },
                            onSelectAppBuildLanguage = { lang -> vm.switchAcsBuildLanguage(lang) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        VsCodeEditorAndTerminalPane(
                            theme = themePreset,
                            files = files,
                            openTabs = openTabs,
                            activeTabPath = activeTabPath,
                            bottomPanelOpen = bottomOpen,
                            activeBottomTab = activeBottomTab,
                            terminalLogs = terminalLogs,
                            diagnostics = diagnostics,
                            logcatLogs = logcatLogs,
                            lastTestResult = lastTestResult,
                            onSelectTab = { ide.openFile(it) },
                            onCloseTab = { ide.closeTab(it) },
                            onContentChange = { ide.updateActiveFileContent(it) },
                            onSelectBottomTab = { ide.setBottomTab(it) },
                            onToggleBottomPanel = { ide.toggleBottomPanel() },
                            onExecuteTerminal = { ide.executeTerminalCommand(it) },
                            onRunActiveScript = { ide.runCompileAndTest() },
                            editorConfig = editorConfig,
                            splitRightPath = splitRightPath,
                            onUndo = { ide.undoActiveFile() },
                            onRedo = { ide.redoActiveFile() },
                            onFindReplace = { q, r, all -> ide.findAndReplaceActiveFile(q, r, all) },
                            onFontSizeChange = { sz -> ide.updateEditorConfig { it.copy(fontSizeSp = sz) } },
                            onToggleSplit = { ide.toggleSplitEditorRight() },
                            onToggleWordWrap = { ide.updateEditorConfig { it.copy(wordWrap = !it.wordWrap) } },
                            onToggleAutoSave = { ide.updateEditorConfig { it.copy(autoSave = !it.autoSave) } },
                            onRunVscodeCommand = { cmd -> ide.executeVscodeTextCommand(cmd) },
                            onToggleSoraAar = {
                                ide.updateEditorConfig { cfg ->
                                    val next = when (cfg.backend) {
                                        IdeEditorBackend.SORA_AAR -> IdeEditorBackend.VSCODROID_AAR
                                        IdeEditorBackend.VSCODROID_AAR -> IdeEditorBackend.COMPOSE
                                        IdeEditorBackend.COMPOSE -> IdeEditorBackend.SORA_AAR
                                    }
                                    cfg.copy(backend = next)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )

                        if (rightOpen) {
                            GodotAndAndroidRightPane(
                                ide = ide,
                                appDesign = appDesign,
                                onAttachScriptClick = { node -> attachScriptTarget = node },
                                onExportAndroidZip = { vm.exportUnifiedAndroidZip() },
                                onSelectAppBuildLanguage = { lang -> vm.switchAcsBuildLanguage(lang) },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    attachScriptTarget?.let { node ->
        AttachScriptDialog(
            node = node,
            onDismiss = { attachScriptTarget = null },
            onConfirmAttach = { lang, tmpl ->
                ide.attachScriptToNode(node.id, lang, tmpl)
                attachScriptTarget = null
            }
        )
    }

    if (showAddNodeDialog) {
        AddGodotNodeDialog(
            onDismiss = { showAddNodeDialog = false },
            onAdd = { type, name ->
                ide.addSceneNode(type, name)
                showAddNodeDialog = false
            }
        )
    }

    if (showNewFileDialog) {
        NewIdeFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { path, starter ->
                ide.createOrUpdateFile(path, starter)
                showNewFileDialog = false
            }
        )
    }

    if (showCommandPalette) {
        CommandPaletteDialog(
            commands = remember { PolyglotGodotCompiler.buildCommandCatalog() },
            onDismiss = { showCommandPalette = false },
            onSelect = { cmd ->
                showCommandPalette = false
                when (cmd.id) {
                    "cmd.mode_dual" -> ide.setWorkspaceMode(IdeWorkspaceMode.DUAL_GAME_AND_APP)
                    "cmd.mode_acs" -> ide.setWorkspaceMode(IdeWorkspaceMode.ANDROID_CODE_STUDIO)
                    "cmd.godot_play" -> if (ide.isScenePlaying.value) ide.pauseScene() else ide.playScene()
                    "cmd.godot_stop" -> ide.stopScene()
                    "cmd.godot_run_47" -> coroutineScope.launch {
                        val sync = withContext(Dispatchers.IO) { ide.syncGodot47ProjectToDisk(context) }
                        context.startActivity(
                            com.example.runtime.GodotRuntimeActivity.createIntent(
                                context = context,
                                projectPath = sync.projectDir.absolutePath,
                                mainScene = sync.mainSceneResPath
                            )
                        )
                    }
                    "cmd.godot_editor_47" -> {
                        if (BuildConfig.GODOT_EDITOR_BUILD) {
                            coroutineScope.launch {
                                val sync = withContext(Dispatchers.IO) { ide.syncGodot47ProjectToDisk(context) }
                                context.startActivity(
                                    com.example.runtime.GodotEditorActivity.createIntent(
                                        context = context,
                                        projectPath = sync.projectDir.absolutePath
                                    )
                                )
                            }
                        } else {
                            vm.setBanner("Godot Editor is disabled in runtime-only builds.")
                        }
                    }
                    "cmd.godot_project_manager" -> {
                        if (BuildConfig.GODOT_EDITOR_BUILD) {
                            context.startActivity(
                                com.example.runtime.GodotEditorActivity.createIntent(
                                    context = context,
                                    openProjectManager = true
                                )
                            )
                        } else {
                            vm.setBanner("Godot Project Manager requires editor-enabled build.")
                        }
                    }
                    "cmd.godot_3d_demo" -> context.startActivity(
                        com.example.runtime.GodotRuntimeActivity.createIntent(context)
                    )
                    "cmd.godot_sync_disk" -> coroutineScope.launch {
                        val sync = withContext(Dispatchers.IO) { ide.syncGodot47ProjectToDisk(context) }
                        vm.setBanner("Synced ${sync.writtenFilesCount} files to Godot 4.7.2 workspace.")
                    }
                    "cmd.godot_export_zip" -> coroutineScope.launch {
                        val zip = withContext(Dispatchers.IO) { ide.exportGodot47ProjectZip(context) }
                        vm.setBanner("Exported Godot 4.7.2 project ZIP: ${zip.name}")
                    }
                    "cmd.godot_add_node" -> showAddNodeDialog = true
                    "cmd.godot_attach_script" -> {
                        val target = sceneNodes.firstOrNull { it.id == selectedNodeId } ?: sceneNodes.firstOrNull()
                        if (target != null) attachScriptTarget = target
                    }
                    "cmd.run_compile" -> ide.runCompileAndTest()
                    "cmd.acs_export_zip" -> vm.exportUnifiedAndroidZip()
                    "cmd.voice_listen" -> ide.setActivityTab(IdeActivityTab.VOICE_AI)
                    "cmd.toggle_landscape" -> ide.toggleLandscapeLock()
                    "cmd.licenses" -> showLicensesDialog = true
                }
            }
        )
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    if (showRepoSetupDialog) {
        RepoImportChoiceAndVaultDialog(
            initialTargetKind = repoSetupDialogKind,
            selectedMode = repoImportMode,
            inAppVaultZips = inAppVaultZips,
            downloadStatus = repoDownloadStatus,
            onSelectMode = { mode -> ide.setRepoImportMode(mode) },
            onMountVaultZip = { vaultZip, mode ->
                ide.setRepoImportMode(mode)
                ide.mountInAppVaultZip(vaultZip, mode)
                vm.setBanner("Loaded ${vaultZip.name} from In-App Vault (${mode.title}).")
            },
            onGenerateStarterZipInApp = { kind, mode ->
                ide.setRepoImportMode(mode)
                ide.generateAndMountInAppRepoZip(kind, mode)
                vm.setBanner("Staged & mounted ${kind.archiveFileName} inside app (${mode.title}).")
            },
            onFetchGitHubUrl = { kind, url, mode ->
                ide.setRepoImportMode(mode)
                ide.downloadAndMountRepo(kind, customUrl = url, importMode = mode)
                vm.setBanner("Downloading ${kind.archiveFileName} straight into In-App Vault...")
            },
            onAddDirectFileInApp = { kind, relPath, body ->
                val vPath = ide.addDirectFileToRepo(kind, relPath, body)
                vm.setBanner("Saved $vPath directly inside app.")
            },
            onOptionalExternalFilePicker = { kind ->
                pendingRepoKindForZip = kind
                runCatching {
                    zipRepoPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                }.onFailure { err ->
                    vm.setBanner("System file picker unavailable: ${err.localizedMessage ?: "No handler"}")
                }
            },
            onDismiss = { showRepoSetupDialog = false }
        )
    }

    if (showProjectManagerDialog) {
        ProjectManagerDialog(
            activeProject = activeProject,
            recentProjects = recentProjects,
            syncState = projectSyncState,
            syncMessage = projectSyncMessage,
            onDismiss = { showProjectManagerDialog = false },
            onOpenDeviceFolder = {
                runCatching { safFolderPicker.launch(null) }.onFailure { err ->
                    vm.setBanner("Folder picker unavailable: ${err.localizedMessage ?: "No handler"}")
                }
            },
            onImportZipProject = {
                runCatching { anyZipProjectPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }
                    .onFailure { err ->
                        vm.setBanner("ZIP picker unavailable: ${err.localizedMessage ?: "No handler"}")
                    }
            },
            onCloneGithubRepo = { url, branch ->
                vm.setBanner("Cloning $url...")
                ide.cloneGithubProject(url, branch) { msg -> vm.setBanner(msg) }
            },
            onCreateNewProject = { name, template ->
                ide.createNewInAppProject(name, template) { msg -> vm.setBanner(msg) }
            },
            onSwitchProject = { proj ->
                ide.switchToProject(context, proj) { msg -> vm.setBanner(msg) }
            },
            onExportProjectZip = { proj ->
                pendingExportProject = proj
                val safeZip = proj.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "project" }
                runCatching { exportProjectZipLauncher.launch("$safeZip.zip") }
                    .onFailure { err -> vm.setBanner("Document saver unavailable: ${err.localizedMessage ?: "No handler"}") }
            },
            onDeleteProject = { proj ->
                ide.removeProject(proj)
                vm.setBanner("Removed '${proj.name}' from Recent Projects.")
            }
        )
    }
    }
}
