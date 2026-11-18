package com.example.ide

import java.util.UUID

enum class IdeWorkspaceMode(val label: String, val subtitle: String) {
    DUAL_GAME_AND_APP("Godot + VS Code + ACS", "Game engine with multi-language scripts + Android packaging"),
    ANDROID_CODE_STUDIO("Android Code Studio", "Full Android App IDE, UI designer, live test & APK builder")
}

enum class IdeThemePreset(
    val label: String,
    val editorBgHex: Long,
    val gutterBgHex: Long,
    val keywordHex: Long,
    val stringHex: Long,
    val accentHex: Long
) {
    OBSIDIAN_STUDIO("Automation Obsidian", 0xFF0B0F17, 0xFF111827, 0xFFA78BFA, 0xFF34D399, 0xFF38BDF8),
    VS_DARK_PLUS("VS Code Dark+", 0xFF1E1E1E, 0xFF252526, 0xFF569CD6, 0xFFCE9178, 0xFF007ACC),
    DRACULA_NIGHT("Dracula Night", 0xFF282A36, 0xFF21222C, 0xFFFF79C6, 0xFFF1FA8C, 0xFFBD93F9),
    MONOKAI_PRO("Monokai Pro", 0xFF2D2A2E, 0xFF221F22, 0xFFFF6188, 0xFFFFD866, 0xFF78DCE8)
}

enum class ScriptLanguage(val label: String, val extension: String, val badge: String) {
    GDSCRIPT("GDScript", ".gd", "GD"),
    PYTHON("Python", ".py", "PY"),
    STUDIOLANG("StudioLang", ".stl", "STL"),
    KOTLIN("Kotlin", ".kt", "KT"),
    JAVA("Java", ".java", "JV"),
    JAVASCRIPT("JavaScript", ".js", "JS"),
    CPP("C / C++", ".cpp", "C++")
}

enum class ScriptTemplateKind(val label: String) {
    MOVEMENT("Player Movement & Physics"),
    AI_PATROL("Enemy AI Patrol"),
    TOUCH_INPUT("Touch & Gesture Controller"),
    VOICE_REACTIVE("Voice & StudioLang Trigger"),
    EMPTY("Blank Script")
}

enum class GodotNodeType(val label: String, val defaultColorHex: Long) {
    NODE_2D("Node2D", 0xFF94A3B8),
    SPRITE_2D("Sprite2D", 0xFF38BDF8),
    CHARACTER_BODY_2D("CharacterBody2D", 0xFF34D399),
    COLLISION_SHAPE_2D("CollisionShape2D", 0xFFFBBF24),
    CAMERA_2D("Camera2D", 0xFFA78BFA),
    LABEL("Label", 0xFFF8FAFC),
    TIMER("Timer", 0xFFFB7185),
    TOUCH_ZONE("TouchZone", 0xFF22D3EE),
    AUDIO_PLAYER("AudioStreamPlayer", 0xFFC084FC)
}

data class GodotSceneNode(
    val id: String = "node_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val type: GodotNodeType,
    val x: Float = 160f,
    val y: Float = 120f,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val width: Float = 52f,
    val height: Float = 52f,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
    val gravityScale: Float = 0f,
    val tintHex: Long = type.defaultColorHex,
    val labelText: String = "",
    val visible: Boolean = true,
    val solidCollision: Boolean = type == GodotNodeType.CHARACTER_BODY_2D || type == GodotNodeType.COLLISION_SHAPE_2D,
    val scriptPath: String? = null,
    val scriptLanguage: ScriptLanguage? = null
)

data class IdeFileEntry(
    val path: String,
    val title: String,
    val content: String,
    val languageHint: String,
    val isDirty: Boolean = false,
    val attachedNodeName: String? = null
)

enum class IdeActivityTab(val label: String) {
    EXPLORER("Explorer"),
    SCENE_TREE("Scene"),
    SEARCH("Search"),
    SOURCE_CONTROL("Git"),
    OUTLINE("Outline"),
    EXTENSIONS("Extensions"),
    ANDROID_IDE("Android"),
    REPOS_BRIDGES("Repos"),
    VOICE_AI("Voice")
}

enum class IdeRightInspectorTab(val label: String) {
    INSPECTOR("Inspector"),
    ANDROID_PREVIEW("Android IDE"),
    VOICE_CONSOLE("Voice / AI")
}

enum class IdeBottomPanelTab(val label: String) {
    TERMINAL("Terminal"),
    PROBLEMS("Problems"),
    OUTPUT("Test Output"),
    LOGCAT("Logcat")
}

data class IdeDiagnostic(
    val filePath: String,
    val line: Int,
    val severity: String,
    val message: String
)

data class IdeExtensionPack(
    val id: String,
    val name: String,
    val publisher: String,
    val description: String,
    val version: String,
    val enabled: Boolean = true
)

data class IdeCommandItem(
    val id: String,
    val title: String,
    val category: String,
    val shortcut: String
)

data class OpenSourceLicenseNotice(
    val name: String,
    val repoUrl: String,
    val license: String,
    val summary: String
)

object UnifiedIdeLicenses {
    val notices: List<OpenSourceLicenseNotice> = listOf(
        OpenSourceLicenseNotice(
            name = "Visual Studio Code (microsoft/vscode)",
            repoUrl = "https://github.com/microsoft/vscode",
            license = "MIT License",
            summary = "Workbench shell UX patterns: Activity Bar, Explorer, Multi-tab Editor, Command Palette, Extensions, Problems, and Integrated Terminal."
        ),
        OpenSourceLicenseNotice(
            name = "Godot Engine (godotengine/godot)",
            repoUrl = "https://github.com/godotengine/godot",
            license = "MIT License",
            summary = "Scene Node tree hierarchy, 2D Viewport transform gizmos, Inspector workflow, Attach Script dialog, and .tscn/.gd interoperability."
        ),
        OpenSourceLicenseNotice(
            name = "Android Code Studio (AndroidCSOfficial/android-code-studio)",
            repoUrl = "https://github.com/AndroidCSOfficial/android-code-studio",
            license = "MIT / GPL-3.0 Ecosystem Inspiration",
            summary = "On-device Android Gradle project explorer, UI layout live preview & interactive testing, manifest audit, and project export workflows."
        )
    )
}
