package com.example.runtime

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.example.R
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin

/**
 * Starts Godot's editor-enabled Android engine build.
 * This Activity is only offered in APKs built with -PgodotEditorBuild=true.
 */
class GodotEditorActivity : FragmentActivity(), GodotHost {
    private var godotFragment: GodotFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this).apply {
            id = R.id.godot_editor_container
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        setContentView(container)

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

    override fun getActivity() = this

    override fun getGodot(): Godot? = godotFragment?.godot

    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> = emptySet()

    override fun getCommandLine(): List<String> =
        super<GodotHost>.getCommandLine() + listOf("--editor")
}
