package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ide.*
import com.example.ui.theme.*

@Composable
fun ProjectManagerDialog(
    activeProject: IdeProjectDescriptor,
    recentProjects: List<IdeProjectDescriptor>,
    syncState: ProjectSyncState,
    syncMessage: String,
    onDismiss: () -> Unit,
    onOpenDeviceFolder: () -> Unit,
    onImportZipProject: () -> Unit,
    onCloneGithubRepo: (url: String, branch: String?) -> Unit,
    onCreateNewProject: (name: String, template: ProjectTemplateKind) -> Unit,
    onSwitchProject: (IdeProjectDescriptor) -> Unit,
    onExportProjectZip: (IdeProjectDescriptor) -> Unit,
    onDeleteProject: (IdeProjectDescriptor) -> Unit
) {
    var githubUrl by remember { mutableStateOf("") }
    var githubBranch by remember { mutableStateOf("") }
    var newProjectName by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(ProjectTemplateKind.GODOT_2D) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .testTag("project_manager_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Projects & Folders", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            "Open any device folder, ZIP, GitHub repo, or in-app workspace",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("project_manager_close_btn")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close project manager", tint = TextSecondary)
                    }
                }

                // Active Project Status Banner
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldRun.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "ACTIVE · ${activeProject.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = EmeraldRun,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${activeProject.detectedType.label} · ${activeProject.sourceKind.badge} · ${activeProject.fileCount} files",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan
                            )
                            Text(
                                syncMessage.ifBlank { syncState.label },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (syncState) {
                                    ProjectSyncState.ERROR -> RoseError
                                    ProjectSyncState.SAVING, ProjectSyncState.SCANNING -> AmberWarn
                                    else -> TextSecondary
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (syncState == ProjectSyncState.SCANNING || syncState == ProjectSyncState.SAVING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = CyberCyan
                            )
                        } else {
                            OutlinedButton(
                                onClick = { onExportProjectZip(activeProject) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("project_export_active_zip_btn")
                            ) {
                                Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("Export ZIP", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("project_manager_scroll_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Open Device Folder (SAF Direct Write-Back) & Import Any ZIP
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onDismiss()
                                    onOpenDeviceFolder()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("project_open_folder_btn")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Folder", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = {
                                    onDismiss()
                                    onImportZipProject()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("project_import_zip_btn")
                            ) {
                                Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import .ZIP", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // 2. Clone Any GitHub Repository URL
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Clone Any GitHub Repository", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                OutlinedTextField(
                                    value = githubUrl,
                                    onValueChange = { githubUrl = it },
                                    placeholder = { Text("https://github.com/owner/repo or owner/repo") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.fillMaxWidth().testTag("project_github_url_input")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = githubBranch,
                                        onValueChange = { githubBranch = it },
                                        placeholder = { Text("Branch (auto)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("project_github_branch_input")
                                    )
                                    Button(
                                        onClick = {
                                            if (githubUrl.isNotBlank()) {
                                                onCloneGithubRepo(githubUrl, githubBranch.ifBlank { null })
                                                githubUrl = ""
                                                githubBranch = ""
                                                onDismiss()
                                            }
                                        },
                                        enabled = githubUrl.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg),
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("project_clone_github_btn")
                                    ) {
                                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Clone", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    // 3. Create New In-App Project Workspace
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("New In-App Project", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                OutlinedTextField(
                                    value = newProjectName,
                                    onValueChange = { newProjectName = it },
                                    placeholder = { Text("Project name (e.g. SpaceRunner, MyApp)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("project_new_name_input")
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(ProjectTemplateKind.entries, key = { it.id }) { tpl ->
                                        FilterChip(
                                            selected = selectedTemplate == tpl,
                                            onClick = { selectedTemplate = tpl },
                                            label = { Text(tpl.label, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.testTag("project_template_${tpl.id}")
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        onCreateNewProject(newProjectName, selectedTemplate)
                                        newProjectName = ""
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SlateElevated, contentColor = CyberCyan),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("project_create_btn")
                                ) {
                                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Create & Open Workspace", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // 4. Saved & Recent Projects Switcher
                    item {
                        Text(
                            "Recent & Saved Workspaces (${recentProjects.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = CyberCyan
                        )
                    }

                    items(recentProjects, key = { it.id }) { proj ->
                        val isActive = proj.id == activeProject.id
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (isActive) SlateElevated else SlateCard),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isActive) CyberCyan else SlateBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    onSwitchProject(proj)
                                    onDismiss()
                                }
                                .testTag("recent_project_${proj.id}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = when (proj.sourceKind) {
                                        ProjectSourceKind.SAF_EXTERNAL_FOLDER -> Icons.Filled.Folder
                                        ProjectSourceKind.GITHUB_REPO -> Icons.Filled.Hub
                                        ProjectSourceKind.ZIP_IMPORT -> Icons.Filled.FolderZip
                                        else -> Icons.Filled.Terminal
                                    },
                                    contentDescription = proj.sourceKind.badge,
                                    tint = if (isActive) EmeraldRun else CyberCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        proj.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${proj.detectedType.label} · ${proj.sourceKind.badge} · ${proj.fileCount} files",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onExportProjectZip(proj) },
                                    modifier = Modifier.size(48.dp).testTag("project_export_zip_${proj.id}")
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = "Export ${proj.name} ZIP", tint = CyberCyan, modifier = Modifier.size(18.dp))
                                }
                                if (proj.id != UniversalProjectManager.DEFAULT_PROJECT_ID) {
                                    IconButton(
                                        onClick = { onDeleteProject(proj) },
                                        modifier = Modifier.size(48.dp).testTag("project_remove_${proj.id}")
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove ${proj.name}", tint = RoseError, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
