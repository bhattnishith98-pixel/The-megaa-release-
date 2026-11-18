package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EditorViewMode
import com.example.StudioViewModel
import com.example.lang.DiagnosticSeverity
import com.example.lang.StudioCapability
import com.example.lang.StudioHighlighter
import com.example.nodes.*
import com.example.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun NodeAndCodeStudioScreen(vm: StudioViewModel) {
    val context = LocalContext.current
    val mode by vm.editorMode.collectAsStateWithLifecycle()
    val source by vm.sourceCode.collectAsStateWithLifecycle()
    val graph by vm.nodeGraph.collectAsStateWithLifecycle()
    val compileOut by vm.compileOutput.collectAsStateWithLifecycle()
    val vmRunning by vm.vmRunning.collectAsStateWithLifecycle()
    val vmPaused by vm.vmPaused.collectAsStateWithLifecycle()
    val vmLogs by vm.vmLogs.collectAsStateWithLifecycle()
    val watchLocals by vm.watchLocals.collectAsStateWithLifecycle()
    val grantedCaps by vm.capabilityManager.grantedCapabilities.collectAsStateWithLifecycle()
    val editingMacroName by vm.editingMacroName.collectAsStateWithLifecycle()
    val isCompiling by vm.isCompiling.collectAsStateWithLifecycle()
    val showQuickStart by vm.showNodeQuickStart.collectAsStateWithLifecycle()
    val binaryTarget by vm.binaryTarget.collectAsStateWithLifecycle()
    val selfHostResult by vm.selfHostResult.collectAsStateWithLifecycle()
    val availableLibraries by vm.availableLibraries.collectAsStateWithLifecycle()

    var showPalette by remember { mutableStateOf(false) }
    var paletteInitialCategory by remember { mutableStateOf<NodeCategory?>(NodeCategory.TRIGGERS) }
    var inspectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var accessibleListMode by remember { mutableStateOf(false) }
    var showCustomCreator by remember { mutableStateOf(false) }
    var showMakeBlockDialog by remember { mutableStateOf(false) }
    var showBinaryHexDialog by remember { mutableStateOf(false) }
    var showLibraryDialog by remember { mutableStateOf(false) }
    var reviewedPackage by remember { mutableStateOf<StlPackageManifest?>(null) }
    var pendingConnectFrom by remember { mutableStateOf<Pair<String, String>?>(null) } // nodeId, portId

    val pkgImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                reviewedPackage = vm.inspectPackageBytes(bytes)
            }.onFailure {
                vm.setBanner("Package rejected: ${it.message}")
            }
        }
    }

    val issues = remember(graph, grantedCaps) { NodeRegistry.validateGraph(graph, grantedCaps) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Top Studio Toolbar
        Surface(color = SlateSurface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorViewMode.entries.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { vm.setEditorMode(m) },
                                label = { Text(m.label) },
                                modifier = Modifier.testTag("mode_${m.name.lowercase()}")
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.undoGraphOrCode() }, modifier = Modifier.testTag("undo_button")) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = TextPrimary)
                        }
                        IconButton(onClick = { vm.redoGraphOrCode() }, modifier = Modifier.testTag("redo_button")) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = TextPrimary)
                        }
                        if (!vmRunning) {
                            FilledIconButton(
                                onClick = { vm.runCurrent(stepByStep = false) },
                                modifier = Modifier.testTag("run_vm_button"),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Run program")
                            }
                        } else {
                            IconButton(onClick = { vm.togglePauseVm() }) {
                                Icon(if (vmPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = "Pause or resume", tint = AmberWarn)
                            }
                            IconButton(onClick = { vm.stepNext() }) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "Step next", tint = CyberCyan)
                            }
                            IconButton(onClick = { vm.stopVm() }, modifier = Modifier.testTag("stop_vm_button")) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop execution", tint = RoseError)
                            }
                        }
                    }
                }

                editingMacroName?.let { macroTitle ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Editing Macro: $macroTitle",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberCyan,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        FilledTonalButton(
                            onClick = { vm.saveCurrentToMacro() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 36.dp).testTag("save_macro_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldRun)
                            Spacer(Modifier.width(4.dp))
                            Text("Save Macro")
                        }
                    }
                }

                // Secondary action strip: Add Node, Format, List Mode, Custom, Package
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        AssistChip(
                            onClick = {
                                paletteInitialCategory = NodeCategory.TRIGGERS
                                showPalette = true
                            },
                            label = { Text("+ Node (1600+)") },
                            leadingIcon = { Icon(Icons.Filled.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("add_node_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                paletteInitialCategory = NodeCategory.TRIGGERS
                                showPalette = true
                            },
                            label = { Text("Triggers (500)", color = Color(NodeCategory.TRIGGERS.accentHex)) },
                            modifier = Modifier.testTag("add_trigger_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                paletteInitialCategory = NodeCategory.ACTIONS
                                showPalette = true
                            },
                            label = { Text("Actions (400)", color = Color(NodeCategory.ACTIONS.accentHex)) },
                            modifier = Modifier.testTag("add_action_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                paletteInitialCategory = NodeCategory.CONSTRAINTS
                                showPalette = true
                            },
                            label = { Text("Constraints (200)", color = Color(NodeCategory.CONSTRAINTS.accentHex)) },
                            modifier = Modifier.testTag("add_constraint_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                paletteInitialCategory = NodeCategory.CONDITIONS
                                showPalette = true
                            },
                            label = { Text("Conditions (500)", color = Color(NodeCategory.CONDITIONS.accentHex)) },
                            modifier = Modifier.testTag("add_condition_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { vm.formatCode() },
                            label = { Text("Format") },
                            leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { vm.runCurrent(stepByStep = true) },
                            label = { Text("Debug Step") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { accessibleListMode = !accessibleListMode },
                            label = { Text(if (accessibleListMode) "Canvas View" else "List View") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showCustomCreator = true },
                            label = { Text("+ Trigger/Action/Block") },
                            modifier = Modifier.testTag("add_custom_block_chip")
                        )
                    }
                    if (editingMacroName == null) {
                        item {
                            AssistChip(
                                onClick = { vm.saveCurrentToMacro() },
                                label = { Text("Save as Macro") },
                                leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldRun) },
                                modifier = Modifier.testTag("save_macro_button")
                            )
                        }
                    }
                    item {
                        AssistChip(
                            onClick = { vm.setShowNodeQuickStart(!showQuickStart) },
                            label = { Text(if (showQuickStart) "Hide Guide" else "? Guide") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan) },
                            modifier = Modifier.testTag("toggle_quickstart_chip")
                        )
                    }
                    if (selectedNodeIds.isNotEmpty()) {
                        item {
                            AssistChip(
                                onClick = { showMakeBlockDialog = true },
                                label = { Text("Make Block (${selectedNodeIds.size})") }
                            )
                        }
                    }
                    if (selectedNodeIds.size >= 2) {
                        item {
                            AssistChip(
                                onClick = { vm.autoWireSelectedNodes(selectedNodeIds) },
                                label = { Text("Auto-Wire (${selectedNodeIds.size})") },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan) },
                                modifier = Modifier.testTag("auto_wire_nodes_chip")
                            )
                        }
                    }
                    if (pendingConnectFrom != null) {
                        item {
                            AssistChip(
                                onClick = { pendingConnectFrom = null },
                                label = { Text("Cancel Wire") },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = RoseError) }
                            )
                        }
                    }
                    item {
                        AssistChip(
                            onClick = {
                                val path = vm.exportCustomPackageToWorkspace()
                                vm.setBanner("Exported .stlpkg to workspace/$path")
                            },
                            label = { Text("Export .stlpkg") }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                runCatching { pkgImportLauncher.launch(arrayOf("*/*")) }.onFailure { err ->
                                    vm.setBanner("File picker unavailable: ${err.localizedMessage ?: "No handler"}")
                                }
                            },
                            label = { Text("Import .stlpkg") }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showBinaryHexDialog = true },
                            label = { Text("Binary / Hex") },
                            leadingIcon = { Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonViolet) },
                            modifier = Modifier.testTag("open_binary_hex_chip")
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showLibraryDialog = true },
                            label = { Text("#include .stl.lib") },
                            leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan) },
                            modifier = Modifier.testTag("open_lib_manager_chip")
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = showQuickStart && mode != EditorViewMode.TEXT) {
            NodeQuickStartBanner(
                onDismiss = { vm.setShowNodeQuickStart(false) },
                onCreateBlock = { showCustomCreator = true }
            )
        }

        // Main Workspace Area (Node / Text / Split)
        Box(modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 160.dp)) {
            when (mode) {
                EditorViewMode.NODE -> {
                    if (accessibleListMode) {
                        AccessibleNodeList(
                            graph = graph,
                            issues = issues,
                            onSelectNode = { inspectedNodeId = it },
                            onDeleteNode = { nid ->
                                vm.updateGraph(graph.copy(nodes = graph.nodes.filterNot { it.id == nid }, wires = graph.wires.filterNot { it.fromNodeId == nid || it.toNodeId == nid }))
                            }
                        )
                    } else {
                        NodeCanvasView(
                            graph = graph,
                            issues = issues,
                            selectedNodeIds = selectedNodeIds,
                            pendingConnectFrom = pendingConnectFrom,
                            onLongPressCanvas = { showPalette = true },
                            onMoveNode = { nodeId, dx, dy ->
                                vm.updateGraph(
                                    graph.copy(nodes = graph.nodes.map {
                                        if (it.id == nodeId) it.copy(x = ((it.x + dx) / 10f).roundToInt() * 10f, y = ((it.y + dy) / 10f).roundToInt() * 10f) else it
                                    }),
                                    syncToSource = false
                                )
                            },
                            onTapNode = { nid ->
                                inspectedNodeId = nid
                                selectedNodeIds = if (nid in selectedNodeIds) selectedNodeIds - nid else selectedNodeIds + nid
                            },
                            onTapPort = { nodeId, portId, isOutput ->
                                val pending = pendingConnectFrom
                                if (isOutput) {
                                    pendingConnectFrom = nodeId to portId
                                    vm.setBanner("Tap an input port to connect wire from $portId.")
                                } else if (pending != null) {
                                    val check = NodeRegistry.validateConnection(graph, pending.first, pending.second, nodeId, portId)
                                    if (check.allowed) {
                                        val w = GraphWire("w_${System.currentTimeMillis()}", pending.first, pending.second, nodeId, portId, check.autoConvert)
                                        vm.updateGraph(graph.copy(wires = graph.wires + w))
                                        if (check.autoConvert) vm.setBanner(check.reason ?: "Auto-converted wire")
                                    } else {
                                        vm.setBanner(check.reason ?: "Invalid connection")
                                    }
                                    pendingConnectFrom = null
                                }
                            }
                        )
                    }
                }
                EditorViewMode.TEXT -> {
                    CodeEditorPane(
                        source = source,
                        diagnostics = compileOut.diagnostics,
                        onSourceChange = { vm.onEditorTextChanged(it) }
                    )
                }
                EditorViewMode.SPLIT -> {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            NodeCanvasView(
                                graph = graph,
                                issues = issues,
                                selectedNodeIds = selectedNodeIds,
                                pendingConnectFrom = pendingConnectFrom,
                                onLongPressCanvas = { showPalette = true },
                                onMoveNode = { nodeId, dx, dy ->
                                    vm.updateGraph(graph.copy(nodes = graph.nodes.map {
                                        if (it.id == nodeId) it.copy(x = it.x + dx, y = it.y + dy) else it
                                    }), syncToSource = false)
                                },
                                onTapNode = { inspectedNodeId = it },
                                onTapPort = { nodeId, portId, isOutput ->
                                    if (isOutput) pendingConnectFrom = nodeId to portId
                                    else pendingConnectFrom?.let { p ->
                                        val check = NodeRegistry.validateConnection(graph, p.first, p.second, nodeId, portId)
                                        if (check.allowed) {
                                            vm.updateGraph(graph.copy(wires = graph.wires + GraphWire("w_${System.currentTimeMillis()}", p.first, p.second, nodeId, portId, check.autoConvert)))
                                        } else vm.setBanner(check.reason ?: "Invalid wire")
                                        pendingConnectFrom = null
                                    }
                                }
                            )
                        }
                        VerticalDivider(color = SlateBorder)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            CodeEditorPane(
                                source = source,
                                diagnostics = compileOut.diagnostics,
                                onSourceChange = { vm.onEditorTextChanged(it) }
                            )
                        }
                    }
                }
            }
        }

        // Bottom Console & Diagnostics Strip
        Surface(color = SlateSurface, modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder)) {
            Column(Modifier.padding(10.dp).heightIn(max = 138.dp).verticalScroll(rememberScrollState())) {
                val errCount = compileOut.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        when {
                            isCompiling -> "Compiling & syncing graph..."
                            errCount == 0 -> "Verified Bytecode (${compileOut.bytecode?.instructions?.size ?: 0} ops · ${compileOut.compileTimeUs} us)"
                            else -> "$errCount Compiler Issue(s)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isCompiling -> AmberWarn
                            errCount == 0 -> EmeraldRun
                            else -> RoseError
                        }
                    )
                    if (watchLocals.isNotEmpty()) {
                        Text("Watch: ${watchLocals.entries.joinToString { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                    }
                }
                compileOut.diagnostics.take(3).forEach { d ->
                    Text(d.format(), style = MaterialTheme.typography.labelSmall, color = RoseError)
                }
                vmLogs.takeLast(5).forEach { l ->
                    val col = when (l.level) {
                        "ERROR" -> RoseError
                        "WARN" -> AmberWarn
                        else -> TextPrimary
                    }
                    Text("[${l.level}] ${l.message}", style = MaterialTheme.typography.labelSmall, color = col)
                }
            }
        }
    }

    // Categorized 1,600+ Node Catalog Dialog (Triggers / Actions / Constraints / Conditions)
    if (showPalette) {
        AutomationCatalogDialog(
            recentIds = vm.recentNodeIds.collectAsStateWithLifecycle().value,
            favouriteIds = vm.favouriteNodeIds.collectAsStateWithLifecycle().value,
            initialCategory = paletteInitialCategory,
            onToggleFav = { vm.toggleFavouriteNode(it) },
            onSelectSpec = { spec ->
                vm.recordNodeUsed(spec.id)
                val newNode = GraphNode(
                    id = "n_${System.currentTimeMillis() % 100000}",
                    typeId = spec.id,
                    label = spec.title,
                    x = 100f + (graph.nodes.size % 4) * 240f,
                    y = 120f + (graph.nodes.size / 4) * 160f,
                    params = spec.params.associate { it.key to it.defaultValue }
                )
                vm.updateGraph(graph.copy(nodes = graph.nodes + newNode))
                showPalette = false
            },
            onDismiss = { showPalette = false }
        )
    }

    // Node Inspector Dialog
    inspectedNodeId?.let { nid ->
        val node = graph.nodes.firstOrNull { it.id == nid }
        val spec = node?.let { NodeRegistry.getSpec(it.typeId) }
        if (node != null && spec != null) {
            val editedParams = remember(node.id) { mutableStateMapOf<String, String>().apply { putAll(node.params) } }
            AlertDialog(
                onDismissRequest = { inspectedNodeId = null },
                title = { Text("${spec.title} (${node.id})") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(spec.summary, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        if (spec.requiredCapabilities.isNotEmpty()) {
                            Text("Needs: ${spec.requiredCapabilities.joinToString { it.id }} · API ${spec.minApiLevel}+", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                        }
                        spec.params.forEach { p ->
                            OutlinedTextField(
                                value = editedParams[p.key] ?: p.defaultValue,
                                onValueChange = { editedParams[p.key] = it },
                                label = { Text("${p.label} (${p.type.label})") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        vm.updateGraph(graph.copy(nodes = graph.nodes.map {
                            if (it.id == node.id) it.copy(params = editedParams.toMap()) else it
                        }))
                        inspectedNodeId = null
                    }) { Text("Apply") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            vm.updateGraph(graph.copy(
                                nodes = graph.nodes.filterNot { it.id == node.id },
                                wires = graph.wires.filterNot { it.fromNodeId == node.id || it.toNodeId == node.id }
                            ))
                            inspectedNodeId = null
                        }) { Text("Delete", color = RoseError) }
                        TextButton(onClick = { inspectedNodeId = null }) { Text("Close") }
                    }
                }
            )
        }
    }

    // Polyglot Custom Trigger / Action / Block Creator
    if (showCustomCreator) {
        var kind by remember { mutableStateOf(CustomDefinitionKind.BLOCK) }
        var language by remember { mutableStateOf(BlockLanguage.STUDIOLANG) }
        var name by remember { mutableStateOf("ClampPercent") }
        var summary by remember { mutableStateOf("Clamps a numeric value between 0 and 100") }
        var paramName by remember { mutableStateOf("x") }
        var body by remember { mutableStateOf("return math.clamp(x, 0, 100)") }
        var testOutput by remember { mutableStateOf<PolyglotTestResult?>(null) }

        AlertDialog(
            onDismissRequest = { showCustomCreator = false },
            title = { Text("Create Polyglot Trigger / Block") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create triggers, actions, or blocks in StudioLang, JSON, JavaScript, HTML5, Kotlin, Python, or C++.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CustomDefinitionKind.entries) { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = {
                                    kind = k
                                    body = PolyglotEngine.starterTemplate(k, language, name)
                                    testOutput = null
                                },
                                label = { Text(k.name) }
                            )
                        }
                    }
                    Text("Language", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(BlockLanguage.entries) { lang ->
                            FilterChip(
                                selected = language == lang,
                                onClick = {
                                    language = lang
                                    body = PolyglotEngine.starterTemplate(kind, lang, name)
                                    testOutput = null
                                },
                                label = { Text(lang.label) },
                                modifier = Modifier.testTag("creator_lang_${lang.id}")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("creator_name_input")
                    )
                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = { Text("What it does") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (kind != CustomDefinitionKind.TRIGGER && language == BlockLanguage.STUDIOLANG) {
                        OutlinedTextField(value = paramName, onValueChange = { paramName = it }, label = { Text("Parameter") }, singleLine = true)
                    }
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("${language.label} Source (${language.extension})") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("creator_code_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                testOutput = PolyglotEngine.evaluateOrValidate(language, body)
                            },
                            modifier = Modifier.testTag("creator_test_button")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test Syntax / Rule")
                        }
                        Text(language.runtimeMode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    testOutput?.let { res ->
                        Text(
                            res.outputSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (res.valid) EmeraldRun else RoseError,
                            modifier = Modifier.testTag("creator_test_output")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.registerCustomDefinition(kind, name, summary, paramName, emptySet(), body, language)
                        showCustomCreator = false
                    },
                    modifier = Modifier.testTag("creator_save_button")
                ) { Text("Save to Palette") }
            },
            dismissButton = { TextButton(onClick = { showCustomCreator = false }) { Text("Cancel") } }
        )
    }

    if (showMakeBlockDialog) {
        var blockName by remember { mutableStateOf("SubWorkflow") }
        AlertDialog(
            onDismissRequest = { showMakeBlockDialog = false },
            title = { Text("Convert Selection to Action Block") },
            text = { OutlinedTextField(value = blockName, onValueChange = { blockName = it }, label = { Text("Action Block Name") }) },
            confirmButton = {
                Button(onClick = {
                    vm.makeBlockFromSelection(selectedNodeIds, blockName)
                    selectedNodeIds = emptySet()
                    showMakeBlockDialog = false
                }) { Text("Create Block") }
            },
            dismissButton = { TextButton(onClick = { showMakeBlockDialog = false }) { Text("Cancel") } }
        )
    }

    reviewedPackage?.let { pkg ->
        AlertDialog(
            onDismissRequest = { reviewedPackage = null },
            title = { Text("Capability Review: ${pkg.packageName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Version ${pkg.version} by ${pkg.author}", color = TextPrimary)
                    Text("Signed: ${pkg.isSigned} (SHA-256: ${pkg.sha256Checksum.take(16)}...)", style = MaterialTheme.typography.labelSmall, color = if (pkg.isSigned) EmeraldRun else AmberWarn)
                    Text("Contains ${pkg.definitions.size} custom node(s).", color = TextSecondary)
                    Text("Required Capabilities (never auto-granted): ${pkg.requiredCapabilities.joinToString { it.id }.ifBlank { "None" }}", color = RoseError)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.importReviewedPackage(pkg)
                    reviewedPackage = null
                }) { Text("Import Definitions") }
            },
            dismissButton = { TextButton(onClick = { reviewedPackage = null }) { Text("Cancel") } }
        )
    }

    if (showBinaryHexDialog) {
        BinaryHexSelfHostDialog(
            binaryTarget = binaryTarget,
            compileOutput = compileOut,
            selfHostResult = selfHostResult,
            onSelectTarget = { vm.selectBinaryTarget(it) },
            onRunSelfHost = { vm.runSelfHostedBootstrap() },
            onExportBinary = { vm.exportBinaryToDownloads() },
            onDismiss = { showBinaryHexDialog = false }
        )
    }

    if (showLibraryDialog) {
        LibraryManagerDialog(
            vm = vm,
            availableLibraries = availableLibraries,
            onDismiss = { showLibraryDialog = false }
        )
    }
}

@Composable
private fun NodeCanvasView(
    graph: NodeGraph,
    issues: List<NodeValidationIssue>,
    selectedNodeIds: Set<String>,
    pendingConnectFrom: Pair<String, String>?,
    onLongPressCanvas: () -> Unit,
    onMoveNode: (String, Float, Float) -> Unit,
    onTapNode: (String) -> Unit,
    onTapPort: (String, String, Boolean) -> Unit
) {
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewportSize by remember { mutableStateOf(IntSize(1080, 1200)) }
    val density = LocalDensity.current

    // Viewport culling for 1,000+ node scalability
    val visibleNodes = remember(graph.nodes, pan, zoom, viewportSize) {
        val margin = 320f
        graph.nodes.filter { n ->
            val sx = n.x * zoom + pan.x
            val sy = n.y * zoom + pan.y
            sx >= -margin && sy >= -margin && sx <= viewportSize.width + margin && sy <= viewportSize.height + margin
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.45f, 2.0f)
                    pan += panChange
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPressCanvas() })
            }
    ) {
        // Grid & Bezier Wires Layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridStep = 48f * zoom
            if (gridStep >= 16f) {
                var x = pan.x % gridStep
                while (x < size.width) {
                    drawLine(SlateBorder.copy(alpha = 0.28f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += gridStep
                }
                var y = pan.y % gridStep
                while (y < size.height) {
                    drawLine(SlateBorder.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += gridStep
                }
            }

            // Draw wires between visible nodes
            val nodeMap = graph.nodes.associateBy { it.id }
            graph.wires.forEach { w ->
                val fromNode = nodeMap[w.fromNodeId] ?: return@forEach
                val toNode = nodeMap[w.toNodeId] ?: return@forEach
                val start = Offset((fromNode.x + 195f) * zoom + pan.x, (fromNode.y + 52f) * zoom + pan.y)
                val end = Offset(toNode.x * zoom + pan.x, (toNode.y + 52f) * zoom + pan.y)
                val spec = NodeRegistry.getSpec(fromNode.typeId)
                val port = spec?.outputs?.firstOrNull { it.id == w.fromPortId }
                val wireColor = Color(port?.type?.colorHex ?: 0xFFF8FAFC)
                val ctrlOffset = ((end.x - start.x).coerceAtLeast(60f)) * 0.5f
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(start.x + ctrlOffset, start.y, end.x - ctrlOffset, end.y, end.x, end.y)
                }
                drawPath(path, color = wireColor.copy(alpha = 0.85f), style = Stroke(width = 3.2f * zoom))
            }
        }

        // Virtualized Node Cards
        visibleNodes.forEach { node ->
            val spec = NodeRegistry.getSpec(node.typeId)
            val nodeIssues = issues.filter { it.nodeId == node.id }
            val accent = Color(spec?.category?.accentHex ?: 0xFF22D3EE)
            val isSelected = node.id in selectedNodeIds
            val screenX = (node.x * zoom + pan.x).roundToInt()
            val screenY = (node.y * zoom + pan.y).roundToInt()

            Surface(
                color = SlateCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .offset { IntOffset(screenX, screenY) }
                    .width((200f * zoom).dp.coerceAtLeast(150.dp))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) CyberCyan else accent.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .pointerInput(node.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onMoveNode(node.id, dragAmount.x / zoom, dragAmount.y / zoom)
                        }
                    }
                    .clickable { onTapNode(node.id) }
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = accent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                (spec?.category?.label ?: "Node").uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (nodeIssues.isNotEmpty()) {
                            val badgeCol = when (nodeIssues.first().severity) {
                                NodeValidationIssue.IssueSeverity.ERROR -> RoseError
                                NodeValidationIssue.IssueSeverity.WARNING -> AmberWarn
                                NodeValidationIssue.IssueSeverity.CAPABILITY -> CyberCyan
                            }
                            Box(Modifier.size(10.dp).clip(CircleShape).background(badgeCol))
                        }
                    }
                    Text(
                        node.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    val previewHint = node.params.values.firstOrNull { it.isNotBlank() } ?: spec?.summary.orEmpty()
                    if (previewHint.isNotBlank()) {
                        Text(
                            previewHint,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    // Compact ports row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            spec?.inputs?.forEach { p ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onTapPort(node.id, p.id, false) }.padding(2.dp)
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(p.type.colorHex)))
                                    Spacer(Modifier.width(4.dp))
                                    Text(p.name, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            spec?.outputs?.forEach { p ->
                                val activeOut = pendingConnectFrom == (node.id to p.id)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onTapPort(node.id, p.id, true) }.padding(2.dp)
                                ) {
                                    Text(p.name, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontSize = 10.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Box(
                                        Modifier
                                            .size(if (activeOut) 13.dp else 10.dp)
                                            .clip(CircleShape)
                                            .background(Color(p.type.colorHex))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fit-to-screen & Zoom HUD
        Surface(
            color = SlateSurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoom = (zoom - 0.15f).coerceAtLeast(0.45f) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
                Text("${graph.nodes.size} nodes · ${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                IconButton(onClick = { zoom = (zoom + 0.15f).coerceAtMost(2.0f) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { pan = Offset.Zero; zoom = 1f }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.FitScreen, contentDescription = "Fit canvas to screen", tint = CyberCyan, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AccessibleNodeList(
    graph: NodeGraph,
    issues: List<NodeValidationIssue>,
    onSelectNode: (String) -> Unit,
    onDeleteNode: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(graph.nodes, key = { it.id }) { node ->
            val spec = NodeRegistry.getSpec(node.typeId)
            val nodeIssue = issues.firstOrNull { it.nodeId == node.id }
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp)).clickable { onSelectNode(node.id) }
            ) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${node.label} (${node.typeId})", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                        Text(spec?.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        if (nodeIssue != null) {
                            Text(nodeIssue.message, style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                        }
                    }
                    IconButton(onClick = { onDeleteNode(node.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete node ${node.label}", tint = RoseError)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeQuickStartBanner(
    onDismiss: () -> Unit,
    onCreateBlock: () -> Unit
) {
    Surface(
        color = SlateCard,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .testTag("node_quickstart_card")
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("What Nodes & Action Blocks Do", style = MaterialTheme.typography.titleSmall, color = CyberCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCreateBlock, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("+ New Block", color = NeonViolet)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Hide guide", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text(
                "1) Trigger (Cyan) starts the workflow -> 2) Flow/Data (Violet/Amber) branches or holds values -> 3) Action (Emerald) runs tasks -> 4) Action Block (Teal) saves reusable steps for Macros & Custom Apps.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PortLegendDot("Flow", Color(PortType.FLOW.colorHex)) }
                item { PortLegendDot("Number", Color(PortType.NUMBER.colorHex)) }
                item { PortLegendDot("Text", Color(PortType.TEXT.colorHex)) }
                item { PortLegendDot("Bool", Color(PortType.BOOL.colorHex)) }
                item { PortLegendDot("List", Color(PortType.LIST.colorHex)) }
            }
        }
    }
}

@Composable
private fun PortLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

private class StudioSyntaxVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(StudioHighlighter.highlight(text.text), OffsetMapping.Identity)
    }
}

@Composable
private fun CodeEditorPane(
    source: String,
    diagnostics: List<com.example.lang.StudioDiagnostic>,
    onSourceChange: (String) -> Unit
) {
    val snippets = remember {
        listOf("let ", "macro ", "trigger ", "action ", "block ", "if ", "repeat 3 times:", "retry 3 times, wait 1s:", "confirm \"Sure?\"", "log.info(\"\")")
    }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(source)) }
    LaunchedEffect(source) {
        if (source != textFieldValue.text) {
            val safeSel = textFieldValue.selection
            val clampedEnd = safeSel.end.coerceIn(0, source.length)
            textFieldValue = TextFieldValue(
                text = source,
                selection = androidx.compose.ui.text.TextRange(clampedEnd)
            )
        }
    }
    val visualTransform = remember { StudioSyntaxVisualTransformation() }

    Column(Modifier.fillMaxSize().background(ObsidianBg)) {
        // Autocomplete snippet bar
        LazyRow(
            Modifier.fillMaxWidth().background(SlateSurface).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(snippets) { sn ->
                SuggestionChip(
                    onClick = {
                        val updated = textFieldValue.text + (if (textFieldValue.text.endsWith("\n") || textFieldValue.text.isEmpty()) "" else "\n") + sn
                        textFieldValue = TextFieldValue(updated, androidx.compose.ui.text.TextRange(updated.length))
                        onSourceChange(updated)
                    },
                    label = { Text(sn.trim(), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        val lines = remember(textFieldValue.text) { textFieldValue.text.lines() }
        val errorLines = remember(diagnostics) { diagnostics.map { it.span.line }.toSet() }
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()

        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll).padding(10.dp)) {
            // Line numbers gutter aligned 1:1 with non-wrapping code lines
            Column(Modifier.padding(end = 10.dp)) {
                lines.indices.forEach { idx ->
                    val hasErr = (idx + 1) in errorLines
                    Text(
                        "${idx + 1}",
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = if (hasErr) RoseError else TextMuted
                        )
                    )
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { next ->
                        textFieldValue = next
                        onSourceChange(next.text)
                    },
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary
                    ),
                    visualTransformation = visualTransform,
                    cursorBrush = SolidColor(CyberCyan),
                    modifier = Modifier.widthIn(min = 520.dp).testTag("studio_code_editor")
                )
            }
        }
    }
}


