package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.StudioViewModel
import com.example.ui.theme.*
import com.example.vm.BinaryTarget
import com.example.vm.CompilationOutput
import com.example.vm.SelfHostBootstrapResult

@Composable
fun BinaryHexSelfHostDialog(
    binaryTarget: BinaryTarget,
    compileOutput: CompilationOutput,
    selfHostResult: SelfHostBootstrapResult?,
    onSelectTarget: (BinaryTarget) -> Unit,
    onRunSelfHost: () -> Unit,
    onExportBinary: () -> Unit,
    onDismiss: () -> Unit
) {
    var viewTab by remember { mutableStateOf("Hex") }
    val artifact = compileOutput.binaryArtifact

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Direct Binary / Hex & Self-Host", color = CyberCyan) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Swatantra StudioLang compiles straight to machine binary & hex — zero Kotlin/Java translation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(BinaryTarget.entries) { target ->
                        FilterChip(
                            selected = binaryTarget == target,
                            onClick = { onSelectTarget(target) },
                            label = { Text(target.id.uppercase()) },
                            modifier = Modifier.testTag("binary_target_${target.id}")
                        )
                    }
                }
                if (artifact != null) {
                    Text(
                        "${artifact.target.label} · ${artifact.bytes.size} bytes · Entry ${artifact.entryPointHex}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldRun
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Hex", "Disassembly", "Self-Host").forEach { t ->
                            FilterChip(selected = viewTab == t, onClick = { viewTab = t }, label = { Text(t) })
                        }
                    }
                    when (viewTab) {
                        "Hex" -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    artifact.hexDump.take(1800),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = CyberCyan,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        "Disassembly" -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                                modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    artifact.disassembly.take(1800),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        else -> {
                            Button(
                                onClick = onRunSelfHost,
                                modifier = Modifier.fillMaxWidth().testTag("run_self_host_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet, contentColor = TextPrimary)
                            ) {
                                Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Bootstrap Stage 1 -> Stage 2")
                            }
                            selfHostResult?.let { sh ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, if (sh.success) EmeraldRun else RoseError, RoundedCornerShape(10.dp))
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            if (sh.success) "Self-Hosted OK (${sh.stage1OpsCount} compiler ops -> ${sh.stage2ByteCount} output bytes, ${sh.durationMs}ms)"
                                            else "Self-Host Failed: ${sh.error}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (sh.success) EmeraldRun else RoseError
                                        )
                                        if (sh.stage2HexOutput.isNotBlank()) {
                                            Text(
                                                "Hex: ${sh.stage2HexOutput.take(360)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = CyberCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Fix compiler errors in editor to emit binary bytes.", color = RoseError, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onExportBinary,
                enabled = artifact != null,
                modifier = Modifier.testTag("export_binary_button")
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save Binary")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun LibraryManagerDialog(
    vm: StudioViewModel,
    availableLibraries: List<String>,
    onDismiss: () -> Unit
) {
    var libName by remember { mutableStateOf("fast_math") }
    var authorLang by remember { mutableStateOf("studiolang") }
    var libCode by remember {
        mutableStateOf(
            """
            |def square_clamp(x: number) -> number:
            |    return math.clamp(x * x, 0, 10000)
            """.trimMargin()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#include <studio.h> & .stl.lib", color = CyberCyan) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Auto-resolves `#include <studio.h>` and `#include <name.stl.lib>` from Downloads & workspace/libs (auto-appends `.stl.lib`).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Available Libraries (${availableLibraries.size})", style = MaterialTheme.typography.labelMedium, color = EmeraldRun)
                    TextButton(onClick = { vm.refreshAvailableLibraries() }) { Text("Scan Downloads") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(availableLibraries) { lib ->
                        AssistChip(
                            onClick = {
                                vm.insertLibraryInclude(lib)
                                onDismiss()
                            },
                            label = { Text("+ #include <$lib>") },
                            modifier = Modifier.testTag("insert_lib_$lib")
                        )
                    }
                }
                HorizontalDivider(color = SlateBorder)
                Text("Create & Save .stl.lib to Downloads (Any Language / Binary)", style = MaterialTheme.typography.labelMedium, color = NeonViolet)
                OutlinedTextField(
                    value = libName,
                    onValueChange = { libName = it },
                    label = { Text("Library Name (auto-saves as name.stl.lib)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("lib_name_input")
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("studiolang", "c", "rust", "python", "kotlin", "hex")) { lang ->
                        FilterChip(
                            selected = authorLang == lang,
                            onClick = {
                                authorLang = lang
                                libCode = when (lang) {
                                    "c" -> "int fast_add(int a, int b) { return a + b; }"
                                    "rust" -> "pub fn safe_hash(x: u64) -> u64 { x ^ 0x5A5A }"
                                    "python" -> "def greet_user(name):\n    return f'Hello {name}'"
                                    "kotlin" -> "fun doubleVal(x: Int): Int = x * 2"
                                    "hex" -> "0x53 0x54 0x4C 0x42 0x00 0x01 0xFF"
                                    else -> "def lib_hello() -> text:\n    return \"From ${libName}.stl.lib\""
                                }
                            },
                            label = { Text(lang.uppercase()) }
                        )
                    }
                }
                OutlinedTextField(
                    value = libCode,
                    onValueChange = { libCode = it },
                    label = { Text("Library Source ($authorLang)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("lib_code_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.saveStlLibrary(libName, authorLang, libCode)
                },
                modifier = Modifier.testTag("save_stl_lib_button")
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save .stl.lib")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
