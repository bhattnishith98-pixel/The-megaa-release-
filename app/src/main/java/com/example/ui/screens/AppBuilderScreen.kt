package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.StudioViewModel
import com.example.appbuilder.*
import com.example.nodes.CustomDefinitionKind
import com.example.nodes.CustomNodeDefinition
import com.example.nodes.NodeRegistry
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBuilderScreen(
    vm: StudioViewModel,
    onOpenInAndroidCodeStudio: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val design by vm.appDesign.collectAsStateWithLifecycle()
    val buildOutput by vm.appBuildOutput.collectAsStateWithLifecycle()
    val previewLog by vm.appPreviewLog.collectAsStateWithLifecycle()
    val customDefs by NodeRegistry.customDefinitions.collectAsStateWithLifecycle()
    val macros by vm.macroEngine.macros.collectAsStateWithLifecycle()
    val studioSource by vm.sourceCode.collectAsStateWithLifecycle()
    val isAcsBuilding by vm.unifiedIde.isAcsBuilding.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf("Designer") }
    var selectedScreenIdx by remember { mutableIntStateOf(0) }
    var showElementDialog by remember { mutableStateOf(false) }
    var editingElement by remember { mutableStateOf<UiElementDesign?>(null) }
    var elementInitialKind by remember { mutableStateOf(UiElementKind.BUTTON) }
    var showAddScreenDialog by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(true) }

    // Keystore generator state
    var ksAlias by remember { mutableStateOf("upload") }
    var ksStorePass by remember { mutableStateOf("studioRelease123") }
    var ksKeyPass by remember { mutableStateOf("studioRelease123") }
    var ksCommonName by remember { mutableStateOf("Automation Studio Developer") }
    var ksOrg by remember { mutableStateOf("Automation Studio") }
    var ksCountry by remember { mutableStateOf("IN") }
    var ksValidityDays by remember { mutableStateOf("9125") }
    var generatedKeystore by remember { mutableStateOf<GeneratedKeystoreResult?>(null) }
    var keystoreError by remember { mutableStateOf<String?>(null) }

    val safeZipName = remember(design.appName) {
        design.appName.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "StudioApp" }
    }

    val saveZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        val currentOut = buildOutput
                        val zipData = if (currentOut?.success == true && currentOut.zipBytes != null) {
                            currentOut.zipBytes
                        } else {
                            val compiled = vm.compiler.compile(studioSource).bytecode
                            AndroidProjectGenerator.buildAndExport(
                                design, vm.generatedAppDir, vm.appBackupsDir, compiled
                            ).zipBytes
                        }
                        if (zipData != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(zipData) }
                                ?: error("Could not open output stream")
                            zipData.size
                        } else null
                    }
                }
                res.onSuccess { size ->
                    if (size != null) {
                        vm.setBanner("Saved Gradle project ZIP ($safeZipName.zip, $size bytes).")
                    } else {
                        vm.setBanner("Could not export ZIP: fix app validation errors first.")
                    }
                }.onFailure { err ->
                    vm.setBanner("Could not save ZIP: ${err.localizedMessage ?: "Write error"}")
                }
            }
        }
    }

    val saveKeystoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri ->
        val ks = generatedKeystore
        if (uri != null && ks != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(ks.keystoreBytes) }
                            ?: error("Could not open output stream")
                    }
                }.onSuccess {
                    vm.setBanner("Saved release keystore (${ks.alias}-release.p12).")
                }.onFailure { err ->
                    vm.setBanner("Failed to save keystore: ${err.localizedMessage ?: "Write error"}")
                }
            }
        }
    }

    val activeScreen = design.screens.getOrNull(selectedScreenIdx.coerceIn(0, (design.screens.size - 1).coerceAtLeast(0)))

    val customBlockNames = remember(customDefs) {
        customDefs.filter { it.kind != CustomDefinitionKind.TRIGGER }.map { it.name }.toSet()
    }
    val hasWiredBlockOrMacro = remember(design, customBlockNames) {
        design.screens.any { screen ->
            screen.elements.any { el ->
                el.kind == UiElementKind.BUTTON && (
                    el.onTapCode.contains("macro.run(") ||
                        customBlockNames.any { b -> el.onTapCode.contains(b) }
                    )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ObsidianBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App Builder & Exporter", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Text(
                        "${design.buildLanguage.label} · ${design.packageId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onOpenInAndroidCodeStudio,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("open_in_acs_ide_btn").defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Android CS IDE", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    }
                    Button(
                        onClick = {
                            vm.buildAppProject()
                            activeTab = "Build & ZIP"
                        },
                        enabled = !isAcsBuilding,
                        modifier = Modifier.testTag("build_app_project_button").defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                    ) {
                        if (isAcsBuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ObsidianBg
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Building...")
                        } else {
                            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Build (ACS)")
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(listOf("Designer", "Code Studio", "Live Preview", "Build & ZIP", "Keystore & GitHub")) { tab ->
                        val tag = "app_tab_" + tab.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            label = { Text(tab) },
                            modifier = Modifier.testTag(tag)
                        )
                    }
                }
                AssistChip(
                    onClick = { showWizard = !showWizard },
                    label = { Text(if (showWizard) "Hide Wizard" else "? Wizard") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = CyberCyan
                        )
                    },
                    modifier = Modifier.testTag("toggle_app_wizard_chip")
                )
            }

            when (activeTab) {
                "Designer" -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("app_designer_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (showWizard) {
                            item {
                                GuidedAppWizardCard(
                                    design = design,
                                    customDefsCount = customDefs.count { it.kind != CustomDefinitionKind.TRIGGER },
                                    hasWiredButton = hasWiredBlockOrMacro,
                                    hasBuiltOrPreviewed = buildOutput != null || previewLog.isNotEmpty(),
                                    onOpenWireButton = {
                                        activeTab = "Designer"
                                        editingElement = null
                                        elementInitialKind = UiElementKind.BUTTON
                                        showElementDialog = true
                                    },
                                    onCreateSampleBlock = {
                                        vm.registerCustomDefinition(
                                            kind = CustomDefinitionKind.BLOCK,
                                            name = "ClampPercent",
                                            summary = "Clamps numeric input safely between 0 and 100",
                                            paramName = "x",
                                            caps = emptySet(),
                                            bodyCode = "return math.clamp(x, 0, 100)"
                                        )
                                    },
                                    onOpenPreview = { activeTab = "Live Preview" },
                                    onOpenCodeStudio = { activeTab = "Code Studio" },
                                    onDismiss = { showWizard = false }
                                )
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("App Configuration & Build Mode", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                    Text(
                                        "Build with direct code languages (Kotlin/Compose, HTML5+JS, StudioLang, JSON) or visual drag elements — no nodes required:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AppBuildLanguage.entries.forEach { lang ->
                                            FilterChip(
                                                selected = design.buildLanguage == lang,
                                                onClick = { vm.updateAppDesign(design.copy(buildLanguage = lang)) },
                                                label = { Text(lang.label) },
                                                modifier = Modifier.testTag("app_lang_${lang.name}")
                                            )
                                        }
                                    }
                                    if (design.buildLanguage !in listOf(AppBuildLanguage.VISUAL_DESIGNER, AppBuildLanguage.JSON_MANIFEST)) {
                                        FilledTonalButton(
                                            onClick = { activeTab = "Code Studio" },
                                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Open ${design.buildLanguage.label} Editor")
                                        }
                                    }

                                    Text("Manifest Permissions (${design.permissions.size})", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(
                                            "android.permission.INTERNET" to "INTERNET",
                                            "android.permission.CAMERA" to "CAMERA",
                                            "android.permission.RECORD_AUDIO" to "RECORD_AUDIO",
                                            "android.permission.ACCESS_FINE_LOCATION" to "LOCATION",
                                            "android.permission.POST_NOTIFICATIONS" to "NOTIFICATIONS",
                                            "android.permission.BLUETOOTH_CONNECT" to "BLUETOOTH"
                                        ).forEach { (perm, shortLabel) ->
                                            val hasPerm = perm in design.permissions
                                            FilterChip(
                                                selected = hasPerm,
                                                onClick = {
                                                    val next = if (hasPerm) design.permissions - perm else design.permissions + perm
                                                    vm.updateAppDesign(design.copy(permissions = next))
                                                },
                                                label = { Text(shortLabel) }
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = design.appName,
                                        onValueChange = { vm.updateAppDesign(design.copy(appName = it)) },
                                        label = { Text("App Name (<= 30 chars)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("app_name_input")
                                    )
                                    OutlinedTextField(
                                        value = design.packageId,
                                        onValueChange = { vm.updateAppDesign(design.copy(packageId = it)) },
                                        label = { Text("Package ID (reverse-DNS)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("app_package_input")
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = design.minSdk.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { vm.updateAppDesign(design.copy(minSdk = it)) } },
                                            label = { Text("minSdk") },
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = design.targetSdk.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { vm.updateAppDesign(design.copy(targetSdk = it)) } },
                                            label = { Text("targetSdk") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Screens", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                TextButton(onClick = { showAddScreenDialog = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Text("Add Screen")
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(design.screens.indices.toList()) { idx ->
                                    FilterChip(
                                        selected = selectedScreenIdx == idx,
                                        onClick = { selectedScreenIdx = idx },
                                        label = { Text(design.screens[idx].name) }
                                    )
                                }
                            }
                        }

                        if (activeScreen != null) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${activeScreen.name} Elements", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                                    FilledTonalButton(
                                        onClick = {
                                            editingElement = null
                                            elementInitialKind = UiElementKind.BUTTON
                                            showElementDialog = true
                                        },
                                        modifier = Modifier.testTag("add_widget_button")
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add Widget")
                                    }
                                }
                            }
                            items(activeScreen.elements, key = { it.id }) { el ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                ) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("${el.kind.label}: ${el.label}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                            if (el.onTapCode.isNotBlank()) {
                                                Text("onTap: ${el.onTapCode}", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                            }
                                            if (el.stateKey.isNotBlank()) {
                                                Text("stateKey: ${el.stateKey}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    editingElement = el
                                                    elementInitialKind = el.kind
                                                    showElementDialog = true
                                                },
                                                modifier = Modifier.testTag("edit_widget_${el.id}")
                                            ) {
                                                Icon(Icons.Filled.Edit, contentDescription = "Edit widget", tint = CyberCyan)
                                            }
                                            IconButton(onClick = {
                                                val updatedScreens = design.screens.map { s ->
                                                    if (s.id == activeScreen.id) s.copy(elements = s.elements.filterNot { it.id == el.id }) else s
                                                }
                                                vm.updateAppDesign(design.copy(screens = updatedScreens))
                                            }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Remove widget", tint = RoseError)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                "Code Studio" -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("app_code_studio_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Language-Driven App Builder", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                    Text(
                                        "Write Android apps or bare-metal Custom OS Kernels using Kotlin/Compose, Java SDK, StudioLang Direct Binary, HTML5+JS, C/C++ NDK, Rust JNI, Python, or x86_64/ARM64/RISC-V Kernel.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AppBuildLanguage.entries.forEach { lang ->
                                            FilterChip(
                                                selected = design.buildLanguage == lang,
                                                onClick = { vm.updateAppDesign(design.copy(buildLanguage = lang)) },
                                                label = { Text(lang.label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (design.buildLanguage in listOf(
                                AppBuildLanguage.JAVA_ANDROID,
                                AppBuildLanguage.CPP_NDK,
                                AppBuildLanguage.RUST_JNI,
                                AppBuildLanguage.PYTHON_BRIDGE,
                                AppBuildLanguage.CUSTOM_OS_KERNEL
                            )
                        ) {
                            item {
                                ExtendedAppLanguageEditorCard(
                                    design = design,
                                    onUpdateDesign = { vm.updateAppDesign(it) }
                                )
                            }
                        }

                        if (design.buildLanguage == AppBuildLanguage.KOTLIN_COMPOSE || design.buildLanguage == AppBuildLanguage.VISUAL_DESIGNER) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("Kotlin / Compose Entry (MainActivity.kt)", style = MaterialTheme.typography.titleSmall, color = EmeraldRun)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(onClick = {
                                                    vm.updateAppDesign(
                                                        design.copy(
                                                            buildLanguage = AppBuildLanguage.KOTLIN_COMPOSE,
                                                            customKotlinCode = AppDesign.DEFAULT_KOTLIN_APP
                                                        )
                                                    )
                                                }) { Text("Reset Kotlin") }
                                            }
                                        }
                                        OutlinedTextField(
                                            value = design.customKotlinCode,
                                            onValueChange = {
                                                vm.updateAppDesign(
                                                    design.copy(
                                                        buildLanguage = AppBuildLanguage.KOTLIN_COMPOSE,
                                                        customKotlinCode = it
                                                    )
                                                )
                                            },
                                            label = { Text("MainActivity.kt") },
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                            minLines = 10,
                                            modifier = Modifier.fillMaxWidth().testTag("app_kotlin_editor")
                                        )
                                    }
                                }
                            }
                        }

                        if (design.buildLanguage == AppBuildLanguage.HTML5_JS) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("HTML5 + JavaScript WebView App", style = MaterialTheme.typography.titleSmall, color = NeonViolet)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(onClick = {
                                                    vm.updateAppDesign(
                                                        design.copy(
                                                            customHtml5Code = AppDesign.DEFAULT_HTML5_APP,
                                                            customJsCode = AppDesign.DEFAULT_JS_APP
                                                        )
                                                    )
                                                }) { Text("Reset HTML5+JS") }
                                                FilledTonalButton(onClick = { activeTab = "Live Preview" }) {
                                                    Text("Preview WebView")
                                                }
                                            }
                                        }
                                        OutlinedTextField(
                                            value = design.customHtml5Code,
                                            onValueChange = { vm.updateAppDesign(design.copy(customHtml5Code = it)) },
                                            label = { Text("assets/index.html") },
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                            minLines = 8,
                                            modifier = Modifier.fillMaxWidth().testTag("app_html5_editor")
                                        )
                                        OutlinedTextField(
                                            value = design.customJsCode,
                                            onValueChange = { vm.updateAppDesign(design.copy(customJsCode = it)) },
                                            label = { Text("assets/app.js") },
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                            minLines = 6,
                                            modifier = Modifier.fillMaxWidth().testTag("app_js_editor")
                                        )
                                    }
                                }
                            }
                        }

                        if (design.buildLanguage == AppBuildLanguage.STUDIOLANG || design.buildLanguage == AppBuildLanguage.JSON_MANIFEST) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${design.buildLanguage.label} App Mode", style = MaterialTheme.typography.titleSmall, color = CyberCyan)
                                        Text(
                                            "Exports your screens and StudioLang bytecode (`assets/logic.stlc`) + `app_model.json` cleanly into the generated Gradle APK/AAB project. Edit screens in Designer or logic in Nodes/Text Studio.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                        Text(
                                            "Active StudioLang lines: ${studioSource.lines().size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = EmeraldRun
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Optional Native C++ & Python Assets", style = MaterialTheme.typography.titleSmall, color = AmberWarn)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(onClick = {
                                                vm.updateAppDesign(
                                                    design.copy(
                                                        customCppCode = "// C++ Android native module\n#include <string>\nextern \"C\" const char* studio_native_status() {\n    return \"native_cpp_ready\";\n}\n"
                                                    )
                                                )
                                            }) { Text("+ C++") }
                                            TextButton(onClick = {
                                                vm.updateAppDesign(
                                                    design.copy(
                                                        customPythonCode = "# Bundled Python automation script\ndef run_task(name: str) -> str:\n    return f'Processed {name}'\n"
                                                    )
                                                )
                                            }) { Text("+ Python") }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = design.customCppCode,
                                        onValueChange = { vm.updateAppDesign(design.copy(customCppCode = it)) },
                                        label = { Text("app/src/main/cpp/native_module.cpp (optional)") },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                        minLines = 3,
                                        modifier = Modifier.fillMaxWidth().testTag("app_cpp_editor")
                                    )
                                    OutlinedTextField(
                                        value = design.customPythonCode,
                                        onValueChange = { vm.updateAppDesign(design.copy(customPythonCode = it)) },
                                        label = { Text("app/src/main/assets/scripts/main.py (optional)") },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                        minLines = 3,
                                        modifier = Modifier.fillMaxWidth().testTag("app_python_editor")
                                    )
                                }
                            }
                        }
                    }
                }

                "Live Preview" -> {
                    if (design.buildLanguage == AppBuildLanguage.HTML5_JS) {
                        var reloadCounter by remember { mutableIntStateOf(0) }
                        val inlinedHtml = remember(design.customHtml5Code, design.customJsCode, reloadCounter) {
                            inlineJsIntoHtml(design.customHtml5Code, design.customJsCode)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("HTML5 + JS Live WebView", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                FilledTonalButton(onClick = { reloadCounter++ }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reload")
                                }
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .border(2.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            ) {
                                Html5LiveWebView(
                                    htmlContent = inlinedHtml,
                                    modifier = Modifier.fillMaxSize().testTag("html5_live_webview")
                                )
                            }
                        }
                    } else {
                        var previewScreenName by remember(design) { mutableStateOf(design.screens.firstOrNull()?.name ?: "Home") }
                        val previewStates = remember { mutableStateMapOf<String, String>() }
                        LaunchedEffect(design.stateVars) {
                            design.stateVars.forEach { sv ->
                                if (sv.key !in previewStates) previewStates[sv.key] = sv.initialValue
                            }
                        }
                        val screen = design.screens.firstOrNull { it.name == previewScreenName } ?: design.screens.firstOrNull()

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(design.screens) { s ->
                                        FilterChip(
                                            selected = previewScreenName == s.name,
                                            onClick = { previewScreenName = s.name },
                                            label = { Text(s.name) }
                                        )
                                    }
                                }
                            }
                            if (screen != null) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                        modifier = Modifier.fillMaxWidth().border(2.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                    ) {
                                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("${design.appName} · ${screen.name}", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
                                            HorizontalDivider(color = SlateBorder)
                                            screen.elements.forEach { el ->
                                                when (el.kind) {
                                                    UiElementKind.TEXT -> Text(el.label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                                    UiElementKind.BUTTON -> Button(
                                                        onClick = {
                                                            if (el.onTapCode.startsWith("ui.navigate(")) {
                                                                previewScreenName = el.onTapCode.substringAfter("\"").substringBefore("\"")
                                                            } else {
                                                                val prefix = previewStates.entries.joinToString("\n") { (k, v) ->
                                                                    val literal = if (v == "true" || v == "false" || v.toDoubleOrNull() != null) v else "\"$v\""
                                                                    "let $k = $literal"
                                                                }
                                                                val program = if (el.onTapCode.contains("macro.run(")) el.onTapCode else "$prefix\n${el.onTapCode}"
                                                                vm.triggerAppPreviewAction(program)
                                                            }
                                                        },
                                                        modifier = Modifier.testTag("preview_btn_${el.id}")
                                                    ) { Text(el.label) }
                                                    UiElementKind.INPUT -> {
                                                        val v = previewStates[el.stateKey].orEmpty()
                                                        OutlinedTextField(
                                                            value = v,
                                                            onValueChange = { if (el.stateKey.isNotBlank()) previewStates[el.stateKey] = it },
                                                            label = { Text(el.label) },
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                    UiElementKind.SWITCH -> {
                                                        val checked = previewStates[el.stateKey] == "true"
                                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                            Text(el.label, color = TextPrimary)
                                                            Switch(checked = checked, onCheckedChange = {
                                                                if (el.stateKey.isNotBlank()) previewStates[el.stateKey] = it.toString()
                                                            })
                                                        }
                                                    }
                                                    UiElementKind.CARD -> Card(colors = CardDefaults.cardColors(containerColor = SlateCard)) {
                                                        Text(el.label, Modifier.padding(14.dp), color = TextPrimary)
                                                    }
                                                    else -> Text("${el.kind.label}: ${el.label}", color = TextSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (previewLog.isNotEmpty()) {
                                item {
                                    Text("Live VM Event Log", style = MaterialTheme.typography.titleMedium, color = EmeraldRun)
                                    previewLog.forEach { line ->
                                        Text(line, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                "Build & ZIP" -> {
                    val out = buildOutput
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Export Complete Gradle Android Project (.zip)", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                    Text(
                                        "Includes Gradle scripts, Manifest, Kotlin/HTML5/JS/C++/Python code, release signing config, and `.github/workflows/build-app.yml` for GitHub APK & `.aab` builds.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { vm.buildAppProject() },
                                            enabled = !isAcsBuilding,
                                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            if (isAcsBuilding) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = TextPrimary
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Building...")
                                            } else {
                                                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Rebuild Files")
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                runCatching { saveZipLauncher.launch("$safeZipName.zip") }.onFailure { err ->
                                                    vm.setBanner("Document saver unavailable: ${err.localizedMessage ?: "No handler"}")
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 48.dp)
                                                .testTag("download_project_zip_button")
                                        ) {
                                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Download ZIP")
                                        }
                                    }
                                }
                            }
                        }

                        if (out == null) {
                            item {
                                Text(
                                    "Tap 'Generate & Package' or 'Download ZIP' to validate manifest, generate native Compose/HTML5/Gradle files, back up previous output, and inspect diffs.",
                                    color = TextSecondary
                                )
                            }
                        } else {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, if (out.success) EmeraldRun else RoseError, RoundedCornerShape(14.dp))
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            if (out.success) "Project & ZIP Bundle Generated (${out.zipBytes?.size ?: 0} bytes)" else "Validation Failed",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (out.success) EmeraldRun else RoseError
                                        )
                                        out.buildLog.forEach { line ->
                                            Text(line, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                        }
                                    }
                                }
                            }
                            items(out.generatedFiles, key = { it.relativePath }) { gf ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(gf.relativePath, style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                            Text(gf.diffSummary, style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                                        }
                                        Text(gf.content.take(700), style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }

                "Keystore & GitHub" -> {
                    val workflowYaml = remember(design.appName) {
                        AndroidProjectGenerator.githubActionsWorkflowYaml(design.appName)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("keystore_github_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, NeonViolet.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Release Signing Keystore Generator (PKCS12 / Play Upload Key)", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                                    Text(
                                        "Generates a real 2048-bit RSA X.509 certificate and PKCS12 keystore (.p12) compatible with Gradle signingConfigs, keytool, Play Console upload signing, and GitHub Secrets.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = ksAlias,
                                            onValueChange = { ksAlias = it },
                                            label = { Text("Key Alias") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).testTag("keystore_alias_input")
                                        )
                                        OutlinedTextField(
                                            value = ksValidityDays,
                                            onValueChange = { ksValidityDays = it.filter { c -> c.isDigit() } },
                                            label = { Text("Validity (days)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = ksStorePass,
                                            onValueChange = { ksStorePass = it },
                                            label = { Text("Store Password (6+)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).testTag("keystore_store_pass_input")
                                        )
                                        OutlinedTextField(
                                            value = ksKeyPass,
                                            onValueChange = { ksKeyPass = it },
                                            label = { Text("Key Password (6+)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).testTag("keystore_key_pass_input")
                                        )
                                    }
                                    OutlinedTextField(
                                        value = ksCommonName,
                                        onValueChange = { ksCommonName = it },
                                        label = { Text("Developer / Common Name (CN)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = ksOrg,
                                            onValueChange = { ksOrg = it },
                                            label = { Text("Organization (O)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(2f)
                                        )
                                        OutlinedTextField(
                                            value = ksCountry,
                                            onValueChange = { ksCountry = it.take(2).uppercase() },
                                            label = { Text("Country") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            keystoreError = null
                                            scope.launch {
                                                val result = runCatching {
                                                    withContext(Dispatchers.Default) {
                                                        ReleaseKeystoreGenerator.generatePkcs12Keystore(
                                                            KeystoreGenerationConfig(
                                                                alias = ksAlias,
                                                                storePassword = ksStorePass,
                                                                keyPassword = ksKeyPass,
                                                                commonName = ksCommonName,
                                                                organization = ksOrg,
                                                                countryCode = ksCountry,
                                                                validityDays = ksValidityDays.toIntOrNull() ?: 9125
                                                            )
                                                        )
                                                    }
                                                }
                                                result.onSuccess {
                                                    generatedKeystore = it
                                                    vm.setBanner("Generated 2048-bit RSA Release Keystore (${it.alias}).")
                                                }.onFailure { err ->
                                                    keystoreError = err.message ?: "Keystore generation failed."
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary),
                                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("generate_keystore_button")
                                    ) {
                                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Generate Release Keystore (.p12)")
                                    }

                                    keystoreError?.let { err ->
                                        Text(err, color = RoseError, style = MaterialTheme.typography.bodySmall)
                                    }

                                    generatedKeystore?.let { ks ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                            modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        ) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("Keystore Ready · ${ks.format} (${ks.keystoreBytes.size} bytes)", style = MaterialTheme.typography.titleSmall, color = EmeraldRun)
                                                Text("Subject: ${ks.subjectDn}", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                                Text("SHA-256: ${ks.sha256Fingerprint}", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                                Text("SHA-1: ${ks.sha1Fingerprint}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            clipboardManager.setText(AnnotatedString(ks.base64Encoded))
                                                            vm.setBanner("Copied KEYSTORE_BASE64 to clipboard.")
                                                        },
                                                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("copy_keystore_base64_button")
                                                    ) {
                                                        Text("Copy Base64")
                                                    }
                                                    Button(
                                                        onClick = {
                                                            runCatching { saveKeystoreLauncher.launch("${ks.alias}-release.p12") }.onFailure { err ->
                                                                vm.setBanner("Document saver unavailable: ${err.localizedMessage ?: "No handler"}")
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg),
                                                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("download_keystore_button")
                                                    ) {
                                                        Text("Download .p12")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("How to Build APK & .aab on GitHub", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                    Text(
                                        "1. Go to 'Build & ZIP' tab and tap 'Download ZIP' to save your complete Gradle project ($safeZipName.zip).\n" +
                                            "2. Extract the ZIP and push its files (including `.github/workflows/build-app.yml`) to the `main` branch of a GitHub repository.\n" +
                                            "3. Generate a Release Keystore above, tap 'Download .p12', and copy `KEYSTORE_BASE64`.\n" +
                                            "4. In GitHub repo -> Settings -> Secrets and variables -> Actions, add 4 Repository Secrets:\n" +
                                            "   • KEYSTORE_BASE64 = (copied Base64 string)\n" +
                                            "   • STORE_PASSWORD = your store password\n" +
                                            "   • KEY_ALIAS = ${ksAlias.ifBlank { "upload" }}\n" +
                                            "   • KEY_PASSWORD = your key password\n" +
                                            "5. Open GitHub -> Actions -> 'Build APK & AAB' -> Run workflow. Download `android-builds` containing your debug APK, signed release APK, and Google Play `.aab`.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(workflowYaml))
                                            vm.setBanner("Copied GitHub Actions APK & AAB workflow YAML.")
                                        },
                                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("copy_github_workflow_button")
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy .github/workflows/build-app.yml")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddScreenDialog) {
            var name by remember { mutableStateOf("Details") }
            AlertDialog(
                onDismissRequest = { showAddScreenDialog = false },
                title = { Text("New Screen") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Screen Name") }) },
                confirmButton = {
                    Button(onClick = {
                        val scr = AppScreenDesign("scr_${System.currentTimeMillis()}", name.ifBlank { "Screen" })
                        vm.updateAppDesign(design.copy(screens = design.screens + scr))
                        showAddScreenDialog = false
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showAddScreenDialog = false }) { Text("Cancel") } }
            )
        }

        if (showElementDialog && activeScreen != null) {
            val currentEdit = editingElement
            var kind by remember(currentEdit, elementInitialKind) { mutableStateOf(currentEdit?.kind ?: elementInitialKind) }
            var label by remember(currentEdit) { mutableStateOf(currentEdit?.label ?: "Trigger Action") }
            var stateKey by remember(currentEdit) { mutableStateOf(currentEdit?.stateKey ?: "") }
            var onTap by remember(currentEdit) { mutableStateOf(currentEdit?.onTapCode ?: "log.info(\"Clicked!\")") }
            val selectableDefs = remember(customDefs) { customDefs.filter { it.kind != CustomDefinitionKind.TRIGGER } }

            BackHandler {
                showElementDialog = false
                editingElement = null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg.copy(alpha = 0.88f))
                    .pointerInput(Unit) {}
                    .padding(14.dp)
                    .testTag("element_editor_sheet"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = SlateCard,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .border(1.dp, SlateBorder, RoundedCornerShape(18.dp))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (currentEdit == null) "Add UI Element" else "Edit UI Element",
                                style = MaterialTheme.typography.titleLarge,
                                color = CyberCyan
                            )
                            IconButton(
                                onClick = {
                                    showElementDialog = false
                                    editingElement = null
                                }
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close editor", tint = TextSecondary)
                            }
                        }

                        Column(
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(UiElementKind.entries) { k ->
                                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                                }
                            }
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it },
                                label = { Text("Label") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("element_label_input")
                            )
                            if (kind == UiElementKind.INPUT || kind == UiElementKind.SWITCH || kind == UiElementKind.CHECKBOX) {
                                OutlinedTextField(
                                    value = stateKey,
                                    onValueChange = { stateKey = it },
                                    label = { Text("State Variable Key (e.g. status)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (kind == UiElementKind.BUTTON) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, NeonViolet.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                        .testTag("block_to_button_picker")
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Block-to-Button Picker", style = MaterialTheme.typography.labelMedium, color = NeonViolet)
                                        Text(
                                            "Tap any Action Block, Macro, Screen, or Preset to wire this button:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )

                                        if (selectableDefs.isNotEmpty()) {
                                            Text("Action Blocks & Custom Actions", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(selectableDefs, key = { it.id }) { def ->
                                                    AssistChip(
                                                        onClick = {
                                                            onTap = buildActionCallSnippet(def)
                                                            if (label.isBlank() || label == "Trigger Action") label = "Run ${def.name}"
                                                        },
                                                        label = { Text("${def.kind.name}: ${def.name}") },
                                                        modifier = Modifier.testTag("pick_block_${def.name}")
                                                    )
                                                }
                                            }
                                        } else {
                                            AssistChip(
                                                onClick = {
                                                    vm.registerCustomDefinition(
                                                        kind = CustomDefinitionKind.BLOCK,
                                                        name = "ClampPercent",
                                                        summary = "Clamps numeric value to 0..100",
                                                        paramName = "x",
                                                        caps = emptySet(),
                                                        bodyCode = "return math.clamp(x, 0, 100)"
                                                    )
                                                    onTap = "log.info(\"Clamped: \" + ClampPercent(125))"
                                                    if (label.isBlank() || label == "Trigger Action") label = "Clamp 125%"
                                                },
                                                label = { Text("+ Create & Wire ClampPercent Block") }
                                            )
                                        }

                                        if (macros.isNotEmpty()) {
                                            Text("Saved Macros", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(macros.take(8), key = { it.id }) { macro ->
                                                    AssistChip(
                                                        onClick = {
                                                            onTap = "macro.run(\"${macro.name}\")"
                                                            if (label.isBlank() || label == "Trigger Action") label = macro.name
                                                        },
                                                        label = { Text("Macro: ${macro.name}") },
                                                        modifier = Modifier.testTag("pick_macro_${macro.name}")
                                                    )
                                                }
                                            }
                                        }

                                        Text("Navigate / Built-In", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(design.screens, key = { it.id }) { targetScreen ->
                                                AssistChip(
                                                    onClick = {
                                                        onTap = "ui.navigate(\"${targetScreen.name}\")"
                                                        if (label.isBlank() || label == "Trigger Action") label = "Go to ${targetScreen.name}"
                                                    },
                                                    label = { Text("Open ${targetScreen.name}") }
                                                )
                                            }
                                            item {
                                                AssistChip(
                                                    onClick = { onTap = "log.info(\"Status = \" + status)" },
                                                    label = { Text("Log Status") }
                                                )
                                            }
                                            item {
                                                AssistChip(
                                                    onClick = { onTap = "notify.show(\"${design.appName}\", \"Action triggered: \" + status)" },
                                                    label = { Text("Show Alert") }
                                                )
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = onTap,
                                    onValueChange = { onTap = it },
                                    label = { Text("onTap StudioLang") },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth().testTag("element_ontap_input")
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                showElementDialog = false
                                editingElement = null
                            }) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val updatedEl = if (currentEdit != null) {
                                        currentEdit.copy(kind = kind, label = label, stateKey = stateKey, onTapCode = if (kind == UiElementKind.BUTTON) onTap else "")
                                    } else {
                                        UiElementDesign(
                                            id = "el_${System.currentTimeMillis()}",
                                            kind = kind,
                                            label = label,
                                            stateKey = stateKey,
                                            onTapCode = if (kind == UiElementKind.BUTTON) onTap else ""
                                        )
                                    }
                                    val updatedScreens = design.screens.map { s ->
                                        if (s.id != activeScreen.id) s
                                        else if (currentEdit != null) s.copy(elements = s.elements.map { if (it.id == currentEdit.id) updatedEl else it })
                                        else s.copy(elements = s.elements + updatedEl)
                                    }
                                    vm.updateAppDesign(design.copy(screens = updatedScreens))
                                    showElementDialog = false
                                    editingElement = null
                                },
                                modifier = Modifier.testTag("confirm_element_dialog_button")
                            ) { Text(if (currentEdit == null) "Add" else "Save") }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Html5LiveWebView(htmlContent: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://studio.local/", htmlContent, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != htmlContent) {
                webView.tag = htmlContent
                webView.loadDataWithBaseURL("https://studio.local/", htmlContent, "text/html", "UTF-8", null)
            }
        }
    )
}

private fun inlineJsIntoHtml(html: String, js: String): String {
    if (js.isBlank()) return html
    val scriptTag = "<script>\n$js\n</script>"
    return when {
        html.contains("<script src=\"app.js\"></script>") -> html.replace("<script src=\"app.js\"></script>", scriptTag)
        html.contains("</body>", ignoreCase = true) -> html.replace("</body>", "$scriptTag\n</body>", ignoreCase = true)
        else -> "$html\n$scriptTag"
    }
}

private fun buildActionCallSnippet(def: CustomNodeDefinition): String {
    val args = def.params.joinToString(", ") { p ->
        val raw = p.defaultValue.trim()
        if (raw.isEmpty()) "\"demo\"" else raw
    }
    return if (def.kind == CustomDefinitionKind.BLOCK) {
        "log.info(\"${def.name} -> \" + ${def.name}($args))"
    } else {
        "${def.name}($args)"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedAppWizardCard(
    design: AppDesign,
    customDefsCount: Int,
    hasWiredButton: Boolean,
    hasBuiltOrPreviewed: Boolean,
    onOpenWireButton: () -> Unit,
    onCreateSampleBlock: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenCodeStudio: () -> Unit,
    onDismiss: () -> Unit
) {
    val step1Done = design.appName.isNotBlank() && design.packageId.contains('.')
    val step2Done = design.screens.isNotEmpty() && design.screens.sumOf { it.elements.size } >= 2
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .testTag("app_wizard_card")
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Guided Custom App & Block Wizard", style = MaterialTheme.typography.titleSmall, color = NeonViolet)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Hide wizard", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            WizardStepRow(1, "Name, Package & Build Language", step1Done, "${design.buildLanguage.label} · ${design.packageId}")
            WizardStepRow(2, "Add Screens, Widgets or Code", step2Done, "${design.screens.size} screens, ${design.screens.sumOf { it.elements.size }} widgets")
            WizardStepRow(3, "Wire Action Blocks / Macros to Buttons", hasWiredButton, "$customDefsCount custom block(s) ready")
            WizardStepRow(4, "Preview, Download ZIP & Sign APK/AAB", hasBuiltOrPreviewed, "Export Gradle ZIP + .p12 Keystore")

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = onOpenCodeStudio,
                    label = { Text("Code Studio") },
                    leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonViolet) }
                )
                AssistChip(
                    onClick = onOpenWireButton,
                    label = { Text("Wire Block to Button") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan) },
                    modifier = Modifier.testTag("wizard_wire_button_chip")
                )
                if (customDefsCount == 0) {
                    AssistChip(
                        onClick = onCreateSampleBlock,
                        label = { Text("Create Sample Block") },
                        modifier = Modifier.testTag("wizard_create_block_chip")
                    )
                }
                AssistChip(
                    onClick = onOpenPreview,
                    label = { Text("Live Preview") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldRun) }
                )
            }
        }
    }
}

@Composable
private fun WizardStepRow(step: Int, title: String, done: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) EmeraldRun else TextMuted,
            modifier = Modifier.size(16.dp)
        )
        Text(
            "$step. $title",
            style = MaterialTheme.typography.labelMedium,
            color = if (done) TextPrimary else TextSecondary
        )
        Text(
            "· $detail",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1
        )
    }
}
