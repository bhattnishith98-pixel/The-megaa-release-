package com.example.services

import android.app.KeyguardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

data class SmsTriggerSnapshot(
    val packageName: String = "",
    val sender: String = "",
    val body: String = "",
    val otpCode: String = "",
    val timestampMs: Long = 0L
)

data class TouchTriggerSnapshot(
    val x: Float = 540f,
    val y: Float = 960f,
    val gestureType: String = "tap",
    val durationMs: Long = 60L,
    val timestampMs: Long = 0L
)

data class TriggerEventRecord(
    val id: String,
    val triggerTypeId: String,
    val category: String,
    val summary: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Thread-safe reactive trigger event bus for PIN unlock, SMS notifications,
 * custom touch gestures, battery/screen broadcasts, and mobile state changes.
 */
object StudioTriggerBus {
    private val OTP_REGEX = Regex("\\b(\\d{4,8})\\b")

    private val _lastSms = MutableStateFlow(SmsTriggerSnapshot())
    val lastSms: StateFlow<SmsTriggerSnapshot> = _lastSms.asStateFlow()

    private val _lastTouch = MutableStateFlow(TouchTriggerSnapshot())
    val lastTouch: StateFlow<TouchTriggerSnapshot> = _lastTouch.asStateFlow()

    private val _lastUnlockTimestampMs = MutableStateFlow(0L)
    val lastUnlockTimestampMs: StateFlow<Long> = _lastUnlockTimestampMs.asStateFlow()

    private val _failedPinAttempts = MutableStateFlow(0)
    val failedPinAttempts: StateFlow<Int> = _failedPinAttempts.asStateFlow()

    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _batteryTempC = MutableStateFlow(30.0)
    val batteryTempC: StateFlow<Double> = _batteryTempC.asStateFlow()

    private val _powerSource = MutableStateFlow("battery")
    val powerSource: StateFlow<String> = _powerSource.asStateFlow()

    private val _lastAutoRunSummary = MutableStateFlow<String?>(null)
    val lastAutoRunSummary: StateFlow<String?> = _lastAutoRunSummary.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<TriggerEventRecord>>(emptyList())
    val recentEvents: StateFlow<List<TriggerEventRecord>> = _recentEvents.asStateFlow()

    @Volatile
    private var lastEmittedBatteryPct: Int = -1

    fun extractOtp(text: String): String {
        val lower = text.lowercase()
        val hasOtpHint = lower.contains("otp") || lower.contains("code") ||
            lower.contains("pin") || lower.contains("verify") ||
            lower.contains("verification") || lower.contains("passcode")
        val match = OTP_REGEX.find(text)?.groupValues?.getOrNull(1).orEmpty()
        return if (hasOtpHint || match.length in 4..6) match else ""
    }

    fun recordIncomingSms(packageName: String, sender: String, body: String) {
        val otp = extractOtp(body)
        val snap = SmsTriggerSnapshot(
            packageName = packageName,
            sender = sender,
            body = body,
            otpCode = otp,
            timestampMs = System.currentTimeMillis()
        )
        _lastSms.value = snap
        val triggerId = if (otp.isNotEmpty()) "trigger.sms_otp_received" else "trigger.sms_received"
        pushEvent(triggerId, "Call/SMS", "SMS from '${sender.ifBlank { "Unknown" }}': ${body.take(64)}")
    }

    fun recordNotification(packageName: String, title: String, text: String) {
        pushEvent("trigger.notification_received", "Notification", "[$packageName] $title: ${text.take(60)}")
    }

    fun recordTouchGesture(gestureType: String, x: Float, y: Float, durationMs: Long = 60L) {
        _lastTouch.value = TouchTriggerSnapshot(
            x = x,
            y = y,
            gestureType = gestureType,
            durationMs = durationMs,
            timestampMs = System.currentTimeMillis()
        )
        pushEvent("trigger.custom_touch_$gestureType", "Device Events", "Touch $gestureType at (${x.toInt()}, ${y.toInt()}) ${durationMs}ms")
    }

    fun recordUnlockEvent(source: String = "user_present") {
        _lastUnlockTimestampMs.value = System.currentTimeMillis()
        _failedPinAttempts.value = 0
        pushEvent("trigger.pin_unlock", "Device Events", "Device unlocked ($source)")
    }

    fun recordFailedPinAttempt(): Int {
        val next = _failedPinAttempts.value + 1
        _failedPinAttempts.value = next
        pushEvent("trigger.pin_failed_attempt", "Device Events", "Failed PIN attempt #$next")
        return next
    }

    fun resetFailedPinAttempts() {
        _failedPinAttempts.value = 0
    }

    fun recordScreenState(on: Boolean) {
        _screenOn.value = on
        pushEvent(
            if (on) "trigger.screen_on" else "trigger.screen_off",
            "Device Events",
            if (on) "Screen turned ON" else "Screen turned OFF"
        )
    }

    fun recordBatteryState(
        level: Int,
        charging: Boolean,
        tempC: Double = _batteryTempC.value,
        source: String = _powerSource.value
    ) {
        val prevLevel = _batteryLevel.value
        _batteryLevel.value = level
        _isCharging.value = charging
        _batteryTempC.value = tempC
        _powerSource.value = source
        if (lastEmittedBatteryPct != level && (lastEmittedBatteryPct != -1 || level != prevLevel)) {
            lastEmittedBatteryPct = level
            pushEvent("trigger.battery_changed", "Battery/Power", "Battery ${level}% (${if (charging) "charging:$source" else "discharging"}, ${"%.1f".format(tempC)}C)")
        } else if (lastEmittedBatteryPct == -1) {
            lastEmittedBatteryPct = level
        }
    }

    fun recordMacroAutoRun(macroName: String, outcome: String) {
        _lastAutoRunSummary.value = "$macroName -> $outcome"
    }

    fun matchesTrigger(patternId: String, patternParam: String, event: TriggerEventRecord): Boolean {
        val normPat = patternId.trim().lowercase()
        val normEvt = event.triggerTypeId.trim().lowercase()
        if (normPat.isEmpty() || normPat == "trigger.manual" || normPat == "manual") return false
        if (normPat == "*" || normPat == "trigger.any") return true
        val idMatched = normEvt == normPat ||
            normEvt.startsWith(normPat) ||
            normPat.removePrefix("trigger.") == normEvt.removePrefix("trigger.") ||
            (normPat.contains("battery") && normPat.contains("level") && normEvt == "trigger.battery_changed") ||
            (normPat.contains("power_connect") && normEvt in setOf("trigger.power_connected", "trigger.power_disconnected")) ||
            (normPat.contains("sms") && normEvt.startsWith("trigger.sms")) ||
            (normPat.contains("unlock") && normEvt == "trigger.pin_unlock") ||
            (normPat.contains("shake") && normEvt == "trigger.shake") ||
            (normPat.contains("wifi") && normEvt.startsWith("trigger.wifi")) ||
            (normPat.contains("bluetooth") && normEvt.startsWith("trigger.bt")) ||
            (normPat.contains("screen") && normEvt.startsWith("trigger.screen"))
        if (!idMatched) return false
        val p = patternParam.trim().lowercase()
        return p.isEmpty() || event.summary.lowercase().contains(p) || event.category.lowercase().contains(p)
    }

    fun pushEvent(triggerTypeId: String, category: String, summary: String) {
        val rec = TriggerEventRecord(
            id = "evt_${System.nanoTime()}",
            triggerTypeId = triggerTypeId,
            category = category,
            summary = summary
        )
        _recentEvents.value = (listOf(rec) + _recentEvents.value).take(60)
    }

    fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    fun isKeyguardSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardSecure
    }

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("studio_pin::$pin").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
