package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ide.*
import com.example.ui.theme.*

@Composable
fun ReposAndBridgesDrawerPane(
    mountedRepos: List<MountedRepoStatus>,
    bridges: List<RepoBridgeDescriptor>,
    downloadStatus: Map<UpstreamRepoKind, String> = emptyMap(),
    selectedImportMode: RepoImportMode = RepoImportMode.FULL_SOURCE_AND_COMPILE,
    inAppVaultZips: List<InAppVaultZipEntry> = emptyList(),
    onSelectImportMode: (RepoImportMode) -> Unit = {},
    onMountRepoZipClick: (UpstreamRepoKind?) -> Unit,
    onDownloadRepoZipClick: (UpstreamRepoKind) -> Unit = {},
    onRescanFoldersClick: () -> Unit,
    onOpenSampleFile: (UpstreamRepoKind) -> Unit,
    onRunBridgeWorkflow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("repos_bridges_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("IMPORT MODE", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
            Surface(color = CyberCyan.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Full Source + Compile", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                    Text("Complete ZIP extraction · no file-count cap · supported text files indexed for the IDE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onMountRepoZipClick(null) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 42.dp).testTag("mount_repo_zip_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                ) {
                    Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("In-App ZIP (${inAppVaultZips.size})", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onRescanFoldersClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 42.dp).testTag("rescan_external_repos_btn")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan local folders", modifier = Modifier.size(16.dp), tint = EmeraldRun)
                }
            }
        }

        item {
            Text(
                "UPSTREAM REPOSITORIES (3)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        items(mountedRepos, key = { it.kind.id }) { repo ->
            val mounted = repo.state == RepoMountState.USER_REPO_MOUNTED
            val dlProgress = downloadStatus[repo.kind].orEmpty()
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (mounted) EmeraldRun.copy(alpha = 0.7f) else SlateBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .testTag("repo_card_${repo.kind.id}")
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repo.kind.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = if (mounted) EmeraldRun.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (mounted) "${repo.indexedFileCount} files" else repo.state.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mounted) EmeraldRun else CyberCyan,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text("${repo.kind.repoSlug} · ${repo.kind.archiveFileName}", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                    Text(
                        "Layers: ${repo.detectedLayers.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (dlProgress.isNotBlank()) {
                        Text(dlProgress, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { onMountRepoZipClick(repo.kind) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 36.dp).testTag("mount_btn_${repo.kind.id}")
                        ) {
                            Text("In-App Upload", style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(
                            onClick = { onDownloadRepoZipClick(repo.kind) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 36.dp).testTag("fetch_github_zip_${repo.kind.id}")
                        ) {
                            Text("Fetch GitHub", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (mounted) {
                        Button(
                            onClick = { onOpenSampleFile(repo.kind) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).testTag("open_repo_btn_${repo.kind.id}"),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                        ) {
                            Text("Open Mounted Source", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Hub, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonViolet)
                Text("WORKFLOW BRIDGES (3)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        items(bridges, key = { it.id }) { bridge ->
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonViolet.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .testTag("bridge_card_${bridge.id}")
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(bridge.title, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    Text("${bridge.sourceLayer} -> ${bridge.targetLayer}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(bridge.lastStatus, style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                    Button(
                        onClick = { onRunBridgeWorkflow(bridge.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).testTag("run_bridge_${bridge.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run & Verify Bridge", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun RepoImportChoiceAndVaultDialog(
    initialTargetKind: UpstreamRepoKind?,
    selectedMode: RepoImportMode,
    inAppVaultZips: List<InAppVaultZipEntry>,
    downloadStatus: Map<UpstreamRepoKind, String> = emptyMap(),
    onSelectMode: (RepoImportMode) -> Unit,
    onMountVaultZip: (InAppVaultZipEntry, RepoImportMode) -> Unit,
    onGenerateStarterZipInApp: (UpstreamRepoKind, RepoImportMode) -> Unit,
    onFetchGitHubUrl: (UpstreamRepoKind, String, RepoImportMode) -> Unit,
    onAddDirectFileInApp: (UpstreamRepoKind, String, String) -> Unit,
    onOptionalExternalFilePicker: (UpstreamRepoKind?) -> Unit,
    onDismiss: () -> Unit
) {
    var targetKind by remember { mutableStateOf(initialTargetKind ?: UpstreamRepoKind.GODOT) }
    var customZipUrl by remember(targetKind) { mutableStateOf(targetKind.archiveDownloadUrl) }
    var directFilePath by remember { mutableStateOf("src/custom_plugin.kt") }
    var directFileBody by remember { mutableStateOf("// Uploaded directly inside Automation Studio\nfun pluginEntry() = println(\"Ready\")\n") }
    var inlineFeedback by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(min = 290.dp, max = 580.dp)
                .heightIn(max = 365.dp)
                .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Repository Setup & In-App ZIP Vault",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan
                )
                val liveDownload = downloadStatus[targetKind].orEmpty()
                val bannerText = liveDownload.ifBlank { inlineFeedback.orEmpty() }
                if (bannerText.isNotBlank()) {
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            bannerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldRun,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("1. Import mode (fixed):", style = MaterialTheme.typography.labelMedium, color = AmberWarn)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberCyan.copy(alpha = 0.16f)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan, RoundedCornerShape(8.dp))
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Full Source + Compile", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                            Text("Extract every archive file. Index supported text files for editor search and preview.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }

                    HorizontalDivider(color = SlateBorder)
                    Text("2. Target Repository:", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(UpstreamRepoKind.entries) { kind ->
                            FilterChip(
                                selected = targetKind == kind,
                                onClick = { targetKind = kind },
                                label = { Text(kind.displayName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Text(
                        "3. In-App ZIP & File Vault (No external File Manager needed):",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldRun
                    )
                    if (inAppVaultZips.isEmpty()) {
                        Text(
                            "No ZIPs in in-app vault yet. Stage a built-in ZIP or fetch GitHub below.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    } else {
                        inAppVaultZips.forEach { entry ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            ) {
                                Row(
                                    Modifier.padding(8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                        Text("${entry.relativePath} · ${entry.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    }
                                    Button(
                                        onClick = {
                                            onMountVaultZip(entry, selectedMode)
                                            inlineFeedback = "Loaded ${entry.name} (${selectedMode.title}). Tap Close when ready."
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
                                    ) { Text("Load", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            onGenerateStarterZipInApp(targetKind, selectedMode)
                            inlineFeedback = "Staged & mounted ${targetKind.archiveFileName} (${selectedMode.title})."
                        },
                        modifier = Modifier.fillMaxWidth().testTag("stage_in_app_zip_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
                    ) {
                        Text("Stage & Mount ${targetKind.archiveFileName} Inside App", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedTextField(
                        value = customZipUrl,
                        onValueChange = { customZipUrl = it },
                        label = { Text("Direct .ZIP / GitHub Archive URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilledTonalButton(
                        onClick = {
                            if (customZipUrl.isBlank()) {
                                inlineFeedback = "Enter a valid .zip URL first."
                            } else {
                                onFetchGitHubUrl(targetKind, customZipUrl, selectedMode)
                                inlineFeedback = "Downloading ${targetKind.archiveFileName} into In-App Vault..."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("fetch_url_in_app_btn")
                    ) {
                        Text("Download ZIP Straight Into App Vault", style = MaterialTheme.typography.labelSmall)
                    }

                    HorizontalDivider(color = SlateBorder)
                    Text("Or Upload / Paste File Directly Inside App:", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    OutlinedTextField(
                        value = directFilePath,
                        onValueChange = { directFilePath = it },
                        label = { Text("Relative file path inside ${targetKind.id}/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = directFileBody,
                        onValueChange = { directFileBody = it },
                        label = { Text("File code / contents") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            if (directFilePath.isBlank()) {
                                inlineFeedback = "Enter a file path first (e.g. src/plugin.kt)."
                            } else {
                                onAddDirectFileInApp(targetKind, directFilePath, directFileBody)
                                inlineFeedback = "Saved ${targetKind.id}/$directFilePath directly inside app."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("upload_direct_file_in_app_btn")
                    ) {
                        Text("Save File Directly Inside App", style = MaterialTheme.typography.labelSmall)
                    }

                    TextButton(
                        onClick = { onOptionalExternalFilePicker(targetKind) },
                        modifier = Modifier.fillMaxWidth().testTag("saf_optional_picker_btn")
                    ) {
                        Text("Import ZIP from System File Manager (Optional)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
