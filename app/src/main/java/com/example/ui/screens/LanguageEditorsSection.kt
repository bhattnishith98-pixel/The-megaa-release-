package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.appbuilder.AppBuildLanguage
import com.example.appbuilder.AppDesign
import com.example.appbuilder.OsTargetArch
import com.example.ui.theme.*

@Composable
fun ExtendedAppLanguageEditorCard(
    design: AppDesign,
    onUpdateDesign: (AppDesign) -> Unit
) {
    when (design.buildLanguage) {
        AppBuildLanguage.JAVA_ANDROID -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, AmberWarn.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Java Android SDK (MainActivity.java)", style = MaterialTheme.typography.titleSmall, color = AmberWarn)
                        TextButton(onClick = { onUpdateDesign(design.copy(customJavaCode = AppDesign.DEFAULT_JAVA_APP)) }) {
                            Text("Reset Java")
                        }
                    }
                    OutlinedTextField(
                        value = design.customJavaCode,
                        onValueChange = { onUpdateDesign(design.copy(customJavaCode = it)) },
                        label = { Text("MainActivity.java") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("app_java_editor")
                    )
                }
            }
        }

        AppBuildLanguage.RUST_JNI -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, AmberWarn.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Rust Safe JNI Crate (rust_core/src/lib.rs)", style = MaterialTheme.typography.titleSmall, color = AmberWarn)
                        TextButton(onClick = { onUpdateDesign(design.copy(customRustCode = AppDesign.DEFAULT_RUST_APP)) }) {
                            Text("Reset Rust")
                        }
                    }
                    OutlinedTextField(
                        value = design.customRustCode,
                        onValueChange = { onUpdateDesign(design.copy(customRustCode = it)) },
                        label = { Text("src/lib.rs") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("app_rust_editor")
                    )
                }
            }
        }

        AppBuildLanguage.CPP_NDK -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("C / C++ Android NDK (CMake + JNI)", style = MaterialTheme.typography.titleSmall, color = CyberCyan)
                        TextButton(onClick = { onUpdateDesign(design.copy(customCppCode = AppDesign.DEFAULT_CPP_NDK_APP)) }) {
                            Text("Reset C++")
                        }
                    }
                    OutlinedTextField(
                        value = design.customCppCode,
                        onValueChange = { onUpdateDesign(design.copy(customCppCode = it)) },
                        label = { Text("app/src/main/cpp/native_module.cpp") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("app_cpp_primary_editor")
                    )
                }
            }
        }

        AppBuildLanguage.PYTHON_BRIDGE -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, EmeraldRun.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Python Automation Module (assets/scripts/main.py)", style = MaterialTheme.typography.titleSmall, color = EmeraldRun)
                        TextButton(onClick = { onUpdateDesign(design.copy(customPythonCode = AppDesign.DEFAULT_PYTHON_APP)) }) {
                            Text("Reset Python")
                        }
                    }
                    OutlinedTextField(
                        value = design.customPythonCode,
                        onValueChange = { onUpdateDesign(design.copy(customPythonCode = it)) },
                        label = { Text("main.py") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 9,
                        modifier = Modifier.fillMaxWidth().testTag("app_python_primary_editor")
                    )
                }
            }
        }

        AppBuildLanguage.CUSTOM_OS_KERNEL -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier.fillMaxWidth().border(1.dp, NeonViolet.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Custom OS Kernel Builder (Bare-Metal ELF64)", style = MaterialTheme.typography.titleSmall, color = NeonViolet)
                        TextButton(onClick = { onUpdateDesign(design.copy(customOsKernelCode = AppDesign.DEFAULT_OS_KERNEL_STL)) }) {
                            Text("Reset Kernel")
                        }
                    }
                    Text(
                        "Target PC/Laptop (x86_64 Multiboot2), Mobile (ARM64 AArch64), or Embedded (RISC-V 64). Emits direct machine `kernel.elf`, hex dump, boot asm & linker script.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(OsTargetArch.entries) { arch ->
                            FilterChip(
                                selected = design.osTargetArch == arch,
                                onClick = { onUpdateDesign(design.copy(osTargetArch = arch)) },
                                label = { Text(arch.label) },
                                modifier = Modifier.testTag("os_arch_${arch.id}")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = design.customOsKernelCode,
                        onValueChange = { onUpdateDesign(design.copy(customOsKernelCode = it)) },
                        label = { Text("os_kernel/kernel.stl (${design.osTargetArch.id})") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        minLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("app_os_kernel_editor")
                    )
                }
            }
        }

        else -> Unit
    }
}
