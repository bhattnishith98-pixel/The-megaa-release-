package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ide.*
import com.example.ui.theme.*

@Composable
private fun IdeModalOverlay(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
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
                .fillMaxWidth(0.92f)
                .widthIn(min = 280.dp, max = 560.dp)
                .heightIn(max = 360.dp)
                .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column {
                    Text(title, color = CyberCyan, style = MaterialTheme.typography.titleMedium)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    if (dismissButton != null && confirmButton != null) {
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton?.invoke()
                }
            }
        }
    }
}

@Composable
fun AttachScriptDialog(
    node: GodotSceneNode,
    onDismiss: () -> Unit,
    onConfirmAttach: (ScriptLanguage, ScriptTemplateKind) -> Unit
) {
    var selectedLang by remember { mutableStateOf(node.scriptLanguage ?: ScriptLanguage.GDSCRIPT) }
    var selectedTemplate by remember { mutableStateOf(ScriptTemplateKind.MOVEMENT) }

    IdeModalOverlay(
        title = "Attach Script to ${node.name}",
        subtitle = "Node Type: ${node.type.label} · Opens directly in VS Code pane",
        onDismiss = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirmAttach(selectedLang, selectedTemplate) },
                modifier = Modifier.testTag("confirm_attach_script_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
            ) {
                Text("Create & Open in VS Code")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Text("Select Language (All supported in Godot):", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ScriptLanguage.entries) { lang ->
                FilterChip(
                    selected = selectedLang == lang,
                    onClick = { selectedLang = lang },
                    label = { Text("${lang.badge} · ${lang.label}") },
                    modifier = Modifier.testTag("attach_lang_${lang.name.lowercase()}")
                )
            }
        }
        Text("Starter Template:", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
        ScriptTemplateKind.entries.forEach { tmpl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedTemplate = tmpl }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedTemplate == tmpl, onClick = { selectedTemplate = tmpl })
                Text(tmpl.label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun AddGodotNodeDialog(
    onDismiss: () -> Unit,
    onAdd: (GodotNodeType, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(GodotNodeType.SPRITE_2D) }
    var nodeName by remember { mutableStateOf("HeroSprite") }

    IdeModalOverlay(
        title = "Add Godot 2D Scene Node",
        onDismiss = onDismiss,
        confirmButton = {
            Button(
                onClick = { onAdd(selectedType, nodeName) },
                modifier = Modifier.testTag("confirm_add_node_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = ObsidianBg)
            ) { Text("Spawn Node") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        OutlinedTextField(
            value = nodeName,
            onValueChange = { nodeName = it },
            label = { Text("Node Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("add_node_name_input")
        )
        Text("Node Type:", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        GodotNodeType.entries.forEach { nt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedType = nt
                        nodeName = nt.label
                    }
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedType == nt, onClick = {
                    selectedType = nt
                    nodeName = nt.label
                })
                Text(nt.label, color = TextPrimary)
            }
        }
    }
}

@Composable
fun NewIdeFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var path by remember { mutableStateOf("scripts/custom_logic.stl") }
    var lang by remember { mutableStateOf(ScriptLanguage.STUDIOLANG) }

    IdeModalOverlay(
        title = "New Workspace File",
        onDismiss = onDismiss,
        confirmButton = {
            Button(onClick = {
                val starter = PolyglotGodotCompiler.starterScript(lang, GodotNodeType.NODE_2D, ScriptTemplateKind.VOICE_REACTIVE, "Player")
                onCreate(path, starter)
            }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Relative File Path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ScriptLanguage.entries) { item ->
                FilterChip(
                    selected = lang == item,
                    onClick = {
                        lang = item
                        val base = path.substringBeforeLast('.')
                        path = "$base${item.extension}"
                    },
                    label = { Text(item.badge) }
                )
            }
        }
    }
}

@Composable
fun CommandPaletteDialog(
    commands: List<IdeCommandItem>,
    onDismiss: () -> Unit,
    onSelect: (IdeCommandItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, commands) {
        commands.filter {
            query.isBlank() || it.title.contains(query, true) || it.category.contains(query, true)
        }
    }

    IdeModalOverlay(
        title = "Command Palette (VS Code + Godot + ACS)",
        onDismiss = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Type a command...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("command_palette_search")
        )
        filtered.forEach { cmd ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .clickable { onSelect(cmd) }
            ) {
                Row(
                    Modifier.padding(10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cmd.title, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        Text(cmd.category, style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    }
                    Text(cmd.shortcut, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    IdeModalOverlay(
        title = "Open-Source Integrations & MIT Notices",
        onDismiss = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    ) {
        UnifiedIdeLicenses.notices.forEach { notice ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(notice.name, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    Text("${notice.license} · ${notice.repoUrl}", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                    Text(notice.summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}
