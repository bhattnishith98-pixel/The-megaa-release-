package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appbuilder.AppBuildLanguage
import com.example.appbuilder.AppDesign
import com.example.ide.*
import com.example.ui.theme.*
import com.tom.rv2ide.studio.AcsAarRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun GodotAndAndroidRightPane(
    ide: UnifiedIdeController,
    appDesign: AppDesign,
    onAttachScriptClick: (GodotSceneNode) -> Unit,
    onExportAndroidZip: () -> Unit,
    onSelectAppBuildLanguage: (AppBuildLanguage) -> Unit = { lang -> ide.switchAndroidBuildLanguage(lang, appDesign) },
    modifier: Modifier = Modifier
) {
    val workspaceMode by ide.workspaceMode.collectAsState()
    val rightTab by ide.activeRightTab.collectAsState()
    val nodes by ide.sceneNodes.collectAsState()
    val selectedNodeId by ide.selectedNodeId.collectAsState()
    val isPlaying by ide.isScenePlaying.collectAsState()
    val fps by ide.sceneFps.collectAsState()
    val collisionStatus by ide.collisionStatus.collectAsState()
    val gridSnap by ide.gridSnapEnabled.collectAsState()
    val files by ide.files.collectAsState()
    val diagnostics by ide.diagnostics.collectAsState()
    val voiceLog by ide.voiceCommandsLog.collectAsState()
    val godotSignals by ide.godotSignalsLog.collectAsState()

    val selectedNode = nodes.firstOrNull { it.id == selectedNodeId }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(SlateSurface)
            .border(1.dp, SlateBorder)
    ) {
        if (workspaceMode == IdeWorkspaceMode.ANDROID_CODE_STUDIO) {
            AndroidCodeStudioLivePane(
                ide = ide,
                appDesign = appDesign,
                files = files,
                diagnostics = diagnostics,
                onExportZip = onExportAndroidZip,
                onSelectBuildLanguage = onSelectAppBuildLanguage,
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        // Godot 2D Transport Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { if (isPlaying) ide.pauseScene() else ide.playScene() },
                    modifier = Modifier.size(36.dp).testTag("godot_play_pause_btn")
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause scene" else "Play scene",
                        tint = EmeraldRun
                    )
                }
                IconButton(
                    onClick = { ide.stepSceneTick() },
                    modifier = Modifier.size(36.dp).testTag("godot_step_btn")
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Step tick", tint = CyberCyan)
                }
                IconButton(
                    onClick = { ide.stopScene() },
                    modifier = Modifier.size(36.dp).testTag("godot_stop_btn")
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop scene", tint = RoseError)
                }
                FilterChip(
                    selected = gridSnap,
                    onClick = { ide.toggleGridSnap() },
                    label = { Text("Snap", style = MaterialTheme.typography.labelSmall) }
                )
            }
            Text(
                if (isPlaying) "${fps}fps" else "Edit",
                style = MaterialTheme.typography.labelSmall,
                color = if (isPlaying) EmeraldRun else CyberCyan
            )
        }

        // Godot Interactive 2D Viewport Canvas
        val textMeasurer = rememberTextMeasurer()
        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth()
                .background(ObsidianBg)
                .border(1.dp, SlateBorder)
                .testTag("godot_2d_canvas")
        ) {
            var draggedId by remember { mutableStateOf<String?>(null) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(nodes) {
                        detectTapGestures { tap ->
                            val sx = size.width / 580f
                            val sy = size.height / 340f
                            val hit = nodes.lastOrNull { n ->
                                val nx = n.x * sx
                                val ny = n.y * sy
                                abs(tap.x - nx) <= (n.width * sx * 0.6f) && abs(tap.y - ny) <= (n.height * sy * 0.6f)
                            }
                            if (hit != null) {
                                ide.selectNode(hit.id)
                                if (isPlaying && hit.type == GodotNodeType.TOUCH_ZONE) {
                                    ide.triggerTouchZone(hit.id)
                                }
                            }
                        }
                    }
                    .pointerInput(nodes) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val sx = size.width / 580f
                                val sy = size.height / 340f
                                draggedId = nodes.lastOrNull { n ->
                                    abs(start.x - n.x * sx) <= (n.width * sx * 0.7f) &&
                                        abs(start.y - n.y * sy) <= (n.height * sy * 0.7f)
                                }?.id ?: selectedNodeId
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val id = draggedId ?: return@detectDragGestures
                                val cur = ide.sceneNodes.value.firstOrNull { it.id == id } ?: return@detectDragGestures
                                val sx = (size.width / 580f).coerceAtLeast(0.1f)
                                val sy = (size.height / 340f).coerceAtLeast(0.1f)
                                ide.dragNodeTo(id, cur.x + dragAmount.x / sx, cur.y + dragAmount.y / sy)
                            },
                            onDragEnd = { draggedId = null }
                        )
                    }
            ) {
                val sx = size.width / 580f
                val sy = size.height / 340f

                // Grid lines
                val stepX = 40f * sx
                var gx = 0f
                while (gx < size.width) {
                    drawLine(SlateBorder.copy(alpha = 0.35f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                    gx += stepX
                }
                val stepY = 40f * sy
                var gy = 0f
                while (gy < size.height) {
                    drawLine(SlateBorder.copy(alpha = 0.35f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                    gy += stepY
                }

                val camOffset = GodotEngineKotlin.RenderingServer2D.computeCameraOffset(nodes)

                // Draw Nodes (applying Camera2D viewport transform from RenderingServer2D)
                nodes.forEach { node ->
                    if (!node.visible) return@forEach
                    val cx = (node.x + camOffset.first) * sx
                    val cy = (node.y + camOffset.second) * sy
                    val w = (node.width * node.scale * sx).coerceAtLeast(18f)
                    val h = (node.height * node.scale * sy).coerceAtLeast(16f)
                    val topLeft = Offset(cx - w / 2f, cy - h / 2f)
                    val baseColor = Color(node.tintHex)

                    if (node.type == GodotNodeType.LABEL) {
                        val txt = node.labelText.ifBlank { node.name }
                        drawText(
                            textMeasurer = textMeasurer,
                            text = txt,
                            topLeft = Offset(topLeft.x, topLeft.y),
                            style = TextStyle(color = baseColor, fontSize = 11.sp)
                        )
                    } else {
                        drawRect(
                            color = baseColor.copy(alpha = if (node.type == GodotNodeType.COLLISION_SHAPE_2D) 0.28f else 0.72f),
                            topLeft = topLeft,
                            size = Size(w, h)
                        )
                        drawRect(
                            color = baseColor,
                            topLeft = topLeft,
                            size = Size(w, h),
                            style = Stroke(width = 2f)
                        )
                        drawText(
                            textMeasurer = textMeasurer,
                            text = node.labelText.ifBlank { node.name }.take(12),
                            topLeft = Offset(topLeft.x + 3f, topLeft.y + 3f),
                            style = TextStyle(color = TextPrimary, fontSize = 9.sp)
                        )
                    }

                    // Selection Transform Gizmo
                    if (node.id == selectedNodeId) {
                        drawRect(
                            color = CyberCyan,
                            topLeft = Offset(topLeft.x - 4f, topLeft.y - 4f),
                            size = Size(w + 8f, h + 8f),
                            style = Stroke(width = 2.5f)
                        )
                        drawLine(RoseError, Offset(cx, cy), Offset(cx + 26f, cy), strokeWidth = 3f)
                        drawLine(EmeraldRun, Offset(cx, cy), Offset(cx, cy - 26f), strokeWidth = 3f)
                    }
                }
            }

            Surface(
                color = ObsidianBg.copy(alpha = 0.8f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
            ) {
                Text(
                    collisionStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Live Godot InputMap Virtual Gamepad Overlay while playing
            if (isPlaying) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(ObsidianBg.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "◀",
                        color = CyberCyan,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable {
                                ide.godotInputMap.actionRelease("ui_right")
                                ide.godotInputMap.actionPress("ui_left")
                                ide.stepSceneTick()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("godot_pad_left")
                    )
                    Text(
                        "▲",
                        color = EmeraldRun,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable {
                                ide.godotInputMap.actionPress("ui_up")
                                ide.stepSceneTick()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("godot_pad_jump")
                    )
                    Text(
                        "▶",
                        color = CyberCyan,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable {
                                ide.godotInputMap.actionRelease("ui_left")
                                ide.godotInputMap.actionPress("ui_right")
                                ide.stepSceneTick()
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("godot_pad_right")
                    )
                }
            }
        }

        // Bottom Inspector / Android IDE / Voice Console Tabs
        LazyRow(
            modifier = Modifier.fillMaxWidth().background(SlateCard).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(IdeRightInspectorTab.entries) { tab ->
                FilterChip(
                    selected = rightTab == tab,
                    onClick = { ide.setRightTab(tab) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Box(modifier = Modifier.weight(0.45f).fillMaxWidth().padding(8.dp)) {
            when (rightTab) {
                IdeRightInspectorTab.INSPECTOR -> {
                    if (selectedNode == null) {
                        Text("Tap a node in Canvas or Scene Tree.", color = TextMuted)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${selectedNode.name} (${selectedNode.type.label})", style = MaterialTheme.typography.labelLarge, color = CyberCyan)
                                    Text(
                                        selectedNode.scriptPath?.let { "Script: $it (${selectedNode.scriptLanguage?.badge})" } ?: "No script attached",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedNode.scriptPath != null) EmeraldRun else AmberWarn
                                    )
                                }
                                Button(
                                    onClick = { onAttachScriptClick(selectedNode) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("inspector_attach_script_btn"),
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary)
                                ) {
                                    Text(if (selectedNode.scriptPath == null) "Attach Script" else "Change Script", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = selectedNode.x.toInt().toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(x = f) } } },
                                    label = { Text("X") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.y.toInt().toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(y = f) } } },
                                    label = { Text("Y") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.vx.toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(vx = f) } } },
                                    label = { Text("VX") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = selectedNode.vy.toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(vy = f) } } },
                                    label = { Text("VY") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.scale.toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(scale = f.coerceIn(0.2f, 4f)) } } },
                                    label = { Text("Scale") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.gravityScale.toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(gravityScale = f) } } },
                                    label = { Text("Grav") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = selectedNode.width.toInt().toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(width = f.coerceIn(12f, 480f)) } } },
                                    label = { Text("W") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.height.toInt().toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(height = f.coerceIn(12f, 320f)) } } },
                                    label = { Text("H") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = selectedNode.rotationDeg.toInt().toString(),
                                    onValueChange = { v -> v.toFloatOrNull()?.let { f -> ide.updateSelectedNode { it.copy(rotationDeg = f % 360f) } } },
                                    label = { Text("Rot°") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = selectedNode.visible,
                                    onClick = { ide.updateSelectedNode { it.copy(visible = !it.visible) } },
                                    label = { Text(if (selectedNode.visible) "Visible" else "Hidden", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = selectedNode.solidCollision,
                                    onClick = { ide.updateSelectedNode { it.copy(solidCollision = !it.solidCollision) } },
                                    label = { Text(if (selectedNode.solidCollision) "Solid" else "Sensor", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (selectedNode.scriptPath != null) {
                                    OutlinedButton(
                                        onClick = { ide.openFile(selectedNode.scriptPath) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Edit in VS Code", style = MaterialTheme.typography.labelSmall) }
                                }
                                OutlinedButton(
                                    onClick = { ide.deleteSelectedNode() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Delete Node", color = RoseError, style = MaterialTheme.typography.labelSmall) }
                            }
                            if (godotSignals.isNotEmpty()) {
                                Text("Recent Godot Signals:", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                godotSignals.take(3).forEach { sig ->
                                    Text(sig, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                IdeRightInspectorTab.ANDROID_PREVIEW -> {
                    AndroidCodeStudioLivePane(
                        ide = ide,
                        appDesign = appDesign,
                        files = files,
                        diagnostics = diagnostics,
                        onExportZip = onExportAndroidZip,
                        onSelectBuildLanguage = onSelectAppBuildLanguage,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                IdeRightInspectorTab.VOICE_CONSOLE -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (voiceLog.isEmpty()) {
                            item { Text("Tap Voice button on top bar to speak commands to VS Code, Godot, or Android IDE.", color = TextMuted, style = MaterialTheme.typography.bodySmall) }
                        } else {
                            items(voiceLog) { entry ->
                                Text(entry, color = CyberCyan, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidCodeStudioLivePane(
    ide: UnifiedIdeController,
    appDesign: AppDesign,
    files: List<IdeFileEntry>,
    diagnostics: List<IdeDiagnostic>,
    onExportZip: () -> Unit,
    onSelectBuildLanguage: (AppBuildLanguage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activePath by ide.activeTabPath.collectAsState()
    val counter by ide.acsTestCounter.collectAsState()
    val lastResult by ide.acsLastTestResult.collectAsState()
    val testInputs by ide.acsTestInputs.collectAsState()
    val testSwitches by ide.acsTestSwitches.collectAsState()
    val resourceTable by ide.acsResourceTable.collectAsState()
    val acsBuildLang by ide.acsBuildLanguage.collectAsState()
    val acsBuildVariant by ide.acsBuildVariant.collectAsState()
    val acsBuildPhases by ide.acsBuildPhases.collectAsState()
    val isAcsBuilding by ide.isAcsBuilding.collectAsState()
    val isCompiling by ide.isCompiling.collectAsState()

    val sourceForPreview = files.firstOrNull {
        it.path == activePath && (it.path.endsWith(".kt") || (it.path.endsWith(".xml") && !it.path.endsWith("AndroidManifest.xml")))
    }?.content ?: files.firstOrNull { it.path.endsWith("MainActivity.kt") }?.content.orEmpty()
    val previewWidgets = remember(sourceForPreview, appDesign, resourceTable) {
        PolyglotGodotCompiler.parseComposeWidgetsForPreview(sourceForPreview, appDesign, resourceTable)
    }

    Column(
        modifier = modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Android Code Studio · Build Environment", style = MaterialTheme.typography.labelLarge, color = EmeraldRun)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("debug", "release").forEach { variant ->
                    FilterChip(
                        selected = acsBuildVariant == variant,
                        onClick = { ide.setAcsBuildVariant(variant) },
                        label = { Text(variant.uppercase(), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("acs_variant_$variant")
                    )
                }
            }
        }
        Text(
            "$lastResult · R.symbols=${resourceTable.totalSymbols} · Lang=${acsBuildLang.label}",
            style = MaterialTheme.typography.labelSmall,
            color = CyberCyan
        )

        val editorConfig by ide.editorConfig.collectAsState()
        val aarStatus by produceState<AcsAarRuntime.AcsAarStatus?>(initialValue = null, files.size) {
            value = withContext(Dispatchers.IO) {
                AcsAarRuntime.inspectStatus(listOf(ide.nativeCompilerWorkspaceRoot()))
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmeraldRun.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .testTag("acs_aar_status_card")
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Android Code Studio AAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldRun
                    )
                    FilterChip(
                        selected = editorConfig.useSoraAar,
                        onClick = { ide.updateEditorConfig { it.copy(useSoraAar = !it.useSoraAar) } },
                        label = { Text(if (editorConfig.useSoraAar) "Sora AAR ON" else "Compose Mode", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("acs_panel_sora_toggle")
                    )
                }
                val st = aarStatus
                Text(
                    if (st != null) {
                        "Module ${st.libraryModule} (${st.packageNamespace}) · ${st.soraEditorArtifact} · Live=${st.soraClassPresent}"
                    } else {
                        "Inspecting :android-code-studio AAR..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (st != null && st.discoveredExternalAars.isNotEmpty()) {
                    Text(
                        "Workspace libs: ${st.discoveredExternalAars.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan
                    )
                }
            }
        }

        // Multi-Language App Build Selector (Build in Any Language)
        Text("Build App Language (Any Language)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("acs_build_lang_row")
        ) {
            items(AppBuildLanguage.entries) { lang ->
                FilterChip(
                    selected = acsBuildLang == lang,
                    onClick = { onSelectBuildLanguage(lang) },
                    label = { Text(lang.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("acs_build_lang_${lang.name}")
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("acs_actions_row")
        ) {
            item {
                Button(
                    onClick = {
                        ide.runAndroidStudioGradleBuildPipeline(appDesign.copy(buildLanguage = acsBuildLang))
                    },
                    enabled = !isAcsBuilding,
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp).testTag("acs_gradle_build_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary)
                ) {
                    if (isAcsBuilding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Building...", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("Gradle Build", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Button(
                    onClick = { ide.runCompileAndTest() },
                    enabled = !isCompiling && !isAcsBuilding,
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp).testTag("acs_run_test_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                ) {
                    Text(if (isCompiling) "Testing..." else "Verify & Test", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Button(
                    onClick = onExportZip,
                    enabled = !isAcsBuilding,
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp).testTag("acs_export_zip_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                ) {
                    Text(if (isAcsBuilding) "Busy..." else "Build APK ZIP", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                OutlinedButton(
                    onClick = { ide.fixMissingAndroidImportsActiveFile() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp).testTag("acs_fix_imports_btn")
                ) { Text("Fix Imports", style = MaterialTheme.typography.labelSmall, color = AmberWarn) }
            }
        }

        // Android Studio Build Phases Output Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonViolet.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .testTag("acs_build_phases_card")
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Gradle Build Output (${acsBuildLang.label})", style = MaterialTheme.typography.labelSmall, color = NeonViolet)
                acsBuildPhases.forEach { phase ->
                    Text(phase, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        // Live Interactive Device Preview
        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .testTag("acs_live_preview_card")
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live Interactive App Preview (Events: $counter)", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                previewWidgets.forEach { w ->
                    when (w.kind) {
                        "TEXT" -> Text(w.label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        "BUTTON" -> Button(
                            onClick = { ide.pressLivePreviewButton(w.label) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("acs_preview_button_${w.id}")
                        ) { Text(w.label, style = MaterialTheme.typography.labelSmall) }
                        "INPUT" -> OutlinedTextField(
                            value = testInputs[w.id] ?: "",
                            onValueChange = { ide.updateLivePreviewInput(w.id, it) },
                            label = { Text(w.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        "SWITCH" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(w.label, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = testSwitches[w.id] ?: false,
                                onCheckedChange = { ide.toggleLivePreviewSwitch(w.id, it) }
                            )
                        }
                        else -> Card(colors = CardDefaults.cardColors(containerColor = SlateCard)) {
                            Text(w.label, Modifier.padding(8.dp), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        AndroidDesignerAndAssetBar(
            onInsertWidget = { kind, label -> ide.insertAndroidDesignerWidget(kind, label) },
            onCreateVectorAsset = { name, preset -> ide.createAndroidVectorAsset(name, preset) }
        )

        val acsIssues = diagnostics.filter { it.filePath.startsWith("android/") }
        Text("Manifest & Android Audit (${acsIssues.size} issue(s))", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        acsIssues.forEach { d ->
            Text("${d.filePath.substringAfterLast('/')}:${d.line} - ${d.message}", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
        }
    }
}
