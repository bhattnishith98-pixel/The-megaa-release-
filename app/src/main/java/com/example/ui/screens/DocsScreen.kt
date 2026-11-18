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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.StudioViewModel
import com.example.docs.DocCategory
import com.example.docs.DocsRegistry
import com.example.docs.ExamplesLibrary
import com.example.ui.theme.*

private data class LanguageDocSection(
    val id: String,
    val badge: String,
    val title: String,
    val summary: String,
    val codeBlock: String? = null,
    val tryInEditorCode: String? = null
)

private val LANGUAGE_DOC_SECTIONS = listOf(
    LanguageDocSection(
        id = "identity",
        badge = "SPEC v1",
        title = "1. Identity, Files & Indentation Rules",
        summary = "Language: StudioLang (.stl)\n" +
            "Layout Sidecar: <name>.stl.layout.json\n" +
            "Package Bundle: .stlpkg (ZIP with manifest.json, .stl source, .md docs, SHA-256)\n" +
            "Bytecode Magic / Version: 0x53544C31 (STL1), format version 1\n" +
            "Indentation: Strictly 4 spaces per block level. Tab characters (\\t) are rejected with LEX001."
    ),
    LanguageDocSection(
        id = "lexical",
        badge = "LEXER",
        title = "2. Lexical Elements & All 45 Keywords",
        summary = "Comments: # ... to end of line\n" +
            "Numbers: Integers and floats (42, 3.14)\n" +
            "Durations: <number>(ms|s|m|h) — e.g. 500ms, 2s, 5m, 1h\n" +
            "Text: \"...\" or '...' with escapes \\n, \\t, \\\\, \\\", \\'\n" +
            "Booleans / Null: true, false, none",
        codeBlock = "let, const, def, return, if, elif, else, for, in, while, repeat, times,\n" +
            "break, continue, try, on, error, raise, retry, wait, timeout, use, as,\n" +
            "needs, confirm, trigger, condition, constraint, action, block, macro,\n" +
            "app, screen, source, when, emit, only, do, true, false, none, and, or, not, is"
    ),
    LanguageDocSection(
        id = "types_ops",
        badge = "TYPES",
        title = "3. Data Types (11) & Operator Precedence",
        summary = "Built-in Types:\n" +
            "number, text, bool, list, map, none, time, duration, file, node, any\n\n" +
            "Operator Precedence (Lowest -> Highest):\n" +
            "1. or\n" +
            "2. and\n" +
            "3. not (unary)\n" +
            "4. ==, !=, <, <=, >, >=, in, is\n" +
            "5. +, -\n" +
            "6. *, /, %\n" +
            "7. ** (right-associative power)\n" +
            "8. Unary -\n" +
            "9. Call (...), Member .prop, Index [idx]"
    ),
    LanguageDocSection(
        id = "ebnf",
        badge = "GRAMMAR",
        title = "4. Complete EBNF Grammar",
        summary = "Full formal StudioLang grammar supported by StudioLexer and StudioParser.",
        codeBlock = """program        := { statement } EOF ;
statement      := use_stmt | needs_stmt | var_decl | assign_stmt
                | func_def | trigger_def | condition_def | constraint_def
                | action_def | block_def | macro_def | app_def | screen_def
                | if_stmt | for_stmt | while_stmt | repeat_stmt
                | break_stmt | continue_stmt | return_stmt
                | try_stmt | raise_stmt | retry_stmt | timeout_stmt
                | confirm_stmt | expr_stmt ;

use_stmt       := "use" IDENT { "." IDENT } [ "as" IDENT ] NEWLINE ;
needs_stmt     := "needs" IDENT { "," IDENT } NEWLINE ;
var_decl       := ( "let" | "const" ) IDENT [ ":" TYPE ] "=" expr NEWLINE ;
assign_stmt    := expr ( "=" | "+=" | "-=" | "*=" | "/=" ) expr NEWLINE ;

func_def       := "def" IDENT "(" [ param_list ] ")" [ "->" TYPE ] block_body ;
condition_def  := "condition" IDENT [ "(" [ param_list ] ")" ] [ "->" TYPE ] block_body ;
constraint_def := "constraint" IDENT [ "(" [ param_list ] ")" ] [ "->" TYPE ] block_body ;
action_def     := "action" IDENT [ "(" [ param_list ] ")" ] [ "->" TYPE ] block_body ;
block_def      := "block" IDENT [ "(" [ param_list ] ")" ] [ "->" TYPE ] block_body ;

trigger_def    := "trigger" IDENT ":" NEWLINE INDENT
                  { needs_stmt | "source" expr NEWLINE | "when" expr NEWLINE | "emit" expr NEWLINE | statement }
                  DEDENT ;

macro_def      := "macro" IDENT ":" NEWLINE INDENT
                  { needs_stmt | "on" expr NEWLINE | "only" "if" expr NEWLINE | "do" block_body | "on" "error" [ IDENT ] block_body }
                  DEDENT ;

if_stmt        := "if" expr block_body { "elif" expr block_body } [ "else" block_body ] ;
for_stmt       := "for" IDENT "in" expr block_body ;
while_stmt     := "while" expr block_body ;
repeat_stmt    := "repeat" expr "times" block_body ;
try_stmt       := "try" block_body "on" "error" [ IDENT ] block_body ;
retry_stmt     := "retry" expr "times" [ "," "wait" expr ] block_body ;
timeout_stmt   := "timeout" expr block_body ;
confirm_stmt   := "confirm" expr NEWLINE ;
block_body     := ":" NEWLINE INDENT { statement } DEDENT ;""",
        tryInEditorCode = """needs SYSTEM_INFO, NOTIFICATIONS

macro BatteryCheck:
    on manual()
    do:
        let pct = device.battery_level()
        if pct < 20:
            notify.show("Low Battery", "Charge soon: " + pct + "%")
        else:
            log.info("Battery healthy at " + pct + "%")"""
    ),
    LanguageDocSection(
        id = "polyglot",
        badge = "POLYGLOT",
        title = "5. Custom Blocks, Triggers & App Builder Languages",
        summary = "Alongside native StudioLang (.stl) compiled to STL1 bytecode, Automation Studio validates and packages multiple languages:\n" +
            "• Kotlin / Compose: Native Android UI screens & logic in App Builder + custom block specs\n" +
            "• HTML5 + JavaScript: Interactive WebView apps with live in-app preview & JS blocks\n" +
            "• JSON Triggers: Declarative event matching rules (e.g. level_gte, charging)\n" +
            "• Python & C++: Custom block templates bundled into exported Gradle projects\n" +
            "• Shizuku & Root Bridge: shizuku.is_available(), shizuku.exec(cmd), root.is_available(), root.exec(cmd) gated behind SHIZUKU_PRIVILEGED / ROOT_SUPERUSER",
        codeBlock = """{
  "trigger": "BatteryHighAlert",
  "when": {
    "level_gte": 85,
    "charging": true
  }
}""",
        tryInEditorCode = """needs SYSTEM_INFO, SHIZUKU_PRIVILEGED
let has_shizuku = shizuku.is_available()
let has_root = root.is_available()
clipboard.set("AutomationReady")
log.info("Shizuku=" + has_shizuku + " Root=" + has_root + " Clip=" + clipboard.get())"""
    ),
    LanguageDocSection(
        id = "security_errors",
        badge = "VM & SAFETY",
        title = "6. Capabilities, Sandbox & Diagnostic Codes",
        summary = "Every program declares required permissions via `needs <CAPABILITY>` and can prompt users interactively via `confirm \"...\"`.\n\n" +
            "Diagnostic Code Ranges:\n" +
            "• LEX001–LEX005: Lexer errors (tabs instead of 4 spaces, invalid indent, unterminated string)\n" +
            "• PAR001–PAR007: Parser syntax errors (missing ':', missing indent, malformed header)\n" +
            "• SEM001–SEM008: Semantic analyzer (undeclared variable, const reassign, unknown capability)\n" +
            "• VER001–VER013: Bytecode verifier (bad STL1 magic, jump bounds, unlisted host API)\n" +
            "• VM001–VM013: Runtime VM limits (division by zero, timeout, instruction/recursion caps)\n" +
            "• SEC001–SEC011: Sandbox blocks (ungranted capability, declined confirm, path traversal)"
    )
)

@Composable
fun DocsScreen(
    vm: StudioViewModel,
    onOpenInEditor: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Language Docs, 1 = API & Nodes, 2 = 52 Examples
    var selectedCategory by remember { mutableStateOf<DocCategory?>(null) }

    val filteredLangSections = remember(query) {
        val q = query.trim().lowercase()
        LANGUAGE_DOC_SECTIONS.filter { sec ->
            q.isEmpty() ||
                sec.title.lowercase().contains(q) ||
                sec.summary.lowercase().contains(q) ||
                sec.codeBlock.orEmpty().lowercase().contains(q)
        }
    }

    val entries = remember(query, selectedCategory) {
        DocsRegistry.search(query, selectedCategory)
    }

    val filteredExamples = remember(query) {
        val q = query.trim().lowercase()
        ExamplesLibrary.examples.filter { ex ->
            q.isEmpty() ||
                ex.title.lowercase().contains(q) ||
                ex.category.lowercase().contains(q) ||
                ex.code.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = CyberCyan)
            Column {
                Text("Language Documentation", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                Text(
                    "StudioLang (.stl) spec, EBNF grammar, polyglot blocks, APIs & 52 examples",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search language spec, keywords, nodes, APIs, errors...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("docs_search_input")
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SlateSurface,
            contentColor = CyberCyan
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Language Docs") },
                modifier = Modifier.testTag("docs_tab_language")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("API & Nodes") },
                modifier = Modifier.testTag("docs_tab_reference")
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Examples (${ExamplesLibrary.examples.size})") },
                modifier = Modifier.testTag("docs_tab_examples")
            )
        }

        when (selectedTab) {
            0 -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("language_documentation_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredLangSections, key = { it.id }) { sec ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        sec.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CyberCyan,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        color = SlateSurface,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                    ) {
                                        Text(
                                            sec.badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberCyan,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                                Text(sec.summary, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                sec.codeBlock?.let { block ->
                                    Surface(
                                        color = ObsidianBg,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                    ) {
                                        Text(
                                            block,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = EmeraldRun,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(block))
                                                vm.setBanner("Copied ${sec.title} to clipboard.")
                                            },
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                        ) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Copy")
                                        }
                                        sec.tryInEditorCode?.let { sample ->
                                            Button(
                                                onClick = {
                                                    vm.updateSource(sample)
                                                    onOpenInEditor()
                                                },
                                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                            ) {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Try in Studio")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("All (${DocsRegistry.allEntries().size})") }
                        )
                    }
                    items(DocCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(entries, key = { it.id }) { doc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(doc.title, style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                                    Text(doc.category.label, style = MaterialTheme.typography.labelSmall, color = NeonViolet)
                                }
                                Text(doc.summary, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text("Syntax: ${doc.syntax}", style = MaterialTheme.typography.labelSmall, color = AmberWarn)
                                Text(
                                    "Params: ${doc.parameters}  |  Returns: ${doc.returnType}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    "Needs: ${doc.requiredCapabilities}  |  Android: ${doc.androidPermissions} (${doc.apiCompatibility})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Text("Security: ${doc.securityNotes}", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                if (doc.exampleCode.isNotBlank()) {
                                    Surface(
                                        color = ObsidianBg,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                    ) {
                                        Text(
                                            doc.exampleCode,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldRun,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            vm.updateSource(doc.exampleCode)
                                            onOpenInEditor()
                                        }
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Try Example in Studio")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredExamples, key = { it.id }) { ex ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.loadExample(ex)
                                    onOpenInEditor()
                                }
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(ex.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                                    AssistChip(
                                        onClick = {
                                            vm.loadExample(ex)
                                            onOpenInEditor()
                                        },
                                        label = { Text("${ex.category} · ${ex.difficulty}") }
                                    )
                                }
                                Text(
                                    ex.code.lines().take(4).joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = CyberCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
