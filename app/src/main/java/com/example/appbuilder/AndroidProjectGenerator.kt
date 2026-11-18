package com.example.appbuilder

import com.example.vm.CompiledBytecode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class GeneratedProjectFile(
    val relativePath: String,
    val content: String,
    val diffSummary: String
)

data class AppValidationReport(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

data class AppBuildOutput(
    val success: Boolean,
    val validation: AppValidationReport,
    val generatedFiles: List<GeneratedProjectFile>,
    val backupDirPath: String?,
    val bundleSha256: String?,
    val buildLog: List<String>,
    val zipBytes: ByteArray?
)

/**
 * Real Android Gradle project generator supporting Visual Designer, Kotlin/Compose,
 * HTML5+JS WebView, StudioLang, JSON, optional Python/C++ extras, GitHub Actions APK/AAB CI,
 * and release signing configuration.
 */
object AndroidProjectGenerator {

    private val PACKAGE_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    fun validate(design: AppDesign): AppValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (design.appName.isBlank() || design.appName.length > 30) {
            errors += "App name must be 1..30 characters (Google Play policy)."
        }
        if (!PACKAGE_REGEX.matches(design.packageId)) {
            errors += "Package ID '${design.packageId}' must be valid reverse-DNS (e.g. com.acme.myapp)."
        }
        if (design.minSdk < 21 || design.minSdk > design.targetSdk) {
            errors += "Invalid SDK range: minSdk=${design.minSdk}, targetSdk=${design.targetSdk}."
        }
        if (design.buildLanguage == AppBuildLanguage.VISUAL_DESIGNER && design.screens.isEmpty()) {
            errors += "Visual Designer app must contain at least one screen."
        }
        if (design.buildLanguage == AppBuildLanguage.KOTLIN_COMPOSE && design.customKotlinCode.isBlank()) {
            errors += "Kotlin / Compose code cannot be empty."
        }
        if (design.buildLanguage == AppBuildLanguage.JAVA_ANDROID && design.customJavaCode.isBlank()) {
            errors += "Java Android code cannot be empty."
        }
        if (design.buildLanguage == AppBuildLanguage.HTML5_JS && design.customHtml5Code.isBlank()) {
            errors += "HTML5 code cannot be empty."
        }
        if (design.buildLanguage == AppBuildLanguage.CPP_NDK && design.customCppCode.isBlank()) {
            errors += "C / C++ NDK code cannot be empty."
        }
        if (design.buildLanguage == AppBuildLanguage.RUST_JNI && design.customRustCode.isBlank()) {
            errors += "Rust JNI code cannot be empty."
        }
        if (design.buildLanguage == AppBuildLanguage.CUSTOM_OS_KERNEL && design.customOsKernelCode.isBlank()) {
            errors += "Custom OS Kernel StudioLang source cannot be empty."
        }
        if (design.permissions.any { it.contains("READ_EXTERNAL_STORAGE") || it.contains("WRITE_EXTERNAL_STORAGE") }) {
            warnings += "Broad storage permissions violate Google Play policies; prefer Photo Picker or SAF."
        }
        return AppValidationReport(errors.isEmpty(), errors, warnings)
    }

    fun generateFiles(design: AppDesign): List<GeneratedProjectFile> {
        val pkgPath = design.packageId.replace('.', '/')
        val manifestXml = buildManifestXml(design)
        val stringsXml = """
            |<resources>
            |    <string name="app_name">${escapeXml(design.appName)}</string>
            |</resources>
        """.trimMargin()
        val colorsXml = """
            |<resources>
            |    <color name="studio_primary">${design.primaryColorHex}</color>
            |</resources>
        """.trimMargin()
        val settingsGradle = """
            |pluginManagement {
            |    repositories { google(); mavenCentral(); gradlePluginPortal() }
            |}
            |dependencyResolutionManagement {
            |    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            |    repositories { google(); mavenCentral() }
            |}
            |rootProject.name = "${design.appName.replace("\"", "")}"
            |include(":app")
        """.trimMargin()
        val rootBuildGradle = """
            |plugins {
            |    id("com.android.application") version "8.9.1" apply false
            |    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
            |    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
            |}
        """.trimMargin()
        val gradleProperties = """
            |org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            |android.useAndroidX=true
            |kotlin.code.style=official
        """.trimMargin()
        val useNdkCmake = design.buildLanguage in setOf(AppBuildLanguage.CPP_NDK, AppBuildLanguage.RUST_JNI) || design.customCppCode.isNotBlank()
        val nativeDefaultConfigBlock = if (useNdkCmake) {
            """
            |        ndk { abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a")) }
            """.trimMargin()
        } else ""
        val externalNativeBlock = if (useNdkCmake) {
            """
            |    ndkVersion = "28.2.13676358"
            |    externalNativeBuild {
            |        cmake {
            |            path = file("src/main/cpp/CMakeLists.txt")
            |            version = "3.22.1"
            |        }
            |    }
            """.trimMargin()
        } else ""

        val appBuildGradle = """
            |plugins {
            |    id("com.android.application")
            |    id("org.jetbrains.kotlin.android")
            |    id("org.jetbrains.kotlin.plugin.compose")
            |}
            |android {
            |    namespace = "${design.packageId}"
            |    compileSdk = ${design.targetSdk}
            |    defaultConfig {
            |        applicationId = "${design.packageId}"
            |        minSdk = ${design.minSdk}
            |        targetSdk = ${design.targetSdk}
            |        versionCode = ${design.versionCode}
            |        versionName = "${design.versionName}"
            |$nativeDefaultConfigBlock
            |    }
            |$externalNativeBlock
            |    signingConfigs {
            |        val ksPath = System.getenv("KEYSTORE_PATH")
            |        if (!ksPath.isNullOrBlank()) {
            |            create("release") {
            |                storeFile = file(ksPath)
            |                storePassword = System.getenv("STORE_PASSWORD")
            |                keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
            |                keyPassword = System.getenv("KEY_PASSWORD")
            |            }
            |        }
            |    }
            |    buildTypes {
            |        release {
            |            isMinifyEnabled = false
            |            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            |        }
            |    }
            |    buildFeatures { compose = true }
            |    compileOptions {
            |        sourceCompatibility = JavaVersion.VERSION_11
            |        targetCompatibility = JavaVersion.VERSION_11
            |    }
            |    kotlinOptions { jvmTarget = "11" }
            |}
            |dependencies {
            |    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
            |    implementation("androidx.activity:activity-compose:1.10.1")
            |    implementation("androidx.compose.material3:material3")
            |    implementation("androidx.compose.ui:ui")
            |}
        """.trimMargin()

        val files = mutableListOf(
            GeneratedProjectFile("gradle.properties", gradleProperties, "+Gradle JVM & AndroidX flags"),
            GeneratedProjectFile("settings.gradle.kts", settingsGradle, "+rootProject.name = \"${design.appName}\""),
            GeneratedProjectFile("build.gradle.kts", rootBuildGradle, "+AGP 8.9.1 / Kotlin 2.1.10 Compose"),
            GeneratedProjectFile("app/build.gradle.kts", appBuildGradle, "+applicationId = ${design.packageId}, release signing"),
            GeneratedProjectFile("app/src/main/AndroidManifest.xml", manifestXml, "+${design.permissions.size} permissions, ${design.components.size} components"),
            GeneratedProjectFile("app/src/main/res/values/strings.xml", stringsXml, "+app_name"),
            GeneratedProjectFile("app/src/main/res/values/colors.xml", colorsXml, "+primary=${design.primaryColorHex}"),
            GeneratedProjectFile("app/proguard-rules.pro", "# Keep StudioLang JNI / Compose entry points\n-keep class ${design.packageId}.** { *; }\n", "+ProGuard rules"),
            GeneratedProjectFile(".github/workflows/build-app.yml", githubActionsWorkflowYaml(design.appName, design.buildLanguage == AppBuildLanguage.RUST_JNI), "+GitHub Actions Debug APK + Signed Release APK/AAB")
        )

        when (design.buildLanguage) {
            AppBuildLanguage.JAVA_ANDROID -> {
                files += GeneratedProjectFile(
                    "app/src/main/java/$pkgPath/MainActivity.java",
                    normalizePackageHeader(design.customJavaCode, design.packageId, semicolon = true),
                    "+Java Android SDK MainActivity"
                )
            }
            AppBuildLanguage.KOTLIN_COMPOSE -> {
                files += GeneratedProjectFile(
                    "app/src/main/java/$pkgPath/MainActivity.kt",
                    normalizePackageHeader(design.customKotlinCode, design.packageId, semicolon = false),
                    "+Kotlin/Compose entry"
                )
            }
            AppBuildLanguage.HTML5_JS -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildWebViewMainActivityKt(design), "+WebView host")
                files += GeneratedProjectFile("app/src/main/assets/index.html", design.customHtml5Code, "+HTML5 entry UI")
                if (design.customJsCode.isNotBlank()) {
                    files += GeneratedProjectFile("app/src/main/assets/app.js", design.customJsCode, "+JS app logic")
                }
            }
            AppBuildLanguage.CPP_NDK -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildNativeHostMainActivityKt(design, "studionative", "studioStatus"), "+NDK JNI host")
                files += GeneratedProjectFile("app/src/main/cpp/native_module.cpp", design.customCppCode, "+C/C++ NDK JNI implementation")
                files += GeneratedProjectFile("app/src/main/cpp/CMakeLists.txt", buildCMakeLists(), "+CMake NDK build script")
            }
            AppBuildLanguage.RUST_JNI -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildNativeHostMainActivityKt(design, "studionative", "rustKernelStatus"), "+Rust JNI host")
                files += GeneratedProjectFile("rust_core/Cargo.toml", buildCargoToml(design.appName), "+Rust cdylib crate")
                files += GeneratedProjectFile("rust_core/src/lib.rs", design.customRustCode, "+Safe Rust FFI module")
                files += GeneratedProjectFile("app/src/main/cpp/native_module.cpp", buildRustJniBridge(design.packageId), "+C++ JNI bridge to Rust cdylib")
                files += GeneratedProjectFile("app/src/main/cpp/CMakeLists.txt", buildRustCMakeLists(), "+CMake link to Rust cdylib")
            }
            AppBuildLanguage.PYTHON_BRIDGE -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildMainActivityKt(design), "+Python Android host")
                files += GeneratedProjectFile("app/src/main/assets/scripts/main.py", design.customPythonCode, "+Python automation script")
            }
            AppBuildLanguage.CUSTOM_OS_KERNEL -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildMainActivityKt(design), "+OS Kernel Companion UI")
                files += GeneratedProjectFile("os_kernel/kernel.stl", design.customOsKernelCode, "+StudioLang Kernel source (${design.osTargetArch.label})")
                files += GeneratedProjectFile("os_kernel/boot_multiboot2.S", buildBootAssembly(design.osTargetArch), "+Bare-metal ${design.osTargetArch.id} boot stub")
                files += GeneratedProjectFile("os_kernel/linker.ld", buildLinkerScript(design.osTargetArch), "+Bare-metal ELF64 linker script")
                files += GeneratedProjectFile("os_kernel/build_os_iso.sh", buildOsIsoScript(design.appName, design.osTargetArch), "+ISO / QEMU boot image builder")
            }
            else -> {
                files += GeneratedProjectFile("app/src/main/java/$pkgPath/MainActivity.kt", buildMainActivityKt(design), "+${design.buildLanguage.label} entry")
            }
        }

        if (design.buildLanguage !in setOf(AppBuildLanguage.CPP_NDK, AppBuildLanguage.RUST_JNI) && design.customCppCode.isNotBlank()) {
            files += GeneratedProjectFile("app/src/main/cpp/native_module.cpp", design.customCppCode, "+Optional C++ native module")
            files += GeneratedProjectFile("app/src/main/cpp/CMakeLists.txt", buildCMakeLists(), "+Optional CMake NDK script")
        }
        if (design.buildLanguage != AppBuildLanguage.PYTHON_BRIDGE && design.customPythonCode.isNotBlank()) {
            files += GeneratedProjectFile("app/src/main/assets/scripts/main.py", design.customPythonCode, "+Bundled Python script")
        }

        return files
    }

    fun buildAndExport(
        design: AppDesign,
        outputRootDir: File,
        backupRootDir: File,
        compiledLogic: CompiledBytecode? = null
    ): AppBuildOutput {
        val log = mutableListOf<String>()
        log += "[1/5] Validating AppDesign '${design.appName}' (${design.packageId}) [${design.buildLanguage.label}]..."
        val validation = validate(design)
        if (!validation.isValid) {
            validation.errors.forEach { log += "ERROR: $it" }
            return AppBuildOutput(false, validation, emptyList(), null, null, log, null)
        }
        validation.warnings.forEach { log += "WARN: $it" }

        var backupPath: String? = null
        if (outputRootDir.exists() && (outputRootDir.listFiles()?.isNotEmpty() == true)) {
            val stampDir = File(backupRootDir, "backup_${System.currentTimeMillis()}")
            stampDir.mkdirs()
            outputRootDir.copyRecursively(stampDir, overwrite = true)
            backupPath = stampDir.absolutePath
            log += "[2/5] Created automatic backup at ${stampDir.name}."
        } else {
            log += "[2/5] Clean workspace; no overwrite backup needed."
        }

        val files = generateFiles(design)
        outputRootDir.mkdirs()
        files.forEach { f ->
            val target = File(outputRootDir, f.relativePath)
            target.parentFile?.mkdirs()
            target.writeText(f.content)
        }
        log += "[3/5] Generated ${files.size} native Android Gradle/OS-Kernel/CI files."

        // Emit direct machine ELF64 binary if building Custom OS Kernel or StudioLang Binary
        val osBinaryArtifact = if (design.buildLanguage == AppBuildLanguage.CUSTOM_OS_KERNEL) {
            val osCompiler = com.example.vm.StudioCompiler()
            val target = when (design.osTargetArch) {
                OsTargetArch.X86_64 -> com.example.vm.BinaryTarget.X86_64_ELF
                OsTargetArch.AARCH64 -> com.example.vm.BinaryTarget.AARCH64_ELF
                OsTargetArch.RISCV64 -> com.example.vm.BinaryTarget.RISCV64_ELF
            }
            val compiled = osCompiler.compile(design.customOsKernelCode, target = target)
            compiled.binaryArtifact
        } else null

        if (osBinaryArtifact != null) {
            val elfSha = MessageDigest.getInstance("SHA-256").digest(osBinaryArtifact.bytes).joinToString("") { "%02x".format(it) }
            File(outputRootDir, "os_kernel/kernel.elf").apply {
                parentFile?.mkdirs()
                writeBytes(osBinaryArtifact.bytes)
            }
            File(outputRootDir, "os_kernel/kernel.hex.txt").writeText(osBinaryArtifact.hexDump)
            log += "[3b/5] Emitted direct bare-metal ${osBinaryArtifact.target.label} binary (${osBinaryArtifact.bytes.size} bytes, SHA-256: ${elfSha.take(12)}...)."
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativePath))
                zos.write(f.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            if (compiledLogic != null) {
                zos.putNextEntry(ZipEntry("app/src/main/assets/logic.stlb"))
                zos.write(compiledLogic.encodeToBytes())
                zos.closeEntry()
            }
            if (osBinaryArtifact != null) {
                zos.putNextEntry(ZipEntry("os_kernel/kernel.elf"))
                zos.write(osBinaryArtifact.bytes)
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("os_kernel/kernel.hex.txt"))
                zos.write(osBinaryArtifact.hexDump.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("app_model.json"))
            zos.write(serializeAppDesign(design).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(zipBytes).joinToString("") { "%02x".format(it) }
        val safeName = design.appName.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "StudioApp" }
        File(outputRootDir, "$safeName.zip").writeBytes(zipBytes)
        File(outputRootDir, "$safeName.stla").writeBytes(zipBytes)

        log += "[4/5] Packaged exportable Gradle/OS ZIP ($safeName.zip, ${zipBytes.size} bytes, SHA-256: ${sha256.take(16)}...)."
        log += "[5/5] Ready to download ZIP and build APK / .aab via GitHub Actions (.github/workflows/build-app.yml included)."

        return AppBuildOutput(
            success = true,
            validation = validation,
            generatedFiles = files,
            backupDirPath = backupPath,
            bundleSha256 = sha256,
            buildLog = log,
            zipBytes = zipBytes
        )
    }

    fun githubActionsWorkflowYaml(appName: String = "StudioApp", includeRust: Boolean = false): String {
        val rustBuildSteps = if (includeRust) """
            |      - uses: dtolnay/rust-toolchain@stable
            |        with:
            |          targets: armv7-linux-androideabi,aarch64-linux-android
            |      - name: Install cargo-ndk
            |        run: cargo install cargo-ndk --locked
            |      - name: Compile Rust Android libraries
            |        run: cargo ndk -t armeabi-v7a -t arm64-v8a -o app/src/main/jniLibs build --manifest-path rust_core/Cargo.toml --release
            """.trimMargin() else ""
        return """
        |name: Build APK & AAB ($appName)
        |on:
        |  push:
        |    branches: [ main ]
        |  workflow_dispatch:
        |
        |permissions:
        |  contents: write
        |
        |jobs:
        |  build:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - uses: actions/checkout@v4
        |      - uses: actions/setup-java@v4
        |        with:
        |          distribution: temurin
        |          java-version: '17'
        |      - uses: android-actions/setup-android@v3
        |      - name: Install Android SDK and native build tools
        |        run: sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
        |      - uses: gradle/actions/setup-gradle@v4
        |        with:
        |          gradle-version: '8.11.1'
        |$rustBuildSteps
        |      - name: Build Debug APK
        |        run: gradle :app:assembleDebug --no-daemon
        |      - name: Decode Release Keystore (if configured)
        |        if: ${'$'}{{ secrets.KEYSTORE_BASE64 != '' }}
        |        env:
        |          KEYSTORE_BASE64: ${'$'}{{ secrets.KEYSTORE_BASE64 }}
        |        run: |
        |          echo "${'$'}KEYSTORE_BASE64" | base64 -d > release-keystore.p12
        |          echo "KEYSTORE_PATH=${'$'}{{ github.workspace }}/release-keystore.p12" >> ${'$'}GITHUB_ENV
        |      - name: Build Release APK & AAB
        |        env:
        |          STORE_PASSWORD: ${'$'}{{ secrets.STORE_PASSWORD }}
        |          KEY_ALIAS: ${'$'}{{ secrets.KEY_ALIAS }}
        |          KEY_PASSWORD: ${'$'}{{ secrets.KEY_PASSWORD }}
        |        run: gradle :app:assembleRelease :app:bundleRelease --no-daemon
        |      - uses: actions/upload-artifact@v4
        |        with:
        |          name: android-builds
        |          path: |
        |            app/build/outputs/apk/**/*.apk
        |            app/build/outputs/bundle/**/*.aab
    """.trimMargin()
    }

    private fun normalizePackageHeader(rawCode: String, pkgId: String, semicolon: Boolean = false): String {
        val trimmed = rawCode.trimStart()
        val end = if (semicolon) ";" else ""
        return if (trimmed.startsWith("package ")) {
            trimmed.replaceFirst(Regex("""^package\s+[^\n]+"""), "package $pkgId$end")
        } else {
            "package $pkgId$end\n\n$trimmed"
        }
    }

    private fun buildNativeHostMainActivityKt(design: AppDesign, libName: String, nativeFn: String): String = """
        |package ${design.packageId}
        |
        |import android.os.Bundle
        |import androidx.activity.ComponentActivity
        |import androidx.activity.compose.setContent
        |import androidx.activity.enableEdgeToEdge
        |import androidx.compose.foundation.layout.*
        |import androidx.compose.material3.*
        |import androidx.compose.ui.Modifier
        |import androidx.compose.ui.unit.dp
        |
        |class MainActivity : ComponentActivity() {
        |    companion object {
        |        init { System.loadLibrary("$libName") }
        |    }
        |    external fun $nativeFn(): String
        |    override fun onCreate(savedInstanceState: Bundle?) {
        |        super.onCreate(savedInstanceState)
        |        enableEdgeToEdge()
        |        val status = runCatching { $nativeFn() }.getOrElse { it.message ?: "native_loaded" }
        |        setContent {
        |            MaterialTheme {
        |                Scaffold { pad ->
        |                    Column(Modifier.padding(pad).padding(20.dp)) {
        |                        Text("${design.appName}", style = MaterialTheme.typography.headlineMedium)
        |                        Spacer(Modifier.height(8.dp))
        |                        Text("Native Status: ${'$'}status")
        |                    }
        |                }
        |            }
        |        }
        |    }
        |}
    """.trimMargin()

    private fun buildCMakeLists(): String = """
        |cmake_minimum_required(VERSION 3.22.1)
        |project("studionative")
        |add_library(studionative SHARED native_module.cpp)
        |find_library(log-lib log)
        |target_link_libraries(studionative ${'$'}{log-lib})
    """.trimMargin()

    private fun buildRustJniBridge(packageId: String): String {
        val jniPackage = buildString {
            packageId.forEach { ch ->
                when (ch) {
                    '.' -> append('_')
                    '_' -> append("_1")
                    ';' -> append("_2")
                    '[' -> append("_3")
                    else -> append(ch)
                }
            }
        }
        return """
            |#include <jni.h>
            |#include <string>
            |
            |extern "C" long long rust_safe_compute(long long x, long long y);
            |
            |extern "C" JNIEXPORT jstring JNICALL
            |Java_${jniPackage}_MainActivity_rustKernelStatus(JNIEnv* env, jobject) {
            |    const auto value = rust_safe_compute(21, 21);
            |    const std::string message = "Rust JNI ready: " + std::to_string(value);
            |    return env->NewStringUTF(message.c_str());
            |}
        """.trimMargin()
    }

    private fun buildRustCMakeLists(): String = """
        |cmake_minimum_required(VERSION 3.22.1)
        |project("studionative")
        |add_library(studionative SHARED native_module.cpp)
        |find_library(log-lib log)
        |find_library(rust-lib studiorust PATHS "${'$'}{CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${'$'}{ANDROID_ABI}" NO_DEFAULT_PATH)
        |if(NOT rust-lib)
        |    message(FATAL_ERROR "Rust cdylib not found. Run the cargo-ndk build step before Gradle.")
        |endif()
        |target_link_libraries(studionative ${'$'}{rust-lib} ${'$'}{log-lib})
    """.trimMargin()

    private fun buildCargoToml(appName: String): String = """
        |[package]
        |name = "${appName.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "studio_rust_core" }}"
        |version = "0.1.0"
        |edition = "2021"
        |
        |[lib]
        |name = "studiorust"
        |crate-type = ["cdylib"]
    """.trimMargin()

    private fun buildBootAssembly(arch: OsTargetArch): String = when (arch) {
        OsTargetArch.X86_64 -> """
            |.section .multiboot2
            |.align 8
            |mb2_start:
            |    .long 0xE85250D6
            |    .long 0
            |    .long mb2_end - mb2_start
            |    .long -(0xE85250D6 + 0 + (mb2_end - mb2_start))
            |    .short 0, 0
            |    .long 8
            |mb2_end:
            |.section .text
            |.global _start
            |_start:
            |    cli
            |    lea stack_top(%rip), %rsp
            |    call studio_kernel_main
            |1:  hlt
            |    jmp 1b
            |.section .bss
            |.align 16
            |stack_bottom: .skip 16384
            |stack_top:
        """.trimMargin()
        OsTargetArch.AARCH64 -> """
            |.section .text.boot
            |.global _start
            |_start:
            |    ldr x0, =stack_top
            |    mov sp, x0
            |    bl studio_kernel_main
            |1:  wfe
            |    b 1b
            |.section .bss
            |.align 16
            |.skip 16384
            |stack_top:
        """.trimMargin()
        OsTargetArch.RISCV64 -> """
            |.section .text.init
            |.global _start
            |_start:
            |    la sp, stack_top
            |    call studio_kernel_main
            |1:  wfi
            |    j 1b
            |.section .bss
            |.align 16
            |.skip 16384
            |stack_top:
        """.trimMargin()
    }

    private fun buildLinkerScript(arch: OsTargetArch): String {
        val entryAddr = when (arch) {
            OsTargetArch.X86_64 -> "0x00100000"
            OsTargetArch.AARCH64 -> "0x40080000"
            OsTargetArch.RISCV64 -> "0x80200000"
        }
        return """
            |ENTRY(_start)
            |SECTIONS {
            |    . = $entryAddr;
            |    .text : { *(.multiboot2) *(.text*) }
            |    .rodata : { *(.rodata*) }
            |    .data : { *(.data*) }
            |    .bss : { *(COMMON) *(.bss*) }
            |}
        """.trimMargin()
    }

    private fun buildOsIsoScript(appName: String, arch: OsTargetArch): String = """
        |#!/usr/bin/env bash
        |set -euo pipefail
        |echo "Direct ELF64 kernel already emitted to os_kernel/kernel.elf for ${arch.label}"
        |echo "To test in QEMU:"
        |# qemu-system-${arch.id} -kernel os_kernel/kernel.elf -nographic
    """.trimMargin()

    private fun buildWebViewMainActivityKt(design: AppDesign): String = """
        |package ${design.packageId}
        |
        |import android.annotation.SuppressLint
        |import android.os.Bundle
        |import android.webkit.WebView
        |import android.webkit.WebViewClient
        |import androidx.activity.ComponentActivity
        |import androidx.activity.enableEdgeToEdge
        |
        |class MainActivity : ComponentActivity() {
        |    @SuppressLint("SetJavaScriptEnabled")
        |    override fun onCreate(savedInstanceState: Bundle?) {
        |        super.onCreate(savedInstanceState)
        |        enableEdgeToEdge()
        |        val webView = WebView(this).apply {
        |            settings.javaScriptEnabled = true
        |            settings.domStorageEnabled = true
        |            webViewClient = WebViewClient()
        |            loadUrl("file:///android_asset/index.html")
        |        }
        |        setContentView(webView)
        |    }
        |}
    """.trimMargin()

    private fun buildManifestXml(design: AppDesign): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
        design.permissions.distinct().forEach { perm ->
            appendLine("""    <uses-permission android:name="$perm" />""")
        }
        appendLine("""    <application""")
        appendLine("""        android:allowBackup="true"""")
        appendLine("""        android:label="@string/app_name"""")
        appendLine("""        android:supportsRtl="true">""")
        appendLine("""        <activity""")
        appendLine("""            android:name=".MainActivity"""")
        appendLine("""            android:exported="true">""")
        appendLine("""            <intent-filter>""")
        appendLine("""                <action android:name="android.intent.action.MAIN" />""")
        appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
        appendLine("""            </intent-filter>""")
        appendLine("""        </activity>""")
        design.components.forEach { comp ->
            when (comp.kind) {
                "Service" -> appendLine("""        <service android:name=".${comp.name}" android:exported="false" />""")
                "BroadcastReceiver" -> appendLine("""        <receiver android:name=".${comp.name}" android:exported="false" />""")
            }
        }
        appendLine("""    </application>""")
        appendLine("""</manifest>""")
    }

    private fun buildMainActivityKt(design: AppDesign): String = buildString {
        appendLine("package ${design.packageId}")
        appendLine()
        appendLine("import android.os.Bundle")
        appendLine("import androidx.activity.ComponentActivity")
        appendLine("import androidx.activity.compose.setContent")
        appendLine("import androidx.activity.enableEdgeToEdge")
        appendLine("import androidx.compose.foundation.layout.*")
        appendLine("import androidx.compose.material3.*")
        appendLine("import androidx.compose.runtime.*")
        appendLine("import androidx.compose.ui.Modifier")
        appendLine("import androidx.compose.ui.unit.dp")
        appendLine()
        appendLine("class MainActivity : ComponentActivity() {")
        appendLine("    override fun onCreate(savedInstanceState: Bundle?) {")
        appendLine("        super.onCreate(savedInstanceState)")
        appendLine("        enableEdgeToEdge()")
        appendLine("        setContent { GeneratedApp() }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("fun GeneratedApp() {")
        appendLine("    var currentScreen by remember { mutableStateOf(\"${design.screens.firstOrNull()?.name ?: "Home"}\") }")
        design.stateVars.forEach { sv ->
            val initVal = if (sv.type == "bool") sv.initialValue else "\"${sv.initialValue.replace("\"", "\\\"")}\""
            appendLine("    var ${sv.key} by remember { mutableStateOf($initVal) }")
        }
        appendLine("    MaterialTheme {")
        appendLine("        Scaffold { padding ->")
        appendLine("            Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {")
        appendLine("                when (currentScreen) {")
        design.screens.forEach { screen ->
            appendLine("                    \"${screen.name}\" -> {")
            appendLine("                        Text(\"${screen.name}\", style = MaterialTheme.typography.headlineSmall)")
            screen.elements.forEach { el ->
                when (el.kind) {
                    UiElementKind.TEXT -> appendLine("                        Text(\"${el.label}\")")
                    UiElementKind.BUTTON -> appendLine("                        Button(onClick = { /* ${el.onTapCode} */ }) { Text(\"${el.label}\") }")
                    UiElementKind.INPUT -> if (el.stateKey.isNotBlank()) {
                        appendLine("                        OutlinedTextField(value = ${el.stateKey}.toString(), onValueChange = { ${el.stateKey} = it }, label = { Text(\"${el.label}\") })")
                    }
                    UiElementKind.SWITCH -> if (el.stateKey.isNotBlank()) {
                        appendLine("                        Row { Text(\"${el.label}\"); Switch(checked = ${el.stateKey} == true, onCheckedChange = { ${el.stateKey} = it }) }")
                    }
                    UiElementKind.CARD -> appendLine("                        Card { Text(\"${el.label}\", modifier = Modifier.padding(16.dp)) }")
                    else -> appendLine("                        Text(\"${el.kind.label}: ${el.label}\")")
                }
            }
            appendLine("                    }")
        }
        appendLine("                }")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
    }

    private fun serializeAppDesign(design: AppDesign): String = JSONObject().apply {
        put("appName", design.appName)
        put("packageId", design.packageId)
        put("versionName", design.versionName)
        put("minSdk", design.minSdk)
        put("targetSdk", design.targetSdk)
        put("buildLanguage", design.buildLanguage.name)
        put("permissions", JSONArray(design.permissions))
        val screensArr = JSONArray()
        design.screens.forEach { s ->
            screensArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                val els = JSONArray()
                s.elements.forEach { e ->
                    els.put(JSONObject().apply {
                        put("id", e.id); put("kind", e.kind.name); put("label", e.label)
                        put("stateKey", e.stateKey); put("onTapCode", e.onTapCode)
                    })
                }
                put("elements", els)
            })
        }
        put("screens", screensArr)
    }.toString(2)

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
