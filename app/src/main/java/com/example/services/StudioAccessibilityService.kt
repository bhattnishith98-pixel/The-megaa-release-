package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class VisibleUiElement(
    val text: String,
    val contentDescription: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: Rect
)

/**
 * Authorized Android AccessibilityService used for inspecting foreground app UI trees
 * (including Godot Editor) and performing user-requested taps, typing, scrolling, and navigation.
 * No root, no hidden APIs, no stealth monitoring.
 */
class StudioAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        refreshForegroundPackage()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        instance = this
        _isConnected.value = true
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _foregroundPackage.value = pkg
        } else {
            refreshForegroundPackage()
        }
    }

    private fun refreshForegroundPackage() {
        val activePkg = rootInActiveWindow?.packageName?.toString()
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?.root?.packageName?.toString()
        if (!activePkg.isNullOrBlank()) {
            _foregroundPackage.value = activePkg
        }
    }

    override fun onInterrupt() {
        _isConnected.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        _isConnected.value = false
    }

    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            val appWindows = windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
            }
            appWindows.sortedByDescending { it.isActive }.forEach { w ->
                w.root?.let { list += it }
            }
        }
        rootInActiveWindow?.let { active ->
            if (list.none { it == active }) list.add(0, active)
        }
        return list
    }

    fun dumpVisibleElements(maxNodes: Int = 120): List<VisibleUiElement> {
        val roots = candidateRoots()
        if (roots.isEmpty()) return emptyList()
        val out = mutableListOf<VisibleUiElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.addLast(it) }
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
                out += VisibleUiElement(
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    bounds = bounds
                )
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return out
    }

    suspend fun captureScreenViaAccessibility(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bmp = runCatching {
                                val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                hw?.recycle()
                                screenshot.hardwareBuffer.close()
                                soft
                            }.getOrNull()
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    suspend fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureAsync(gesture)
        if (ok) StudioTriggerBus.recordTouchGesture("tap", x, y, 60L)
        return ok
    }

    suspend fun performLongPress(x: Float, y: Float, durationMs: Long = 700L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val hold = durationMs.coerceIn(250L, 5000L)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, hold)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureAsync(gesture)
        if (ok) StudioTriggerBus.recordTouchGesture("long_press", x, y, hold)
        return ok
    }

    suspend fun performDoubleTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 45L)
        val s2 = GestureDescription.StrokeDescription(p2, 95L, 45L)
        val gesture = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
        val ok = dispatchGestureAsync(gesture)
        if (ok) StudioTriggerBus.recordTouchGesture("double_tap", x, y, 140L)
        return ok
    }

    suspend fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val d = durationMs.coerceIn(50L, 3000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, d)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureAsync(gesture)
        if (ok) StudioTriggerBus.recordTouchGesture("swipe", x2, y2, d)
        return ok
    }

    suspend fun performCustomGesturePath(points: List<Pair<Float, Float>>, durationMs: Long = 450L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || points.isEmpty()) return false
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
        }
        val d = durationMs.coerceIn(60L, 5000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, d)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGestureAsync(gesture)
        if (ok) {
            val last = points.last()
            StudioTriggerBus.recordTouchGesture("path", last.first, last.second, d)
        }
        return ok
    }

    suspend fun performClickByText(query: String): Boolean {
        val el = dumpVisibleElements().firstOrNull {
            it.text.contains(query, ignoreCase = true) || it.contentDescription.contains(query, ignoreCase = true)
        } ?: return false
        return performTap(el.bounds.centerX().toFloat(), el.bounds.centerY().toFloat())
    }

    fun performTypeText(text: String): Boolean {
        val roots = candidateRoots()
        val target = roots.firstNotNullOfOrNull { r ->
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(r)
        } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child)?.let { return it }
        }
        return null
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openNotificationsGlobal(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettingsGlobal(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun openPowerDialogGlobal(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    fun lockScreenGlobal(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    companion object {
        @Volatile
        var instance: StudioAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _foregroundPackage = MutableStateFlow("")
        val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

        fun isEnabledInSettings(context: Context): Boolean {
            if (instance != null && _isConnected.value) return true
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledList = am?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).orEmpty()
            val expected = ComponentName(context, StudioAccessibilityService::class.java).flattenToString()
            if (enabledList.any { it.id.equals(expected, ignoreCase = true) || it.resolveInfo?.serviceInfo?.name == StudioAccessibilityService::class.java.name }) {
                return true
            }
            val setting = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull().orEmpty()
            return setting.contains(StudioAccessibilityService::class.java.name)
        }
    }
}
