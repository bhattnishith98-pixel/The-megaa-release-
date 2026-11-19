package com.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.monetization.MonetizationManager
import com.example.runtime.GodotBridgeReceiver
import com.example.services.StudioDeviceTriggerReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.ui.screens.*
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutomationStudioTheme {
                val vm: StudioViewModel = viewModel()
                AutomationStudioRoot(vm)
            }
        }
    }
}

@Composable
fun AutomationStudioRoot(vm: StudioViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var currentDestName by rememberSaveable { mutableStateOf(StudioDestination.STUDIO.name) }
    val currentDest = StudioDestination.entries.firstOrNull { it.name == currentDestName } ?: StudioDestination.STUDIO
    fun navigateTo(dest: StudioDestination) {
        currentDestName = dest.name
    }
    val banner by vm.statusBanner.collectAsStateWithLifecycle()
    val pendingConfirm by vm.pendingConfirmPrompt.collectAsStateWithLifecycle()
    val pendingPermission by vm.pendingPermissionRequest.collectAsStateWithLifecycle()
    val showFirstLaunchPerms by vm.showFirstLaunchPermissionsDialog.collectAsStateWithLifecycle()
    val monetizationState by vm.monetization.state.collectAsStateWithLifecycle()
    val ideLandscapeLock by vm.unifiedIde.landscapeLockEnabled.collectAsStateWithLifecycle()

    DisposableEffect(currentDest, ideLandscapeLock) {
        val activity = context as? Activity
        if (currentDest == StudioDestination.IDE && ideLandscapeLock) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var startupRuntimeLaunched by remember { mutableStateOf(false) }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.refreshAndroidTweaks()
        vm.resolvePermissionRequest(granted)
    }

    val startupPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        vm.refreshAndroidTweaks()
    }

    LaunchedEffect(pendingPermission) {
        val perm = pendingPermission ?: return@LaunchedEffect
        runCatching { runtimePermissionLauncher.launch(perm) }.onFailure {
            vm.resolvePermissionRequest(false)
        }
    }

    LaunchedEffect(showFirstLaunchPerms) {
        if (showFirstLaunchPerms && !startupRuntimeLaunched) {
            startupRuntimeLaunched = true
            // Let MainActivity finish its initial SurfaceSyncGroup transaction before launching system permission UI
            withFrameNanos { }
            delay(650L)
            val missing = vm.getAllRuntimePermissionsToRequest()
            if (missing.isNotEmpty()) {
                runCatching { startupPermissionsLauncher.launch(missing) }
            }
        }
    }

    LaunchedEffect(vm) {
        // Defer AdMob / WebView initialization past initial Window SurfaceSync
        withFrameNanos { }
        delay(1500L)
        vm.initAdsSdk()
    }

    DisposableEffect(lifecycleOwner, vm) {
        val appCtx = context.applicationContext
        runCatching { com.example.runtime.HardwareController.ensureSensorsListening(appCtx) }
        val triggerReceiver = runCatching { StudioDeviceTriggerReceiver.register(appCtx) }.getOrNull()
        val godotBridgeReceiver = runCatching {
            GodotBridgeReceiver.register(appCtx) { type, name, payload ->
                vm.unifiedIde.recordGodotBridgeEvent(type, name, payload)
            }
        }.getOrNull()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    vm.monetization.onForegroundChanged(true)
                    vm.refreshAndroidTweaks()
                    if (event == Lifecycle.Event.ON_RESUME) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { vm.unifiedIde.syncFromGodot47Disk(appCtx, onlyIfSessionWasActive = true) }
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> vm.monetization.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            triggerReceiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
            godotBridgeReceiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
            vm.monetization.onForegroundChanged(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (currentDest != StudioDestination.STUDIO) {
        BackHandler { navigateTo(StudioDestination.STUDIO) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(currentDest) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }
    LaunchedEffect(banner) {
        val msg = banner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearBanner()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isWide) {
                    NavigationBar(containerColor = SlateSurface) {
                        StudioDestination.entries.forEach { dest ->
                            val selected = currentDest == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest) },
                                icon = { Icon(iconForDest(dest, selected), contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_${dest.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (isWide) {
                    NavigationRail(containerColor = SlateSurface) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(6.dp))
                            StudioDestination.entries.forEach { dest ->
                                val selected = currentDest == dest
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTo(dest) },
                                    icon = { Icon(iconForDest(dest, selected), contentDescription = dest.label) },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }
                    VerticalDivider(color = SlateBorder)
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentDest) {
                        StudioDestination.STUDIO -> StudioDashboardScreen(
                            vm = vm,
                            onOpenEditor = { navigateTo(StudioDestination.NODES) },
                            onOpenAgent = { navigateTo(StudioDestination.AGENT) },
                            onOpenDocs = { navigateTo(StudioDestination.DOCS) },
                            onOpenIde = { navigateTo(StudioDestination.IDE) }
                        )
                        StudioDestination.IDE -> UnifiedIdeScreen(
                            vm = vm,
                            onOpenAgentConfig = { navigateTo(StudioDestination.AGENT) }
                        )
                        StudioDestination.NODES -> NodeAndCodeStudioScreen(vm = vm)
                        StudioDestination.AGENT -> AgentBriefingScreen(vm = vm)
                        StudioDestination.APPS -> AppBuilderScreen(
                            vm = vm,
                            onOpenInAndroidCodeStudio = {
                                vm.unifiedIde.setWorkspaceMode(com.example.ide.IdeWorkspaceMode.ANDROID_CODE_STUDIO)
                                vm.syncAppDesignToUnifiedIde()
                                navigateTo(StudioDestination.IDE)
                            }
                        )
                        StudioDestination.DOCS -> DocsScreen(
                            vm = vm,
                            onOpenInEditor = { navigateTo(StudioDestination.NODES) }
                        )
                    }
                }
            }
        }
    }

    // Interactive `confirm "..."` Safety Dialog from StudioVm
    pendingConfirm?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.resolveConfirm(false) },
            title = { Text("Confirm Automation Action", color = CyberCyan) },
            text = { Text(prompt, color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { vm.resolveConfirm(true) },
                    modifier = Modifier.testTag("vm_confirm_yes")
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.resolveConfirm(false) },
                    modifier = Modifier.testTag("vm_confirm_no")
                ) { Text("Cancel") }
            }
        )
    }

    // Periodic 2-3 Minute Rewarded Ad Prompt (dismissible until foreground limit)
    if (monetizationState.showPeriodicAdPrompt && !monetizationState.showMandatoryAdGate && !monetizationState.isPlayingTestAd) {
        val gateMinutes = (monetizationState.profile.effectiveGateSeconds() / 60L).coerceAtLeast(1L)
        AlertDialog(
            onDismissRequest = { vm.monetization.dismissPeriodicPromptLater() },
            title = {
                Text("Rewarded Ad Break", color = CyberCyan)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Watch a short rewarded ad to reset your ${gateMinutes}-minute foreground timer, or remove ads with the ₹${monetizationState.profile.upi.subscriptionAmountInr}/month plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        "Foreground timer: ${monetizationState.foregroundSecondsWithoutAd / 60}m ${monetizationState.foregroundSecondsWithoutAd % 60}s / ${gateMinutes}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarn
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.playRewardedAd(context as? Activity) },
                    modifier = Modifier.testTag("periodic_watch_ad_button")
                ) { Text("Watch Ad") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            vm.monetization.activateMonthlySubscriptionTest()
                            vm.setBanner("₹${monetizationState.profile.upi.subscriptionAmountInr}/month Pro Ad-Free activated for ${monetizationState.profile.upi.subscriptionDays} days (Test Mode).")
                        },
                        modifier = Modifier.testTag("periodic_subscribe_button")
                    ) { Text("₹${monetizationState.profile.upi.subscriptionAmountInr}/mo Ad-Free") }
                    TextButton(onClick = { vm.monetization.dismissPeriodicPromptLater() }) {
                        Text("Later")
                    }
                }
            }
        )
    }

    // Mandatory 20-Minute Foreground Gate ("This is Ad Time": only Watch Ad to continue or Close App)
    if (monetizationState.showMandatoryAdGate && !monetizationState.isPlayingTestAd) {
        val gateMinutes = (monetizationState.profile.effectiveGateSeconds() / 60L).coerceAtLeast(1L)
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            modifier = Modifier.testTag("mandatory_ad_time_dialog"),
            title = { Text("This is Ad Time", color = AmberWarn) },
            text = {
                Text(
                    "You have used Automation Studio in the foreground for $gateMinutes minutes without viewing a rewarded ad. Watch an ad to continue using the app for more time, otherwise the app will be closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.playRewardedAd(context as? Activity) },
                    modifier = Modifier.testTag("ad_gate_watch_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                ) { Text("See Ad to Continue") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        (context as? Activity)?.finishAffinity()
                    },
                    modifier = Modifier.testTag("ad_gate_close_app_button")
                ) { Text("Close App", color = RoseError) }
            }
        )
    }

    // Active 5-Second Test Rewarded Ad Player
    if (monetizationState.isPlayingTestAd) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            modifier = Modifier.testTag("test_rewarded_ad_player"),
            title = { Text("Rewarded Ad Playing", color = CyberCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sponsored Rewarded Break", style = MaterialTheme.typography.labelMedium, color = EmeraldRun)
                            Text(
                                "Reward unlocks +20 minutes of foreground app time in ${monetizationState.testAdSecondsRemaining}s...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            (MonetizationManager.TEST_AD_DURATION_SECONDS - monetizationState.testAdSecondsRemaining).toFloat() /
                                MonetizationManager.TEST_AD_DURATION_SECONDS.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }
}

private fun iconForDest(dest: StudioDestination, selected: Boolean) = when (dest) {
    StudioDestination.STUDIO -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
    StudioDestination.IDE -> if (selected) Icons.Filled.Code else Icons.Outlined.Code
    StudioDestination.NODES -> if (selected) Icons.Filled.AccountTree else Icons.Outlined.AccountTree
    StudioDestination.AGENT -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
    StudioDestination.APPS -> if (selected) Icons.Filled.PhoneAndroid else Icons.Outlined.PhoneAndroid
    StudioDestination.DOCS -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
}
