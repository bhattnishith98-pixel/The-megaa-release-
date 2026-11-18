package com.example.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Draggable Floating AI Voice Overlay shown on top of ANY foreground app when Editor Mode is active.
 * Displays foreground app target, spoken AI announcement, Mic button, and Emergency Stop.
 */
class FloatingAgentOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) {
            _isRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (Settings.canDrawOverlays(this)) {
            showOverlayWindow()
        }
        val statusMsg = intent?.getStringExtra(EXTRA_STATUS)
        if (!statusMsg.isNullOrBlank()) {
            _statusText.value = statusMsg
            statusTextView?.text = statusMsg
        }
        _isRunning.value = true
        return START_NOT_STICKY
    }

    private fun startAsForeground(): Boolean {
        return runCatching {
            val chId = "studio_floating_ai_overlay"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        chId,
                        getString(R.string.channel_floating_ai_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val notif = NotificationCompat.Builder(this, chId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Floating AI Editor Agent Active")
                .setContentText(_statusText.value)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(109, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(109, notif)
            }
            true
        }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            statusText.collect { txt ->
                statusTextView?.text = txt
            }
        }
    }

    private fun showOverlayWindow() {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 36
            y = 180
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#22D3EE"))
            }
        }

        val title = TextView(this).apply {
            text = "AI Editor Overlay (Drag)"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 12f
        }
        val statusTv = TextView(this).apply {
            text = _statusText.value
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 11f
            maxLines = 3
        }
        statusTextView = statusTv

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        val micBtn = Button(this).apply {
            text = "Mic"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.parseColor("#0B1220"))
            setOnClickListener {
                onMicClicked?.invoke()
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#F43F5E"))
            setTextColor(Color.parseColor("#0B1220"))
            setOnClickListener {
                onStopClicked?.invoke()
                stopSelf()
            }
        }
        btnRow.addView(micBtn)
        btnRow.addView(stopBtn)
        root.addView(title)
        root.addView(statusTv)
        root.addView(btnRow)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(root, params)
            overlayView = root
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
        overlayView = null
        statusTextView = null
        _isRunning.value = false
    }

    companion object {
        const val EXTRA_STATUS = "overlay_status"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _statusText = MutableStateFlow("Tap Mic & speak command")
        val statusText: StateFlow<String> = _statusText.asStateFlow()

        var onMicClicked: (() -> Unit)? = null
        var onStopClicked: (() -> Unit)? = null

        fun updateStatus(text: String) {
            _statusText.value = text
        }

        fun start(context: Context, status: String = "Tap Mic & speak command"): Boolean {
            return runCatching {
                val intent = Intent(context, FloatingAgentOverlayService::class.java).apply {
                    putExtra(EXTRA_STATUS, status)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingAgentOverlayService::class.java)) }
            _isRunning.value = false
        }
    }
}
