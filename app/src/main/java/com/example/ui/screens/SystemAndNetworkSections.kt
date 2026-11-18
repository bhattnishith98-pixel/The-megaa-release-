package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.StudioViewModel
import com.example.runtime.*
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AndroidControlPanelContent(vm: StudioViewModel) {
    val context = LocalContext.current
    val tweaks by vm.androidTweaks.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    val selectedPkg by vm.selectedAppPackage.collectAsState()
    val selectedPerms by vm.selectedAppPerms.collectAsState()
    val privOutput by vm.privilegeCommandOutput.collectAsState()
    val privStatus = remember { vm.inspectPrivilegeStatus() }

    var brightnessSlider by remember(tweaks.brightness) { mutableFloatStateOf(tweaks.brightness.toFloat()) }
    var volumeSlider by remember(tweaks.mediaVolume) { mutableFloatStateOf(tweaks.mediaVolume.toFloat()) }
    var customDpi by remember { mutableStateOf("420") }
    var settingTable by remember { mutableStateOf("system") }
    var settingKey by remember { mutableStateOf("screen_off_timeout") }
    var settingVal by remember { mutableStateOf("60000") }
    var preferRoot by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Standard Android Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Standard Android Customization", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                Text("Media Volume: ${volumeSlider.toInt()} / ${tweaks.maxMediaVolume}", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                Slider(
                    value = volumeSlider,
                    onValueChange = { volumeSlider = it },
                    onValueChangeFinished = { AndroidCustomizer.setStandardMediaVolume(context, volumeSlider.toInt()) },
                    valueRange = 0f..tweaks.maxMediaVolume.coerceAtLeast(1).toFloat()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness (${brightnessSlider.toInt()}/255)", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    if (!tweaks.canWriteSettings) {
                        TextButton(onClick = { AndroidCustomizer.openWriteSettingsConsent(context) }) { Text("Grant Write Settings") }
                    }
                }
                Slider(
                    value = brightnessSlider,
                    onValueChange = { brightnessSlider = it },
                    onValueChangeFinished = {
                        val ok = AndroidCustomizer.setStandardBrightness(context, brightnessSlider.toInt())
                        if (!ok) vm.setBanner("Grant WRITE_SETTINGS in Android Settings to adjust brightness.")
                    },
                    valueRange = 10f..255f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { AndroidCustomizer.openOverlayConsent(context) },
                        label = { Text("Overlay: ${if (tweaks.canDrawOverlays) "Granted" else "Tap to Grant"}") }
                    )
                    AssistChip(
                        onClick = { vm.refreshAndroidTweaks() },
                        label = { Text("Refresh State") }
                    )
                }
            }
        }

        // Mobile On/Off, PIN Unlock, Custom Touch & SMS Trigger Controls
        val lastSms by com.example.services.StudioTriggerBus.lastSms.collectAsState()
        val recentEvents by com.example.services.StudioTriggerBus.recentEvents.collectAsState()
        var torchOn by remember { mutableStateOf(false) }
        val smsAccessOk = remember(tweaks) { com.example.services.StudioNotificationTriggerService.isNotificationAccessEnabled(context) }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mobile On/Off · PIN Unlock · SMS & Touch Triggers", style = MaterialTheme.typography.titleMedium, color = EmeraldRun)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilledTonalButton(
                            onClick = {
                                torchOn = !torchOn
                                vm.runPrivilegedAction(if (torchOn) "Flashlight ON" else "Flashlight OFF") {
                                    AndroidCustomizer.setFlashlight(context, torchOn)
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text(if (torchOn) "Flash OFF" else "Flash ON") }
                    }
                    item {
                        OutlinedButton(
                            onClick = { vm.runPrivilegedAction("Wi-Fi ON") { AndroidCustomizer.setWifiEnabled(context, true) } },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Wi-Fi") }
                    }
                    item {
                        OutlinedButton(
                            onClick = { vm.runPrivilegedAction("Mobile Data") { AndroidCustomizer.setMobileDataEnabled(context, true) } },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Data") }
                    }
                    item {
                        OutlinedButton(
                            onClick = { vm.runPrivilegedAction("Lock Screen") { AndroidCustomizer.performPowerAction(context, "lock") } },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Lock") }
                    }
                    item {
                        OutlinedButton(
                            onClick = { com.example.services.StudioNotificationTriggerService.openNotificationAccessSettings(context) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text(if (smsAccessOk) "SMS Access: Ready" else "Enable SMS Triggers") }
                    }
                }
                Text(
                    "Last SMS: ${lastSms.sender.ifBlank { "None" }} ${if (lastSms.otpCode.isNotBlank()) "· OTP: ${lastSms.otpCode}" else ""} | Keyguard Locked=${com.example.services.StudioTriggerBus.isDeviceLocked(context)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (recentEvents.isNotEmpty()) {
                    Text(
                        "Latest Trigger: ${recentEvents.first().summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan
                    )
                }
            }
        }

        // Shizuku & Root System Tweaks
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, NeonViolet.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Shizuku & Root System Editor", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Root", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Switch(checked = preferRoot, onCheckedChange = { preferRoot = it })
                    }
                }
                Text(
                    "Shizuku=${privStatus.shizukuInstalled} (rish=${privStatus.shizukuRishPresent}) · Root=${privStatus.rootAvailable}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (privStatus.shizukuInstalled || privStatus.rootAvailable) EmeraldRun else AmberWarn
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customDpi,
                        onValueChange = { customDpi = it.filter { c -> c.isDigit() } },
                        label = { Text("Display DPI") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val dpi = customDpi.toIntOrNull() ?: 420
                            vm.runPrivilegedAction("wm density $dpi") { AndroidCustomizer.setWmDensity(context, dpi, preferRoot) }
                        },
                        modifier = Modifier.testTag("apply_dpi_button")
                    ) { Text("Set DPI") }
                    OutlinedButton(onClick = {
                        vm.runPrivilegedAction("Reset Display") { AndroidCustomizer.resetDisplayMetrics(context, preferRoot) }
                    }) { Text("Reset") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system", "secure", "global").forEach { tbl ->
                        FilterChip(selected = settingTable == tbl, onClick = { settingTable = tbl }, label = { Text(tbl) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = settingKey, onValueChange = { settingKey = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = settingVal, onValueChange = { settingVal = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        vm.runPrivilegedAction("settings put $settingTable $settingKey") {
                            AndroidCustomizer.setSystemSetting(context, settingTable, settingKey, settingVal, preferRoot)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("apply_system_setting_button")
                ) { Text("Apply System Setting (Shizuku / Root)") }

                privOutput?.let { out ->
                    Text(out, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                }
            }
        }

        // Installed App Permission Inspector & Controller
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("App Permissions Controller (${selectedPerms.size})", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    TextButton(onClick = { vm.refreshInstalledApps() }) { Text("Scan Apps") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(installedApps.take(25), key = { it.packageName }) { app ->
                        FilterChip(
                            selected = selectedPkg == app.packageName,
                            onClick = { vm.selectAppForPermissions(app.packageName) },
                            label = { Text(app.label.take(18)) }
                        )
                    }
                }
                Text("Target: $selectedPkg", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                selectedPerms.take(12).forEach { perm ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            perm.permission.removePrefix("android.permission."),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (perm.granted) EmeraldRun else TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                vm.runPrivilegedAction("Grant") { AndroidCustomizer.grantPermission(context, selectedPkg, perm.permission, preferRoot) }
                            }) { Text("Grant", color = EmeraldRun) }
                            TextButton(onClick = {
                                vm.runPrivilegedAction("Revoke") { AndroidCustomizer.revokePermission(context, selectedPkg, perm.permission, preferRoot) }
                            }) { Text("Revoke", color = RoseError) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkSharePanelContent(
    vm: StudioViewModel,
    scope: CoroutineScope
) {
    val serverRunning by vm.networkShareManager.serverRunning.collectAsState()
    val port by vm.networkShareManager.listeningPort.collectAsState()
    val ips by vm.networkShareManager.localIps.collectAsState()
    val logs by vm.networkShareManager.transferLogs.collectAsState()
    val activeFile by vm.activeFileName.collectAsState()
    val sourceCode by vm.sourceCode.collectAsState()

    var targetHost by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf(NetworkShareManager.DEFAULT_PORT.toString()) }
    var customFileName by remember { mutableStateOf("shared_script.stl") }
    var customPayload by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("IPv4 / IPv6 Dual-Stack Data Sharing", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                    TextButton(onClick = { vm.networkShareManager.refreshLocalIps() }) { Text("Refresh IPs") }
                }
                Text("IPv4: ${ips.ipv4Addresses.joinToString()}", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                Text("IPv6: ${ips.ipv6Addresses.joinToString()}", style = MaterialTheme.typography.labelSmall, color = NeonViolet)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (serverRunning) vm.networkShareManager.stopReceiverServer()
                            else vm.networkShareManager.startReceiverServer(scope, targetPort.toIntOrNull() ?: NetworkShareManager.DEFAULT_PORT)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serverRunning) RoseError else EmeraldRun,
                            contentColor = ObsidianBg
                        ),
                        modifier = Modifier.testTag("toggle_net_server_button")
                    ) {
                        Icon(if (serverRunning) Icons.Filled.Stop else Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (serverRunning) "Stop Receiver (:$port)" else "Start IPv4/IPv6 Receiver")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send Script, .stl.lib, or Data over IPv4 / IPv6", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { targetHost = ips.ipv4Addresses.firstOrNull() ?: "127.0.0.1" }, label = { Text("Self IPv4") })
                    AssistChip(onClick = { targetHost = ips.ipv6Addresses.firstOrNull() ?: "::1" }, label = { Text("Self IPv6") })
                    AssistChip(onClick = { targetHost = "::1" }, label = { Text("::1 Loopback") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = targetHost,
                        onValueChange = { targetHost = it },
                        label = { Text("Target IPv4 or IPv6") },
                        singleLine = true,
                        modifier = Modifier.weight(2f).testTag("net_target_host_input")
                    )
                    OutlinedTextField(
                        value = targetPort,
                        onValueChange = { targetPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = customFileName,
                    onValueChange = { customFileName = it },
                    label = { Text("Filename (e.g. mylib.stl.lib or main.stl)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customPayload,
                    onValueChange = { customPayload = it },
                    label = { Text("Payload (leave empty to send active '$activeFile')") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            val nameToSend = customFileName.ifBlank { activeFile }
                            val bytes = (customPayload.ifBlank { sourceCode }).toByteArray(Charsets.UTF_8)
                            val entry = vm.networkShareManager.sendPayload(
                                host = targetHost,
                                port = targetPort.toIntOrNull() ?: NetworkShareManager.DEFAULT_PORT,
                                fileName = nameToSend,
                                payload = bytes
                            )
                            vm.setBanner("${entry.protocol}: ${entry.message}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("net_send_payload_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send via IPv4 / IPv6")
                }
            }
        }

        if (logs.isNotEmpty()) {
            Text("Recent IPv4/IPv6 Transfers (${logs.size})", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
            logs.take(10).forEach { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (entry.ok) EmeraldRun.copy(alpha = 0.4f) else RoseError.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("[${entry.direction} ${entry.protocol}] ${entry.fileName} -> ${entry.peer}", style = MaterialTheme.typography.labelMedium, color = if (entry.ok) EmeraldRun else RoseError)
                        Text(entry.message, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    }
                }
            }
        }
    }
}
