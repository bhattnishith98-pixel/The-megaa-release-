package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.appbuilder.AppBuildLanguage
import com.example.ide.*
import com.example.ui.theme.*
import com.tom.rv2ide.studio.AcsAarRuntime
import com.tom.rv2ide.studio.AcsEditorThemeColors
import com.tom.rv2ide.studio.AcsSoraEditorFactory
import com.tom.rv2ide.studio.AcsSoraEditorHandle
import com.vscodroid.studio.VscodroidAarRuntime
import com.vscodroid.studio.VscodroidEditorOptions
import com.vscodroid.studio.VscodroidWorkbenchFactory
import com.vscodroid.studio.VscodroidWorkbenchHandle

@Composable
fun VsCodeActivityRail(
    activeTab: IdeActivityTab,
    leftDrawerOpen: Boolean,
    onSelectTab: (IdeActivityTab) -> Unit
) {
    Column(
        modifier = Modifier
            .width(50.dp)
            .fillMaxHeight()
            .background(SlateSurface)
            .border(1.dp, SlateBorder)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        IdeActivityTab.entries.forEach { tab ->
            val selected = leftDrawerOpen && activeTab == tab
            val icon = when (tab) {
                IdeActivityTab.EXPLORER -> Icons.Outlined.FolderOpen
                IdeActivityTab.SCENE_TREE -> Icons.Filled.AccountTree
                IdeActivityTab.SEARCH -> Icons.Filled.Search
                IdeActivityTab.SOURCE_CONTROL -> Icons.AutoMirrored.Filled.CallSplit
                IdeActivityTab.OUTLINE -> Icons.AutoMirrored.Filled.FormatListBulleted
                IdeActivityTab.EXTENSIONS -> Icons.Outlined.Extension
                IdeActivityTab.ANDROID_IDE -> Icons.Filled.Android
                IdeActivityTab.REPOS_BRIDGES -> Icons.Filled.Hub
                IdeActivityTab.VOICE_AI -> Icons.Filled.Mic
            }
            IconButton(
                onClick = { onSelectTab(tab) },
                modifier = Modifier
                    .size(48.dp)
                    .background(if (selected) CyberCyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .testTag("activity_tab_${tab.name.lowercase()}")
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tab.label,
                    tint = if (selected) CyberCyan else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun VsCodeLeftDrawerPane(
    activeTab: IdeActivityTab,
    files: List<IdeFileEntry>,
    activeFilePath: String,
    sceneNodes: List<GodotSceneNode>,
    selectedNodeId: String?,
    extensions: List<IdeExtensionPack>,
    themePreset: IdeThemePreset,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onNewFileClick: () -> Unit,
    onSelectNode: (String) -> Unit,
    onAddNodeClick: () -> Unit,
    onAttachScriptClick: (GodotSceneNode) -> Unit,
    onToggleExtension: (String) -> Unit,
    onSelectTheme: (IdeThemePreset) -> Unit,
    onSwitchAndroidMode: () -> Unit,
    onRunVoiceText: (String) -> Unit,
    mountedRepos: List<MountedRepoStatus> = emptyList(),
    repoBridges: List<RepoBridgeDescriptor> = emptyList(),
    selectedImportMode: RepoImportMode = RepoImportMode.FULL_SOURCE_AND_COMPILE,
    inAppVaultZips: List<InAppVaultZipEntry> = emptyList(),
    onSelectImportMode: (RepoImportMode) -> Unit = {},
    onMountRepoZipClick: (UpstreamRepoKind?) -> Unit = {},
    onRescanReposClick: () -> Unit = {},
    onOpenMountedSampleFile: (UpstreamRepoKind) -> Unit = {},
    onRunBridgeWorkflow: (String) -> Unit = {},
    repoDownloadStatus: Map<UpstreamRepoKind, String> = emptyMap(),
    onDownloadRepoZipClick: (UpstreamRepoKind) -> Unit = {},
    selectedAppBuildLang: AppBuildLanguage = AppBuildLanguage.KOTLIN_COMPOSE,
    onSelectAppBuildLang: (AppBuildLanguage) -> Unit = {},
    onRunAcsGradleBuild: () -> Unit = {},
    gitChanges: List<GitFileChange> = emptyList(),
    gitCommits: List<GitCommitRecord> = emptyList(),
    onCommitGit: (String) -> Unit = {},
    onRevertGitFile: (String) -> Unit = {},
    aiProviderLabel: String = "Active AI Model",
    ideAiBusy: Boolean = false,
    ideAiReply: String? = null,
    onAskIdeAi: (String) -> Unit = {},
    isAcsBuilding: Boolean = false,
    onOpenAgentConfig: () -> Unit = {},
    activeProjectName: String = "Automation Sandbox",
    projectSyncMessage: String = "",
    onOpenProjectsManager: () -> Unit = {},
    onSaveAllFiles: () -> Unit = {},
    onDeleteFile: (String) -> Unit = {},
    onExportProjectZip: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(SlateSurface)
            .border(1.dp, SlateBorder)
            .padding(8.dp)
    ) {
        Text(
            activeTab.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = CyberCyan
        )
        Spacer(Modifier.height(6.dp))

        when (activeTab) {
            IdeActivityTab.EXPLORER -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBg, RoundedCornerShape(8.dp))
                        .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { onOpenProjectsManager() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("explorer_projects_btn"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            activeProjectName,
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (projectSyncMessage.isNotBlank()) {
                            Text(
                                projectSyncMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Switch Project", tint = CyberCyan, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = onNewFileClick,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 40.dp).testTag("new_ide_file_btn"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SlateElevated, contentColor = CyberCyan)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("File", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onSaveAllFiles,
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp).testTag("explorer_save_all_btn"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("Save", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                    }
                    OutlinedButton(
                        onClick = onExportProjectZip,
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp).testTag("explorer_export_zip_btn"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("ZIP", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(files, key = { it.path }) { f ->
                        val active = f.path == activeFilePath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (active) CyberCyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { onOpenFile(f.path) }
                                .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                f.languageHint.uppercase().take(4),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (f.isDirty) AmberWarn else EmeraldRun
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (f.isDirty) "${f.path} *" else f.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (active) TextPrimary else TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onDeleteFile(f.path) },
                                modifier = Modifier.size(28.dp).testTag("delete_file_${f.title}")
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Delete ${f.title}",
                                    tint = TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            IdeActivityTab.SCENE_TREE -> {
                Button(
                    onClick = onAddNodeClick,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).testTag("add_godot_node_btn"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                ) {
                    Icon(Icons.Filled.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Node", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.testTag("scene_tree_list"),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(sceneNodes, key = { it.id }) { node ->
                        val selected = node.id == selectedNodeId
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (selected) SlateElevated else SlateCard),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (selected) CyberCyan else SlateBorder, RoundedCornerShape(8.dp))
                                .clickable { onSelectNode(node.id) }
                                .testTag("scene_node_item_${node.name}")
                        ) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(node.name, style = MaterialTheme.typography.labelMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(node.type.label, style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        node.scriptLanguage?.badge?.let { "$it attached" } ?: "No script",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (node.scriptPath != null) EmeraldRun else TextMuted
                                    )
                                    TextButton(
                                        onClick = { onAttachScriptClick(node) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.testTag("attach_script_node_${node.name}")
                                    ) {
                                        Text(if (node.scriptPath == null) "+ Script" else "Change", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            IdeActivityTab.SEARCH -> {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Ripgrep workspace search...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ide_search_input")
                )
                Spacer(Modifier.height(6.dp))
                val hits = remember(searchQuery, files) {
                    VscodeCoreKotlin.RipgrepSearch.searchWorkspace(files, searchQuery)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(hits) { hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SlateCard, RoundedCornerShape(6.dp))
                                .clickable { onOpenFile(hit.filePath) }
                                .padding(6.dp)
                        ) {
                            Text("${hit.fileTitle}:${hit.lineNumber}", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                            Text(hit.linePreview, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            IdeActivityTab.SOURCE_CONTROL -> {
                SourceControlDrawerPane(
                    changes = gitChanges,
                    commits = gitCommits,
                    onCommit = onCommitGit,
                    onOpenFile = onOpenFile,
                    onRevertFile = onRevertGitFile,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IdeActivityTab.OUTLINE -> {
                val activeEntry = files.firstOrNull { it.path == activeFilePath }
                val symbols = remember(activeEntry) { IdeAdvancedModulesEngine.extractCodeOutline(activeEntry) }
                CodeOutlineDrawerPane(
                    activeFile = activeEntry,
                    symbols = symbols,
                    onSymbolClick = { sym -> onOpenFile(activeFilePath) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            IdeActivityTab.EXTENSIONS -> {
                Text("VS Code Themes", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                IdeThemePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = themePreset == preset,
                        onClick = { onSelectTheme(preset) },
                        label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.fillMaxWidth().testTag("theme_chip_${preset.name.lowercase()}")
                    )
                }
                HorizontalDivider(color = SlateBorder, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(extensions, key = { it.id }) { ext ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ext.name, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                    Text("v${ext.version}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                                Switch(checked = ext.enabled, onCheckedChange = { onToggleExtension(ext.id) })
                            }
                        }
                    }
                }
            }

            IdeActivityTab.ANDROID_IDE -> {
                Button(
                    onClick = onSwitchAndroidMode,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                ) {
                    Text("Open Android App Preview", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRunAcsGradleBuild,
                    enabled = !isAcsBuilding,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp).testTag("drawer_acs_gradle_build_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary)
                ) {
                    Text(if (isAcsBuilding) "Building Gradle..." else "Gradle Build (ACS)", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text("Build App Language", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(AppBuildLanguage.entries) { lang ->
                        FilterChip(
                            selected = selectedAppBuildLang == lang,
                            onClick = { onSelectAppBuildLang(lang) },
                            label = { Text(lang.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("drawer_acs_lang_${lang.name}")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val androidFiles = files.filter { it.path.startsWith("android/") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(androidFiles, key = { it.path }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (f.path == activeFilePath) CyberCyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { onOpenFile(f.path) }
                                .padding(8.dp)
                        ) {
                            Text(f.title, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                    }
                }
            }

            IdeActivityTab.REPOS_BRIDGES -> {
                ReposAndBridgesDrawerPane(
                    mountedRepos = mountedRepos,
                    bridges = repoBridges,
                    downloadStatus = repoDownloadStatus,
                    selectedImportMode = selectedImportMode,
                    inAppVaultZips = inAppVaultZips,
                    onSelectImportMode = onSelectImportMode,
                    onMountRepoZipClick = onMountRepoZipClick,
                    onDownloadRepoZipClick = onDownloadRepoZipClick,
                    onRescanFoldersClick = onRescanReposClick,
                    onOpenSampleFile = onOpenMountedSampleFile,
                    onRunBridgeWorkflow = onRunBridgeWorkflow,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IdeActivityTab.VOICE_AI -> {
                var customCmd by remember { mutableStateOf("") }
                var aiCodePrompt by remember { mutableStateOf("") }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ask AI -> Code", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    Text(
                        aiProviderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedTextField(
                        value = aiCodePrompt,
                        onValueChange = { aiCodePrompt = it },
                        placeholder = { Text("Generate or edit active file...") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("ide_ai_code_prompt_input")
                    )
                    Button(
                        onClick = {
                            if (aiCodePrompt.isNotBlank() && !ideAiBusy) {
                                onAskIdeAi(aiCodePrompt)
                                aiCodePrompt = ""
                            }
                        },
                        enabled = !ideAiBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg),
                        modifier = Modifier.fillMaxWidth().testTag("ide_ai_code_generate_btn")
                    ) {
                        Text(if (ideAiBusy) "Generating..." else "Generate -> File", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!ideAiReply.isNullOrBlank()) {
                        Text(
                            ideAiReply,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ideAiReply.startsWith("FAIL:")) RoseError else EmeraldRun
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenAgentConfig,
                        modifier = Modifier.fillMaxWidth().testTag("ide_open_ai_setup_btn"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(14.dp), tint = AmberWarn)
                        Spacer(Modifier.width(4.dp))
                        Text("AI Studio Key Setup", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                    }

                    HorizontalDivider(color = SlateBorder, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Quick Voice Macros", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    listOf("Play scene", "Spawn Sprite", "Attach Python", "Android mode", "Compile test").forEach { phrase ->
                        OutlinedButton(
                            onClick = { onRunVoiceText(phrase) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(phrase, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedTextField(
                        value = customCmd,
                        onValueChange = { customCmd = it },
                        placeholder = { Text("Type voice macro...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { if (customCmd.isNotBlank()) { onRunVoiceText(customCmd); customCmd = "" } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Execute") }
                }
            }
        }
    }
}

@Composable
fun VsCodeEditorAndTerminalPane(
    theme: IdeThemePreset,
    files: List<IdeFileEntry>,
    openTabs: List<String>,
    activeTabPath: String,
    bottomPanelOpen: Boolean,
    activeBottomTab: IdeBottomPanelTab,
    terminalLogs: List<String>,
    diagnostics: List<IdeDiagnostic>,
    logcatLogs: List<String>,
    lastTestResult: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSelectBottomTab: (IdeBottomPanelTab) -> Unit,
    onToggleBottomPanel: () -> Unit,
    onExecuteTerminal: (String) -> Unit,
    onRunActiveScript: () -> Unit,
    editorConfig: IdeEditorConfig = IdeEditorConfig(),
    splitRightPath: String? = null,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onFindReplace: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onFontSizeChange: (Int) -> Unit = {},
    onToggleSplit: () -> Unit = {},
    onToggleWordWrap: () -> Unit = {},
    onToggleAutoSave: () -> Unit = {},
    onRunVscodeCommand: (String) -> Unit = {},
    onToggleSoraAar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeFile = files.firstOrNull { it.path == activeTabPath }
    val splitFile = splitRightPath?.let { sp -> files.firstOrNull { it.path == sp } }
    var terminalInput by remember { mutableStateOf("") }
    var findOpen by remember { mutableStateOf(false) }
    val symbolTokens = remember { listOf("{", "}", "(", ")", ":", "=", ".", "_", "->", "\"", "#") }
    val snippets = remember(activeTabPath) { VsCodeEditorFeaturesEngine.snippetsForFile(activeTabPath) }
    val soraAarAvailable = remember { AcsAarRuntime.isSoraEditorAvailable() }
    val vscodroidAarAvailable = remember { VscodroidAarRuntime.isWebViewAvailable() }
    val useSoraAarNow = editorConfig.useSoraAar && soraAarAvailable
    val useVscodroidAarNow = editorConfig.useVscodroidAar && vscodroidAarAvailable
    var soraHandle by remember { mutableStateOf<AcsSoraEditorHandle?>(null) }
    var vscodroidHandle by remember { mutableStateOf<VscodroidWorkbenchHandle?>(null) }
    val currentOnContentChange by rememberUpdatedState(onContentChange)
    val currentOnRunCommand by rememberUpdatedState(onRunVscodeCommand)
    val currentOnRunActiveScript by rememberUpdatedState(onRunActiveScript)

    DisposableEffect(Unit) {
        onDispose {
            vscodroidHandle?.release()
            vscodroidHandle = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(theme.editorBgHex))
            .border(1.dp, SlateBorder)
    ) {
        // Tab Strip
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(theme.gutterBgHex))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(openTabs, key = { it }) { path ->
                val isSelected = path == activeTabPath
                val entry = files.firstOrNull { it.path == path }
                Row(
                    modifier = Modifier
                        .background(
                            if (isSelected) Color(theme.editorBgHex) else SlateSurface.copy(alpha = 0.6f),
                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                        )
                        .border(1.dp, if (isSelected) Color(theme.accentHex) else SlateBorder, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .clickable { onSelectTab(path) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("editor_tab_${entry?.title ?: path}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry?.title ?: path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color(theme.accentHex) else TextSecondary
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close tab",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp).clickable { onCloseTab(path) }
                    )
                }
            }
        }

        // Mobile Quick Symbol Bar + Run Active Script
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateSurface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(symbolTokens) { tok ->
                    Surface(
                        color = SlateCard,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.clickable {
                            val handle = soraHandle
                            if (useSoraAarNow && handle != null) {
                                handle.insertAtCursor(tok)
                            } else {
                                activeFile?.let { onContentChange(it.content + tok) }
                            }
                        }
                    ) {
                        Text(
                            tok,
                            color = Color(theme.keywordHex),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .background(EmeraldRun.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                    .clickable { onRunActiveScript() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .testTag("vscode_run_file_btn"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = EmeraldRun, modifier = Modifier.size(14.dp))
                Text("Run File", color = EmeraldRun, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Editor Undo/Redo, Find & Replace, Split Right, Breadcrumbs, Snippets & Font Controls
        EditorQuickActionsHeader(
            editorConfig = editorConfig,
            findOpen = findOpen,
            splitOpen = splitFile != null,
            activePath = activeTabPath,
            snippets = snippets,
            onUndo = onUndo,
            onRedo = onRedo,
            onToggleFind = { findOpen = !findOpen },
            onToggleSplit = onToggleSplit,
            onToggleWordWrap = onToggleWordWrap,
            onToggleAutoSave = onToggleAutoSave,
            onFontSizeChange = onFontSizeChange,
            onRunFindReplace = onFindReplace,
            onInsertSnippet = { snip ->
                val handle = soraHandle
                if (useSoraAarNow && handle != null) {
                    handle.insertAtCursor("\n$snip")
                } else {
                    activeFile?.let { onContentChange(it.content + "\n" + snip) }
                }
            },
            onRunVscodeCommand = onRunVscodeCommand,
            onToggleSoraAar = onToggleSoraAar
        )

        // Code Editor Body with Sora CodeEditor AAR or Compose Fallback (+ optional Split Right side-by-side pane)
        val editorScroll = rememberScrollState()
        val fontSize = editorConfig.fontSizeSp.sp
        val lineHeight = (editorConfig.fontSizeSp + 6).sp
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (activeFile == null) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Text("Select a file or attach a script to a Godot node.", color = TextMuted)
                }
            } else if (useSoraAarNow) {
                val acsTheme = remember(theme) {
                    AcsEditorThemeColors(
                        editorBackgroundArgb = theme.editorBgHex.toInt(),
                        gutterBackgroundArgb = theme.gutterBgHex.toInt(),
                        accentArgb = theme.accentHex.toInt(),
                        textPrimaryArgb = 0xFFF8FAFC.toInt(),
                        lineNumberArgb = 0xFF64748B.toInt()
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("vscode_sora_aar_editor")
                ) {
                    AndroidView(
                        factory = { ctx ->
                            AcsSoraEditorFactory.create(ctx).also { handle ->
                                soraHandle = handle
                            }.editor
                        },
                        update = { editor ->
                            val handle = soraHandle?.takeIf { it.editor === editor } ?: AcsSoraEditorHandle(editor).also { soraHandle = it }
                            handle.bind(
                                text = activeFile.content,
                                languageHint = activeFile.languageHint,
                                fontSizeSp = editorConfig.fontSizeSp.toFloat(),
                                wordWrap = editorConfig.wordWrap,
                                theme = acsTheme,
                                onTextChanged = { updated -> currentOnContentChange(updated) }
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (useVscodroidAarNow) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("vscode_vscodroid_aar_editor")
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VscodroidWorkbenchFactory.createWorkbench(
                                context = ctx,
                                initialContent = activeFile.content,
                                initialOptions = VscodroidEditorOptions(
                                    filePath = activeFile.path,
                                    fontSizePx = editorConfig.fontSizeSp + 1,
                                    tabSpaces = editorConfig.tabSpaces,
                                    wordWrap = editorConfig.wordWrap
                                ),
                                onTextChanged = { updated -> currentOnContentChange(updated) },
                                onRunCommand = { cmdId ->
                                    if (cmdId == "workbench.action.runActive") currentOnRunActiveScript()
                                    else currentOnRunCommand(cmdId)
                                }
                            ).also { handle ->
                                vscodroidHandle = handle
                            }.webView
                        },
                        update = { _ ->
                            val opts = VscodroidEditorOptions(
                                filePath = activeFile.path,
                                fontSizePx = editorConfig.fontSizeSp + 1,
                                tabSpaces = editorConfig.tabSpaces,
                                wordWrap = editorConfig.wordWrap
                            )
                            vscodroidHandle?.updateCallback(
                                onTextChanged = { updated -> currentOnContentChange(updated) },
                                onRunCommand = { cmdId ->
                                    if (cmdId == "workbench.action.runActive") currentOnRunActiveScript()
                                    else currentOnRunCommand(cmdId)
                                }
                            )
                            vscodroidHandle?.syncState(activeFile.content, opts)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                val wrapScrollModifier = if (!editorConfig.wordWrap) {
                    Modifier.horizontalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(editorScroll)
                        .then(wrapScrollModifier)
                        .padding(6.dp)
                ) {
                    val syntaxVisual = remember(theme, activeFile.languageHint) {
                        VsCodeEditorFeaturesEngine.buildSyntaxVisualTransformation(theme, activeFile.languageHint)
                    }
                    val linesCount = activeFile.content.count { it == '\n' } + 1
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .width(34.dp)
                                .background(Color(theme.gutterBgHex))
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (ln in 1..linesCount) {
                                Text(
                                    ln.toString(),
                                    color = TextMuted,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = activeFile.content,
                            onValueChange = onContentChange,
                            visualTransformation = syntaxVisual,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(Color(theme.accentHex)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 90.dp)
                                .padding(vertical = 4.dp)
                                .testTag("vscode_code_editor")
                        )
                    }
                }
            }

            if (splitFile != null) {
                VerticalDivider(color = SlateBorder)
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .background(Color(theme.gutterBgHex).copy(alpha = 0.6f))
                        .verticalScroll(rememberScrollState())
                        .padding(6.dp)
                        .testTag("vscode_split_right_pane")
                ) {
                    Text(
                        "SPLIT · ${splitFile.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = VsCodeEditorFeaturesEngine.highlightCode(splitFile.content, theme, splitFile.languageHint),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Bottom Terminal / Problems / Output / Logcat Panel
        if (bottomPanelOpen) {
            HorizontalDivider(color = SlateBorder)
            Column(
                modifier = Modifier
                    .height(112.dp)
                    .fillMaxWidth()
                    .background(ObsidianBg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(SlateSurface).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        items(IdeBottomPanelTab.entries) { tab ->
                            val selected = activeBottomTab == tab
                            val badge = if (tab == IdeBottomPanelTab.PROBLEMS && diagnostics.isNotEmpty()) " (${diagnostics.size})" else ""
                            Text(
                                "${tab.label}$badge",
                                color = if (selected) CyberCyan else TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clickable { onSelectBottomTab(tab) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("bottom_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                    IconButton(onClick = onToggleBottomPanel, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse bottom panel", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }

                when (activeBottomTab) {
                    IdeBottomPanelTab.TERMINAL -> {
                        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            items(terminalLogs) { line ->
                                Text(line, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().background(SlateSurface).padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$ ", color = EmeraldRun, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            BasicTextField(
                                value = terminalInput,
                                onValueChange = { terminalInput = it },
                                textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(CyberCyan),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("ide_terminal_input")
                            )
                            IconButton(
                                onClick = {
                                    if (terminalInput.isNotBlank()) {
                                        onExecuteTerminal(terminalInput)
                                        terminalInput = ""
                                    }
                                },
                                modifier = Modifier.size(32.dp).testTag("ide_terminal_send")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run command", tint = CyberCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    IdeBottomPanelTab.PROBLEMS -> {
                        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (diagnostics.isEmpty()) {
                                item { Text("0 errors, 0 warnings across workspace.", color = EmeraldRun, fontSize = 11.sp) }
                            } else {
                                items(diagnostics) { d ->
                                    val c = if (d.severity == "ERROR") RoseError else AmberWarn
                                    Text("${d.filePath}:${d.line} [${d.severity}] ${d.message}", color = c, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    IdeBottomPanelTab.OUTPUT -> {
                        Text(lastTestResult, color = EmeraldRun, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                    }

                    IdeBottomPanelTab.LOGCAT -> {
                        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp)) {
                            items(logcatLogs) { line ->
                                Text(line, color = CyberCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        VsCodeBottomStatusBar(
            activePath = activeTabPath,
            lineCount = activeFile?.content?.let { it.count { c -> c == '\n' } + 1 } ?: 0,
            charCount = activeFile?.content?.length ?: 0,
            errorCount = diagnostics.count { it.severity == "ERROR" },
            warningCount = diagnostics.count { it.severity != "ERROR" },
            theme = theme,
            tabSpaces = editorConfig.tabSpaces
        )
    }
}
