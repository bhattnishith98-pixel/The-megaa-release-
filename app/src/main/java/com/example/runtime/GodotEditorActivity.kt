package com.example.runtime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.example.R
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import java.io.File
import kotlin.system.exitProcess

/**
 * Hosts Godot 4.7.2.stable's native Android runtime alongside an integrated
 * Workspace Inspector, Script/Scene Live Editor, and Project Manager dock.
 *
 * Because `godot-tools:4.7.2.stable`'s bundled `libgodot_android.so` disables
 * `--editor`, `--project-manager`, and `--path` overrides at compile time,
 * native engine CLI arguments are sanitized via [GodotCommandLineSanitizer] while
 * live project editing, scene hot-reload, and project management run cleanly over
 * [AutomationStudioGodotPlugin].
 */
class GodotEditorActivity : FragmentActivity(), GodotHost {
    private var godotFragment: GodotFragment? = null
    private var bridgePlugin: AutomationStudioGodotPlugin? = null
    private var nextRuntimeInstanceId = 100

    private var activeProjectPath: String = ""
    private var isProjectManagerMode: Boolean = false
    private var selectedRelPath: String = ""
    private var panelExpanded: Boolean = false

    private var badgeView: TextView? = null
    private var toggleDockBtn: Button? = null
    private var dockCard: LinearLayout? = null
    private var dockHeaderLabel: TextView? = null
    private var fileChipsRow: LinearLayout? = null
    private var codeEditorInput: EditText? = null
    private var projectListContainer: LinearLayout? = null
    private var editorBodyContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveStateFromIntent(intent)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = FrameLayout(this).apply {
            id = R.id.godot_editor_container
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(container)
        root.addView(buildOverlayUi())
        setContentView(root)
        refreshDockUi()

        val existing = supportFragmentManager.findFragmentById(R.id.godot_editor_container)
        if (existing is GodotFragment) {
            godotFragment = existing
        } else if (savedInstanceState == null) {
            godotFragment = GodotFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.godot_editor_container, godotFragment!!)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveStateFromIntent(intent)
        refreshDockUi()
        bridgePlugin?.dispatchIdeCommand("reload_workspace")
    }

    private fun resolveStateFromIntent(targetIntent: Intent?) {
        isProjectManagerMode = targetIntent?.getBooleanExtra(EXTRA_PROJECT_MANAGER, false) ?: false
        val requestedPath = targetIntent?.getStringExtra(EXTRA_PROJECT_PATH).orEmpty().trim()
        val fallbackDir = GodotProjectWorkspace.activeProjectDir(this)
        activeProjectPath = if (requestedPath.isNotBlank() && File(requestedPath).isDirectory) {
            requestedPath
        } else {
            fallbackDir.absolutePath
        }
        panelExpanded = isProjectManagerMode
    }

    private fun currentLaunchConfig(): GodotCommandLineSanitizer.LaunchConfig =
        GodotCommandLineSanitizer.LaunchConfig(
            mode = if (isProjectManagerMode) {
                GodotCommandLineSanitizer.MODE_PROJECT_MANAGER
            } else {
                GodotCommandLineSanitizer.MODE_EDITOR
            },
            projectPath = activeProjectPath,
            mainScene = ""
        )

    private fun buildOverlayUi(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 11, 17, 32))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(150, 167, 139, 250))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(this).apply {
            text = "Godot 4.7.2 Editor"
            setTextColor(Color.rgb(167, 139, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(6), 0)
        }
        badgeView = badge
        bar.addView(badge)

        val dockBtn = Button(this).apply {
            text = "Files"
            isAllCaps = false
            setTextColor(Color.rgb(56, 189, 248))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Toggle Godot workspace inspector and project list"
            setOnClickListener {
                panelExpanded = !panelExpanded
                refreshDockUi()
            }
        }
        toggleDockBtn = dockBtn
        bar.addView(dockBtn)

        val runBtn = Button(this).apply {
            text = "Run"
            isAllCaps = false
            setTextColor(Color.rgb(52, 211, 153))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Run current Godot workspace"
            setOnClickListener { launchProjectInRuntime(activeProjectPath, "") }
        }
        bar.addView(runBtn)

        val backBtn = Button(this).apply {
            text = "Sync & Back"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(2), 0)
            contentDescription = "Sync workspace and return to IDE"
            setOnClickListener { finish() }
        }
        bar.addView(backBtn)

        wrapper.addView(bar)

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(236, 13, 20, 36))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(130, 56, 189, 248))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        dockCard = dock

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val hdr = TextView(this).apply {
            setTextColor(Color.rgb(226, 232, 240))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dockHeaderLabel = hdr
        headerRow.addView(hdr)

        val switchModeBtn = Button(this).apply {
            text = "Projects / Files"
            isAllCaps = false
            setTextColor(Color.rgb(167, 139, 250))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener {
                isProjectManagerMode = !isProjectManagerMode
                refreshDockUi()
                bridgePlugin?.dispatchIdeCommand("reload_workspace")
            }
        }
        headerRow.addView(switchModeBtn)
        dock.addView(headerRow)

        val pmScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            )
        }
        val pmList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        projectListContainer = pmList
        pmScroll.addView(pmList)

        val edBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        editorBodyContainer = edBody

        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fileChipsRow = chipsRow
        chipsScroll.addView(chipsRow)
        edBody.addView(chipsScroll)

        val codeBox = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.rgb(226, 232, 240))
            setHintTextColor(Color.rgb(148, 163, 184))
            hint = "Select a workspace file above to inspect or edit..."
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 11, 22))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(90, 148, 163, 184))
            }
            minLines = 8
            maxLines = 12
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        codeEditorInput = codeBox
        edBody.addView(codeBox)

        val edActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val saveReloadBtn = Button(this).apply {
            text = "Save & Hot-Reload"
            isAllCaps = false
            setTextColor(Color.rgb(11, 17, 32))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            background = GradientDrawable().apply {
                setColor(Color.rgb(52, 211, 153))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                saveActiveFileAndReload()
            }
        }
        edActions.addView(saveReloadBtn)
        edBody.addView(edActions)

        dock.addView(pmScroll)
        dock.addView(edBody)
        wrapper.addView(dock)

        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left + dp(12), sys.top + dp(8), sys.right + dp(12), dp(8))
            insets
        }
        return wrapper
    }

    private fun refreshDockUi() {
        badgeView?.text = if (isProjectManagerMode) "Godot 4.7.2 Project Mgr" else "Godot 4.7.2 Editor"
        toggleDockBtn?.text = if (panelExpanded) "Hide Dock" else if (isProjectManagerMode) "Projects" else "Files"
        dockCard?.visibility = if (panelExpanded) View.VISIBLE else View.GONE

        val pmParent = projectListContainer?.parent as? View
        if (isProjectManagerMode) {
            dockHeaderLabel?.text = "Godot 4.7.2 Project Manager"
            pmParent?.visibility = View.VISIBLE
            editorBodyContainer?.visibility = View.GONE
            populateProjectManagerList()
        } else {
            val dirName = File(activeProjectPath).name.ifBlank { "workspace" }
            dockHeaderLabel?.text = "Inspector · $dirName"
            pmParent?.visibility = View.GONE
            editorBodyContainer?.visibility = View.VISIBLE
            populateFileChipsAndEditor()
        }
    }

    private fun populateProjectManagerList() {
        val list = projectListContainer ?: return
        list.removeAllViews()

        val newProjBtn = Button(this).apply {
            text = "+ Create New Godot 4.7 Project"
            isAllCaps = false
            setTextColor(Color.rgb(11, 17, 32))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = dp(48)
            background = GradientDrawable().apply {
                setColor(Color.rgb(56, 189, 248))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { createNewProjectWorkspace() }
        }
        list.addView(newProjBtn)

        GodotProjectWorkspace.listProjectDirs(this).forEach { dir ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(19, 28, 48))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.argb(70, 148, 163, 184))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            val hasGodotCfg = File(dir, "project.godot").isFile
            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labelCol.addView(TextView(this).apply {
                text = dir.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            labelCol.addView(TextView(this).apply {
                text = if (hasGodotCfg) "Godot 4.7.2 · Ready" else "Workspace Folder"
                setTextColor(Color.rgb(148, 163, 184))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            row.addView(labelCol)

            row.addView(Button(this).apply {
                text = "Edit"
                isAllCaps = false
                setTextColor(Color.rgb(56, 189, 248))
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener {
                    activeProjectPath = dir.absolutePath
                    isProjectManagerMode = false
                    selectedRelPath = ""
                    refreshDockUi()
                    bridgePlugin?.dispatchIdeCommand("reload_workspace")
                }
            })

            row.addView(Button(this).apply {
                text = "Run"
                isAllCaps = false
                setTextColor(Color.rgb(52, 211, 153))
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener {
                    launchProjectInRuntime(dir.absolutePath, "")
                }
            })
            list.addView(row)
        }
    }

    private fun createNewProjectWorkspace() {
        val root = GodotProjectWorkspace.projectsRootDir(this)
        val slug = "project_${System.currentTimeMillis() % 10000}"
        val dir = File(root, slug).apply { mkdirs() }
        File(dir, "scenes").mkdirs()
        File(dir, "scripts").mkdirs()
        File(dir, "project.godot").writeText(GodotProjectWorkspace.defaultProjectGodot("Godot Project $slug"))
        File(dir, "scripts/main.gd").writeText(
            """
            |extends Node2D
            |
            |func _ready() -> void:
            |    print("Ready in $slug")
            """.trimMargin() + "\n"
        )
        File(dir, "scenes/main.tscn").writeText(
            """
            |[gd_scene load_steps=2 format=3]
            |
            |[ext_resource type="Script" path="res://scripts/main.gd" id="1_gd"]
            |
            |[node name="MainScene" type="Node2D"]
            |script = ExtResource("1_gd")
            |
            |[node name="Banner" type="Label" parent="."]
            |offset_left = 40.0
            |offset_top = 80.0
            |offset_right = 640.0
            |offset_bottom = 130.0
            |text = "$slug · Live Godot 4.7.2"
            """.trimMargin() + "\n"
        )
        activeProjectPath = dir.absolutePath
        isProjectManagerMode = false
        selectedRelPath = "scenes/main.tscn"
        refreshDockUi()
        bridgePlugin?.dispatchIdeCommand("reload_workspace")
    }

    private fun listEditableFiles(): List<String> {
        val dir = File(activeProjectPath)
        if (!dir.isDirectory) return emptyList()
        val canonicalBase = dir.canonicalPath
        val skip = setOf(".godot", ".import", ".git", "build")
        val exts = setOf("gd", "tscn", "godot", "tres", "cfg", "gdshader", "txt")
        val out = mutableListOf<String>()
        dir.walkTopDown()
            .onEnter { it.name !in skip }
            .filter { it.isFile && it.extension.lowercase() in exts }
            .forEach { file ->
                val canon = file.canonicalPath
                if (canon.startsWith(canonicalBase + File.separator)) {
                    val rel = canon.removePrefix(canonicalBase + File.separator).replace('\\', '/')
                    if (rel.isNotBlank()) out += rel
                }
            }
        return out.sortedWith(compareBy<String>({ it != "project.godot" }, { !it.endsWith(".tscn") }, { it }))
    }

    private fun populateFileChipsAndEditor() {
        val row = fileChipsRow ?: return
        row.removeAllViews()
        val files = listEditableFiles()
        if (files.isEmpty()) {
            selectedRelPath = ""
            codeEditorInput?.setText("")
            return
        }
        if (selectedRelPath !in files) {
            selectedRelPath = files.first()
        }
        files.forEach { rel ->
            val selected = rel == selectedRelPath
            val chip = Button(this).apply {
                text = rel
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (selected) Color.rgb(11, 17, 32) else Color.rgb(203, 213, 225))
                minHeight = dp(48)
                background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(56, 189, 248) else Color.rgb(24, 34, 56))
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    selectedRelPath = rel
                    populateFileChipsAndEditor()
                }
            }
            row.addView(chip)
        }
        val target = AutomationStudioGodotPlugin.resolveSafeWorkspaceFile(activeProjectPath, selectedRelPath)
        val content = if (target?.isFile == true) runCatching { target.readText() }.getOrDefault("") else ""
        codeEditorInput?.setText(content)
    }

    private fun saveActiveFileAndReload() {
        if (selectedRelPath.isBlank()) return
        val content = codeEditorInput?.text?.toString().orEmpty()
        val target = AutomationStudioGodotPlugin.resolveSafeWorkspaceFile(activeProjectPath, selectedRelPath)
        if (target == null) {
            Toast.makeText(this, "Invalid file path", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.onSuccess {
            Toast.makeText(this, "Saved $selectedRelPath & reloaded scene", Toast.LENGTH_SHORT).show()
            bridgePlugin?.dispatchIdeCommand("reload_workspace")
        }.onFailure { err ->
            Toast.makeText(this, "Save failed: ${err.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchProjectInRuntime(projectPath: String, mainScene: String) {
        startActivity(
            GodotRuntimeActivity.createIntent(
                context = this,
                projectPath = projectPath.ifBlank { activeProjectPath },
                mainScene = mainScene.ifBlank { null }
            )
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun getActivity() = this

    override fun getGodot(): Godot? = godotFragment?.godot

    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> {
        val plugin = AutomationStudioGodotPlugin(
            godot = godot,
            launchConfigProvider = { currentLaunchConfig() },
            onOpenRuntimeRequested = { projectPath, mainScene ->
                launchProjectInRuntime(projectPath, mainScene)
            }
        )
        bridgePlugin = plugin
        return setOf(plugin)
    }

    override fun getCommandLine(): List<String> =
        GodotCommandLineSanitizer.sanitizeCommandLine(
            rawArgs = emptyList(),
            baseArgs = super<GodotHost>.getCommandLine()
        )

    override fun onNewGodotInstanceRequested(args: Array<String>): Int {
        val instanceId = nextRuntimeInstanceId++
        val cfg = GodotCommandLineSanitizer.extractLaunchConfig(cliArgs = args.toList())
        startActivity(
            GodotRuntimeActivity.createIntent(
                context = this,
                projectPath = cfg.projectPath.ifBlank { activeProjectPath },
                mainScene = cfg.mainScene.ifBlank { null },
                cliArgs = args.toList()
            )
        )
        return instanceId
    }

    override fun onGodotForceQuit(godot: Godot) {
        runOnUiThread { finish() }
    }

    override fun onGodotForceQuit(instanceId: Int): Boolean {
        runOnUiThread { finish() }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            exitProcess(0)
        }
    }

    companion object {
        const val EXTRA_PROJECT_PATH = "godot_editor_project_path"
        const val EXTRA_PROJECT_MANAGER = "godot_editor_project_manager"

        fun createIntent(
            context: Context,
            projectPath: String? = null,
            openProjectManager: Boolean = false
        ): Intent = Intent(context, GodotEditorActivity::class.java).apply {
            if (!projectPath.isNullOrBlank()) putExtra(EXTRA_PROJECT_PATH, projectPath)
            putExtra(EXTRA_PROJECT_MANAGER, openProjectManager)
        }
    }
}
