# Automation Studio

Android application built with Kotlin, Jetpack Compose (Material Design 3), NDK/CMake, and Godot Engine Android runtime (`org.godotengine:godot:4.7.2.stable`).

## Key Modules & Features

- **Studio Dashboard (`StudioDashboardScreen`)**: Automation overview, system/network triggers, and device integration controls.
- **Unified IDE (`UnifiedIdeScreen`)**: Polyglot editor workspace with Android Code Studio, VS Code core features, external repo bridges, and Godot runtime/editor launch entries (`GodotRuntimeActivity` & `GodotEditorActivity` with `-PgodotEditorBuild=true`).
- **Node & Code Studio (`NodeAndCodeStudioScreen`)**: Visual node graph builder, Macro Engine, and StudioLang compiler, IR optimizer, bytecode verifier, and VM (`com.example.lang` & `com.example.vm`).
- **AI Agent Workspace (`AgentBriefingScreen`)**: Interactive agent tools, voice controller, and BYOK REST AI clients (OpenAI, Gemini, Anthropic, Sarvam AI) stored in Android Keystore.
- **App Builder (`AppBuilderScreen`)**: In-app Android project generator and release keystore tooling (`com.example.appbuilder`).
- **Docs & Examples (`DocsScreen`)**: Built-in language specification, examples library, and documentation registry.

## Build Requirements

- JDK 21, Android SDK 36, NDK `28.2.13676358`, CMake `3.22.1`
- Gradle Kotlin DSL (`compile_applet` / `gradle assembleDebug`)
- Optional editor-enabled Godot build: `-PgodotEditorBuild=true`