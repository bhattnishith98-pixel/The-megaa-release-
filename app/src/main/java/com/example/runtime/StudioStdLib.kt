package com.example.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.services.ScreenCaptureService
import com.example.services.StudioAccessibilityService
import com.example.services.StudioNotificationTriggerService
import com.example.services.StudioTriggerBus
import com.example.vm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.random.Random

/**
 * Controlled Kotlin host bindings for StudioLang's standard library and Android API layer.
 * Enforces workspace sandboxing, Android runtime permission checks, deterministic memory arenas,
 * and secret redaction.
 */
class StudioStdLib(
    private val context: Context,
    private val workspaceRoot: File,
    private val credentialVault: CredentialVault,
    private val aiAskCallback: (suspend (String) -> String)? = null,
    private val networkShareManager: NetworkShareManager? = null,
    private val requestPermissionCallback: (suspend (String) -> Boolean)? = null,
    private val voiceSpeakCallback: ((String) -> Unit)? = null
) : HostApiDispatcher {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val arenas = ConcurrentHashMap<Int, ByteArray>()
    private var nextArenaHandle = 1

    init {
        if (!workspaceRoot.exists()) workspaceRoot.mkdirs()
    }

    override suspend fun invokeHost(
        qualifiedName: String,
        args: List<StudioValue>,
        line: Int,
        logSink: (level: String, message: String) -> Unit
    ): StudioValue {
        return when (qualifiedName) {
            // ---- LOGGING ----
            "log.info" -> { logSink("INFO", args.joinToString(" ") { it.asDisplayString() }); NoneVal }
            "log.warn" -> { logSink("WARN", args.joinToString(" ") { it.asDisplayString() }); NoneVal }
            "log.error" -> { logSink("ERROR", args.joinToString(" ") { it.asDisplayString() }); NoneVal }
            "log.debug" -> { logSink("DEBUG", args.joinToString(" ") { it.asDisplayString() }); NoneVal }

            // ---- MATH ----
            "math.abs" -> NumVal(abs(argNum(args, 0, line)))
            "math.min" -> NumVal(min(argNum(args, 0, line), argNum(args, 1, line)))
            "math.max" -> NumVal(max(argNum(args, 0, line), argNum(args, 1, line)))
            "math.round" -> NumVal(round(argNum(args, 0, line)))
            "math.floor" -> NumVal(floor(argNum(args, 0, line)))
            "math.ceil" -> NumVal(ceil(argNum(args, 0, line)))
            "math.sqrt" -> NumVal(sqrt(argNum(args, 0, line)))
            "math.pow" -> NumVal(argNum(args, 0, line).pow(argNum(args, 1, line)))
            "math.random" -> {
                if (args.size >= 2) {
                    val lo = argNum(args, 0, line)
                    val hi = argNum(args, 1, line)
                    NumVal(Random.nextDouble(lo, hi.coerceAtLeast(lo + 0.0001)))
                } else NumVal(Random.nextDouble())
            }
            "math.clamp" -> NumVal(argNum(args, 0, line).coerceIn(argNum(args, 1, line), argNum(args, 2, line)))

            // ---- TEXT ----
            "text.len" -> NumVal(argText(args, 0).length.toDouble())
            "text.upper" -> TextVal(argText(args, 0).uppercase())
            "text.lower" -> TextVal(argText(args, 0).lowercase())
            "text.trim" -> TextVal(argText(args, 0).trim())
            "text.split" -> {
                val src = argText(args, 0)
                val delim = if (args.size > 1) argText(args, 1) else " "
                ListVal(src.split(delim).map { TextVal(it) as StudioValue }.toMutableList())
            }
            "text.join" -> {
                val list = (args.getOrNull(0) as? ListVal)?.items ?: emptyList()
                val sep = if (args.size > 1) argText(args, 1) else ""
                TextVal(list.joinToString(sep) { it.asDisplayString() })
            }
            "text.contains" -> BoolVal(argText(args, 0).contains(argText(args, 1)))
            "text.replace" -> TextVal(argText(args, 0).replace(argText(args, 1), argText(args, 2)))
            "text.starts_with" -> BoolVal(argText(args, 0).startsWith(argText(args, 1)))
            "text.ends_with" -> BoolVal(argText(args, 0).endsWith(argText(args, 1)))

            // ---- LIST ----
            "list.len" -> NumVal(((args.getOrNull(0) as? ListVal)?.items?.size ?: 0).toDouble())
            "list.push" -> {
                val l = args.getOrNull(0) as? ListVal ?: throw StudioVmException("STD001", "Expected list.", line)
                l.items.add(args.getOrNull(1) ?: NoneVal)
                l
            }
            "list.pop" -> {
                val l = args.getOrNull(0) as? ListVal ?: throw StudioVmException("STD001", "Expected list.", line)
                if (l.items.isEmpty()) NoneVal else l.items.removeAt(l.items.lastIndex)
            }
            "list.contains" -> {
                val l = args.getOrNull(0) as? ListVal ?: return BoolVal(false)
                BoolVal(args.getOrNull(1) in l.items)
            }
            "list.sort" -> {
                val l = args.getOrNull(0) as? ListVal ?: return ListVal()
                val sorted = l.items.sortedBy { it.asDisplayString() }.toMutableList()
                ListVal(sorted)
            }
            "list.reverse" -> {
                val l = args.getOrNull(0) as? ListVal ?: return ListVal()
                ListVal(l.items.reversed().toMutableList())
            }

            // ---- JSON ----
            "json.parse", "json.loads" -> parseJsonToValue(argText(args, 0), line)
            "json.stringify", "json.dumps" -> TextVal(valueToJsonString(args.getOrNull(0) ?: NoneVal))

            // ---- TIME ----
            "time.now" -> TextVal(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            "time.timestamp_ms" -> NumVal(System.currentTimeMillis().toDouble())
            "time.sleep" -> {
                val ms = when (val d = args.getOrNull(0)) {
                    is DurationVal -> d.millis
                    is NumVal -> d.value.toLong()
                    else -> 0L
                }.coerceIn(0L, 10_000L)
                if (ms > 0) delay(ms)
                NoneVal
            }
            "time.at" -> MapVal(linkedMapOf("kind" to TextVal("schedule"), "time" to TextVal(argText(args, 0))))

            // ---- DEVICE & BATTERY ----
            "device.model" -> TextVal(Build.MODEL ?: "Android")
            "device.sdk_int" -> NumVal(Build.VERSION.SDK_INT.toDouble())
            "device.battery_level", "battery.level" -> NumVal(readBatteryLevel().toDouble())
            "device.is_charging" -> BoolVal(readIsCharging())
            "device.vibrate" -> {
                val ms = (args.getOrNull(0)?.asDouble()?.toLong() ?: 120L).coerceIn(20L, 1500L)
                vibrateDevice(ms)
                NoneVal
            }

            // ---- NETWORK & IPv4/IPv6 SHARING ----
            "net.is_connected" -> BoolVal(isNetworkConnected(wifiOnly = false))
            "net.is_wifi" -> BoolVal(isNetworkConnected(wifiOnly = true))
            "net.http_get" -> httpGet(argText(args, 0), line)
            "net.http_post" -> httpPost(argText(args, 0), argText(args, 1), line)
            "net.local_ips" -> {
                val ips = NetworkShareManager.inspectLocalIps()
                MapVal(linkedMapOf(
                    "ipv4" to ListVal(ips.ipv4Addresses.map { TextVal(it) as StudioValue }.toMutableList()),
                    "ipv6" to ListVal(ips.ipv6Addresses.map { TextVal(it) as StudioValue }.toMutableList())
                ))
            }
            "net.ipv4_send", "net.ipv6_send" -> {
                val mgr = networkShareManager ?: NetworkShareManager(workspaceRoot)
                val host = argText(args, 0)
                val port = argNum(args, 1, line).toInt()
                val textPayload = argText(args, 2)
                val fileName = if (args.size > 3) argText(args, 3) else "message.txt"
                val entry = mgr.sendPayload(host, port, fileName, textPayload.toByteArray(Charsets.UTF_8))
                MapVal(linkedMapOf(
                    "ok" to BoolVal(entry.ok),
                    "protocol" to TextVal(entry.protocol),
                    "peer" to TextVal(entry.peer),
                    "bytes" to NumVal(entry.bytesCount.toDouble()),
                    "message" to TextVal(entry.message)
                ))
            }
            "net.share_file" -> {
                val mgr = networkShareManager ?: NetworkShareManager(workspaceRoot)
                val host = argText(args, 0)
                val port = argNum(args, 1, line).toInt()
                val relPath = argText(args, 2)
                val file = SafePathValidator.resolveInsideWorkspace(workspaceRoot, relPath)
                if (!file.exists()) throw StudioVmException("FILE001", "File not found: $relPath", line)
                val entry = mgr.sendPayload(host, port, file.name, file.readBytes())
                MapVal(linkedMapOf(
                    "ok" to BoolVal(entry.ok),
                    "protocol" to TextVal(entry.protocol),
                    "message" to TextVal(entry.message)
                ))
            }
            "net.share_start" -> {
                val mgr = networkShareManager ?: throw StudioVmException("NET002", "Share server manager unavailable.", line)
                val port = if (args.isEmpty()) NetworkShareManager.DEFAULT_PORT else argNum(args, 0, line).toInt()
                logSink("INFO", "Dual-stack IPv4/IPv6 receiver requested on port $port (start from Dashboard for lifecycle binding).")
                MapVal(linkedMapOf("running" to BoolVal(mgr.serverRunning.value), "port" to NumVal(port.toDouble())))
            }
            "net.share_stop" -> {
                networkShareManager?.stopReceiverServer()
                BoolVal(true)
            }

            // ---- NOTIFICATIONS ----
            "notify.show" -> {
                val title = argText(args, 0)
                val body = argText(args, 1)
                showNotification(title, body, line)
                logSink("INFO", "Notification posted: $title")
                NoneVal
            }
            "notify.cancel" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(args.getOrNull(0)?.asDouble()?.toInt() ?: 5001)
                NoneVal
            }

            // ---- FILES (SANDBOXED WORKSPACE) ----
            "files.list" -> {
                val sub = if (args.isEmpty()) "" else argText(args, 0)
                val dir = SafePathValidator.resolveInsideWorkspace(workspaceRoot, sub)
                if (!dir.exists() || !dir.isDirectory) ListVal()
                else {
                    val entries = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.map { f ->
                        val ageDays = ((System.currentTimeMillis() - f.lastModified()) / 86_400_000.0)
                        MapVal(
                            linkedMapOf(
                                "name" to TextVal(f.name),
                                "path" to TextVal(f.relativeTo(workspaceRoot).path),
                                "is_dir" to BoolVal(f.isDirectory),
                                "size" to NumVal(f.length().toDouble()),
                                "age_days" to NumVal(ageDays)
                            )
                        ) as StudioValue
                    }.toMutableList()
                    ListVal(entries)
                }
            }
            "files.read" -> {
                val file = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                if (!file.exists()) throw StudioVmException("FILE001", "File not found: ${file.name}", line)
                TextVal(file.readText())
            }
            "files.write" -> {
                val file = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                file.parentFile?.mkdirs()
                file.writeText(argText(args, 1))
                BoolVal(true)
            }
            "files.copy" -> {
                val src = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                val dst = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 1))
                if (!src.exists()) throw StudioVmException("FILE001", "Source not found: ${src.name}", line)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                BoolVal(true)
            }
            "files.rename" -> {
                val src = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                val dst = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 1))
                BoolVal(src.renameTo(dst))
            }
            "files.delete" -> {
                val pathArg = when (val first = args.getOrNull(0)) {
                    is MapVal -> first.entries["path"]?.asDisplayString() ?: first.entries["name"]?.asDisplayString().orEmpty()
                    else -> argText(args, 0)
                }
                val file = SafePathValidator.resolveInsideWorkspace(workspaceRoot, pathArg)
                BoolVal(if (file.exists()) file.deleteRecursively() else false)
            }
            "files.exists" -> {
                val file = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                BoolVal(file.exists())
            }
            "files.mkdir" -> {
                val dir = SafePathValidator.resolveInsideWorkspace(workspaceRoot, argText(args, 0))
                BoolVal(dir.mkdirs() || dir.isDirectory)
            }

            // ---- CREDENTIALS (ANDROID KEYSTORE, REDACTED IN LOGS) ----
            "creds.store" -> {
                credentialVault.storeSecret(argText(args, 0), argText(args, 1))
                BoolVal(true)
            }
            "creds.get" -> {
                val secret = credentialVault.getSecret(argText(args, 0)) ?: ""
                TextVal(secret, isSecret = true)
            }
            "creds.delete" -> BoolVal(credentialVault.deleteSecret(argText(args, 0)))
            "creds.list_keys" -> ListVal(credentialVault.listKeys().map { TextVal(it) as StudioValue }.toMutableList())

            // ---- PERMISSIONS & SETTINGS ----
            "perms.check" -> {
                val perm = normalizePermissionName(argText(args, 0))
                BoolVal(isPermissionGranted(perm))
            }
            "perms.request" -> {
                val raw = argText(args, 0).trim()
                val perm = normalizePermissionName(raw)
                when (perm) {
                    Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                        if (Settings.canDrawOverlays(context)) {
                            BoolVal(true)
                        } else {
                            AndroidCustomizer.openOverlayConsent(context)
                            logSink("INFO", "Opened Overlay (SYSTEM_ALERT_WINDOW) consent screen.")
                            BoolVal(false)
                        }
                    }
                    Manifest.permission.WRITE_SETTINGS -> {
                        if (Settings.System.canWrite(context)) {
                            BoolVal(true)
                        } else {
                            AndroidCustomizer.openWriteSettingsConsent(context)
                            logSink("INFO", "Opened Modify System Settings (WRITE_SETTINGS) consent screen.")
                            BoolVal(false)
                        }
                    }
                    "android.permission.BIND_ACCESSIBILITY_SERVICE", "ACCESSIBILITY" -> {
                        if (StudioAccessibilityService.isConnected.value) {
                            BoolVal(true)
                        } else {
                            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(i) }
                            logSink("INFO", "Opened Android Accessibility Settings screen.")
                            BoolVal(false)
                        }
                    }
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "NOTIFICATION_ACCESS" -> {
                        if (StudioNotificationTriggerService.isNotificationAccessEnabled(context)) {
                            BoolVal(true)
                        } else {
                            StudioNotificationTriggerService.openNotificationAccessSettings(context)
                            logSink("INFO", "Opened Notification Access Settings for SMS/Notification triggers.")
                            BoolVal(false)
                        }
                    }
                    else -> {
                        if (isPermissionGranted(perm)) {
                            BoolVal(true)
                        } else {
                            val declared = runCatching {
                                @Suppress("DEPRECATION")
                                context.packageManager.getPackageInfo(
                                    context.packageName,
                                    PackageManager.GET_PERMISSIONS
                                ).requestedPermissions?.toList().orEmpty()
                            }.getOrDefault(emptyList())
                            if (perm !in declared) {
                                logSink(
                                    "WARN",
                                    "Android OS only allows runtime permission dialogs for permissions declared in AndroidManifest.xml ($perm is not declared in this APK). Add it in App Builder Manifest toggles or use Shizuku/Root."
                                )
                                BoolVal(false)
                            } else if (requestPermissionCallback != null) {
                                val granted = requestPermissionCallback.invoke(perm)
                                logSink("INFO", "Permission request '$perm' -> granted=$granted")
                                BoolVal(granted)
                            } else {
                                logSink("WARN", "No active Activity UI available to display runtime dialog for '$perm'.")
                                BoolVal(false)
                            }
                        }
                    }
                }
            }
            "perms.list_declared" -> {
                val items = AndroidCustomizer.getPackagePermissions(context, context.packageName).map { p ->
                    MapVal(linkedMapOf(
                        "permission" to TextVal(p.permission),
                        "granted" to BoolVal(p.granted)
                    )) as StudioValue
                }.toMutableList()
                ListVal(items)
            }
            "perms.is_accessibility_enabled" -> BoolVal(StudioAccessibilityService.isConnected.value)
            "perms.is_screen_capture_active" -> BoolVal(ScreenCaptureService.isActive.value)
            "perms.open_app_settings" -> {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(i) }
                NoneVal
            }
            "perms.open_accessibility_settings" -> {
                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(i) }
                NoneVal
            }

            // ---- SCREEN & ACCESSIBILITY ----
            "screen.capture_frame" -> {
                val svc = ScreenCaptureService.instance
                    ?: throw StudioVmException("ANDROID001", "Screen capture is not active. Start MediaProjection consent in Agent/Studio first.", line)
                val bmp = svc.captureLatestBitmap()
                    ?: throw StudioVmException("ANDROID002", "No frame available yet from MediaProjection.", line)
                MapVal(linkedMapOf("width" to NumVal(bmp.width.toDouble()), "height" to NumVal(bmp.height.toDouble()), "captured" to BoolVal(true)))
            }
            "screen.tap" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled. Enable Automation Studio in Android Accessibility settings.", line)
                BoolVal(svc.performTap(argNum(args, 0, line).toFloat(), argNum(args, 1, line).toFloat()))
            }
            "screen.long_press" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                val dur = args.getOrNull(2)?.asDouble()?.toLong() ?: 700L
                BoolVal(svc.performLongPress(argNum(args, 0, line).toFloat(), argNum(args, 1, line).toFloat(), dur))
            }
            "screen.double_tap" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                BoolVal(svc.performDoubleTap(argNum(args, 0, line).toFloat(), argNum(args, 1, line).toFloat()))
            }
            "screen.custom_swipe" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                val dur = args.getOrNull(4)?.asDouble()?.toLong() ?: 320L
                BoolVal(
                    svc.performSwipe(
                        argNum(args, 0, line).toFloat(),
                        argNum(args, 1, line).toFloat(),
                        argNum(args, 2, line).toFloat(),
                        argNum(args, 3, line).toFloat(),
                        dur
                    )
                )
            }
            "screen.custom_touch" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                val x = argNum(args, 0, line).toFloat()
                val y = argNum(args, 1, line).toFloat()
                val holdMs = (args.getOrNull(2)?.asDouble()?.toLong() ?: 60L).coerceIn(40L, 4000L)
                val repeats = (args.getOrNull(3)?.asDouble()?.toInt() ?: 1).coerceIn(1, 20)
                var allOk = true
                repeat(repeats) { idx ->
                    val ok = if (holdMs >= 250L) svc.performLongPress(x, y, holdMs) else svc.performTap(x, y)
                    if (!ok) allOk = false
                    if (idx < repeats - 1) delay(90L)
                }
                BoolVal(allOk)
            }
            "screen.gesture_path" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                val csv = argText(args, 0)
                val dur = args.getOrNull(1)?.asDouble()?.toLong() ?: 450L
                val pts = csv.split(";").mapNotNull { token ->
                    val parts = token.trim().split(",")
                    val px = parts.getOrNull(0)?.trim()?.toFloatOrNull()
                    val py = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                    if (px != null && py != null) px to py else null
                }
                BoolVal(svc.performCustomGesturePath(pts, dur))
            }
            "screen.click_text" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                BoolVal(svc.performClickByText(argText(args, 0)))
            }
            "screen.type_text" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                BoolVal(svc.performTypeText(argText(args, 0)))
            }
            "screen.scroll" -> {
                val svc = StudioAccessibilityService.instance
                    ?: throw StudioVmException("ANDROID003", "Accessibility service is not enabled.", line)
                val dy = argNum(args, 0, line).toFloat()
                BoolVal(svc.performSwipe(540f, 1200f, 540f, 1200f - dy))
            }
            "screen.back" -> {
                val svc = StudioAccessibilityService.instance ?: return BoolVal(false)
                BoolVal(svc.goBack())
            }
            "screen.home" -> {
                val svc = StudioAccessibilityService.instance ?: return BoolVal(false)
                BoolVal(svc.goHome())
            }
            "screen.find_text" -> {
                val svc = StudioAccessibilityService.instance ?: return ListVal()
                val query = argText(args, 0)
                val matches = svc.dumpVisibleElements().filter {
                    it.text.contains(query, ignoreCase = true) || it.contentDescription.contains(query, ignoreCase = true)
                }.map { el ->
                    MapVal(linkedMapOf(
                        "text" to TextVal(el.text),
                        "x" to NumVal(el.bounds.centerX().toDouble()),
                        "y" to NumVal(el.bounds.centerY().toDouble()),
                        "clickable" to BoolVal(el.clickable)
                    )) as StudioValue
                }.toMutableList()
                ListVal(matches)
            }
            "screen.ui_tree" -> {
                val svc = StudioAccessibilityService.instance ?: return ListVal()
                val nodes = svc.dumpVisibleElements().map { el ->
                    MapVal(linkedMapOf(
                        "text" to TextVal(el.text),
                        "desc" to TextVal(el.contentDescription),
                        "clickable" to BoolVal(el.clickable),
                        "x" to NumVal(el.bounds.centerX().toDouble()),
                        "y" to NumVal(el.bounds.centerY().toDouble())
                    )) as StudioValue
                }.toMutableList()
                ListVal(nodes)
            }
            "touch.last_event" -> {
                val t = StudioTriggerBus.lastTouch.value
                MapVal(linkedMapOf(
                    "x" to NumVal(t.x.toDouble()),
                    "y" to NumVal(t.y.toDouble()),
                    "gesture" to TextVal(t.gestureType),
                    "duration_ms" to NumVal(t.durationMs.toDouble()),
                    "timestamp_ms" to NumVal(t.timestampMs.toDouble())
                ))
            }

            // ---- PIN UNLOCK & DEVICE LOCK STATE ----
            "pin.is_locked" -> BoolVal(StudioTriggerBus.isDeviceLocked(context))
            "pin.is_secure" -> BoolVal(StudioTriggerBus.isKeyguardSecure(context))
            "pin.set_code" -> {
                val pin = argText(args, 0).trim()
                if (pin.length < 4) throw StudioVmException("PIN001", "Automation PIN must be at least 4 characters.", line)
                credentialVault.storeSecret("studio_automation_pin_hash", StudioTriggerBus.hashPin(pin))
                StudioTriggerBus.resetFailedPinAttempts()
                BoolVal(true)
            }
            "pin.verify" -> {
                val candidate = argText(args, 0).trim()
                val stored = credentialVault.getSecret("studio_automation_pin_hash")
                val ok = stored != null && stored == StudioTriggerBus.hashPin(candidate)
                if (ok) {
                    StudioTriggerBus.recordUnlockEvent("studio_pin_verified")
                } else {
                    StudioTriggerBus.recordFailedPinAttempt()
                }
                BoolVal(ok)
            }
            "pin.failed_attempts" -> NumVal(StudioTriggerBus.failedPinAttempts.value.toDouble())
            "pin.last_unlock_ms" -> NumVal(StudioTriggerBus.lastUnlockTimestampMs.value.toDouble())
            "pin.lock_now" -> {
                val r = AndroidCustomizer.performPowerAction(context, "lock")
                if (!r.ok) logSink("WARN", r.stderr)
                BoolVal(r.ok)
            }

            // ---- MOBILE ON / OFF CONTROLS ----
            "mobile.set_wifi" -> privilegeResultToMap(AndroidCustomizer.setWifiEnabled(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_data" -> privilegeResultToMap(AndroidCustomizer.setMobileDataEnabled(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_bluetooth" -> privilegeResultToMap(AndroidCustomizer.setBluetoothEnabled(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_flashlight" -> privilegeResultToMap(AndroidCustomizer.setFlashlight(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_airplane_mode" -> privilegeResultToMap(AndroidCustomizer.setAirplaneMode(context, args.getOrNull(0)?.asBool() ?: false), logSink)
            "mobile.set_hotspot" -> privilegeResultToMap(AndroidCustomizer.setHotspotEnabled(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_dnd" -> privilegeResultToMap(AndroidCustomizer.setDndEnabled(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_auto_rotate" -> privilegeResultToMap(AndroidCustomizer.setAutoRotate(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.set_screen" -> privilegeResultToMap(AndroidCustomizer.setScreenPower(context, args.getOrNull(0)?.asBool() ?: true), logSink)
            "mobile.power_action" -> privilegeResultToMap(AndroidCustomizer.performPowerAction(context, argText(args, 0).ifBlank { "lock" }), logSink)
            "mobile.set_brightness" -> BoolVal(AndroidCustomizer.setStandardBrightness(context, argNum(args, 0, line).toInt()))
            "mobile.set_volume" -> {
                val pct = argNum(args, 0, line).toInt().coerceIn(0, 100)
                val tweaks = AndroidCustomizer.readDeviceTweaks(context)
                val vol = ((pct / 100.0) * tweaks.maxMediaVolume).roundToInt()
                BoolVal(AndroidCustomizer.setStandardMediaVolume(context, vol))
            }

            // ---- SMS TRIGGERS & PLAY-COMPLIANT HELPERS ----
            "sms.has_notification_access" -> BoolVal(StudioNotificationTriggerService.isNotificationAccessEnabled(context))
            "sms.open_notification_access" -> BoolVal(StudioNotificationTriggerService.openNotificationAccessSettings(context))
            "sms.last_received" -> {
                val s = StudioTriggerBus.lastSms.value
                MapVal(linkedMapOf(
                    "package" to TextVal(s.packageName),
                    "sender" to TextVal(s.sender),
                    "body" to TextVal(s.body),
                    "otp" to TextVal(s.otpCode),
                    "timestamp_ms" to NumVal(s.timestampMs.toDouble())
                ))
            }
            "sms.extract_otp" -> {
                val txt = if (args.isNotEmpty()) argText(args, 0) else StudioTriggerBus.lastSms.value.body
                TextVal(StudioTriggerBus.extractOtp(txt))
            }
            "sms.matches_filter" -> {
                val senderPattern = argText(args, 0).trim()
                val bodyKeyword = argText(args, 1).trim()
                val s = StudioTriggerBus.lastSms.value
                val senderOk = senderPattern.isEmpty() || s.sender.contains(senderPattern, ignoreCase = true)
                val bodyOk = bodyKeyword.isEmpty() || s.body.contains(bodyKeyword, ignoreCase = true)
                BoolVal(s.timestampMs > 0L && senderOk && bodyOk)
            }
            "sms.open_composer" -> {
                val number = argText(args, 0)
                val body = argText(args, 1)
                val uri = Uri.parse("smsto:${Uri.encode(number)}")
                val i = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                BoolVal(runCatching { context.startActivity(i); true }.getOrDefault(false))
            }

            // ---- CATALOG MACRO RUNTIME BRIDGE (REAL HARDWARE & PDF EXECUTION) ----
            "catalog.fire_trigger" -> {
                val id = argText(args, 0)
                val label = argText(args, 1)
                val cat = argText(args, 2)
                val param = argText(args, 3)
                StudioTriggerBus.pushEvent(id, cat, "$label ($param)")
                logSink("INFO", "[Trigger] $label ($id) -> $param")
                MapVal(linkedMapOf("id" to TextVal(id), "label" to TextVal(label), "category" to TextVal(cat), "param" to TextVal(param)))
            }
            "catalog.run_action" -> {
                val id = argText(args, 0)
                val label = argText(args, 1)
                val target = argText(args, 2)
                val value = argText(args, 3)
                val execResult = executeRealCatalogAction(id, label, target, value, logSink)
                logSink("INFO", "[Action] $label ($id): target=$target value=$value -> ${execResult.asDisplayString()}")
                StudioTriggerBus.pushEvent(id, "Actions", "$label: $target=$value")
                execResult
            }
            "catalog.check_condition" -> {
                val id = argText(args, 0)
                val param = argText(args, 1)
                val expected = argText(args, 2)
                evaluateCatalogPredicate(id, param, expected)
            }
            "catalog.check_constraint" -> {
                val id = argText(args, 0)
                val param = argText(args, 1)
                val expected = argText(args, 2)
                evaluateCatalogPredicate(id, param, expected)
            }

            // ---- APPS & SYSTEM ----
            "apps.launch" -> {
                val pkg = argText(args, 0)
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    context.startActivity(launch)
                    BoolVal(true)
                } else BoolVal(false)
            }
            "apps.open_url" -> {
                val url = argText(args, 0)
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val ok = runCatching { context.startActivity(i) }.isSuccess
                BoolVal(ok)
            }
            "apps.installed" -> {
                val pkgs = runCatching {
                    context.packageManager.getInstalledApplications(0)
                        .map { it.packageName }
                        .sorted()
                        .take(200)
                }.getOrDefault(emptyList())
                ListVal(pkgs.map { TextVal(it) as StudioValue }.toMutableList())
            }
            "clipboard.get" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                TextVal(text)
            }
            "clipboard.set" -> {
                val text = argText(args, 0)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Automation Studio", text))
                BoolVal(cm != null)
            }
            "intent.broadcast" -> {
                val action = argText(args, 0)
                val i = Intent(action)
                val extraJson = args.getOrNull(1)?.let { argText(args, 1) }.orEmpty()
                if (extraJson.startsWith("{")) {
                    runCatching {
                        val obj = JSONObject(extraJson)
                        obj.keys().forEach { k -> i.putExtra(k, obj.optString(k)) }
                    }
                }
                val ok = runCatching { context.sendBroadcast(i) }.isSuccess
                BoolVal(ok)
            }
            "settings.open" -> {
                val panel = argText(args, 0).lowercase()
                val action = when (panel) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val i = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val ok = runCatching { context.startActivity(i) }.isSuccess
                BoolVal(ok)
            }
            "settings.dnd" -> {
                logSink("WARN", "Android requires Notification Policy Access for system DND changes; opening DND settings if needed.")
                BoolVal(false)
            }
            "wifi.set" -> {
                logSink("WARN", "Android 10+ restricts direct Wi-Fi toggling by apps; use Settings Panel instead.")
                BoolVal(false)
            }

            // ---- SHIZUKU & ROOT PRIVILEGE TIERS ----
            "shizuku.is_available" -> {
                BoolVal(PrivilegeBridge.inspectEnvironment(context).shizukuInstalled)
            }
            "shizuku.exec" -> {
                val cmd = argText(args, 0)
                val res = PrivilegeBridge.execShizuku(context, cmd)
                if (!res.ok) logSink("WARN", "shizuku.exec: ${res.stderr}")
                MapVal(linkedMapOf(
                    "ok" to BoolVal(res.ok),
                    "exit_code" to NumVal(res.exitCode.toDouble()),
                    "stdout" to TextVal(res.stdout),
                    "stderr" to TextVal(res.stderr),
                    "backend" to TextVal(res.backend)
                ))
            }
            "root.is_available" -> {
                BoolVal(PrivilegeBridge.inspectEnvironment(context).rootAvailable)
            }
            "root.exec" -> {
                val cmd = argText(args, 0)
                val res = PrivilegeBridge.execRoot(context, cmd)
                if (!res.ok) logSink("WARN", "root.exec: ${res.stderr}")
                MapVal(linkedMapOf(
                    "ok" to BoolVal(res.ok),
                    "exit_code" to NumVal(res.exitCode.toDouble()),
                    "stdout" to TextVal(res.stdout),
                    "stderr" to TextVal(res.stderr),
                    "backend" to TextVal(res.backend)
                ))
            }

            // ---- DETERMINISTIC MEMORY ARENAS (RUST-SAFE BOUNDS) ----
            "mem.arena_create" -> {
                val size = argNum(args, 0, line).toInt().coerceIn(1, 1024 * 1024)
                val id = nextArenaHandle++
                arenas[id] = ByteArray(size)
                NumVal(id.toDouble())
            }
            "mem.arena_free" -> {
                val id = argNum(args, 0, line).toInt()
                BoolVal(arenas.remove(id) != null)
            }
            "mem.write_u8" -> {
                val id = argNum(args, 0, line).toInt()
                val offset = argNum(args, 1, line).toInt()
                val value = argNum(args, 2, line).toInt()
                val buf = arenas[id] ?: throw StudioVmException("MEM001", "Use-after-free or invalid arena handle #$id.", line)
                if (offset < 0 || offset >= buf.size) throw StudioVmException("MEM002", "Arena out-of-bounds write_u8 at $offset (len=${buf.size}).", line)
                buf[offset] = (value and 0xFF).toByte()
                BoolVal(true)
            }
            "mem.read_u8" -> {
                val id = argNum(args, 0, line).toInt()
                val offset = argNum(args, 1, line).toInt()
                val buf = arenas[id] ?: throw StudioVmException("MEM001", "Use-after-free or invalid arena handle #$id.", line)
                if (offset < 0 || offset >= buf.size) throw StudioVmException("MEM002", "Arena out-of-bounds read_u8 at $offset (len=${buf.size}).", line)
                NumVal((buf[offset].toInt() and 0xFF).toDouble())
            }
            "mem.write_u32" -> {
                val id = argNum(args, 0, line).toInt()
                val offset = argNum(args, 1, line).toInt()
                val value = argNum(args, 2, line).toLong().toInt()
                val buf = arenas[id] ?: throw StudioVmException("MEM001", "Invalid arena handle #$id.", line)
                if (offset < 0 || offset + 4 > buf.size) throw StudioVmException("MEM002", "Arena out-of-bounds write_u32 at $offset (len=${buf.size}).", line)
                ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
                BoolVal(true)
            }
            "mem.read_u32" -> {
                val id = argNum(args, 0, line).toInt()
                val offset = argNum(args, 1, line).toInt()
                val buf = arenas[id] ?: throw StudioVmException("MEM001", "Invalid arena handle #$id.", line)
                if (offset < 0 || offset + 4 > buf.size) throw StudioVmException("MEM002", "Arena out-of-bounds read_u32 at $offset (len=${buf.size}).", line)
                val v = ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                NumVal(v.toDouble())
            }
            "mem.stats" -> {
                val totalBytes = arenas.values.sumOf { it.size }
                MapVal(linkedMapOf(
                    "active_arenas" to NumVal(arenas.size.toDouble()),
                    "allocated_bytes" to NumVal(totalBytes.toDouble())
                ))
            }

            // ---- BINARY & HEXADECIMAL PRIMITIVES ----
            "bin.to_hex_u32" -> {
                val v = argNum(args, 0, line).toLong() and 0xFFFFFFFFL
                TextVal("0x" + v.toString(16).uppercase().padStart(8, '0'))
            }
            "bin.from_hex" -> {
                val clean = argText(args, 0).replace("0x", "", ignoreCase = true).replace(Regex("\\s+"), "")
                val parsed = clean.toLongOrNull(16) ?: throw StudioVmException("BIN001", "Invalid hex value.", line)
                NumVal(parsed.toDouble())
            }
            "bin.to_bits" -> {
                val v = argNum(args, 0, line).toLong() and 0xFFL
                TextVal("0b" + v.toString(2).padStart(8, '0'))
            }

            // ---- HARDWARE & CUSTOM OS KERNEL PRIMITIVES ----
            "hw.cpu_info" -> MapVal(linkedMapOf(
                "abi" to TextVal(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                "cores" to NumVal(Runtime.getRuntime().availableProcessors().toDouble()),
                "hardware" to TextVal(Build.HARDWARE ?: "android"),
                "board" to TextVal(Build.BOARD ?: "generic")
            ))
            "hw.mem_info" -> {
                val rt = Runtime.getRuntime()
                MapVal(linkedMapOf(
                    "max_mb" to NumVal((rt.maxMemory() / (1024 * 1024)).toDouble()),
                    "free_mb" to NumVal((rt.freeMemory() / (1024 * 1024)).toDouble()),
                    "arena_bytes" to NumVal(arenas.values.sumOf { it.size }.toDouble())
                ))
            }
            "hw.syscall" -> {
                val num = argNum(args, 0, line).toInt()
                val arg = args.getOrNull(1)?.asDisplayString().orEmpty()
                logSink("INFO", "[SYSCALL #$num] arg=$arg")
                MapVal(linkedMapOf("nr" to NumVal(num.toDouble()), "status" to NumVal(0.0), "arg" to TextVal(arg)))
            }
            "os.kernel_log" -> {
                val msg = argText(args, 0)
                logSink("INFO", "[KERNEL] $msg")
                NoneVal
            }
            "os.scheduler_tick" -> {
                val quantum = if (args.isEmpty()) 1.0 else argNum(args, 0, line)
                NumVal(quantum)
            }

            // ---- ANDROID OS & PERMISSION CUSTOMIZER (STANDARD + SHIZUKU/ROOT) ----
            "android.device_tweaks" -> {
                val t = AndroidCustomizer.readDeviceTweaks(context)
                MapVal(linkedMapOf(
                    "brightness" to NumVal(t.brightness.toDouble()),
                    "timeout_ms" to NumVal(t.screenTimeoutMs.toDouble()),
                    "media_vol" to NumVal(t.mediaVolume.toDouble()),
                    "max_media_vol" to NumVal(t.maxMediaVolume.toDouble()),
                    "can_write_settings" to BoolVal(t.canWriteSettings),
                    "can_draw_overlays" to BoolVal(t.canDrawOverlays)
                ))
            }
            "android.package_perms" -> {
                val pkg = argText(args, 0).ifBlank { context.packageName }
                val perms = AndroidCustomizer.getPackagePermissions(context, pkg)
                ListVal(perms.map {
                    MapVal(linkedMapOf("permission" to TextVal(it.permission), "granted" to BoolVal(it.granted))) as StudioValue
                }.toMutableList())
            }
            "android.grant_perm" -> {
                val r = AndroidCustomizer.grantPermission(context, argText(args, 0), argText(args, 1))
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }
            "android.revoke_perm" -> {
                val r = AndroidCustomizer.revokePermission(context, argText(args, 0), argText(args, 1))
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }
            "android.appops" -> {
                val r = AndroidCustomizer.setAppOps(context, argText(args, 0), argText(args, 1), argText(args, 2))
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }
            "android.set_setting" -> {
                val r = AndroidCustomizer.setSystemSetting(context, argText(args, 0), argText(args, 1), argText(args, 2))
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }
            "android.wm_density" -> {
                val r = AndroidCustomizer.setWmDensity(context, argNum(args, 0, line).toInt())
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }
            "android.wm_size" -> {
                val r = AndroidCustomizer.setWmSize(context, argNum(args, 0, line).toInt(), argNum(args, 1, line).toInt())
                MapVal(linkedMapOf("ok" to BoolVal(r.ok), "stderr" to TextVal(r.stderr), "backend" to TextVal(r.backend)))
            }

            // ---- UNIFIED TRI-PANE IDE (GODOT + VS CODE + ANDROID CODE STUDIO + VOICE) ----
            "godot.spawn" -> {
                val typeName = argText(args, 0).ifBlank { "Sprite2D" }
                val nodeName = argText(args, 1).ifBlank { "Node_${System.currentTimeMillis() % 1000}" }
                val x = args.getOrNull(2)?.asDouble()?.toFloat() ?: 160f
                val y = args.getOrNull(3)?.asDouble()?.toFloat() ?: 120f
                val res = com.example.ide.UnifiedIdeBridge.spawnNode(typeName, nodeName, x, y)
                logSink("INFO", "[Godot] Spawned $typeName '$nodeName' at ($x, $y)")
                MapVal(
                    linkedMapOf(
                        "ok" to BoolVal((res["ok"] as? Boolean) ?: false),
                        "id" to TextVal(res["id"]?.toString().orEmpty()),
                        "name" to TextVal(res["name"]?.toString().orEmpty()),
                        "type" to TextVal(res["type"]?.toString().orEmpty())
                    )
                )
            }
            "godot.move" -> {
                val target = argText(args, 0)
                val dx = args.getOrNull(1)?.asDouble()?.toFloat() ?: 0f
                val dy = args.getOrNull(2)?.asDouble()?.toFloat() ?: 0f
                BoolVal(com.example.ide.UnifiedIdeBridge.moveNode(target, dx, dy))
            }
            "godot.set_prop" -> {
                BoolVal(com.example.ide.UnifiedIdeBridge.setNodeProp(argText(args, 0), argText(args, 1), argText(args, 2)))
            }
            "godot.get_prop" -> {
                TextVal(com.example.ide.UnifiedIdeBridge.getNodeProp(argText(args, 0), argText(args, 1)))
            }
            "godot.play" -> BoolVal(com.example.ide.UnifiedIdeBridge.setScenePlaying(playing = true, paused = false))
            "godot.pause" -> BoolVal(com.example.ide.UnifiedIdeBridge.setScenePlaying(playing = false, paused = true))
            "godot.stop" -> BoolVal(com.example.ide.UnifiedIdeBridge.setScenePlaying(playing = false, paused = false))
            "godot.list_nodes" -> {
                val list = com.example.ide.UnifiedIdeBridge.listSceneNodes().map { m ->
                    MapVal(
                        linkedMapOf(
                            "id" to TextVal(m["id"]?.toString().orEmpty()),
                            "name" to TextVal(m["name"]?.toString().orEmpty()),
                            "type" to TextVal(m["type"]?.toString().orEmpty()),
                            "x" to NumVal((m["x"] as? Double) ?: 0.0),
                            "y" to NumVal((m["y"] as? Double) ?: 0.0),
                            "script" to TextVal(m["script"]?.toString().orEmpty())
                        )
                    ) as StudioValue
                }.toMutableList()
                ListVal(list)
            }
            "godot.export_tscn" -> TextVal(com.example.ide.UnifiedIdeBridge.exportTscn())
            "ide.open_file" -> BoolVal(com.example.ide.UnifiedIdeBridge.openEditorFile(argText(args, 0)))
            "ide.write_file" -> BoolVal(com.example.ide.UnifiedIdeBridge.writeEditorFile(argText(args, 0), argText(args, 1)))
            "ide.run_terminal" -> TextVal(com.example.ide.UnifiedIdeBridge.runTerminal(argText(args, 0)))
            "acs.validate" -> {
                val r = com.example.ide.UnifiedIdeBridge.validateAndroidProject()
                val diags = (r["diagnostics"] as? List<*>)?.map { TextVal(it.toString()) as StudioValue }?.toMutableList() ?: mutableListOf()
                MapVal(
                    linkedMapOf(
                        "ok" to BoolVal((r["ok"] as? Boolean) ?: false),
                        "error_count" to NumVal(((r["error_count"] as? Int) ?: 0).toDouble()),
                        "diagnostics" to ListVal(diags)
                    )
                )
            }
            "voice.speak" -> {
                val msg = argText(args, 0)
                logSink("INFO", "[Voice] $msg")
                voiceSpeakCallback?.invoke(msg)
                NoneVal
            }

            // ---- AGENT ----
            "agent.ask" -> {
                val prompt = argText(args, 0)
                val reply = aiAskCallback?.invoke(prompt) ?: "Agent AI callback not attached in this session."
                TextVal(reply)
            }

            else -> {
                PythonStdLibBridge.dispatchOrNull(qualifiedName, args, line, workspaceRoot, logSink)
                    ?: OsAndHardwareBridge.dispatchOrNull(context, qualifiedName, args, line, workspaceRoot, logSink)
                    ?: throw StudioVmException("STD404", "Unknown host API '$qualifiedName'.", line)
            }
        }
    }

    private fun argNum(args: List<StudioValue>, idx: Int, line: Int): Double =
        args.getOrNull(idx)?.asDouble() ?: throw StudioVmException("STD002", "Missing numeric argument #$idx.", line)

    private fun argText(args: List<StudioValue>, idx: Int): String =
        args.getOrNull(idx)?.let {
            if (it is TextVal) it.value else it.asDisplayString()
        } ?: ""

    private fun readBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun readIsCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) bm.isCharging else false
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice(ms: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v?.vibrate(ms)
                }
            }
        }
    }

    private fun isNetworkConnected(wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return if (wifiOnly) caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        else caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun httpGet(url: String, line: Int): MapVal = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw StudioVmException("NET001", "URL must start with http:// or https://", line)
        }
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            MapVal(
                linkedMapOf(
                    "status" to NumVal(resp.code.toDouble()),
                    "ok" to BoolVal(resp.isSuccessful),
                    "body" to TextVal(resp.body?.string().orEmpty().take(65_536))
                )
            )
        }
    }

    private suspend fun httpPost(url: String, jsonBody: String, line: Int): MapVal = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw StudioVmException("NET001", "URL must start with http:// or https://", line)
        }
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        httpClient.newCall(req).execute().use { resp ->
            MapVal(
                linkedMapOf(
                    "status" to NumVal(resp.code.toDouble()),
                    "ok" to BoolVal(resp.isSuccessful),
                    "body" to TextVal(resp.body?.string().orEmpty().take(65_536))
                )
            )
        }
    }

    private fun showNotification(title: String, body: String, line: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                throw StudioVmException("PERM001", "POST_NOTIFICATIONS permission not granted by Android user.", line)
            }
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "studio_macro_notifications"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chId, context.getString(R.string.channel_macro_notify_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(context, chId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(5001, notif)
    }

    private fun parseJsonToValue(raw: String, line: Int): StudioValue = try {
        val trimmed = raw.trim()
        when {
            trimmed.startsWith("{") -> jsonObjToMap(JSONObject(trimmed))
            trimmed.startsWith("[") -> jsonArrToList(JSONArray(trimmed))
            else -> TextVal(trimmed)
        }
    } catch (e: Exception) {
        throw StudioVmException("JSON001", "Invalid JSON: ${e.message}", line)
    }

    private fun jsonObjToMap(obj: JSONObject): MapVal {
        val m = linkedMapOf<String, StudioValue>()
        obj.keys().forEach { k ->
            m[k] = anyJsonToStudio(obj.get(k))
        }
        return MapVal(m)
    }

    private fun jsonArrToList(arr: JSONArray): ListVal {
        val list = mutableListOf<StudioValue>()
        for (i in 0 until arr.length()) list += anyJsonToStudio(arr.get(i))
        return ListVal(list)
    }

    private fun anyJsonToStudio(v: Any?): StudioValue = when (v) {
        null, JSONObject.NULL -> NoneVal
        is Boolean -> BoolVal(v)
        is Number -> NumVal(v.toDouble())
        is String -> TextVal(v)
        is JSONObject -> jsonObjToMap(v)
        is JSONArray -> jsonArrToList(v)
        else -> TextVal(v.toString())
    }

    private fun valueToJsonString(v: StudioValue): String = when (v) {
        is NumVal -> if (v.value % 1.0 == 0.0) v.value.toLong().toString() else v.value.toString()
        is TextVal -> JSONObject.quote(if (v.isSecret) "••••" else v.value)
        is BoolVal -> v.value.toString()
        NoneVal -> "null"
        is DurationVal -> "${v.millis}"
        is ListVal -> "[${v.items.joinToString(",") { valueToJsonString(it) }}]"
        is MapVal -> "{${v.entries.entries.joinToString(",") { "${JSONObject.quote(it.key)}:${valueToJsonString(it.value)}" }}}"
        else -> JSONObject.quote(v.asDisplayString())
    }

    private fun normalizePermissionName(raw: String): String {
        val clean = raw.trim()
        return when (clean.uppercase(Locale.US)) {
            "MIC", "MICROPHONE", "RECORD_AUDIO" -> Manifest.permission.RECORD_AUDIO
            "NOTIFICATIONS", "NOTIFICATION", "POST_NOTIFICATIONS" -> Manifest.permission.POST_NOTIFICATIONS
            "OVERLAY", "FLOATING_OVERLAY", "SYSTEM_ALERT_WINDOW" -> Manifest.permission.SYSTEM_ALERT_WINDOW
            "WRITE_SETTINGS", "MODIFY_SETTINGS" -> Manifest.permission.WRITE_SETTINGS
            "ACCESSIBILITY", "ACCESSIBILITY_SERVICE" -> "android.permission.BIND_ACCESSIBILITY_SERVICE"
            "NOTIFICATION_ACCESS", "BIND_NOTIFICATION_LISTENER_SERVICE" -> "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            "CAMERA" -> Manifest.permission.CAMERA
            "LOCATION", "FINE_LOCATION", "ACCESS_FINE_LOCATION" -> Manifest.permission.ACCESS_FINE_LOCATION
            "COARSE_LOCATION", "ACCESS_COARSE_LOCATION" -> Manifest.permission.ACCESS_COARSE_LOCATION
            "VIBRATE" -> Manifest.permission.VIBRATE
            "INTERNET" -> Manifest.permission.INTERNET
            else -> if (clean.contains(".")) clean else "android.permission.${clean.uppercase(Locale.US)}"
        }
    }

    private fun isPermissionGranted(normalizedPerm: String): Boolean {
        return when (normalizedPerm) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(context)
            Manifest.permission.WRITE_SETTINGS -> Settings.System.canWrite(context)
            "android.permission.BIND_ACCESSIBILITY_SERVICE" -> StudioAccessibilityService.isConnected.value
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> StudioNotificationTriggerService.isNotificationAccessEnabled(context)
            else -> ContextCompat.checkSelfPermission(context, normalizedPerm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun privilegeResultToMap(
        res: PrivilegedCommandResult,
        logSink: (level: String, message: String) -> Unit
    ): MapVal {
        if (!res.ok && res.stderr.isNotBlank()) logSink("WARN", res.stderr)
        return MapVal(
            linkedMapOf(
                "ok" to BoolVal(res.ok),
                "exit_code" to NumVal(res.exitCode.toDouble()),
                "stdout" to TextVal(res.stdout),
                "stderr" to TextVal(res.stderr),
                "backend" to TextVal(res.backend)
            )
        )
    }

    private suspend fun executeRealCatalogAction(
        id: String,
        label: String,
        target: String,
        value: String,
        logSink: (level: String, message: String) -> Unit
    ): MapVal {
        val low = "$id $label $target".lowercase(Locale.US)
        val desiredOn = value.lowercase(Locale.US) in setOf("true", "on", "1", "enable", "yes", "")
        var detail = value
        val ok = when {
            low.contains("torch") || low.contains("flash") -> {
                HardwareController.setTorch(context, desiredOn)
            }
            low.contains("vibrate") -> {
                val ms = value.toLongOrNull()?.coerceIn(20L, 3000L) ?: 180L
                vibrateDevice(ms); true
            }
            low.contains("speakerphone") -> HardwareController.setSpeakerphone(context, desiredOn)
            low.contains("mute") && low.contains("mic") -> HardwareController.setMicMute(context, desiredOn)
            low.contains("dnd") || low.contains("do_not_disturb") -> {
                AndroidCustomizer.setDndEnabled(context, desiredOn).ok
            }
            low.contains("volume") -> {
                val pct = value.toIntOrNull() ?: 60
                val newPct = HardwareController.setVolumePercent(context, target.ifBlank { "media" }, pct)
                detail = "${newPct}%"
                newPct >= 0
            }
            low.contains("brightness") -> {
                AndroidCustomizer.setStandardBrightness(context, value.toIntOrNull() ?: 128)
            }
            low.contains("wifi") -> AndroidCustomizer.setWifiEnabled(context, desiredOn).ok
            low.contains("bluetooth") -> AndroidCustomizer.setBluetoothEnabled(context, desiredOn).ok
            low.contains("airplane") -> AndroidCustomizer.setAirplaneMode(context, desiredOn).ok
            low.contains("auto_rotate") || low.contains("rotation") -> AndroidCustomizer.setAutoRotate(context, desiredOn).ok
            low.contains("screen_off") || low.contains("lock_screen") -> AndroidCustomizer.performPowerAction(context, "lock").ok
            low.contains("reboot") || low.contains("shutdown") -> AndroidCustomizer.performPowerAction(context, value.ifBlank { "reboot" }).ok
            low.contains("stopwatch_start") || (low.contains("stopwatch") && low.contains("start")) -> {
                CustomOsKernel.startStopwatch(value.ifBlank { "default" }); true
            }
            low.contains("stopwatch_stop") -> {
                detail = "${CustomOsKernel.stopStopwatch(value.ifBlank { "default" })}ms"; true
            }
            low.contains("stopwatch_reset") -> {
                CustomOsKernel.resetStopwatch(value.ifBlank { "default" }); true
            }
            low.contains("clipboard") -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Automation Studio", value))
                cm != null
            }
            low.contains("speak") || low.contains("voice") -> {
                voiceSpeakCallback?.invoke(value.ifBlank { label })
                true
            }
            low.contains("beep") || low.contains("sound") -> {
                OsAndHardwareBridge.dispatchOrNull(context, "audio.beep", listOf(NumVal(160.0)), 1, workspaceRoot, logSink)
                true
            }
            low.contains("notify") || low.contains("notification") -> {
                runCatching { showNotification(label.ifBlank { "Macro" }, value.ifBlank { target }, 1); true }.getOrDefault(false)
            }
            low.contains("kernel_bench") -> {
                detail = CustomOsKernel.benchCpu(10_000).toString()
                true
            }
            else -> true
        }
        return MapVal(linkedMapOf(
            "ok" to BoolVal(ok),
            "id" to TextVal(id),
            "target" to TextVal(target),
            "value" to TextVal(detail)
        ))
    }

    private fun evaluateCatalogPredicate(id: String, param: String, expected: String): BoolVal {
        val low = id.lowercase(Locale.US)
        val ok = when {
            low.contains("battery_above") || low.contains("batt_gte") || low.contains("battery_level_gt") ->
                readBatteryLevel() >= (expected.toIntOrNull() ?: 20)
            low.contains("battery_below") || low.contains("batt_lte") || low.contains("battery_level_lt") ->
                readBatteryLevel() <= (expected.toIntOrNull() ?: 20)
            low.contains("battery_temp") -> {
                val temp = (HardwareController.batteryInfo(context)["temperature_c"] as? Double) ?: 30.0
                temp >= (expected.toDoubleOrNull() ?: 38.0)
            }
            low.contains("power_save") || low.contains("battery_saver") -> HardwareController.isPowerSaveMode(context)
            low.contains("charging") || low.contains("power_connected") -> readIsCharging()
            low.contains("discharging") -> !readIsCharging()
            low.contains("wifi") -> isNetworkConnected(wifiOnly = true)
            low.contains("network") || low.contains("online") || low.contains("internet") -> isNetworkConnected(wifiOnly = false)
            low.contains("device_locked") || low.contains("keyguard_locked") -> StudioTriggerBus.isDeviceLocked(context)
            low.contains("device_unlocked") || low.contains("pin_unlocked") -> !StudioTriggerBus.isDeviceLocked(context)
            low.contains("pin_secure") -> StudioTriggerBus.isKeyguardSecure(context)
            low.contains("screen_on") -> StudioTriggerBus.screenOn.value
            low.contains("screen_off") -> !StudioTriggerBus.screenOn.value
            low.contains("dark_theme") -> HardwareController.isDarkTheme(context)
            low.contains("torch_on") -> HardwareController.isTorchKnownOn()
            low.contains("headphone") || low.contains("headset") -> HardwareController.isHeadsetConnected(context)
            low.contains("music_active") -> HardwareController.isMusicActive(context)
            low.contains("ringer_silent") -> HardwareController.getRingerMode(context) == "silent"
            low.contains("ringer_vibrate") -> HardwareController.getRingerMode(context) == "vibrate"
            low.contains("ringer_normal") -> HardwareController.getRingerMode(context) == "normal"
            low.contains("face_down") -> HardwareController.isFaceDown(context)
            low.contains("face_up") -> HardwareController.isFaceUp(context)
            low.contains("light_lux") -> {
                val lux = HardwareController.lightLux(context)
                lux >= 0 && lux <= (expected.toDoubleOrNull() ?: 50.0)
            }
            low.contains("proximity_near") -> {
                val prox = HardwareController.proximityCm(context)
                prox in 0.0..4.9
            }
            low.contains("stopwatch") -> {
                val elapsed = CustomOsKernel.elapsedStopwatchMs(param.ifBlank { "default" })
                elapsed >= (expected.toLongOrNull() ?: 1000L)
            }
            low.contains("sms_otp") -> StudioTriggerBus.lastSms.value.otpCode.isNotEmpty()
            low.contains("sms_") -> {
                val s = StudioTriggerBus.lastSms.value
                s.timestampMs > 0L && (expected.isBlank() || s.body.contains(expected, ignoreCase = true) || s.sender.contains(expected, ignoreCase = true))
            }
            low.contains("shizuku") -> PrivilegeBridge.inspectEnvironment(context).shizukuInstalled
            low.contains("root") -> PrivilegeBridge.inspectEnvironment(context).rootAvailable
            low.contains("accessibility") -> StudioAccessibilityService.isConnected.value
            low.contains("overlay") -> Settings.canDrawOverlays(context)
            low.contains("write_settings") -> Settings.System.canWrite(context)
            else -> param.isNotBlank() || expected.isNotBlank()
        }
        return BoolVal(ok)
    }
}
