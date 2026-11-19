package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.StudioViewModel
import com.example.bridge.TermuxToolchainBridge
import com.example.docs.ExamplesLibrary
import com.example.lang.StudioCapability
import com.example.macros.MacroRecord
import com.example.runtime.GodotEditorActivity
import com.example.runtime.GodotRuntimeActivity
import com.example.runtime.NativeRuntimeBridge
import com.example.services.ScreenCaptureService
import com.example.services.StudioAccessibilityService
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudioDashboardScreen(
    vm: StudioViewModel,
    onOpenEditor: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenDocs: () -> Unit = {},
    onOpenIde: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val macros by vm.macroEngine.macros.collectAsStateWithLifecycle()
    val history by vm.macroEngine.history.collectAsStateWithLifecycle()
    val grantedCaps by vm.capabilityManager.grantedCapabilities.collectAsStateWithLifecycle()
    val benchmark by vm.benchmarkReport.collectAsStateWithLifecycle()
    val workspaceFiles by vm.workspaceFiles.collectAsStateWithLifecycle()
    val accConnected by StudioAccessibilityService.isConnected.collectAsStateWithLifecycle()
    val captureActive by ScreenCaptureService.isActive.collectAsStateWithLifecycle()
    val monetizationState by vm.monetization.state.collectAsStateWithLifecycle()
    val showFirstLaunchPerms by vm.showFirstLaunchPermissionsDialog.collectAsStateWithLifecycle()
    val androidTweaks by vm.androidTweaks.collectAsStateWithLifecycle()
    val autoTriggerEnabled by vm.autoTriggerEngineEnabled.collectAsStateWithLifecycle()
    val variables by vm.macroEngine.variables.collectAsStateWithLifecycle()
    val lastAutoRun by com.example.services.StudioTriggerBus.lastAutoRunSummary.collectAsStateWithLifecycle()

    var selectedSection by remember { mutableStateOf("Macros") }
    val privStatus = remember(selectedSection) { vm.inspectPrivilegeStatus() }
    var exampleSearch by remember { mutableStateOf("") }
    var showNewMacroDialog by remember { mutableStateOf(false) }
    var editingBlocksMacro by remember { mutableStateOf<MacroRecord?>(null) }
    var showVariablesDialog by remember { mutableStateOf(false) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.setBanner(if (granted) "POST_NOTIFICATIONS granted." else "POST_NOTIFICATIONS denied by user.")
    }

    val allRuntimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        vm.refreshAndroidTweaks()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 16.dp)
            .testTag("studio_dashboard_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)
    ) {
        // Hero Banner Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
            ) {
                Image(
                    painter = painterResource(R.drawable.img_studio_hero),
                    contentDescription = "Automation Studio workspace banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(ObsidianBg.copy(alpha = 0.92f), ObsidianBg.copy(alpha = 0.45f))
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Column {
                            Text(
                                "AUTOMATION STUDIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "StudioLang VM + Node IDE",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary
                            )
                            Text(
                                "Custom compiler, bytecode verifier, node graphs, app builder & AI agent.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Button(
                                    onClick = onOpenIde,
                                    modifier = Modifier.testTag("hero_open_ide_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                ) {
                                    Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Tri-Pane IDE")
                                }
                            }
                            item {
                                Button(
                                    onClick = onOpenEditor,
                                    modifier = Modifier.testTag("hero_open_nodes_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                                ) {
                                    Icon(Icons.Filled.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open Canvas")
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = onOpenAgent,
                                    modifier = Modifier.testTag("hero_open_agent_button")
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = NeonViolet)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Brief Agent", color = TextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFirstLaunchPerms) {
            item {
                val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
                val overlayOk = androidTweaks.canDrawOverlays
                val writeOk = androidTweaks.canWriteSettings
                val accOk = accConnected || StudioAccessibilityService.isEnabledInSettings(context)

                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .testTag("first_launch_permissions_card")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("All App Permissions Setup", style = MaterialTheme.typography.titleSmall, color = CyberCyan)
                            TextButton(
                                onClick = { vm.markFirstLaunchPermissionsPromptShown() },
                                modifier = Modifier.testTag("first_launch_perms_continue")
                            ) { Text("Done") }
                        }
                        Text(
                            "Grant permissions for voice mic, notifications, floating AI overlay, system settings tweaks, and accessibility automation:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                AssistChip(
                                    onClick = {
                                        val missing = vm.getAllRuntimePermissionsToRequest()
                                        if (missing.isNotEmpty()) {
                                            runCatching { allRuntimePermLauncher.launch(missing) }.onFailure { err ->
                                                vm.setBanner("Permission dialog unavailable: ${err.localizedMessage}")
                                            }
                                        } else {
                                            vm.setBanner("Mic & Notification permissions are already granted.")
                                        }
                                    },
                                    label = { Text(if (micOk && notifOk) "Mic & Notif: OK" else "Allow Mic & Notif") }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { com.example.runtime.AndroidCustomizer.openOverlayConsent(context) },
                                    label = { Text(if (overlayOk) "Overlay: OK" else "Allow Overlay") }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { com.example.runtime.AndroidCustomizer.openWriteSettingsConsent(context) },
                                    label = { Text(if (writeOk) "Sys Settings: OK" else "Allow Write Settings") }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }.onFailure { err ->
                                            vm.setBanner("Unable to open Accessibility Settings: ${err.localizedMessage}")
                                        }
                                    },
                                    label = { Text(if (accOk) "Accessibility: OK" else "Allow Accessibility") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section Filter Chips
        item {
            val sections = listOf("Macros", "Android Control", "IPv4 / IPv6", "Examples (52)", "Language Docs", "Security", "Benchmarks", "Files", "Termux")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections) { sec ->
                    FilterChip(
                        selected = selectedSection == sec,
                        onClick = {
                            if (sec == "Language Docs") onOpenDocs() else selectedSection = sec
                        },
                        label = { Text(sec) },
                        modifier = Modifier.testTag("dashboard_tab_${sec.substringBefore(" ").lowercase()}")
                    )
                }
            }
        }

        // End-User Subscription Card (Ad/UPI config is developer-only in code, never editable in UI)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (monetizationState.isSubscribed) EmeraldRun.copy(alpha = 0.6f) else AmberWarn.copy(alpha = 0.45f),
                        RoundedCornerShape(14.dp)
                    )
                    .testTag("subscription_card")
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val profile = monetizationState.profile
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (monetizationState.isSubscribed) "Pro Ad-Free Plan Active (₹${profile.upi.subscriptionAmountInr}/mo)"
                                else "Free Plan · Ad-Supported",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (monetizationState.isSubscribed) EmeraldRun else AmberWarn
                            )
                            Text(
                                if (monetizationState.isSubscribed) {
                                    "All rewarded ads and foreground gates removed for ${profile.upi.subscriptionDays} days."
                                } else {
                                    val fgMin = monetizationState.foregroundSecondsWithoutAd / 60
                                    val fgSec = monetizationState.foregroundSecondsWithoutAd % 60
                                    val gateMin = profile.effectiveGateSeconds() / 60
                                    "Foreground timer: ${fgMin}m ${fgSec}s / ${gateMin}m"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        if (!monetizationState.isSubscribed) {
                            Surface(
                                color = SlateSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            ) {
                                Text(
                                    "Ads: ${monetizationState.totalTestAdsWatched}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!monetizationState.isSubscribed) {
                            Button(
                                onClick = {
                                    vm.monetization.activateMonthlySubscriptionTest()
                                    vm.setBanner("Subscribed to Pro Ad-Free (₹${profile.upi.subscriptionAmountInr}/month) for ${profile.upi.subscriptionDays} days.")
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("subscribe_300_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                            ) {
                                Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Remove Ads · ₹${profile.upi.subscriptionAmountInr}/mo")
                            }
                            OutlinedButton(
                                onClick = { vm.playRewardedAd(context as? Activity) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("watch_test_ad_now_button")
                            ) { Text("Watch Ad") }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    vm.monetization.cancelSubscriptionForTesting()
                                    vm.setBanner("Pro Ad-Free plan cancelled.")
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("cancel_sub_test_button")
                            ) { Text("Cancel Plan") }
                        }
                    }
                }
            }
        }

        when (selectedSection) {
            "Macros" -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (autoTriggerEnabled) EmeraldRun.copy(alpha = 0.55f) else SlateBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .testTag("auto_trigger_engine_card")
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (autoTriggerEnabled) "Auto-Trigger Engine · ARMED" else "Auto-Trigger Engine · PAUSED",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (autoTriggerEnabled) EmeraldRun else AmberWarn
                                    )
                                    Text(
                                        lastAutoRun ?: "Monitors sensors, battery, unlock, SMS, & hardware; runs actions when constraints pass.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Switch(
                                    checked = autoTriggerEnabled,
                                    onCheckedChange = { vm.setAutoTriggerEngineEnabled(it) },
                                    modifier = Modifier.testTag("auto_trigger_master_switch")
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilledTonalButton(
                                        onClick = {
                                            editingBlocksMacro = null
                                            showNewMacroDialog = true
                                        },
                                        modifier = Modifier.testTag("new_macro_button")
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("New Macro")
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = { showVariablesDialog = true },
                                        modifier = Modifier.testTag("variables_manager_button")
                                    ) {
                                        Icon(Icons.Filled.DataObject, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Variables (${variables.size})")
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            vm.emitAndDispatchTrigger("trigger.shake", "Sensors", "Simulated Shake Trigger Test")
                                        },
                                        modifier = Modifier.testTag("test_auto_trigger_button")
                                    ) {
                                        Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Fire Trigger")
                                    }
                                }
                            }
                        }
                    }
                }
                items(macros, key = { it.id }) { macro ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(macro.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    Text(macro.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                }
                                Switch(
                                    checked = macro.enabled,
                                    onCheckedChange = { vm.toggleEnabledCompat(macro.id) }
                                )
                            }
                            if (macro.triggers.isNotEmpty() || macro.actionSteps.isNotEmpty() || macro.constraints.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item {
                                        AssistChip(
                                            onClick = { editingBlocksMacro = macro; showNewMacroDialog = true },
                                            label = { Text("T:${macro.triggers.size}", color = androidx.compose.ui.graphics.Color(0xFFEF9A9A)) }
                                        )
                                    }
                                    item {
                                        AssistChip(
                                            onClick = { editingBlocksMacro = macro; showNewMacroDialog = true },
                                            label = { Text("A:${macro.actionSteps.size}", color = androidx.compose.ui.graphics.Color(0xFF90CAF9)) }
                                        )
                                    }
                                    item {
                                        AssistChip(
                                            onClick = { editingBlocksMacro = macro; showNewMacroDialog = true },
                                            label = { Text("C:${macro.constraints.size} (${macro.constraintGate.name})", color = androidx.compose.ui.graphics.Color(0xFFA5D6A7)) }
                                        )
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val res = vm.macroEngine.runMacro(macro)
                                                vm.setBanner(
                                                    if (res.success) "Macro '${macro.name}' ran (${res.instructionsExecuted} ops, ${res.durationMs}ms)"
                                                    else "Macro '${macro.name}' error: ${res.error}"
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("run_macro_${macro.id}"),
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Run")
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            editingBlocksMacro = macro
                                            showNewMacroDialog = true
                                        },
                                        modifier = Modifier.testTag("edit_blocks_${macro.id}")
                                    ) {
                                        Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Blocks")
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            vm.openMacroInEditor(macro)
                                            onOpenEditor()
                                        },
                                        modifier = Modifier.testTag("edit_macro_${macro.id}")
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Edit in Studio")
                                    }
                                }
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        Text("Recent Execution History", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    }
                    items(history.take(8), key = { it.id }) { h ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.macroName, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                                    Text(h.summary, style = MaterialTheme.typography.labelSmall, color = if (h.success) EmeraldRun else RoseError)
                                }
                                Text("${h.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
            }

            "Android Control" -> {
                item {
                    AndroidControlPanelContent(vm = vm)
                }
            }

            "IPv4 / IPv6" -> {
                item {
                    NetworkSharePanelContent(vm = vm, scope = scope)
                }
            }

            "Examples (52)" -> {
                item {
                    OutlinedTextField(
                        value = exampleSearch,
                        onValueChange = { exampleSearch = it },
                        label = { Text("Search 52 verified StudioLang programs...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("examples_search_input")
                    )
                }
                val filtered = ExamplesLibrary.examples.filter {
                    exampleSearch.isBlank() ||
                        it.title.contains(exampleSearch, true) ||
                        it.category.contains(exampleSearch, true) ||
                        it.code.contains(exampleSearch, true)
                }
                items(filtered, key = { it.id }) { ex ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                vm.loadExample(ex)
                                onOpenEditor()
                            }
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ex.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                                AssistChip(
                                    onClick = {
                                        vm.loadExample(ex)
                                        onOpenEditor()
                                    },
                                    label = { Text("${ex.category} · ${ex.difficulty}") }
                                )
                            }
                            Text(
                                ex.code.lines().take(3).joinToString("\n"),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }

            "Security" -> {
                item {
                    val idReport by vm.identityReportState.collectAsStateWithLifecycle()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Exclusive Runtime Identity", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                            Text("Package: ${idReport.packageName}", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                            Text("Cert SHA-256: ${idReport.signingSha256.take(28)}...", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(
                                "Session Valid: ${idReport.isExclusiveSessionValid}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (idReport.isExclusiveSessionValid) EmeraldRun else RoseError
                            )
                        }
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Android OS Permissions & Services", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            } else true
                            val accSecReady = accConnected || StudioAccessibilityService.isEnabledInSettings(context)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Notifications: ${if (notifOk) "Granted" else "Denied"}", color = TextSecondary)
                                if (!notifOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    TextButton(onClick = {
                                        runCatching { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { err ->
                                            vm.setBanner("Permission dialog unavailable: ${err.localizedMessage}")
                                        }
                                    }) {
                                        Text("Request")
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Accessibility Service: ${if (accConnected) "Active" else if (accSecReady) "Enabled (Waiting first window)" else "Off"}",
                                    color = TextSecondary
                                )
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { err ->
                                        vm.setBanner("Unable to open Accessibility Settings: ${err.localizedMessage}")
                                    }
                                }) {
                                    Text("Settings")
                                }
                            }
                            Text("MediaProjection Screen Capture: ${if (captureActive) "Active (Visible Indicator)" else "Inactive"}", color = TextSecondary)
                        }
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .testTag("privilege_tier_card")
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Advanced Android Control: Shizuku & Root", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                            Text("Shizuku Installed: ${privStatus.shizukuInstalled} (rish binary: ${privStatus.shizukuRishPresent})", style = MaterialTheme.typography.labelSmall, color = if (privStatus.shizukuInstalled) EmeraldRun else TextPrimary)
                            Text("Root `su`: ${privStatus.rootBinaryPath ?: "Not detected"}", style = MaterialTheme.typography.labelSmall, color = if (privStatus.rootAvailable) EmeraldRun else TextPrimary)
                            Text(privStatus.notes, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
                item {
                    val nativeInfo = remember { NativeRuntimeBridge.buildInfo() }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .testTag("godot_47_runtime_card")
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Godot 4.7.2.stable & Native C++20 Runtime", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                            Text(
                                "Engine: Godot 4.7.2.stable (${if (BuildConfig.GODOT_EDITOR_BUILD) "Editor + Runtime" else "Runtime Only"})",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmeraldRun
                            )
                            Text("NDK Bridge: $nativeInfo", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val sync = withContext(Dispatchers.IO) { vm.unifiedIde.syncGodot47ProjectToDisk(context) }
                                                context.startActivity(
                                                    GodotRuntimeActivity.createIntent(
                                                        context = context,
                                                        projectPath = sync.projectDir.absolutePath,
                                                        mainScene = sync.mainSceneResPath
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dashboard_godot_run_btn"),
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                    ) { Text("Run Live Project") }
                                }
                                if (BuildConfig.GODOT_EDITOR_BUILD) {
                                    item {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val sync = withContext(Dispatchers.IO) { vm.unifiedIde.syncGodot47ProjectToDisk(context) }
                                                    context.startActivity(
                                                        GodotEditorActivity.createIntent(
                                                            context = context,
                                                            projectPath = sync.projectDir.absolutePath
                                                        )
                                                    )
                                                }
                                            },
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dashboard_godot_editor_btn")
                                        ) { Text("Godot 4.7 Editor") }
                                    }
                                    item {
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(
                                                    GodotEditorActivity.createIntent(
                                                        context = context,
                                                        openProjectManager = true
                                                    )
                                                )
                                            },
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dashboard_godot_pm_btn")
                                        ) { Text("Project Manager") }
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = { context.startActivity(GodotRuntimeActivity.createIntent(context)) },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dashboard_godot_3d_btn")
                                    ) { Text("Godot 3D") }
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Automation Studio Capabilities", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                items(StudioCapability.entries, key = { it.id }) { cap ->
                    val enabled = cap in grantedCaps
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cap.id, style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                Text(cap.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    if (checked) vm.capabilityManager.grant(cap) else vm.capabilityManager.revoke(cap)
                                },
                                modifier = Modifier.testTag("cap_switch_${cap.id}")
                            )
                        }
                    }
                }
            }

            "Benchmarks" -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Reproducible Compiler & VM Benchmarks", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                            Text(
                                "Measures real cold vs cached compilation, IR optimization reductions, VM loop throughput, heap delta, and 1,000-node graph codegen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Button(
                                onClick = { vm.runBenchmarks() },
                                modifier = Modifier.testTag("run_benchmarks_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                            ) {
                                Icon(Icons.Filled.Speed, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Run Benchmark Suite")
                            }
                            benchmark?.let { r ->
                                HorizontalDivider(color = SlateBorder)
                                BenchmarkRow("Cold Compile", "${r.coldCompileUs} us")
                                BenchmarkRow("Cached Compile", "${r.cachedCompileUs} us")
                                BenchmarkRow("IR Ops (Raw -> Opt)", "${r.originalIrOps} -> ${r.optimizedIrOps} (folded ${r.foldedConstants})")
                                BenchmarkRow("VM Loop (2,000 iters)", "${r.vmLoopMs} ms (${r.vmInstructionsExecuted} ops)")
                                BenchmarkRow("VM Throughput", "${r.instructionsPerSecond} ops/sec")
                                BenchmarkRow("1,000-Node Graph Codegen", "${r.largeGraphCodegenMs} ms")
                                BenchmarkRow("Measured Heap Delta", "${r.heapDeltaKb} KB")
                            }
                        }
                    }
                }
            }

            "Files" -> {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Sandboxed Studio Workspace", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        IconButton(onClick = { vm.refreshWorkspaceFiles() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh workspace files", tint = CyberCyan)
                        }
                    }
                }
                items(workspaceFiles) { path ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = CyberCyan)
                            Spacer(Modifier.width(10.dp))
                            Text(path, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }

            "Termux" -> {
                item {
                    val status = remember { vm.inspectTermuxStatus() }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Termux Integration (Install-Only)", style = MaterialTheme.typography.titleLarge, color = AmberWarn)
                            Text("Termux Installed: ${status.isTermuxInstalled}", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                            Text("Execution/Compilation Delegated to Termux: ${status.executionDelegationAllowed}", style = MaterialTheme.typography.labelMedium, color = EmeraldRun)
                            Text(status.sandboxNote, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                }
                items(TermuxToolchainBridge.supportedInstallPackages, key = { it.id }) { pkg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pkg.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(pkg.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text(pkg.installCommand, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                            OutlinedButton(onClick = {
                                TermuxToolchainBridge.copyInstallCommand(context, pkg)
                                vm.setBanner("Copied install command to clipboard (run inside Termux).")
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Install Command")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewMacroDialog) {
        PdfMacroBuilderDialog(
            vm = vm,
            initialMacro = editingBlocksMacro,
            onDismiss = {
                showNewMacroDialog = false
                editingBlocksMacro = null
            }
        )
    }
    if (showVariablesDialog) {
        VariablesManagerDialog(
            vm = vm,
            onDismiss = { showVariablesDialog = false }
        )
    }
}

@Composable
private fun BenchmarkRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
    }
}

private fun StudioViewModel.toggleEnabledCompat(id: String) {
    macroEngine.toggleEnabled(id)
}
