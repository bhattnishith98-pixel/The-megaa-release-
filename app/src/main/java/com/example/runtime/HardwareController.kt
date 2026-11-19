package com.example.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.example.services.StudioTriggerBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Full Android Hardware & Sensor Controller (`hw.*`, `sensor.*`, `audio.*`, `display.*`, `camera.*`, `power.*`).
 * Operates strictly within Play-Policy-safe permissions (no banned sensors or storage permissions).
 */
object HardwareController {

    private val sensorValues = ConcurrentHashMap<Int, FloatArray>()
    private val listenersRegistered = AtomicBoolean(false)
    @Volatile
    private var torchStateKnown: Boolean = false

    fun ensureSensorsListening(context: Context) {
        if (!listenersRegistered.compareAndSet(false, true)) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                sensorValues[e.sensor.type] = e.values.copyOf()
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER && e.values.size >= 3) {
                    val x = e.values[0]
                    val y = e.values[1]
                    val z = e.values[2]
                    val mag = sqrt(x * x + y * y + z * z)
                    if (mag > 21.5f) {
                        StudioTriggerBus.pushEvent("trigger.shake", "Sensors", "Device shake detected (g=${"%.1f".format(mag)})")
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val targetTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE
        )
        targetTypes.forEach { t ->
            sm.getDefaultSensor(t)?.let { sensor ->
                runCatching { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        }
    }

    // ---- SENSORS ----

    fun listSensors(context: Context): List<Map<String, Any>> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return sm.getSensorList(Sensor.TYPE_ALL).map { s ->
            mapOf(
                "name" to s.name,
                "vendor" to s.vendor,
                "type" to s.type,
                "max_range" to s.maximumRange.toDouble(),
                "power_ma" to s.power.toDouble()
            )
        }
    }

    fun readSensor(context: Context, kind: String): Map<String, Any> {
        ensureSensorsListening(context)
        val type = when (kind.lowercase()) {
            "accel", "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "light", "lux" -> Sensor.TYPE_LIGHT
            "proximity", "prox" -> Sensor.TYPE_PROXIMITY
            "gyro", "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "mag", "compass", "magnetic" -> Sensor.TYPE_MAGNETIC_FIELD
            "pressure", "barometer" -> Sensor.TYPE_PRESSURE
            else -> kind.toIntOrNull() ?: Sensor.TYPE_ACCELEROMETER
        }
        val vals = sensorValues[type]?.map { it.toDouble() }.orEmpty()
        return mapOf(
            "kind" to kind,
            "type" to type,
            "values" to vals,
            "available" to (vals.isNotEmpty() || (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(type) != null)
        )
    }

    fun lightLux(context: Context): Double {
        ensureSensorsListening(context)
        return sensorValues[Sensor.TYPE_LIGHT]?.firstOrNull()?.toDouble() ?: -1.0
    }

    fun proximityCm(context: Context): Double {
        ensureSensorsListening(context)
        return sensorValues[Sensor.TYPE_PROXIMITY]?.firstOrNull()?.toDouble() ?: -1.0
    }

    fun isFaceDown(context: Context): Boolean {
        ensureSensorsListening(context)
        val z = sensorValues[Sensor.TYPE_ACCELEROMETER]?.getOrNull(2) ?: return false
        return z < -7.5f
    }

    fun isFaceUp(context: Context): Boolean {
        ensureSensorsListening(context)
        val z = sensorValues[Sensor.TYPE_ACCELEROMETER]?.getOrNull(2) ?: return false
        return z > 7.5f
    }

    // ---- AUDIO ----

    private fun resolveAudioStream(streamName: String): Int = when (streamName.lowercase()) {
        "ring", "ringer" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification", "notif" -> AudioManager.STREAM_NOTIFICATION
        "call", "voice", "voice_call" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    fun getVolumePercent(context: Context, streamName: String = "media"): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 50
        val stream = resolveAudioStream(streamName)
        val cur = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ((cur * 100.0) / max).toInt().coerceIn(0, 100)
    }

    fun setVolumePercent(context: Context, streamName: String = "media", percent: Int): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        val stream = resolveAudioStream(streamName)
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = ((percent.coerceIn(0, 100) / 100.0) * max).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(stream, target, 0) }
        return getVolumePercent(context, streamName)
    }

    fun getRingerMode(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "normal"
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    }

    fun setRingerMode(context: Context, mode: String): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val target = when (mode.lowercase()) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return runCatching {
            am.ringerMode = target
            true
        }.getOrDefault(false)
    }

    fun isMusicActive(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.isMusicActive
    }

    fun isHeadsetConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
        @Suppress("DEPRECATION")
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn
    }

    fun setSpeakerphone(context: Context, enabled: Boolean): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = enabled
        return true
    }

    fun setMicMute(context: Context, muted: Boolean): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        am.isMicrophoneMute = muted
        return true
    }

    fun audioInfo(context: Context): Map<String, Any> = mapOf(
        "media_pct" to getVolumePercent(context, "media"),
        "ring_pct" to getVolumePercent(context, "ring"),
        "alarm_pct" to getVolumePercent(context, "alarm"),
        "notification_pct" to getVolumePercent(context, "notification"),
        "ringer_mode" to getRingerMode(context),
        "music_active" to isMusicActive(context),
        "headset_connected" to isHeadsetConnected(context)
    )

    // ---- BATTERY & POWER ----

    fun batteryInfo(context: Context): Map<String, Any> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100) / scale else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100)
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3900) ?: 3900
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || plugged > 0
        val powerSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        return mapOf(
            "level" to pct,
            "charging" to charging,
            "power_source" to powerSource,
            "temperature_c" to (tempTenths / 10.0),
            "voltage_mv" to voltageMv
        )
    }

    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }

    fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "unknown"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            else -> "shutdown"
        }
    }

    // ---- DISPLAY & CAMERA TORCH ----

    fun isDarkTheme(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun displayInfo(context: Context): Map<String, Any> {
        val metrics = context.resources.displayMetrics
        val tweaks = AndroidCustomizer.readDeviceTweaks(context)
        val autoRot = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        }.getOrDefault(true)
        return mapOf(
            "width_px" to metrics.widthPixels,
            "height_px" to metrics.heightPixels,
            "density_dpi" to metrics.densityDpi,
            "brightness" to tweaks.brightness,
            "auto_rotate" to autoRot,
            "dark_theme" to isDarkTheme(context),
            "font_scale" to tweaks.fontScale.toDouble()
        )
    }

    fun setTorch(context: Context, on: Boolean): Boolean {
        val res = AndroidCustomizer.setFlashlight(context, on)
        if (res.ok) torchStateKnown = on
        return res.ok
    }

    fun isTorchKnownOn(): Boolean = torchStateKnown

    fun cameraInfo(context: Context): Map<String, Any> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val ids = runCatching { cm?.cameraIdList?.toList().orEmpty() }.getOrDefault(emptyList())
        val hasFlash = ids.any { id ->
            runCatching {
                cm?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
        return mapOf(
            "camera_count" to ids.size,
            "has_flash" to hasFlash,
            "torch_on" to torchStateKnown
        )
    }

    // ---- VIBRATION & HARDWARE SUMMARY ----

    fun vibratePattern(context: Context, durationsMs: List<Long>): Boolean {
        if (durationsMs.isEmpty()) return false
        val safe = durationsMs.map { it.coerceIn(10L, 5000L) }.toLongArray()
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (safe.size == 1) {
                    vib.vibrate(VibrationEffect.createOneShot(safe[0], VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(safe, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(safe, -1)
            }
            true
        }.getOrDefault(false)
    }

    fun hardwareSummary(context: Context): Map<String, Any> {
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val freeMb = stat?.let { (it.availableBytes / (1024L * 1024L)) } ?: 0L
        val totalMb = stat?.let { (it.totalBytes / (1024L * 1024L)) } ?: 0L
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_sdk" to Build.VERSION.SDK_INT,
            "cpu_cores" to Runtime.getRuntime().availableProcessors(),
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "storage_free_mb" to freeMb,
            "storage_total_mb" to totalMb,
            "battery" to batteryInfo(context),
            "audio" to audioInfo(context),
            "display" to displayInfo(context),
            "camera" to cameraInfo(context),
            "power_save" to isPowerSaveMode(context),
            "thermal" to thermalStatus(context)
        )
    }
}
