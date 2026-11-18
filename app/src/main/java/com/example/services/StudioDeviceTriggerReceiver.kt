package com.example.services

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/**
 * Dynamic BroadcastReceiver capturing real OS triggers:
 * - PIN / Keyguard unlock (ACTION_USER_PRESENT)
 * - Screen ON / OFF
 * - Power Connected / Disconnected & Battery level updates
 * - Airplane mode, Headset plug, Wi-Fi & Bluetooth state changes
 */
class StudioDeviceTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_USER_PRESENT -> StudioTriggerBus.recordUnlockEvent("keyguard_user_present")
            Intent.ACTION_SCREEN_ON -> StudioTriggerBus.recordScreenState(true)
            Intent.ACTION_SCREEN_OFF -> StudioTriggerBus.recordScreenState(false)
            Intent.ACTION_POWER_CONNECTED -> StudioTriggerBus.pushEvent("trigger.power_connected", "Battery/Power", "Charger connected")
            Intent.ACTION_POWER_DISCONNECTED -> StudioTriggerBus.pushEvent("trigger.power_disconnected", "Battery/Power", "Charger disconnected")
            Intent.ACTION_BATTERY_LOW -> StudioTriggerBus.pushEvent("trigger.battery_low", "Battery/Power", "Battery Low alert")
            Intent.ACTION_BATTERY_OKAY -> StudioTriggerBus.pushEvent("trigger.battery_okay", "Battery/Power", "Battery Okay")
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val pct = if (level >= 0) (level * 100) / scale else 100
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                StudioTriggerBus.recordBatteryState(pct, charging)
            }
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                StudioTriggerBus.pushEvent(
                    if (state == 1) "trigger.headset_plugged" else "trigger.headset_unplugged",
                    "Device Events",
                    if (state == 1) "Headset plugged in" else "Headset unplugged"
                )
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                StudioTriggerBus.pushEvent(
                    if (on) "trigger.airplane_on" else "trigger.airplane_off",
                    "Connectivity",
                    if (on) "Airplane Mode ON" else "Airplane Mode OFF"
                )
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_DISABLED) {
                    val on = state == WifiManager.WIFI_STATE_ENABLED
                    StudioTriggerBus.pushEvent(
                        if (on) "trigger.wifi_enabled" else "trigger.wifi_disabled",
                        "Connectivity",
                        if (on) "Wi-Fi Enabled" else "Wi-Fi Disabled"
                    )
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                    val on = state == BluetoothAdapter.STATE_ON
                    StudioTriggerBus.pushEvent(
                        if (on) "trigger.bt_enabled" else "trigger.bt_disabled",
                        "Connectivity",
                        if (on) "Bluetooth Enabled" else "Bluetooth Disabled"
                    )
                }
            }
        }
    }

    companion object {
        fun register(context: Context): StudioDeviceTriggerReceiver {
            val receiver = StudioDeviceTriggerReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            return receiver
        }
    }
}
