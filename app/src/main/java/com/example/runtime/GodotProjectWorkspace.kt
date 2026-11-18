package com.example.runtime

import android.content.Context
import com.example.ide.GodotNodeType
import com.example.ide.GodotSceneNode
import com.example.ide.IdeFileEntry
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synchronizes Automation Studio's IDE workspace (project.godot, .tscn scenes, .gd scripts)
 * to a real writable Godot 4.7.2.stable project directory on disk so both
 * [GodotRuntimeActivity] and [GodotEditorActivity] can open, edit, and run it natively.
 */
object GodotProjectWorkspace {

    data class SyncResult(
        val projectDir: File,
        val mainSceneResPath: String,
        val writtenFilesCount: Int
    )

    private const val WORKSPACE_FOLDER = "godot_projects/active_workspace"
    private val SKIP_IMPORT_DIRS = setOf(".godot", ".import", "build", ".git")
    private val IMPORTABLE_EXTENSIONS = setOf("godot", "tscn", "tres", "gd", "gdshader", "cfg", "md", "txt")
    private const val MAX_IMPORT_BYTES = 128 * 1024L

    fun activeProjectDir(context: Context): File =
        File(context.filesDir, WORKSPACE_FOLDER).apply { mkdirs() }

    fun defaultProjectGodot(appName: String = "Automation Studio Project"): String = """
        |; Engine configuration file.
        |; Configured for Godot 4.7.2.stable inside Automation Studio.
        |
        |config_version=5
        |
        |[application]
        |
        |config/name="$appName"
        |run/main_scene="res://scenes/main.tscn"
        |config/features=PackedStringArray("4.7", "GL Compatibility")
        |
        |[display]
        |
        |window/size/viewport_width=1280
        |window/size/viewport_height=720
        |window/handheld/orientation=1
        |
        |[input]
        |
        |ui_left={
        |"deadzone": 0.5,
        |"events": []
        |}
        |ui_right={
        |"deadzone": 0.5,
        |"events": []
        |}
        |ui_up={
        |"deadzone": 0.5,
        |"events": []
        |}
        |ui_accept={
        |"deadzone": 0.5,
        |"events": []
        |}
        |
        |[rendering]
        |
        |renderer/rendering_method="gl_compatibility"
        |renderer/rendering_method.mobile="gl_compatibility"
        |textures/default_filters/use_nearest_mipmap_filter=false
    """.trimMargin() + "\n"

    /**
     * Generates a real Godot 4.7.2 runnable 2D .tscn file from the IDE scene graph,
     * attaching valid StaticBody2D/CollisionShape2D shapes, tappable TouchScreenButton shapes,
     * CanvasLayer live HUD, and GDScript files.
     */
    fun serializeRunnableGodot47Tscn(
        sceneName: String,
        nodes: List<GodotSceneNode>,
        validGdScripts: Set<String>
    ): String = buildString {
        val shapeNodes = nodes.filter {
            it.type == GodotNodeType.COLLISION_SHAPE_2D || it.type == GodotNodeType.TOUCH_ZONE
        }
        val scriptRefs = linkedMapOf<String, String>()
        var extCounter = 1
        nodes.forEach { n ->
            val sp = n.scriptPath
            if (!sp.isNullOrBlank() && sp in validGdScripts && sp !in scriptRefs) {
                scriptRefs[sp] = "${extCounter}_gd"
                extCounter++
            }
        }
        val loadSteps = 1 + scriptRefs.size + shapeNodes.size
        appendLine("[gd_scene load_steps=$loadSteps format=3]")
        appendLine()
        scriptRefs.forEach { (sp, id) ->
            appendLine("[ext_resource type=\"Script\" path=\"res://$sp\" id=\"$id\"]")
        }
        shapeNodes.forEachIndexed { idx, sn ->
            appendLine()
            appendLine("[sub_resource type=\"RectangleShape2D\" id=\"RectShape_${idx + 1}\"]")
            appendLine("size = Vector2(${sn.width.coerceAtLeast(24f)}, ${sn.height.coerceAtLeast(24f)})")
        }
        appendLine()
        appendLine("[node name=\"$sceneName\" type=\"Node2D\"]")
        appendLine()
        appendLine("[node name=\"InteractiveHud\" type=\"CanvasLayer\" parent=\".\"]")
        appendLine()
        appendLine("[node name=\"LiveHint\" type=\"Label\" parent=\"InteractiveHud\"]")
        appendLine("offset_left = 18.0")
        appendLine("offset_top = 14.0")
        appendLine("offset_right = 760.0")
        appendLine("offset_bottom = 52.0")
        appendLine("text = \"Godot 4.7.2-stable Live · Touch & Drag Interactive\"")
        var shapeIdx = 0
        nodes.forEach { n ->
            val godotType = when (n.type) {
                GodotNodeType.NODE_2D -> "Node2D"
                GodotNodeType.SPRITE_2D -> "Sprite2D"
                GodotNodeType.CHARACTER_BODY_2D -> "CharacterBody2D"
                GodotNodeType.COLLISION_SHAPE_2D -> "CollisionShape2D"
                GodotNodeType.CAMERA_2D -> "Camera2D"
                GodotNodeType.LABEL -> "Label"
                GodotNodeType.TIMER -> "Timer"
                GodotNodeType.TOUCH_ZONE -> "TouchScreenButton"
                GodotNodeType.AUDIO_PLAYER -> "AudioStreamPlayer"
            }
            if (n.type == GodotNodeType.COLLISION_SHAPE_2D) {
                shapeIdx++
                val bodyName = "${n.name}_Body"
                appendLine()
                appendLine("[node name=\"$bodyName\" type=\"StaticBody2D\" parent=\".\"]")
                appendLine("position = Vector2(${n.x}, ${n.y})")
                appendLine()
                appendLine("[node name=\"${n.name}\" type=\"CollisionShape2D\" parent=\"$bodyName\"]")
                appendLine("shape = SubResource(\"RectShape_$shapeIdx\")")
                appendLine()
                appendLine("[node name=\"FloorVisual\" type=\"ColorRect\" parent=\"$bodyName\"]")
                val hw = n.width / 2f
                val hh = n.height / 2f
                appendLine("offset_left = -$hw")
                appendLine("offset_top = -$hh")
                appendLine("offset_right = $hw")
                appendLine("offset_bottom = $hh")
                val r = ((n.tintHex ushr 16) and 0xFF) / 255f
                val g = ((n.tintHex ushr 8) and 0xFF) / 255f
                val b = (n.tintHex and 0xFF) / 255f
                appendLine("color = Color($r, $g, $b, 0.85)")
                return@forEach
            }

            appendLine()
            appendLine("[node name=\"${n.name}\" type=\"$godotType\" parent=\".\"]")
            if (n.type != GodotNodeType.TIMER && n.type != GodotNodeType.AUDIO_PLAYER) {
                appendLine("position = Vector2(${n.x}, ${n.y})")
            }
            if (n.type == GodotNodeType.TOUCH_ZONE) {
                shapeIdx++
                appendLine("shape = SubResource(\"RectShape_$shapeIdx\")")
                appendLine("action = \"ui_up\"")
            }
            if (n.type == GodotNodeType.LABEL && n.labelText.isNotBlank()) {
                appendLine("text = \"${n.labelText.replace("\"", "\\\"")}\"")
            }
            if (n.type == GodotNodeType.CAMERA_2D) {
                appendLine("enabled = true")
            }
            if (!n.visible) {
                appendLine("visible = false")
            }
            val sid = n.scriptPath?.let { scriptRefs[it] }
            if (sid != null) {
                appendLine("script = ExtResource(\"$sid\")")
            }
            if (n.type == GodotNodeType.SPRITE_2D || n.type == GodotNodeType.CHARACTER_BODY_2D || n.type == GodotNodeType.TOUCH_ZONE) {
                appendLine()
                appendLine("[node name=\"VisualRect\" type=\"ColorRect\" parent=\"${n.name}\"]")
                val hw = n.width / 2f
                val hh = n.height / 2f
                appendLine("offset_left = -$hw")
                appendLine("offset_top = -$hh")
                appendLine("offset_right = $hw")
                appendLine("offset_bottom = $hh")
                val r = ((n.tintHex ushr 16) and 0xFF) / 255f
                val g = ((n.tintHex ushr 8) and 0xFF) / 255f
                val b = (n.tintHex and 0xFF) / 255f
                appendLine("color = Color($r, $g, $b, 1)")
            }
        }
    }

    /**
     * Converts IDE polyglot-style GDScript snippets (which may use `move(...)` shorthand
     * and omit `extends ...`) into valid, live-interactive Godot 4.7.2 GDScript with
     * touch/drag input handling and AutomationStudioBridge telemetry.
     */
    fun normalizeGdScriptForGodot47(source: String, nodeType: GodotNodeType): String {
        val trimmed = source.trimStart()
        val hasExtends = trimmed.startsWith("extends ")
        val baseClass = when (nodeType) {
            GodotNodeType.CHARACTER_BODY_2D -> "CharacterBody2D"
            GodotNodeType.SPRITE_2D -> "Sprite2D"
            GodotNodeType.COLLISION_SHAPE_2D -> "CollisionShape2D"
            GodotNodeType.CAMERA_2D -> "Camera2D"
            GodotNodeType.LABEL -> "Label"
            GodotNodeType.TIMER -> "Timer"
            GodotNodeType.TOUCH_ZONE -> "TouchScreenButton"
            else -> "Node2D"
        }
        val body = source
            .replace(Regex("""\bmove\s*\(\s*([^,]+)\s*,\s*([^)]+)\s*\)"""), "position += Vector2($1, $2)")
            .replace(Regex("""\bvoice_speak\s*\(\s*([^)]+)\s*\)"""), "print($1)")
            .replace(Regex("""\bset_prop\s*\(\s*[^,]+,\s*([^)]+)\s*\)"""), "print($1)")
        val withExtends = if (hasExtends) body else "extends $baseClass\n\n$body"
        val needsInteractiveHook = (nodeType == GodotNodeType.CHARACTER_BODY_2D || nodeType == GodotNodeType.SPRITE_2D || nodeType == GodotNodeType.NODE_2D) &&
            !withExtends.contains("func _input(") && !withExtends.contains("func _unhandled_input(")
        return if (!needsInteractiveHook) {
            withExtends
        } else {
            withExtends.trimEnd() + """
                |
                |var _studio_bridge_ref: Object = null
                |
                |func _input(event: InputEvent) -> void:
                |    if _studio_bridge_ref == null and Engine.has_singleton("AutomationStudioBridge"):
                |        _studio_bridge_ref = Engine.get_singleton("AutomationStudioBridge")
                |    if event is InputEventScreenTouch and event.pressed:
                |        position = position.lerp(event.position, 0.35)
                |        if _studio_bridge_ref:
                |            _studio_bridge_ref.emitStudioEvent("touch_move", "%s -> (%d,%d)" % [name, int(position.x), int(position.y)])
                |    elif event is InputEventScreenDrag:
                |        position += event.relative
            """.trimMargin() + "\n"
        }
    }

    fun syncIdeToGodotProject(
        context: Context,
        files: List<IdeFileEntry>,
        sceneNodes: List<GodotSceneNode>
    ): SyncResult {
        val projectDir = activeProjectDir(context)
        val canonicalRoot = projectDir.canonicalPath
        var written = 0

        fun writeSafe(relPath: String, content: String) {
            val cleanRel = relPath.trimStart('/')
            val target = File(projectDir, cleanRel).canonicalFile
            require(target.path.startsWith(canonicalRoot + File.separator)) {
                "Unsafe path rejected: $relPath"
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            written++
        }

        // 1. Sync GDScript files (normalized for real Godot 4.7.2 execution)
        val gdPaths = mutableSetOf<String>()
        val nodeByScript = sceneNodes.filter { !it.scriptPath.isNullOrBlank() }.associateBy { it.scriptPath!! }
        files.filter { it.path.endsWith(".gd", ignoreCase = true) && !it.path.startsWith("repos/") && !it.path.startsWith("android/") }
            .forEach { file ->
                val nodeType = nodeByScript[file.path]?.type ?: GodotNodeType.NODE_2D
                val gdBody = normalizeGdScriptForGodot47(file.content, nodeType)
                writeSafe(file.path, gdBody)
                gdPaths += file.path
            }

        // 2. Sync any bundled 3D demo assets from APK assets so 3D scenes are also editable in Godot Editor
        runCatching {
            val demo3d = context.assets.open("main.tscn").bufferedReader().use { it.readText() }
            writeSafe("scenes/studio_3d.tscn", demo3d)
            val demoGd = context.assets.open("scripts/runtime_demo.gd").bufferedReader().use { it.readText() }
            writeSafe("scripts/runtime_demo.gd", demoGd)
        }

        // 3. Sync scenes/*.tscn (preserve existing imported format=3 scenes or generate runnable Godot 4.7.2 main.tscn)
        val projectGodotFile = files.firstOrNull { it.path == "project.godot" }
        val configuredMainRes = projectGodotFile?.content?.let { body ->
            Regex("""run/main_scene\s*=\s*"(res://[^"]+)"""")
                .find(body)?.groupValues?.getOrNull(1)
        }
        val configuredMainRel = configuredMainRes?.removePrefix("res://")
        val hasExistingMainTscn = configuredMainRel != null && files.any { it.path == configuredMainRel }

        val customMainTscn = files.firstOrNull { it.path == "scenes/main.tscn" }
        if (!hasExistingMainTscn || configuredMainRel == "scenes/main.tscn") {
            val tscnToWrite = if (
                customMainTscn != null &&
                (customMainTscn.content.contains("ColorRect") || customMainTscn.content.contains("format=3"))
            ) {
                customMainTscn.content
            } else {
                serializeRunnableGodot47Tscn("MainScene", sceneNodes, gdPaths)
            }
            writeSafe("scenes/main.tscn", tscnToWrite)
        }

        files.filter {
            it.path.endsWith(".tscn", ignoreCase = true) &&
                it.path != "scenes/main.tscn" &&
                !it.path.startsWith("repos/") &&
                !it.path.startsWith("android/")
        }.forEach { writeSafe(it.path, it.content) }

        // Also sync non-script config/asset text files (e.g., .tres, .cfg, .gdshader) from custom projects
        files.filter {
            (it.path.endsWith(".tres", ignoreCase = true) ||
                it.path.endsWith(".cfg", ignoreCase = true) ||
                it.path.endsWith(".gdshader", ignoreCase = true)) &&
                !it.path.startsWith("repos/") &&
                !it.path.startsWith("android/")
        }.forEach { writeSafe(it.path, it.content) }

        // 4. Write project.godot (use IDE's project.godot if edited, else default)
        writeSafe("project.godot", projectGodotFile?.content ?: defaultProjectGodot())

        val resolvedMainScene = if (hasExistingMainTscn && configuredMainRes != null) {
            configuredMainRes
        } else {
            "res://scenes/main.tscn"
        }

        return SyncResult(
            projectDir = projectDir,
            mainSceneResPath = resolvedMainScene,
            writtenFilesCount = written
        )
    }

    fun importGodotProjectToIde(projectDir: File): List<IdeFileEntry> {
        if (!projectDir.isDirectory) return emptyList()
        val canonicalBase = projectDir.canonicalPath
        val imported = mutableListOf<IdeFileEntry>()
        projectDir.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP_IMPORT_DIRS }
            .forEach { file ->
                if (file.isFile && file.length() <= MAX_IMPORT_BYTES) {
                    val ext = file.extension.lowercase()
                    if (ext in IMPORTABLE_EXTENSIONS) {
                        val rel = file.canonicalPath.removePrefix(canonicalBase)
                            .trimStart(File.separatorChar, '/')
                            .replace('\\', '/')
                        if (rel.isNotBlank() && rel != "scenes/studio_3d.tscn") {
                            runCatching { file.readText() }.getOrNull()?.let { body ->
                                val langHint = when (ext) {
                                    "gd" -> "gdscript"
                                    "tscn", "tres", "godot" -> "godot-scene"
                                    else -> ext
                                }
                                imported += IdeFileEntry(
                                    path = rel,
                                    title = file.name,
                                    content = body,
                                    languageHint = langHint
                                )
                            }
                        }
                    }
                }
            }
        return imported
    }

    fun findMountedGodotProjectDir(workspaceRoot: File): File? {
        val godotRepoDir = File(workspaceRoot, "external_repos/godot")
        if (!godotRepoDir.isDirectory) return null
        val direct = File(godotRepoDir, "project.godot")
        if (direct.isFile) return godotRepoDir
        return godotRepoDir.walkTopDown()
            .maxDepth(3)
            .firstOrNull { it.isFile && it.name == "project.godot" }
            ?.parentFile
    }

    fun exportGodotProjectZip(projectDir: File, outputZip: File): File {
        outputZip.parentFile?.mkdirs()
        val canonicalBase = projectDir.canonicalPath
        ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
            projectDir.walkTopDown()
                .onEnter { dir -> dir.name !in SKIP_IMPORT_DIRS }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.canonicalPath.removePrefix(canonicalBase)
                        .trimStart(File.separatorChar, '/')
                        .replace('\\', '/')
                    if (rel.isNotBlank()) {
                        zos.putNextEntry(ZipEntry("godot_project/$rel"))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
        }
        return outputZip
    }
}
