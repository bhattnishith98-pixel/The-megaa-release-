package com.example

import com.example.agent.AiProviderKind
import com.example.appbuilder.AppDesign
import com.example.ide.ExternalRepoBridgeEngine
import com.example.ide.GodotNodeType
import com.example.ide.GodotSceneNode
import com.example.ide.PolyglotGodotCompiler
import com.example.ide.RepoImportMode
import com.example.ide.ScriptLanguage
import com.example.ide.UnifiedIdeController
import com.example.ide.UpstreamRepoKind
import com.example.runtime.AutomationStudioGodotPlugin
import com.example.runtime.GodotCommandLineSanitizer
import com.example.runtime.GodotProjectWorkspace
import com.example.ui.screens.AiStudioWebPortals
import com.example.vm.CapabilityManager
import com.example.vm.HostApiDispatcher
import com.example.vm.NoneVal
import com.example.vm.StudioCompiler
import com.example.vm.StudioValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class Godot47IntegrationTest {

    @Test
    fun defaultProjectGodotTargetsGodot47GlCompatibility() {
        val cfg = GodotProjectWorkspace.defaultProjectGodot("Test Studio Game")
        assertTrue(cfg.contains("config_version=5"))
        assertTrue(cfg.contains("PackedStringArray(\"4.7\", \"GL Compatibility\")"))
        assertTrue(cfg.contains("run/main_scene=\"res://scenes/main.tscn\""))
        assertTrue(cfg.contains("config/name=\"Test Studio Game\""))
    }

    @Test
    fun serializeRunnableGodot47TscnEmitsShapesVisualsTouchAndScripts() {
        val nodes = listOf(
            GodotSceneNode(
                id = "p1",
                name = "Player",
                type = GodotNodeType.CHARACTER_BODY_2D,
                x = 120f,
                y = 140f,
                width = 48f,
                height = 48f,
                scriptPath = "scripts/player_controller.gd",
                scriptLanguage = ScriptLanguage.GDSCRIPT
            ),
            GodotSceneNode(
                id = "c1",
                name = "Floor",
                type = GodotNodeType.COLLISION_SHAPE_2D,
                x = 240f,
                y = 280f,
                width = 320f,
                height = 32f
            ),
            GodotSceneNode(
                id = "t1",
                name = "JumpPad",
                type = GodotNodeType.TOUCH_ZONE,
                x = 300f,
                y = 220f,
                width = 80f,
                height = 60f,
                labelText = "JUMP"
            )
        )
        val tscn = GodotProjectWorkspace.serializeRunnableGodot47Tscn(
            sceneName = "MainScene",
            nodes = nodes,
            validGdScripts = setOf("scripts/player_controller.gd")
        )
        assertTrue(tscn.contains("format=3"))
        assertTrue(tscn.contains("[ext_resource type=\"Script\" path=\"res://scripts/player_controller.gd\""))
        assertTrue(tscn.contains("[sub_resource type=\"RectangleShape2D\" id=\"RectShape_1\"]"))
        assertTrue(tscn.contains("type=\"StaticBody2D\""))
        assertTrue(tscn.contains("type=\"TouchScreenButton\""))
        assertTrue(tscn.contains("action = \"ui_up\""))
        assertTrue(tscn.contains("[node name=\"InteractiveHud\" type=\"CanvasLayer\" parent=\".\"]"))
        assertTrue(tscn.contains("[node name=\"VisualRect\" type=\"ColorRect\" parent=\"Player\"]"))
    }

    @Test
    fun normalizeGdScriptTranslatesShorthandAddsExtendsAndLiveInput() {
        val raw = """
            |func _process(delta):
            |    move(speed, 0)
            |    voice_speak("hi")
        """.trimMargin()
        val normalized = GodotProjectWorkspace.normalizeGdScriptForGodot47(
            source = raw,
            nodeType = GodotNodeType.CHARACTER_BODY_2D
        )
        assertTrue(normalized.startsWith("extends CharacterBody2D"))
        assertTrue(normalized.contains("position += Vector2(speed, 0)"))
        assertTrue(normalized.contains("print(\"hi\")"))
        assertTrue(normalized.contains("func _input(event: InputEvent) -> void:"))
        assertTrue(normalized.contains("AutomationStudioBridge"))
    }

    @Test
    fun godotUpstreamRepoTargets472StableAndStripsArchiveRoot() {
        assertEquals(
            "https://github.com/godotengine/godot/releases/tag/4.7.2-stable",
            UpstreamRepoKind.GODOT.repoUrl
        )
        assertEquals("godot-4.7.2-stable.zip", UpstreamRepoKind.GODOT.archiveFileName)
        assertEquals(
            "https://github.com/godotengine/godot/archive/refs/tags/4.7.2-stable.zip",
            UpstreamRepoKind.GODOT.archiveDownloadUrl
        )

        val tempRoot = Files.createTempDirectory("godot472_repo_test").toFile()
        try {
            val zipFile = ExternalRepoBridgeEngine.createReadyInAppRepoZip(UpstreamRepoKind.GODOT, tempRoot)
            assertTrue(zipFile.isFile)
            val mounted = zipFile.inputStream().use { stream ->
                ExternalRepoBridgeEngine.mountFromZipStream(
                    kindOverride = UpstreamRepoKind.GODOT,
                    zipDisplayName = zipFile.name,
                    inputStream = stream,
                    workspaceRoot = tempRoot,
                    importMode = RepoImportMode.FULL_SOURCE_AND_COMPILE
                )
            }
            assertTrue(mounted.indexedFiles.isNotEmpty())
            assertFalse(mounted.indexedFiles.any { it.path.contains("godot-4.7.2-stable/") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun aiStudioWebAndGeminiProviderDefaultsAreConfigured() {
        assertEquals("gemini-2.5-flash", AiProviderKind.GEMINI.models.first())
        assertTrue(AiStudioWebPortals.presets.any { it.url == "https://aistudio.google.com/apikey" })
        assertTrue(AiStudioWebPortals.isGoogleOAuthUrl("https://accounts.google.com/v3/signin"))
        assertTrue(AiStudioWebPortals.isGoogleOAuthUrl("https://aistudio.google.com/?error=disallowed_useragent"))
        assertFalse(AiStudioWebPortals.isGoogleOAuthUrl("https://aistudio.google.com/app/prompts/new_chat"))
    }

    @Test
    fun unifiedIdeGradleBuildPipelineCompletesCleanly() = runBlocking {
        val tempRoot = Files.createTempDirectory("ide_gradle_test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ide = UnifiedIdeController(
                scope = scope,
                compiler = StudioCompiler(workspaceRoot = tempRoot),
                capabilityManager = CapabilityManager(),
                hostDispatcher = object : HostApiDispatcher {
                    override suspend fun invokeHost(
                        qualifiedName: String,
                        args: List<StudioValue>,
                        line: Int,
                        logSink: (level: String, message: String) -> Unit
                    ): StudioValue = NoneVal
                },
                workspaceRoot = tempRoot,
                initialAppDesign = AppDesign()
            )
            val phases = ide.executeAndroidGradleBuildPipeline(AppDesign())
            assertFalse(ide.isAcsBuilding.value)
            assertEquals(5, phases.size)
            assertTrue(phases.last().contains("PASS"))
        } finally {
            scope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun importAndExportGodotWorkspaceRoundTripsFiles() {
        val tempRoot = Files.createTempDirectory("godot47_ws_test").toFile()
        try {
            File(tempRoot, "project.godot").writeText(GodotProjectWorkspace.defaultProjectGodot("RoundTrip"))
            File(tempRoot, "scripts").mkdirs()
            File(tempRoot, "scripts/hero.gd").writeText("extends Node2D\n")
            val imported = GodotProjectWorkspace.importGodotProjectToIde(tempRoot)
            assertEquals(2, imported.size)
            assertTrue(imported.any { it.path == "project.godot" })
            assertTrue(imported.any { it.path == "scripts/hero.gd" })

            val zipOut = File(tempRoot, "out/project.zip")
            GodotProjectWorkspace.exportGodotProjectZip(tempRoot, zipOut)
            assertTrue(zipOut.isFile && zipOut.length() > 0L)
            ZipFile(zipOut).use { zf ->
                assertTrue(zf.getEntry("godot_project/project.godot") != null)
                assertTrue(zf.getEntry("godot_project/scripts/hero.gd") != null)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun commandCatalogIncludesGodot47Actions() {
        val ids = PolyglotGodotCompiler.buildCommandCatalog().map { it.id }.toSet()
        assertTrue("cmd.godot_run_47" in ids)
        assertTrue("cmd.godot_editor_47" in ids)
        assertTrue("cmd.godot_project_manager" in ids)
        assertTrue("cmd.godot_3d_demo" in ids)
        assertTrue("cmd.godot_sync_disk" in ids)
        assertTrue("cmd.godot_export_zip" in ids)
    }

    @Test
    fun godotCommandLineSanitizerStripsUnsupportedSetupAbortFlagsAndPreservesSafeFlags() {
        val sanitized = GodotCommandLineSanitizer.sanitizeCommandLine(
            rawArgs = listOf(
                "--editor",
                "--project-manager",
                "--path", "/data/user/0/com.example/files/godot_projects/active_workspace",
                "--scene", "res://scenes/main.tscn",
                "--main-pack", "/sdcard/game.pck",
                "--remote-debug", "tcp://127.0.0.1:6007",
                "--embedded",
                "--unknown-flag",
                "--rendering-driver", "opengl3",
                "--verbose"
            ),
            baseArgs = listOf("--xr-mode", "regular", "--fullscreen")
        )
        assertEquals(
            listOf("--xr-mode", "regular", "--fullscreen", "--rendering-driver", "opengl3", "--verbose"),
            sanitized
        )
        assertFalse("--path" in sanitized)
        assertFalse("--scene" in sanitized)
        assertFalse("--editor" in sanitized)
        assertFalse("--project-manager" in sanitized)
    }

    @Test
    fun godotLaunchConfigResolvesWorkspaceEditorProjectManagerAndDemoModes() {
        val demo = GodotCommandLineSanitizer.extractLaunchConfig()
        assertEquals(GodotCommandLineSanitizer.MODE_DEMO_3D, demo.mode)

        val ws = GodotCommandLineSanitizer.extractLaunchConfig(
            intentProjectPath = "/tmp/active_workspace",
            intentMainScene = "res://scenes/main.tscn"
        )
        assertEquals(GodotCommandLineSanitizer.MODE_WORKSPACE, ws.mode)
        assertEquals("/tmp/active_workspace", ws.projectPath)
        assertEquals("res://scenes/main.tscn", ws.mainScene)

        val editor = GodotCommandLineSanitizer.extractLaunchConfig(
            cliArgs = listOf("--editor", "--path", "/tmp/my_proj")
        )
        assertEquals(GodotCommandLineSanitizer.MODE_EDITOR, editor.mode)
        assertEquals("/tmp/my_proj", editor.projectPath)

        val pm = GodotCommandLineSanitizer.extractLaunchConfig(openProjectManager = true)
        assertEquals(GodotCommandLineSanitizer.MODE_PROJECT_MANAGER, pm.mode)
    }

    @Test
    fun workspaceBridgeRejectsPathTraversalAndResolvesSafeChildren() {
        val tempRoot = Files.createTempDirectory("godot_bridge_path_test").toFile()
        try {
            val safe = AutomationStudioGodotPlugin.resolveSafeWorkspaceFile(
                tempRoot.absolutePath,
                "res://scenes/main.tscn"
            )
            assertTrue(safe != null && safe.canonicalPath.endsWith("scenes${File.separator}main.tscn"))

            val traversal = AutomationStudioGodotPlugin.resolveSafeWorkspaceFile(
                tempRoot.absolutePath,
                "../../etc/passwd"
            )
            assertEquals(null, traversal)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}

