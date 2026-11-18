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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ide.*
import com.example.ui.theme.*

@Composable
fun SourceControlDrawerPane(
    changes: List<GitFileChange>,
    commits: List<GitCommitRecord>,
    onCommit: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var commitMessage by remember { mutableStateOf("") }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("scm_drawer_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                placeholder = { Text("Commit message...", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("git_commit_input")
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    onCommit(commitMessage)
                    commitMessage = ""
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).testTag("git_commit_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = ObsidianBg)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Commit (${changes.size})", style = MaterialTheme.typography.labelSmall)
            }
        }

        item {
            Text("CHANGES (${changes.size})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }

        if (changes.isEmpty()) {
            item {
                Text("Working tree clean.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        } else {
            items(changes, key = { it.path }) { ch ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(6.dp))
                        .clickable { onOpenFile(ch.path) }
                ) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "[${ch.status}] ${ch.path.substringAfterLast('/')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ch.status == "A") EmeraldRun else AmberWarn,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onRevertFile(ch.path) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("Revert", style = MaterialTheme.typography.labelSmall, color = RoseError)
                            }
                        }
                        Text(
                            ch.previewDiff,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("HISTORY (${commits.size})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        items(commits.take(6), key = { it.id }) { c ->
            Text(
                "${c.id} · ${c.message}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CodeOutlineDrawerPane(
    activeFile: IdeFileEntry?,
    symbols: List<OutlineSymbol>,
    onSymbolClick: (OutlineSymbol) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            activeFile?.title ?: "No active file",
            style = MaterialTheme.typography.labelSmall,
            color = AmberWarn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        if (symbols.isEmpty()) {
            Text("No symbols detected in current file.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("outline_symbols_list")) {
                items(symbols, key = { "${it.line}_${it.name}" }) { sym ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateCard, RoundedCornerShape(6.dp))
                            .border(1.dp, SlateBorder, RoundedCornerShape(6.dp))
                            .clickable { onSymbolClick(sym) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sym.name, style = MaterialTheme.typography.labelSmall, color = CyberCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sym.kind, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                        Text("L${sym.line}", style = MaterialTheme.typography.labelSmall, color = EmeraldRun)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorQuickActionsHeader(
    editorConfig: IdeEditorConfig,
    findOpen: Boolean,
    splitOpen: Boolean,
    activePath: String,
    snippets: List<IdeSnippet>,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFind: () -> Unit,
    onToggleSplit: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onToggleAutoSave: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onRunFindReplace: (String, String, Boolean) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onRunVscodeCommand: (String) -> Unit = {},
    onToggleSoraAar: () -> Unit = {}
) {
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().background(SlateSurface)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = CyberCyan,
                    modifier = Modifier.size(20.dp).clickable { onUndo() }.padding(2.dp).testTag("editor_undo_btn")
                )
            }
            item {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = CyberCyan,
                    modifier = Modifier.size(20.dp).clickable { onRedo() }.padding(2.dp).testTag("editor_redo_btn")
                )
            }
            item {
                Icon(
                    Icons.Filled.FindReplace,
                    contentDescription = "Find and Replace",
                    tint = if (findOpen) EmeraldRun else TextSecondary,
                    modifier = Modifier.size(20.dp).clickable { onToggleFind() }.padding(2.dp).testTag("editor_find_toggle_btn")
                )
            }
            item {
                Icon(
                    Icons.Filled.VerticalSplit,
                    contentDescription = "Split Editor Right",
                    tint = if (splitOpen) CyberCyan else TextSecondary,
                    modifier = Modifier.size(20.dp).clickable { onToggleSplit() }.padding(2.dp).testTag("editor_split_btn")
                )
            }
            item {
                Text(
                    if (editorConfig.useSoraAar) "Sora AAR" else "Compose",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (editorConfig.useSoraAar) EmeraldRun else CyberCyan,
                    modifier = Modifier
                        .background(SlateCard, RoundedCornerShape(4.dp))
                        .clickable { onToggleSoraAar() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor_sora_aar_toggle_btn")
                )
            }
            item {
                Text(
                    "//",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberWarn,
                    modifier = Modifier
                        .background(SlateCard, RoundedCornerShape(4.dp))
                        .clickable { onRunVscodeCommand("editor.action.commentLine") }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor_comment_btn")
                )
            }
            item {
                Text(
                    "Fmt",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldRun,
                    modifier = Modifier
                        .background(SlateCard, RoundedCornerShape(4.dp))
                        .clickable { onRunVscodeCommand("editor.action.formatDocument") }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor_format_btn")
                )
            }
            item {
                Text(
                    if (editorConfig.wordWrap) "Wrap:ON" else "Wrap:OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (editorConfig.wordWrap) EmeraldRun else TextMuted,
                    modifier = Modifier
                        .background(SlateCard, RoundedCornerShape(4.dp))
                        .clickable { onToggleWordWrap() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor_wrap_toggle")
                )
            }
            item {
                Text(
                    if (editorConfig.autoSave) "AutoSave" else "Manual",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (editorConfig.autoSave) CyberCyan else TextMuted,
                    modifier = Modifier
                        .background(SlateCard, RoundedCornerShape(4.dp))
                        .clickable { onToggleAutoSave() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor_autosave_toggle")
                )
            }
            item {
                Text(
                    "A-",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onFontSizeChange((editorConfig.fontSizeSp - 1).coerceAtLeast(9)) }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            item {
                Text("${editorConfig.fontSizeSp}sp", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            item {
                Text(
                    "A+",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onFontSizeChange((editorConfig.fontSizeSp + 1).coerceAtMost(20)) }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        // VS Code Breadcrumbs + Smart Snippet Autocomplete Bar combined cleanly
        if (activePath.isNotBlank() || snippets.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activePath.isNotBlank()) {
                    item {
                        Text(
                            activePath.split('/').joinToString(" › "),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("vscode_breadcrumbs")
                        )
                    }
                }
                items(snippets) { snip ->
                    Surface(
                        color = SlateCard,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .border(1.dp, NeonViolet.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .clickable { onInsertSnippet(snip.insertText) }
                            .testTag("snippet_chip_${snip.label}")
                    ) {
                        Text(
                            snip.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        if (findOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = { findQuery = it },
                    placeholder = { Text("Find", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("editor_find_input")
                )
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    placeholder = { Text("Replace", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("editor_replace_input")
                )
                Button(
                    onClick = { if (findQuery.isNotEmpty()) onRunFindReplace(findQuery, replaceText, true) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).testTag("editor_replace_all_btn")
                ) { Text("All", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
fun VsCodeBottomStatusBar(
    activePath: String,
    lineCount: Int,
    charCount: Int,
    errorCount: Int,
    warningCount: Int,
    theme: IdeThemePreset,
    tabSpaces: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(theme.accentHex).copy(alpha = 0.16f))
            .border(1.dp, SlateBorder)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("vscode_status_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "main*  E:$errorCount W:$warningCount",
            style = MaterialTheme.typography.labelSmall,
            color = if (errorCount > 0) RoseError else EmeraldRun
        )
        Text(
            "Lines:$lineCount Chars:$charCount · Spaces:$tabSpaces · UTF-8 · ${VsCodeEditorFeaturesEngine.languageDisplayName(activePath)}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AndroidDesignerAndAssetBar(
    onInsertWidget: (String, String) -> Unit,
    onCreateVectorAsset: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateCard, RoundedCornerShape(8.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("UI Designer & Asset Studio", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedButton(
                    onClick = { onInsertWidget("BUTTON", "New Action") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).testTag("acs_add_widget_button")
                ) { Text("+ Button", style = MaterialTheme.typography.labelSmall) }
            }
            item {
                OutlinedButton(
                    onClick = { onInsertWidget("TEXT", "Status Label") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).testTag("acs_add_widget_text")
                ) { Text("+ Text", style = MaterialTheme.typography.labelSmall) }
            }
            item {
                OutlinedButton(
                    onClick = { onInsertWidget("INPUT", "Input Field") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).testTag("acs_add_widget_input")
                ) { Text("+ Input", style = MaterialTheme.typography.labelSmall) }
            }
            item {
                OutlinedButton(
                    onClick = { onCreateVectorAsset("badge_bolt", "bolt") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp).testTag("acs_create_vector_btn")
                ) { Text("+ Vector XML", style = MaterialTheme.typography.labelSmall, color = EmeraldRun) }
            }
        }
    }
}
