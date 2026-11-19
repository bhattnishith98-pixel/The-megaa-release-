package com.example.lang

/**
 * Centralized configuration for StudioLang so the language name, file extension,
 * indentation width, and table-driven keywords can be customized in one place.
 */
object LanguageConfig {
    const val LANGUAGE_NAME = "StudioLang"
    const val FILE_EXTENSION = ".stl"
    const val LAYOUT_EXTENSION = ".stl.layout.json"
    const val PACKAGE_EXTENSION = ".stlpkg"
    const val APP_BUNDLE_EXTENSION = ".stla"
    const val BYTECODE_MAGIC = 0x53544C31 // "STL1"
    const val BYTECODE_VERSION = 1
    const val INDENT_WIDTH = 4
}

enum class StudioCapability(val id: String, val title: String, val description: String) {
    FILE_ACCESS("FILE_ACCESS", "File Access", "Read, write, copy, list, or delete files inside studio workspace or user-picked SAF folders."),
    APP_CONFIGURATION("APP_CONFIGURATION", "App Configuration", "Generate or modify AndroidManifest, resources, and app models."),
    MACRO_EXECUTION("MACRO_EXECUTION", "Macro Execution", "Run automated workflows, schedules, and background macro services."),
    SCREEN_CAPTURE("SCREEN_CAPTURE", "Screen Capture", "Observe visible screen frames through user-consented MediaProjection."),
    ACCESSIBILITY_CONTROL("ACCESSIBILITY_CONTROL", "Accessibility Control", "Inspect UI text and dispatch taps, typing, scrolling, or navigation via AccessibilityService."),
    BUILD_MANAGEMENT("BUILD_MANAGEMENT", "Build Management", "Validate project configurations and export native Gradle bundles."),
    CREDENTIAL_MANAGEMENT("CREDENTIAL_MANAGEMENT", "Credential Management", "Store, retrieve, or delete encrypted secrets in Android Keystore."),
    NOTIFICATIONS("NOTIFICATIONS", "Notifications", "Display or cancel system notifications."),
    NETWORK_ACCESS("NETWORK_ACCESS", "Network Access", "Check connectivity state and perform HTTP requests."),
    SYSTEM_INFO("SYSTEM_INFO", "System Info", "Read battery, device, and installed app metadata."),
    AGENT_AI("AGENT_AI", "Agent AI", "Invoke AI model analysis and agent planning tools."),
    SHIZUKU_PRIVILEGED("SHIZUKU_PRIVILEGED", "Shizuku Privileged Shell", "Run ADB-level Android controls and system edits via Shizuku when installed and authorized."),
    ROOT_SUPERUSER("ROOT_SUPERUSER", "Root Superuser (su)", "Execute root-level Android system modifications via `su` on rooted devices.");

    companion object {
        fun fromId(raw: String): StudioCapability? =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
    }
}

enum class StudioType(val label: String) {
    NUMBER("number"),
    TEXT("text"),
    BOOL("bool"),
    LIST("list"),
    TUPLE("tuple"),
    SET("set"),
    MAP("map"),
    OBJECT("object"),
    NONE("none"),
    TIME("time"),
    DURATION("duration"),
    FILE("file"),
    NODE("node"),
    ANY("any");

    fun isAssignableFrom(other: StudioType): Boolean {
        if (this == ANY || other == ANY) return true
        if (this == other) return true
        if (this == TEXT && (other == NUMBER || other == BOOL || other == DURATION || other == TIME)) return false
        if (this == LIST && (other == TUPLE || other == SET)) return true
        return false
    }

    companion object {
        fun fromLabel(raw: String): StudioType? =
            entries.firstOrNull { it.label.equals(raw.trim(), ignoreCase = true) }
    }
}
