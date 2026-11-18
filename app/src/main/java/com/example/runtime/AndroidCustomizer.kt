package com.example.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.services.StudioAccessibilityService
import com.example.services.StudioTriggerBus

data class AppPermissionInfo(
    val permission: String,
    val granted: Boolean
)

data class InstalledAppSummary(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val requestedPermissionsCount: Int
)

data class DeviceTweaksSnapshot(
    val brightness: Int,
    val screenTimeoutMs: Int,
    val mediaVolume: Int,
    val maxMediaVolume: Int,
    val ringVolume: Int,
    val canWriteSettings: Boolean,
    val canDrawOverlays: Boolean,
    val fontScale: Float
)

/**
 * Real Android OS customization & permission control manager.
 * - Standard Android features use official Settings, AudioManager, PackageManager, and Intents.
 * - Elevated controls (pm grant/revoke, appops, wm density/size, settings put global/secure)
 *   delegate honestly to Shizuku (`rish`) or Root (`su`) via PrivilegeBridge without ever mocking success.
 */
object AndroidCustomizer {

    private val SAFE_KEY_REGEX = Regex("^[A-Za-z0-9_.-]+$")
    private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9_.]+$")
    private val SAFE_PERM_REGEX = Regex("^[a-zA-Z0-9_.]+$")

    fun readDeviceTweaks(context: Context): DeviceTweaksSnapshot {
        val resolver = context.contentResolver
        val brightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        }.getOrDefault(128)
        val timeout = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
        }.getOrDefault(30_000)
        val fontScale = runCatching {
            Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1.0f)
        }.getOrDefault(1.0f)

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mediaVol = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val maxMedia = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val ringVol = audio?.getStreamVolume(AudioManager.STREAM_RING) ?: 0

        return DeviceTweaksSnapshot(
            brightness = brightness,
            screenTimeoutMs = timeout,
            mediaVolume = mediaVol,
            maxMediaVolume = maxMedia,
            ringVolume = ringVol,
            canWriteSettings = Settings.System.canWrite(context),
            canDrawOverlays = Settings.canDrawOverlays(context),
            fontScale = fontScale
        )
    }

    fun setStandardMediaVolume(context: Context, volume: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceIn(0, max), 0)
        return true
    }

    fun setStandardBrightness(context: Context, value0To255: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value0To255.coerceIn(1, 255)
            )
            true
        }.getOrDefault(false)
    }

    fun openWriteSettingsConsent(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(i) }
    }

    fun openOverlayConsent(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(i) }
    }

    fun listInstalledAppsWithPerms(context: Context, limit: Int = 80): List<InstalledAppSummary> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPkgs = runCatching {
            pm.queryIntentActivities(mainIntent, 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

        val candidatePkgs = (launcherPkgs + context.packageName).distinct().sorted()
        return candidatePkgs.take(limit).mapNotNull { pkg ->
            runCatching {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val appInfo = info.applicationInfo
                val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
                val isSys = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                InstalledAppSummary(
                    packageName = pkg,
                    label = label,
                    isSystemApp = isSys,
                    requestedPermissionsCount = info.requestedPermissions?.size ?: 0
                )
            }.getOrNull()
        }
    }

    fun getPackagePermissions(context: Context, packageName: String): List<AppPermissionInfo> {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val perms = info.requestedPermissions ?: return emptyList()
            val flags = info.requestedPermissionsFlags ?: IntArray(perms.size)
            perms.mapIndexed { idx, perm ->
                val granted = (flags.getOrElse(idx) { 0 } and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 ||
                    (packageName == context.packageName && ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
                AppPermissionInfo(perm, granted)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun grantPermission(context: Context, packageName: String, permission: String, preferRoot: Boolean = false): PrivilegedCommandResult {
        if (!SAFE_PKG_REGEX.matches(packageName) || !SAFE_PERM_REGEX.matches(permission)) {
            return PrivilegedCommandResult(false, -1, "", "Invalid package or permission identifier.", "validation")
        }
        return execPreferred(context, "pm grant $packageName $permission", preferRoot)
    }

    suspend fun revokePermission(context: Context, packageName: String, permission: String, preferRoot: Boolean = false): PrivilegedCommandResult {
        if (!SAFE_PKG_REGEX.matches(packageName) || !SAFE_PERM_REGEX.matches(permission)) {
            return PrivilegedCommandResult(false, -1, "", "Invalid package or permission identifier.", "validation")
        }
        return execPreferred(context, "pm revoke $packageName $permission", preferRoot)
    }

    suspend fun setAppOps(context: Context, packageName: String, op: String, mode: String, preferRoot: Boolean = false): PrivilegedCommandResult {
        if (!SAFE_PKG_REGEX.matches(packageName) || !SAFE_KEY_REGEX.matches(op) || !SAFE_KEY_REGEX.matches(mode)) {
            return PrivilegedCommandResult(false, -1, "", "Invalid appops parameters.", "validation")
        }
        return execPreferred(context, "appops set $packageName $op $mode", preferRoot)
    }

    suspend fun setSystemSetting(context: Context, table: String, key: String, value: String, preferRoot: Boolean = false): PrivilegedCommandResult {
        val cleanTable = table.lowercase().takeIf { it in setOf("system", "secure", "global") }
            ?: return PrivilegedCommandResult(false, -1, "", "Setting table must be system, secure, or global.", "validation")
        if (!SAFE_KEY_REGEX.matches(key) || !SAFE_KEY_REGEX.matches(value)) {
            return PrivilegedCommandResult(false, -1, "", "Invalid key or value characters.", "validation")
        }
        return execPreferred(context, "settings put $cleanTable $key $value", preferRoot)
    }

    suspend fun setWmDensity(context: Context, dpi: Int, preferRoot: Boolean = false): PrivilegedCommandResult {
        val safeDpi = dpi.coerceIn(120, 960)
        return execPreferred(context, "wm density $safeDpi", preferRoot)
    }

    suspend fun setWmSize(context: Context, width: Int, height: Int, preferRoot: Boolean = false): PrivilegedCommandResult {
        val w = width.coerceIn(320, 4096)
        val h = height.coerceIn(320, 4096)
        return execPreferred(context, "wm size ${w}x${h}", preferRoot)
    }

    suspend fun resetDisplayMetrics(context: Context, preferRoot: Boolean = false): PrivilegedCommandResult {
        return execPreferred(context, "wm size reset && wm density reset", preferRoot)
    }

    suspend fun forceStopPackage(context: Context, packageName: String, preferRoot: Boolean = false): PrivilegedCommandResult {
        if (!SAFE_PKG_REGEX.matches(packageName)) {
            return PrivilegedCommandResult(false, -1, "", "Invalid package name.", "validation")
        }
        return execPreferred(context, "am force-stop $packageName", preferRoot)
    }

    fun setFlashlight(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return PrivilegedCommandResult(false, -1, "", "CameraManager unavailable.", "android_api")
        val camId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return PrivilegedCommandResult(false, -1, "", "No hardware flashlight available.", "android_api")
        return runCatching {
            cm.setTorchMode(camId, enabled)
            StudioTriggerBus.pushEvent("action.mobile_flashlight", "Device Actions", "Flashlight ${if (enabled) "ON" else "OFF"}")
            PrivilegedCommandResult(true, 0, "Torch=${if (enabled) "ON" else "OFF"}", "", "camera2_torch")
        }.getOrElse { e ->
            PrivilegedCommandResult(false, -1, "", e.message ?: "Torch failed", "camera2_torch")
        }
    }

    fun setDndEnabled(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return PrivilegedCommandResult(false, -1, "", "NotificationManager unavailable.", "android_api")
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            return PrivilegedCommandResult(false, -1, "", "Notification Policy Access required; opened DND settings.", "android_settings")
        }
        val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
        StudioTriggerBus.pushEvent("action.mobile_dnd", "Device Settings", "DND ${if (enabled) "ON" else "OFF"}")
        return PrivilegedCommandResult(true, 0, "DND=${if (enabled) "ON" else "OFF"}", "", "notification_policy")
    }

    suspend fun setAutoRotate(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val v = if (enabled) 1 else 0
        if (Settings.System.canWrite(context)) {
            val ok = runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, v)
            }.getOrDefault(false)
            if (ok) return PrivilegedCommandResult(true, 0, "AutoRotate=$enabled", "", "write_settings")
        }
        val priv = execPreferredIfAvailable(context, "settings put system accelerometer_rotation $v")
        if (priv != null) return priv
        openWriteSettingsConsent(context)
        return PrivilegedCommandResult(false, -1, "", "WRITE_SETTINGS or Shizuku/Root required; opened consent.", "android_settings")
    }

    suspend fun setWifiEnabled(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val cmd = if (enabled) "svc wifi enable" else "svc wifi disable"
        execPreferredIfAvailable(context, cmd)?.let { return it }
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        runCatching { context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        return PrivilegedCommandResult(false, -1, "", "Android 10+ requires Shizuku/Root or user panel for Wi-Fi toggle; opened Wi-Fi panel.", "android_settings")
    }

    suspend fun setMobileDataEnabled(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val cmd = if (enabled) "svc data enable" else "svc data disable"
        execPreferredIfAvailable(context, cmd)?.let { return it }
        runCatching { context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        return PrivilegedCommandResult(false, -1, "", "Mobile Data toggle requires Shizuku/Root; opened Mobile Network settings.", "android_settings")
    }

    suspend fun setBluetoothEnabled(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val cmd = if (enabled) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable"
        execPreferredIfAvailable(context, cmd)?.let { return it }
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        return PrivilegedCommandResult(false, -1, "", "Bluetooth toggle requires Shizuku/Root on modern Android; opened Bluetooth settings.", "android_settings")
    }

    suspend fun setAirplaneMode(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val arg = if (enabled) "enable" else "disable"
        execPreferredIfAvailable(context, "cmd connectivity airplane-mode $arg")?.let { return it }
        runCatching { context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        return PrivilegedCommandResult(false, -1, "", "Airplane Mode toggle requires Shizuku/Root; opened Airplane Mode settings.", "android_settings")
    }

    suspend fun setHotspotEnabled(context: Context, enabled: Boolean): PrivilegedCommandResult {
        val cmd = if (enabled) "cmd wifi start-softap StudioHotspot open" else "cmd wifi stop-softap"
        execPreferredIfAvailable(context, cmd)?.let { return it }
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
        return PrivilegedCommandResult(false, -1, "", "Hotspot toggle requires Shizuku/Root; opened Wireless settings.", "android_settings")
    }

    @Suppress("DEPRECATION")
    suspend fun setScreenPower(context: Context, on: Boolean): PrivilegedCommandResult {
        if (on) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                runCatching {
                    val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "AutomationStudio:ScreenWake")
                    wl.acquire(2500L)
                    wl.release()
                }
            }
            execPreferredIfAvailable(context, "input keyevent 224")?.let { return it }
            StudioTriggerBus.recordScreenState(true)
            return PrivilegedCommandResult(true, 0, "Screen wake requested", "", "power_wakelock")
        } else {
            val lockedByA11y = StudioAccessibilityService.instance?.lockScreenGlobal() == true
            if (lockedByA11y) {
                StudioTriggerBus.recordScreenState(false)
                return PrivilegedCommandResult(true, 0, "Screen locked via Accessibility", "", "accessibility_global")
            }
            execPreferredIfAvailable(context, "input keyevent 26")?.let { return it }
            return PrivilegedCommandResult(false, -1, "", "Enable Accessibility Service or Shizuku/Root to turn screen OFF.", "accessibility_or_privilege")
        }
    }

    suspend fun performPowerAction(context: Context, mode: String): PrivilegedCommandResult {
        return when (mode.lowercase()) {
            "lock", "screen_off" -> setScreenPower(context, false)
            "screen_on", "wake" -> setScreenPower(context, true)
            "power_dialog" -> {
                val ok = StudioAccessibilityService.instance?.openPowerDialogGlobal() == true
                if (ok) PrivilegedCommandResult(true, 0, "Power dialog opened", "", "accessibility_global")
                else PrivilegedCommandResult(false, -1, "", "Enable Accessibility Service to open Power Dialog.", "accessibility")
            }
            "reboot" -> execPreferred(context, "svc power reboot", preferRoot = false)
            "shutdown", "power_off" -> execPreferred(context, "svc power shutdown", preferRoot = true)
            "recovery" -> execPreferred(context, "svc power reboot recovery", preferRoot = true)
            "bootloader" -> execPreferred(context, "svc power reboot bootloader", preferRoot = true)
            else -> PrivilegedCommandResult(false, -1, "", "Unknown power action '$mode'", "validation")
        }
    }

    private suspend fun execPreferredIfAvailable(context: Context, cmd: String): PrivilegedCommandResult? {
        val status = PrivilegeBridge.inspectEnvironment(context)
        return when {
            status.shizukuInstalled && status.shizukuRishPresent -> PrivilegeBridge.execShizuku(context, cmd)
            status.rootAvailable -> PrivilegeBridge.execRoot(context, cmd)
            else -> null
        }
    }

    private suspend fun execPreferred(context: Context, cmd: String, preferRoot: Boolean): PrivilegedCommandResult {
        val status = PrivilegeBridge.inspectEnvironment(context)
        return if (preferRoot) {
            if (status.rootAvailable) PrivilegeBridge.execRoot(context, cmd)
            else PrivilegeBridge.execShizuku(context, cmd)
        } else {
            if (status.shizukuInstalled && status.shizukuRishPresent) PrivilegeBridge.execShizuku(context, cmd)
            else if (status.rootAvailable) PrivilegeBridge.execRoot(context, cmd)
            else PrivilegeBridge.execShizuku(context, cmd)
        }
    }
}
