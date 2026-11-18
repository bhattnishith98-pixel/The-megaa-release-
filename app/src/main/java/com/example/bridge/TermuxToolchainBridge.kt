package com.example.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager

data class TermuxInstallPackage(
    val id: String,
    val title: String,
    val description: String,
    val installCommand: String
)

data class TermuxEnvironmentStatus(
    val isTermuxInstalled: Boolean,
    val executionDelegationAllowed: Boolean = false,
    val sandboxNote: String
)

/**
 * Strictly installation-only Termux integration (Part C Part 3).
 * Termux is ONLY used for preparing optional developer packages inside Termux's own sandbox.
 * Automation Studio NEVER delegates code editing, StudioLang compilation, script execution,
 * or APK/AAB building to Termux.
 */
object TermuxToolchainBridge {

    private const val TERMUX_PACKAGE = "com.termux"

    val supportedInstallPackages: List<TermuxInstallPackage> = listOf(
        TermuxInstallPackage(
            id = "python",
            title = "Python 3 & Pip (Termux-local)",
            description = "Installs Python inside Termux for standalone terminal use (Automation Studio uses its own StudioLang VM).",
            installCommand = "pkg update -y && pkg install -y python"
        ),
        TermuxInstallPackage(
            id = "editors",
            title = "Neovim & Micro Editors",
            description = "Installs terminal editors inside Termux.",
            installCommand = "pkg install -y neovim micro"
        ),
        TermuxInstallPackage(
            id = "git_tools",
            title = "Git & OpenSSH",
            description = "Installs Git CLI tools inside Termux for repository syncing.",
            installCommand = "pkg install -y git openssh"
        ),
        TermuxInstallPackage(
            id = "native_toolchain",
            title = "Clang & CMake (Termux-local)",
            description = "Prepares C/C++ packages inside Termux's sandbox.",
            installCommand = "pkg install -y clang cmake make"
        )
    )

    fun inspectStatus(context: Context): TermuxEnvironmentStatus {
        val installed = try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
        return TermuxEnvironmentStatus(
            isTermuxInstalled = installed,
            executionDelegationAllowed = false,
            sandboxNote = "Android isolates app sandboxes: binaries inside Termux cannot be executed directly by Automation Studio. " +
                "All StudioLang compilation, VM execution, and app project generation run natively inside Automation Studio."
        )
    }

    fun copyInstallCommand(context: Context, pkg: TermuxInstallPackage): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Termux Install Command", pkg.installCommand))
        return true
    }
}
