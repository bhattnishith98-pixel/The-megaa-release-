package com.example.runtime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.example.BuildConfig
import com.example.R
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import kotlin.system.exitProcess

/**
 * Hosts the official Godot 4.7.2.stable runtime via [GodotFragment].
 * Runs in an isolated `:godot_runtime` process so native engine lifecycles
 * never conflict with [GodotEditorActivity] or kill the main IDE process.
 *
 * Note: The bundled `libgodot_android.so` inside `godot-tools:4.7.2.stable` was
 * compiled with `disable_path_overrides=yes`, so passing `--path`, `--scene`,
 * `--editor`, or `--project-manager` on the native CLI aborts `Main::setup()`.
 * All native CLI arguments are sanitized via [GodotCommandLineSanitizer] while
 * workspace metadata and files are supplied to GDScript through [AutomationStudioGodotPlugin].
 */
class GodotRuntimeActivity : FragmentActivity(), GodotHost {
    private var godotFragment: GodotFragment? = null
    private var bridgePlugin: AutomationStudioGodotPlugin? = null
    private var badgeView: TextView? = null
    private var editButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = FrameLayout(this).apply {
            id = R.id.godot_runtime_container
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(container)
        root.addView(buildFloatingIdeOverlay())
        setContentView(root)
        refreshOverlayState()

        val existing = supportFragmentManager.findFragmentById(R.id.godot_runtime_container)
        if (existing is GodotFragment) {
            godotFragment = existing
        } else if (savedInstanceState == null) {
            godotFragment = GodotFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.godot_runtime_container, godotFragment!!)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshOverlayState()
        bridgePlugin?.dispatchIdeCommand("reload_workspace")
    }

    private fun currentLaunchConfig(): GodotCommandLineSanitizer.LaunchConfig =
        GodotCommandLineSanitizer.extractLaunchConfig(
            intentProjectPath = intent?.getStringExtra(EXTRA_PROJECT_PATH),
            intentMainScene = intent?.getStringExtra(EXTRA_MAIN_SCENE),
            cliArgs = intent?.getStringArrayListExtra(EXTRA_CLI_ARGS).orEmpty()
        )

    private fun refreshOverlayState() {
        val cfg = currentLaunchConfig()
        badgeView?.text = if (cfg.projectPath.isNotBlank()) "Godot 4.7.2 · Live" else "Godot 4.7.2"
        editButton?.visibility = if (BuildConfig.GODOT_EDITOR_BUILD && cfg.projectPath.isNotBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun buildFloatingIdeOverlay(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(10)
            val padV = dp(2)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 11, 17, 32))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(150, 56, 189, 248))
            }
        }

        val badge = TextView(this).apply {
            text = "Godot 4.7.2"
            setTextColor(Color.rgb(52, 211, 153))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(6), 0)
        }
        badgeView = badge
        bar.addView(badge)

        val editBtn = Button(this).apply {
            text = "Edit"
            isAllCaps = false
            setTextColor(Color.rgb(56, 189, 248))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Edit project in Godot 4.7.2 Editor"
            setOnClickListener {
                val path = currentLaunchConfig().projectPath
                startActivity(GodotEditorActivity.createIntent(this@GodotRuntimeActivity, projectPath = path))
                finish()
            }
        }
        editButton = editBtn
        bar.addView(editBtn)

        val pingBtn = Button(this).apply {
            text = "Ping IDE"
            isAllCaps = false
            setTextColor(Color.rgb(251, 191, 36))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Send live ping to Godot scene and IDE"
            setOnClickListener {
                bridgePlugin?.dispatchIdeCommand("pulse_from_overlay")
            }
        }
        bar.addView(pingBtn)

        val backBtn = Button(this).apply {
            text = "Back to IDE"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(6), 0, dp(2), 0)
            contentDescription = "Return to Automation Studio IDE"
            setOnClickListener { finish() }
        }
        bar.addView(backBtn)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(12)
            marginEnd = dp(12)
        }
        bar.layoutParams = params

        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = sys.top + dp(8)
            lp.marginEnd = sys.right + dp(12)
            v.layoutParams = lp
            insets
        }
        return bar
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
                val cfg = currentLaunchConfig()
                val nextPath = projectPath.ifBlank { cfg.projectPath }
                val nextScene = mainScene.ifBlank { cfg.mainScene }
                intent = createIntent(this, projectPath = nextPath, mainScene = nextScene)
                refreshOverlayState()
                bridgePlugin?.dispatchIdeCommand("reload_workspace")
            }
        )
        bridgePlugin = plugin
        return setOf(plugin)
    }

    override fun getCommandLine(): List<String> {
        val cliArgs = intent?.getStringArrayListExtra(EXTRA_CLI_ARGS).orEmpty()
        return GodotCommandLineSanitizer.sanitizeCommandLine(
            rawArgs = cliArgs,
            baseArgs = super<GodotHost>.getCommandLine()
        )
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
        const val EXTRA_PROJECT_PATH = "godot_project_path"
        const val EXTRA_MAIN_SCENE = "godot_main_scene"
        const val EXTRA_CLI_ARGS = "godot_cli_args"

        fun createIntent(
            context: Context,
            projectPath: String? = null,
            mainScene: String? = null,
            cliArgs: List<String>? = null
        ): Intent = Intent(context, GodotRuntimeActivity::class.java).apply {
            if (!projectPath.isNullOrBlank()) putExtra(EXTRA_PROJECT_PATH, projectPath)
            if (!mainScene.isNullOrBlank()) putExtra(EXTRA_MAIN_SCENE, mainScene)
            if (!cliArgs.isNullOrEmpty()) putStringArrayListExtra(EXTRA_CLI_ARGS, ArrayList(cliArgs))
        }
    }
}
