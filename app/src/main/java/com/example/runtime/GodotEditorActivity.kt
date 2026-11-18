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
import com.example.R
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import kotlin.system.exitProcess

/**
 * Hosts Godot 4.7.2.stable's official Android Editor (`godot-tools:4.7.2.stable`).
 * Runs in `:godot_editor` process so pressing Play inside the Godot Editor
 * can cleanly launch [GodotRuntimeActivity] in `:godot_runtime` via [onNewGodotInstanceRequested].
 */
class GodotEditorActivity : FragmentActivity(), GodotHost {
    private var godotFragment: GodotFragment? = null
    private var nextRuntimeInstanceId = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        root.addView(buildFloatingIdeOverlay())
        setContentView(root)

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

    private fun buildFloatingIdeOverlay(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 11, 17, 32))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.argb(140, 167, 139, 250))
            }
        }

        val badge = TextView(this).apply {
            text = "Godot 4.7.2 Editor"
            setTextColor(Color.rgb(167, 139, 250))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(8), 0)
        }
        bar.addView(badge)

        val backBtn = Button(this).apply {
            text = "Sync & Back to IDE"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = dp(48)
            minHeight = dp(36)
            setPadding(dp(6), 0, dp(2), 0)
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

    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> =
        setOf(AutomationStudioGodotPlugin(godot))

    override fun getCommandLine(): List<String> {
        val args = super<GodotHost>.getCommandLine().toMutableList()
        val openProjectManager = intent?.getBooleanExtra(EXTRA_PROJECT_MANAGER, false) ?: false
        if (openProjectManager) {
            args += "--project-manager"
            return args
        }
        args += "--editor"
        val projectPath = intent?.getStringExtra(EXTRA_PROJECT_PATH)
        if (!projectPath.isNullOrBlank()) {
            args += listOf("--path", projectPath)
        }
        return args
    }

    override fun onNewGodotInstanceRequested(args: Array<String>): Int {
        val instanceId = nextRuntimeInstanceId++
        val intent = GodotRuntimeActivity.createIntent(
            context = this,
            cliArgs = args.toList()
        )
        startActivity(intent)
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
