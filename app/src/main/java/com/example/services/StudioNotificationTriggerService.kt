package com.example.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play Policy-compliant NotificationListenerService that listens for incoming
 * SMS / Messaging app notifications when explicitly enabled by the user in Android Notification Access.
 * Does NOT declare restricted READ_SMS or RECEIVE_SMS permissions.
 */
class StudioNotificationTriggerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName.orEmpty()
        if (pkg == applicationContext.packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        if (isSmsPackage(pkg) || looksLikeSmsOtp(title, text)) {
            StudioTriggerBus.recordIncomingSms(pkg, title, text)
        } else {
            StudioTriggerBus.recordNotification(pkg, title, text)
        }
    }

    private fun isSmsPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in SMS_PACKAGES || p.contains("messaging") || p.contains(".mms") || p.contains(".sms")
    }

    private fun looksLikeSmsOtp(title: String, body: String): Boolean {
        val combined = "$title $body".lowercase()
        return (combined.contains("otp") || combined.contains("verification code") || combined.contains("sms")) &&
            Regex("\\b\\d{4,8}\\b").containsMatchIn(body)
    }

    companion object {
        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.oneplus.mms",
            "com.sonyericsson.conversations",
            "com.motorola.messaging",
            "com.textra",
            "org.thoughtcrime.securesms"
        )

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            if (_isConnected.value) return true
            val enabled = runCatching {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            }.getOrDefault("")
            val flat = ComponentName(context, StudioNotificationTriggerService::class.java).flattenToString()
            return enabled.contains(flat) || enabled.contains(StudioNotificationTriggerService::class.java.name)
        }

        fun openNotificationAccessSettings(context: Context): Boolean {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
    }
}
