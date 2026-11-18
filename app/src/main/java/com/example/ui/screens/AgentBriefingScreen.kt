package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.StudioViewModel
import com.example.agent.AgentStatus
import com.example.agent.AgentTargetMode
import com.example.agent.AiProviderKind
import com.example.nodes.BlockLanguage
import com.example.runtime.AndroidCustomizer
import com.example.services.FloatingAgentOverlayService
import com.example.services.ScreenCaptureService
import com.example.services.StudioAccessibilityService
import com.example.ui.theme.*

@Composable
fun AgentBriefingScreen(vm: StudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgListState = rememberLazyListState()

    val status by vm.agentStatus.collectAsStateWithLifecycle()
    val attachments by vm.briefAttachments.collectAsStateWithLifecycle()
    val messages by vm.agentMessages.collectAsStateWithLifecycle()
    val pendingPlan by vm.pendingPlan.collectAsStateWithLifecycle()
    val toolLog by vm.toolExecutor.actionLog.collectAsStateWithLifecycle()
    val observeStatus by vm.godotObserve.status.collectAsStateWithLifecycle()
    val observeLogs by vm.godotObserve.stepLogs.collectAsStateWithLifecycle()
    val fgPackage by StudioAccessibilityService.foregroundPackage.collectAsStateWithLifecycle()
    val lastSpokenPlan by vm.godotObserve.lastSpokenPlan.collectAsStateWithLifecycle()
    val pendingGodotConfirm by vm.godotObserve.pendingConfirmation.collectAsStateWithLifecycle()
    val captureActive by ScreenCaptureService.isActive.collectAsStateWithLifecycle()
    val accConnected by StudioAccessibilityService.isConnected.collectAsStateWithLifecycle()
    val overlayRunning by FloatingAgentOverlayService.isRunning.collectAsStateWithLifecycle()
    val isListening by vm.voiceController.isListening.collectAsStateWithLifecycle()
    val isSpeaking by vm.voiceController.isSpeaking.collectAsStateWithLifecycle()
    val lastTranscript by vm.voiceController.lastTranscript.collectAsStateWithLifecycle()
    val voiceError by vm.voiceController.voiceError.collectAsStateWithLifecycle()
    val aiTestResult by vm.aiTestResult.collectAsStateWithLifecycle()
    val speakingOnlyMode by vm.speakingOnlyMode.collectAsStateWithLifecycle()
    val aiConfig by vm.aiProvider.config.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    var accEnabledInSettings by remember { mutableStateOf(StudioAccessibilityService.isEnabledInSettings(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var inspectDiagText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accEnabledInSettings = StudioAccessibilityService.isEnabledInSettings(context)
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var ideaInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelInput by remember(aiConfig.model) { mutableStateOf(aiConfig.model) }
    var baseUrlInput by remember(aiConfig.customBaseUrl) { mutableStateOf(aiConfig.customBaseUrl) }
    var systemPromptInput by remember(aiConfig.customSystemPrompt) { mutableStateOf(aiConfig.customSystemPrompt) }
    var showAiConfig by rememberSaveable { mutableStateOf(!aiConfig.hasApiKey) }
    var universalGoal by rememberSaveable { mutableStateOf("Observe the foreground app and complete the next task step") }
    var speakBeforeAct by rememberSaveable { mutableStateOf(true) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showSendPreviewDialog by remember { mutableStateOf(false) }
    var activeSection by rememberSaveable { mutableStateOf("Briefing") }

    LaunchedEffect(messages.size, status) {
        if (messages.isNotEmpty()) {
            msgListState.animateScrollToItem(messages.lastIndex)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.voiceController.startListening { spoken ->
                if (speakingOnlyMode) {
                    vm.submitBriefToAgent(spoken)
                } else {
                    ideaInput = spoken
                }
                vm.setBanner("Heard: $spoken")
            }
        } else {
            vm.setBanner("Microphone permission denied.")
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.attachImageUri(uri) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.attachFileUri(uri) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val svcIntent = ScreenCaptureService.startIntent(context, res.resultCode, res.data!!)
            ContextCompat.startForegroundService(context, svcIntent)
            vm.setBanner("MediaProjection capture active (visible notification shown).")
        } else {
            vm.setBanner("Screen capture consent declined.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Agent Status + BYOK Config Toggle + Emergency Stop Header
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Image(
                        painter = painterResource(R.drawable.img_agent_core),
                        contentDescription = "AI Agent core emblem",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("BYOK AI Agent & Voice Overlay", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            "${aiConfig.provider.displayName} · ${aiConfig.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan
                        )
                        Text(
                            "${aiConfig.maskedKeyHint} · ${if (observeStatus != AgentStatus.IDLE) observeStatus.label else status.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (aiConfig.hasApiKey) EmeraldRun else AmberWarn
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showAiConfig = !showAiConfig },
                        modifier = Modifier.testTag("toggle_ai_config_button")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = "Configure AI provider & API key", tint = CyberCyan)
                    }
                    Button(
                        onClick = { vm.emergencyStopAll() },
                        modifier = Modifier.testTag("agent_emergency_stop"),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseError, contentColor = ObsidianBg)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("STOP")
                    }
                }
            }
        }

        // Expandable BYOK Provider, Model, API Key, Custom URL, & Polyglot Target Card
        AnimatedVisibility(visible = showAiConfig) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .testTag("ai_provider_config_card")
            ) {
                Column(
                    Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Any AI Provider (BYOK API Key)", style = MaterialTheme.typography.titleSmall, color = CyberCyan)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure {
                                        activeSection = "AI Studio Web"
                                        showAiConfig = false
                                    }
                                },
                                modifier = Modifier.testTag("ai_get_studio_key_btn")
                            ) { Text("Get AI Studio Key", color = EmeraldRun) }
                            TextButton(onClick = { showAiConfig = false }) { Text("Hide") }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AiProviderKind.entries) { p ->
                            FilterChip(
                                selected = aiConfig.provider == p,
                                onClick = { vm.aiProvider.selectProvider(p) },
                                label = { Text(p.displayName) },
                                modifier = Modifier.testTag("provider_chip_${p.id}")
                            )
                        }
                    }

                    if (aiConfig.provider == AiProviderKind.CUSTOM_OPENAI) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = {
                                baseUrlInput = it
                                vm.aiProvider.setCustomBaseUrl(it)
                            },
                            label = { Text("OpenAI-Compatible Endpoint URL (Groq/OpenRouter/Ollama)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ai_custom_url_input")
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(aiConfig.provider.models) { m ->
                            AssistChip(
                                onClick = {
                                    modelInput = m
                                    vm.aiProvider.selectModel(m)
                                },
                                label = { Text(m, color = if (aiConfig.model == m) CyberCyan else TextPrimary) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = {
                            modelInput = it
                            vm.aiProvider.selectModel(it)
                        },
                        label = { Text("Model ID (Any model supported by provider)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("ai_model_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Temperature: ${"%.2f".format(aiConfig.temperature)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan
                        )
                        Text(
                            if (aiConfig.temperature <= 0.3f) "Precise Code"
                            else if (aiConfig.temperature <= 0.9f) "Balanced"
                            else "Creative",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Slider(
                        value = aiConfig.temperature,
                        onValueChange = { vm.aiProvider.setTemperature(it) },
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth().testTag("ai_temperature_slider")
                    )

                    OutlinedTextField(
                        value = systemPromptInput,
                        onValueChange = {
                            systemPromptInput = it
                            vm.aiProvider.setCustomSystemPrompt(it)
                        },
                        label = { Text("System Instructions (Google AI Studio Style)") },
                        placeholder = { Text("e.g. Always write Godot 4 GDScript & Kotlin Compose...") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("ai_system_prompt_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("${aiConfig.provider.displayName} API Key (${aiConfig.provider.keyHint})") },
                            placeholder = { Text(aiConfig.maskedKeyHint) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("ai_api_key_input")
                        )
                        Button(
                            onClick = {
                                if (apiKeyInput.isBlank()) {
                                    vm.setBanner("Paste an API key first before saving.")
                                } else {
                                    vm.aiProvider.saveApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                    vm.setBanner("Saved ${aiConfig.provider.displayName} API key in encrypted Keystore vault.")
                                }
                            },
                            modifier = Modifier.testTag("ai_save_key_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = { vm.testAiConnection() },
                            modifier = Modifier.testTag("ai_test_key_button")
                        ) { Text("Test") }
                    }

                    aiTestResult?.let { res ->
                        Text(
                            res,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (res.startsWith("OK:")) EmeraldRun else if (res.startsWith("FAIL:")) RoseError else AmberWarn
                        )
                    }

                    Text("Generate Target & Output Language", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AgentTargetMode.entries) { mode ->
                            FilterChip(
                                selected = aiConfig.targetMode == mode,
                                onClick = { vm.aiProvider.selectTargetMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("agent_target_${mode.id}")
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(BlockLanguage.entries) { lang ->
                            FilterChip(
                                selected = aiConfig.targetLanguage == lang,
                                onClick = { vm.aiProvider.selectTargetLanguage(lang) },
                                label = { Text(lang.label) },
                                modifier = Modifier.testTag("agent_lang_${lang.id}")
                            )
                        }
                    }
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("agent_section_tabs_row")
        ) {
            items(listOf("Briefing", "AI Studio Web", "Foreground AI", "Tool Log (${toolLog.size})")) { tab ->
                val tagSuffix = when {
                    tab == "Briefing" -> "briefing"
                    tab == "AI Studio Web" -> "ai_studio_web"
                    tab == "Foreground AI" -> "foreground_ai"
                    else -> "tool_log"
                }
                FilterChip(
                    selected = activeSection == tab,
                    onClick = {
                        activeSection = tab
                        if (tab == "AI Studio Web" || tab == "Foreground AI") showAiConfig = false
                    },
                    label = { Text(tab) },
                    modifier = Modifier.testTag("agent_tab_$tagSuffix")
                )
            }
            item {
                FilterChip(
                    selected = speakingOnlyMode,
                    onClick = { vm.setSpeakingOnlyMode(!speakingOnlyMode) },
                    leadingIcon = {
                        Icon(
                            if (speakingOnlyMode) Icons.Filled.RecordVoiceOver else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(if (speakingOnlyMode) "Only Speaking: ON" else "Only Speaking") },
                    modifier = Modifier.testTag("toggle_speaking_only_mode")
                )
            }
            if (isSpeaking) {
                item {
                    AssistChip(
                        onClick = { vm.voiceController.stopSpeaking() },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, tint = RoseError, modifier = Modifier.size(16.dp)) },
                        label = { Text("Stop Voice", color = RoseError) }
                    )
                }
            }
        }

        when (activeSection) {
            "Briefing" -> {
                if (speakingOnlyMode) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Text(
                            "Only Speaking Mode Active — AI reads everything you send (messages, files, screenshots) and replies by voice only without modifying project code.",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldRun
                        )
                    }
                }
                LazyColumn(
                    state = msgListState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.isUser) SlateElevated else SlateCard
                            ),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (msg.isUser) "You" else "Automation Agent (${aiConfig.provider.displayName})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (msg.isUser) CyberCyan else NeonViolet
                                    )
                                    if (!msg.isUser) {
                                        IconButton(
                                            onClick = { vm.speakMessageAloud(msg.text) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Read reply aloud",
                                                tint = CyberCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                if (msg.attachmentNames.isNotEmpty()) {
                                    Text("Attached: ${msg.attachmentNames.joinToString()}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                                msg.plan?.let { plan ->
                                    HorizontalDivider(color = SlateBorder)
                                    plan.steps.forEachIndexed { idx, s ->
                                        Text("${idx + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                    }
                                    if (!plan.proposedSourceCode.isNullOrBlank()) {
                                        Text(
                                            plan.proposedSourceCode.take(500),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberCyan
                                        )
                                    }
                                    if (plan.requiredCapabilities.isNotEmpty()) {
                                        Text("Requires capabilities: ${plan.requiredCapabilities.joinToString { it.id }}", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                    }
                                }
                            }
                        }
                    }

                    if (status == AgentStatus.PLANNING) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${aiConfig.provider.displayName} (${aiConfig.model}) is thinking...", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    pendingPlan?.let {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.approvePendingPlan() },
                                    modifier = Modifier.testTag("approve_plan_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Approve & Apply")
                                }
                                OutlinedButton(onClick = { vm.cancelPendingPlan() }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }

                if (attachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(attachments, key = { it.id }) { att ->
                            InputChip(
                                selected = att.includeInSend,
                                onClick = { vm.toggleAttachmentIncluded(att.id) },
                                label = {
                                    val extra = buildString {
                                        if (att.redactedSecretsCount > 0) append(" (${att.redactedSecretsCount} redacted)")
                                        if (att.injectionWarnings.isNotEmpty()) append(" [!]")
                                    }
                                    Text("${att.displayName}$extra")
                                },
                                trailingIcon = {
                                    IconButton(onClick = { vm.removeAttachment(att.id) }, modifier = Modifier.size(18.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove ${att.displayName}", modifier = Modifier.size(14.dp))
                                    }
                                }
                            )
                        }
                    }
                    attachments.flatMap { it.injectionWarnings }.forEach { warn ->
                        Text(warn, style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                    }
                }

                // Composer Bar with Mic Voice Input + Attach + Send
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        IconButton(onClick = { showAttachMenu = true }, modifier = Modifier.testTag("agent_attach_button")) {
                            Icon(Icons.Filled.AddCircle, contentDescription = "Attach screenshot, file, or workspace item", tint = CyberCyan)
                        }
                        DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Image / Screenshot (Photo Picker)") },
                                onClick = {
                                    showAttachMenu = false
                                    runCatching {
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }.onFailure { err ->
                                        vm.setBanner("Photo Picker unavailable: ${err.localizedMessage ?: "No handler"}")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Capture Current Screen Frame") },
                                onClick = {
                                    showAttachMenu = false
                                    vm.attachCurrentScreenCapture()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("File / .stl / .zip (SAF)") },
                                onClick = {
                                    showAttachMenu = false
                                    runCatching {
                                        filePicker.launch(arrayOf("*/*"))
                                    }.onFailure { err ->
                                        vm.setBanner("File picker unavailable: ${err.localizedMessage ?: "No handler"}")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Attach Current StudioLang Code") },
                                onClick = {
                                    showAttachMenu = false
                                    vm.attachWorkspaceReference(vm.activeFileName.value, vm.sourceCode.value)
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isListening) {
                                vm.voiceController.stopListening()
                            } else if (vm.voiceController.hasMicPermission()) {
                                vm.voiceController.startListening { spoken ->
                                    if (speakingOnlyMode) {
                                        vm.submitBriefToAgent(spoken)
                                    } else {
                                        ideaInput = spoken
                                    }
                                }
                            } else {
                                runCatching {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }.onFailure { err ->
                                    vm.setBanner("Microphone permission prompt unavailable: ${err.localizedMessage}")
                                }
                            }
                        },
                        modifier = Modifier.testTag("agent_mic_button")
                    ) {
                        Icon(
                            if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Speak voice prompt",
                            tint = if (isListening) RoseError else EmeraldRun
                        )
                    }

                    OutlinedTextField(
                        value = ideaInput,
                        onValueChange = { ideaInput = it },
                        placeholder = {
                            Text(
                                if (speakingOnlyMode) "Send anything for AI to read & speak..."
                                else "Ask ${aiConfig.provider.displayName} (or tap Mic)..."
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("agent_brief_input"),
                        maxLines = 3
                    )

                    if (vm.toolExecutor.canUndo) {
                        IconButton(onClick = { vm.undoLastAgentChange() }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo agent action", tint = AmberWarn)
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (speakingOnlyMode) {
                                val sent = ideaInput
                                ideaInput = ""
                                vm.submitBriefToAgent(sent)
                            } else {
                                showSendPreviewDialog = true
                            }
                        },
                        modifier = Modifier.testTag("agent_send_preview_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to Agent")
                    }
                }
            }

            "AI Studio Web" -> {
                AiStudioWebPane(
                    modifier = Modifier.weight(1f),
                    onOpenAgentKeySetup = {
                        activeSection = "Briefing"
                        showAiConfig = true
                    }
                )
            }

            "Foreground AI" -> {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier.fillMaxWidth().border(1.dp, NeonViolet.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Floating Voice AI Editor (Any Foreground App)", style = MaterialTheme.typography.titleMedium, color = NeonViolet)
                                Text(
                                    "Operates Godot OR ANY active foreground app. Speaks what it will do FIRST via TTS, listens via Mic, and acts automatically via Accessibility + Screen Capture.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                val isAccActive = accConnected || accEnabledInSettings
                                Text(
                                    "Accessibility: ${if (accConnected) "Connected & Reading" else if (accEnabledInSettings) "Enabled (Waiting first window)" else "Off"} · App: ${fgPackage.ifBlank { "Idle" }} · Overlay: ${if (overlayRunning) "ACTIVE" else "Off"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAccActive) CyberCyan else AmberWarn
                                )
                                inspectDiagText?.let { diag ->
                                    Text(diag, style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                                }
                                if (lastTranscript.isNotBlank()) {
                                    Text("Last Voice Command: \"$lastTranscript\"", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                                }
                                if (lastSpokenPlan.isNotBlank()) {
                                    Text("Spoken Plan: \"$lastSpokenPlan\"", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                }
                                voiceError?.let { err ->
                                    Text(err, style = MaterialTheme.typography.labelSmall, color = RoseError)
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        AssistChip(
                                            onClick = {
                                                if (captureActive) ScreenCaptureService.stop(context)
                                                else {
                                                    runCatching {
                                                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                                                    }.onFailure { err ->
                                                        vm.setBanner("Screen capture prompt unavailable: ${err.localizedMessage ?: "Unsupported"}")
                                                    }
                                                }
                                            },
                                            label = { Text(if (captureActive) "Capture: ON" else "Screen Capture") }
                                        )
                                    }
                                    item {
                                        AssistChip(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                }.onFailure { err ->
                                                    vm.setBanner("Unable to open Accessibility Settings: ${err.localizedMessage ?: "Unsupported"}")
                                                }
                                            },
                                            label = { Text(if (isAccActive) "Accessibility: ON" else "Enable Accessibility") }
                                        )
                                    }
                                    item {
                                        AssistChip(
                                            onClick = { AndroidCustomizer.openOverlayConsent(context) },
                                            label = { Text(if (overlayGranted) "Overlay: ON" else "Overlay Perm") }
                                        )
                                    }
                                    item {
                                        AssistChip(
                                            onClick = {
                                                val svc = StudioAccessibilityService.instance
                                                val enabledSetting = StudioAccessibilityService.isEnabledInSettings(context)
                                                accEnabledInSettings = enabledSetting
                                                overlayGranted = Settings.canDrawOverlays(context)
                                                inspectDiagText = if (svc != null) {
                                                    val els = svc.dumpVisibleElements(60)
                                                    val pkg = StudioAccessibilityService.foregroundPackage.value.ifBlank { context.packageName }
                                                    vm.speakMessageAloud("Accessibility active on $pkg with ${els.size} readable elements.")
                                                    "OK: Bound & reading $pkg (${els.size} visible UI nodes)"
                                                } else if (enabledSetting) {
                                                    "Enabled in Settings; toggle service off/on once if Android delayed binding."
                                                } else {
                                                    "Not enabled yet — tap 'Enable Accessibility' and switch on Automation Studio."
                                                }
                                            },
                                            modifier = Modifier.testTag("foreground_test_inspect_btn"),
                                            label = { Text("Test Accessibility") }
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Speak Plan First (TTS before acting)", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                                    Switch(checked = speakBeforeAct, onCheckedChange = { speakBeforeAct = it })
                                }
                                OutlinedTextField(
                                    value = universalGoal,
                                    onValueChange = { universalGoal = it },
                                    label = { Text("Task / Edit Goal on Foreground App") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        Button(
                                            onClick = {
                                                if (!vm.voiceController.hasMicPermission()) {
                                                    runCatching {
                                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }.onFailure { err ->
                                                        vm.setBanner("Mic permission prompt unavailable: ${err.localizedMessage}")
                                                    }
                                                } else {
                                                    vm.startFloatingEditorMode()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary),
                                            modifier = Modifier.testTag("start_floating_editor_button")
                                        ) {
                                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (overlayRunning) "Listening Overlay" else "Floating AI + Mic")
                                        }
                                    }
                                    item {
                                        Button(
                                            onClick = { vm.startUniversalObserve(universalGoal, speakFirst = speakBeforeAct, withFloatingOverlay = Settings.canDrawOverlays(context)) },
                                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Run Loop")
                                        }
                                    }
                                    item {
                                        OutlinedButton(onClick = { vm.stopFloatingEditorMode() }) {
                                            Text("Stop", color = RoseError)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(observeLogs) { step ->
                        Card(colors = CardDefaults.cardColors(containerColor = SlateSurface)) {
                            Text("[#${step.stepIndex} ${step.phase}] ${step.message}", Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(toolLog, key = { it.id }) { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${entry.toolName} (${if (entry.success) "OK" else "FAIL"})", style = MaterialTheme.typography.labelMedium, color = if (entry.success) EmeraldRun else RoseError)
                                Text("Args: ${entry.argsRedacted}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(entry.output, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSendPreviewDialog) {
        val included = attachments.filter { it.includeInSend }
        AlertDialog(
            onDismissRequest = { showSendPreviewDialog = false },
            title = { Text("Send to ${aiConfig.provider.displayName} (${aiConfig.model})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target: ${aiConfig.targetMode.label} (${aiConfig.targetLanguage.label})", color = NeonViolet, style = MaterialTheme.typography.labelMedium)
                    Text("Prompt: ${ideaInput.ifBlank { "(Attachments only)" }}", color = TextPrimary)
                    Text("Included attachments: ${included.size} (${included.joinToString { it.displayName }.ifBlank { "None" }})", color = TextSecondary)
                    Text("Secrets are redacted and attachment contents are isolated inside <UNTRUSTED_ATTACHMENT_DATA>.", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    if (!aiConfig.hasApiKey) {
                        Text("Warning: No API key saved yet for ${aiConfig.provider.displayName}. Tap the Key icon above to save your API key.", color = RoseError, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val sent = ideaInput
                    ideaInput = ""
                    showSendPreviewDialog = false
                    vm.submitBriefToAgent(sent)
                }) { Text("Send to Agent") }
            },
            dismissButton = { TextButton(onClick = { showSendPreviewDialog = false }) { Text("Review") } }
        )
    }

    pendingGodotConfirm?.let { decision ->
        AlertDialog(
            onDismissRequest = { vm.godotObserve.confirmPending(false) },
            title = { Text("Confirm Destructive UI Action") },
            text = { Text("Agent wants to perform '${decision.actionKind}': ${decision.reason}") },
            confirmButton = { Button(onClick = { vm.godotObserve.confirmPending(true) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { vm.godotObserve.confirmPending(false) }) { Text("Cancel") } }
        )
    }
}
