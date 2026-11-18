package com.example.nodes

import com.example.lang.StudioCapability

/**
 * Generates distinct Condition NodeTypeSpecs totaling 500 Conditions when combined
 * with base conditions in NodeRegistry across:
 * Battery/Power, Connectivity, Date/Time, Device State (PIN Unlock, SMS, Custom Touch),
 * Location, Studio Specific, Media, Notification, Phone, Screen, Sensors, Volume, and Logic.
 */
object AutomationCatalogConditions {

    private fun conditionSpec(
        id: String,
        title: String,
        subCategory: String,
        summary: String,
        paramKey: String = "target",
        paramLabel: String = "Expected / Filter",
        defaultVal: String = "\"true\"",
        caps: Set<StudioCapability> = setOf(StudioCapability.SYSTEM_INFO),
        syntax: String = ""
    ) = NodeTypeSpec(
        id = id,
        title = title,
        category = NodeCategory.CONDITIONS,
        subCategory = subCategory,
        summary = summary,
        inputs = listOf(NodePortSpec("value", "Input", PortDirection.IN, PortType.ANY)),
        outputs = listOf(NodePortSpec("result", "Result", PortDirection.OUT, PortType.BOOL)),
        params = listOf(NodeParamSpec(paramKey, paramLabel, PortType.TEXT, defaultVal)),
        requiredCapabilities = caps,
        docSyntax = syntax.ifBlank { "catalog.check_condition(\"$id\", \"cond\", $defaultVal)" },
        docExample = "if ${syntax.ifBlank { "catalog.check_condition(\"$id\", \"cond\", $defaultVal)" }}:\n    log.info(\"Condition $title is true\")"
    )

    fun build(neededCount: Int): List<NodeTypeSpec> {
        val list = ArrayList<NodeTypeSpec>(neededCount + 64)
        val seenIds = HashSet<String>()
        fun addUnique(spec: NodeTypeSpec) {
            if (seenIds.add(spec.id)) list.add(spec)
        }

        // Priority Curated Conditions: PIN Unlock, SMS, Custom Touch, Mobile State
        val priority = listOf(
            Triple("cond.pin_is_locked", "Is Device PIN / Keyguard Locked?", "pin.is_locked()"),
            Triple("cond.pin_is_unlocked", "Is Device PIN Unlocked?", "not pin.is_locked()"),
            Triple("cond.pin_is_secure", "Is Secure Lock Screen Set?", "pin.is_secure()"),
            Triple("cond.pin_verify_code", "Does Automation PIN Match?", "pin.verify(\"1234\")"),
            Triple("cond.pin_failed_gt_zero", "Were Any PIN Attempts Failed?", "pin.failed_attempts() > 0"),
            Triple("cond.sms_matches_filter", "Does Last SMS Match Sender/Keyword?", "sms.matches_filter(\"\", \"OTP\")"),
            Triple("cond.sms_has_otp", "Does Last SMS Contain OTP Code?", "sms.extract_otp() != \"\""),
            Triple("cond.sms_access_ready", "Is Notification SMS Access Enabled?", "sms.has_notification_access()"),
            Triple("cond.touch_recent", "Was Custom Touch Triggered Recently?", "touch.last_event().timestamp_ms > 0"),
            Triple("cond.accessibility_enabled", "Is Accessibility Touch Engine Enabled?", "perms.is_accessibility_enabled()"),
            Triple("cond.shizuku_available", "Is Shizuku Privileged Bridge Available?", "shizuku.is_available()"),
            Triple("cond.root_available", "Is Root Superuser Available?", "root.is_available()")
        )
        priority.forEach { (id, title, syn) ->
            val cat = if (id.contains("sms")) "Notification" else "Device State"
            addUnique(conditionSpec(id, title, cat, "Boolean condition check: $title", syntax = syn))
        }

        // 1. Battery/Power (~44)
        for (pct in 5..100 step 5) {
            addUnique(conditionSpec("cond.batt_gte_$pct", "Battery >= $pct%", "Battery/Power", "True if battery level is at least $pct%.", "min", "Min %", "$pct", syntax = "device.battery_level() >= $pct"))
            addUnique(conditionSpec("cond.batt_lte_$pct", "Battery <= $pct%", "Battery/Power", "True if battery level is at or below $pct%.", "max", "Max %", "$pct", syntax = "device.battery_level() <= $pct"))
        }
        listOf(
            "Is Charging Right Now?", "Is Charging via AC Fast?", "Is Charging via USB?", "Is Wireless Charging?",
            "Is Battery Saver Active?", "Is Battery Full (100%)?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.power_bool_$idx", label, "Battery/Power", "Power condition: $label"))
        }

        // 2. Connectivity (~48)
        listOf(
            "Is Wi-Fi Enabled?", "Is Wi-Fi Connected?", "Is Connected to Home SSID?", "Is Wi-Fi 5GHz?",
            "Is Wi-Fi Signal Strong?", "Is Wi-Fi Signal Weak?", "Is Mobile Data Enabled?", "Is Mobile Data Connected?",
            "Is 5G Active?", "Is 4G LTE Active?", "Is Data Roaming?", "Is Network Metered?",
            "Is Bluetooth Enabled?", "Is Bluetooth Connected?", "Is Bluetooth Headset Connected?", "Is Bluetooth Watch Connected?",
            "Is Car Bluetooth Connected?", "Is Hotspot Active?", "Is Airplane Mode On?", "Is VPN Active?",
            "Is USB Tethering Active?", "Is Ethernet Connected?", "Is NFC Enabled?", "Is Private DNS Enabled?",
            "Is Data Saver Active?", "Is Auto-Sync Enabled?", "Is Nearby Share Ready?", "Is Dual-Stack IPv6 Ready?",
            "Is Local IPv4 Assigned?", "Is LAN Receiver Running?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.conn_$idx", label, "Connectivity", "Connectivity condition: $label", caps = setOf(StudioCapability.NETWORK_ACCESS)))
        }
        val ssidPresets = listOf("HomeWiFi", "OfficeNet", "GuestWiFi", "PixelHotspot", "Lab5G")
        for (s in ssidPresets) {
            addUnique(conditionSpec("cond.wifi_ssid_${s.lowercase()}", "Wi-Fi SSID == $s", "Connectivity", "Checks if active Wi-Fi SSID equals $s.", "ssid", "SSID", "\"$s\"", setOf(StudioCapability.NETWORK_ACCESS)))
        }
        for (port in listOf(80, 443, 8080, 8765, 9000, 3000, 5000, 5555, 22, 1883, 8883, 5353, 8443)) {
            addUnique(conditionSpec("cond.port_open_$port", "Local Port $port Reachable?", "Connectivity", "Network probe check for TCP port $port.", "port", "Port", "$port", setOf(StudioCapability.NETWORK_ACCESS)))
        }

        // 3. Date/Time (~48)
        for (hr in 0..23) {
            val hh = hr.toString().padStart(2, '0')
            addUnique(conditionSpec("cond.hour_eq_$hh", "Current Hour == $hh:00", "Date/Time", "True during hour $hh:00–$hh:59.", "hour", "Hour", "$hr"))
        }
        val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        days.forEach { d ->
            addUnique(conditionSpec("cond.day_${d.lowercase()}", "Is Today $d?", "Date/Time", "True when current day of week is $d."))
            addUnique(conditionSpec("cond.not_${d.lowercase()}", "Is Today NOT $d?", "Date/Time", "True when current day of week is not $d."))
        }
        listOf(
            "Is Weekday?", "Is Weekend?", "Is Morning (06-12)?", "Is Afternoon (12-17)?", "Is Evening (17-21)?",
            "Is Night (21-06)?", "Is Daytime (After Sunrise)?", "Is Nighttime (After Sunset)?", "Is Leap Year?", "Is DST Active?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.time_win_$idx", label, "Date/Time", "Date/Time window condition: $label"))
        }

        // 4. Device State (~55)
        val devStates = listOf(
            "Is Dark Mode Enabled?", "Is Light Mode Enabled?", "Is Screen Orientation Portrait?", "Is Screen Orientation Landscape?",
            "Is Auto-Rotate Enabled?", "Is Interactive / Awake?", "Is Doze Power Idle?", "Is USB Debugging On?",
            "Is Developer Options On?", "Is Overlay Permission Granted?", "Is Write Settings Granted?", "Is Notification Permission Granted?",
            "Is Mic Permission Granted?", "Is Location Permission Granted?", "Is Battery Optimization Ignored?",
            "Is Keyboard Open?", "Is Split Screen Active?", "Is PiP Window Active?", "Is Car Mode Active?",
            "Is Work Profile Enabled?", "Is Guest User Active?", "Is Free Space > 1GB?", "Is Free Space > 5GB?",
            "Is Free RAM > 256MB?", "Is Free RAM > 1GB?", "Is CPU 64-Bit ARM64?", "Is Emulator Environment?",
            "Is Thermal Status Normal?", "Is System Locale English?", "Is System Locale Hindi?", "Is System Locale Gujarati?",
            "Is Clipboard Non-Empty?", "Is Clipboard Containing URL?", "Is Clipboard Containing Number?",
            "Is Foreground App Launcher?", "Is Foreground App Settings?", "Is Foreground App Browser?",
            "Is Foreground App Messaging?", "Is Foreground App Studio?"
        )
        devStates.forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.devstate_$idx", label, "Device State", "System state condition: $label"))
        }
        for (sdk in listOf(26, 28, 29, 30, 31, 33, 34, 35, 36)) {
            addUnique(conditionSpec("cond.sdk_gte_$sdk", "Android SDK >= API $sdk?", "Device State", "True when Build.VERSION.SDK_INT >= $sdk.", "sdk", "API Level", "$sdk", syntax = "device.sdk_int() >= $sdk"))
        }

        // 5. Location (~32)
        val zones = listOf("Home", "Office", "Gym", "School", "Airport", "Station", "Hospital", "Mall", "Park", "Cafe")
        zones.forEach { z ->
            val s = z.lowercase()
            addUnique(conditionSpec("cond.loc_in_$s", "Is Inside Zone: $z?", "Location", "True if device is inside geofence '$z'."))
            addUnique(conditionSpec("cond.loc_out_$s", "Is Outside Zone: $z?", "Location", "True if device is outside geofence '$z'."))
        }
        listOf(
            "Is GPS Enabled?", "Is High Accuracy Location On?", "Is Stationary (Speed < 2)?", "Is Walking?",
            "Is Cycling?", "Is Driving (Speed > 25)?", "Is GPS Accuracy < 15m?", "Is GPS Accuracy < 50m?",
            "Is Altitude > 500m?", "Is Bearing North?", "Is Bearing East?", "Is Mock Location Active?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.loc_state_$idx", label, "Location", "Location condition: $label"))
        }

        // 6. Studio Specific (~42)
        listOf(
            "Is Macro Enabled?", "Is Macro Currently Running?", "Did Previous Action Succeed?", "Has Errors In Log?",
            "Is Variable Equal To?", "Is Variable Greater Than?", "Is Variable Less Than?", "Does Text Variable Contain?",
            "Does List Variable Contain?", "Is List Empty?", "Is Workspace File Present?", "Is Workspace File Non-Empty?",
            "Does Keystore Secret Exist?", "Is Binary Artifact Ready?", "Is Self-Host Pass Verified?",
            "Is Shizuku Authorized?", "Is Root Authorized?", "Is AI Provider Key Valid?", "Is AI Speaking Only Mode On?",
            "Is Floating AI Overlay Active?", "Is Live Screen Observe Active?", "Is Watch Variable Over Limit?",
            "Is Memory Arena Active?", "Is Arena Bounds Valid?", "Is Custom Trigger Registered?",
            "Is Custom Action Registered?", "Is Custom Block Registered?", "Is Package Signature Valid?",
            "Is IPv4 Address Valid?", "Is IPv6 Address Valid?", "Is Peer Port Listening?", "Is Stopwatch Active?",
            "Is Countdown Running?", "Is Mutex Lock Held?", "Is Retry Budget Remaining?", "Is Timeout Healthy?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.studio_$idx", label, "Studio Specific", "Automation Studio internal condition: $label"))
        }

        // 7. Media (~28)
        listOf(
            "Is Music Playing?", "Is Audio Output Speaker?", "Is Audio Output Headphones?", "Is Audio Output Bluetooth?",
            "Is Wired Headset Mic Present?", "Is Bluetooth SCO Active?", "Is Microphone Muted?", "Is Speakerphone On?",
            "Is Camera Flash Available?", "Is Flashlight Currently On?", "Is Camera In Use?", "Is Audio Recording Active?",
            "Is Media Session Playing?", "Is Media Session Paused?", "Is YouTube Playing?", "Is Spotify Playing?",
            "Is Cast Session Active?", "Is Voice Recognition Listening?", "Is TTS Speaking Now?", "Is Volume Boost Active?",
            "Is Equalizer Enabled?", "Is Ringtone Playing?", "Is Alarm Ringing?", "Is Notification Sound Playing?",
            "Is DTMF Tone Enabled?", "Is Haptic Feedback Enabled?", "Is Touch Sounds Enabled?", "Is Stereo Speakers Supported?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.media_$idx", label, "Media", "Media & audio condition: $label"))
        }

        // 8. Notification (~36)
        val notifSenders = listOf("Messages", "WhatsApp", "Telegram", "Gmail", "Bank", "Calendar", "Clock", "SystemUI")
        notifSenders.forEach { app ->
            addUnique(conditionSpec("cond.notif_from_${app.lowercase()}", "Active Notification From $app?", "Notification", "Checks if an active notification from $app was observed."))
        }
        val otpHints = listOf("OTP", "Bank", "Debit", "Credit", "Password", "Login", "Verification", "Alert", "Delivered", "Payment")
        otpHints.forEach { kw ->
            addUnique(conditionSpec("cond.sms_has_${kw.lowercase()}", "Last SMS Contains '$kw'?", "Notification", "Checks if last received SMS body contains '$kw'.", syntax = "sms.matches_filter(\"\", \"$kw\")"))
        }
        listOf(
            "Is DND Off?", "Is DND Priority Only?", "Is DND Total Silence?", "Is DND Alarms Only?",
            "Are Lockscreen Notifications Shown?", "Is Notification Listener Connected?", "Is Any SMS Received?",
            "Is Last SMS Recent (< 60s)?", "Is Last SMS OTP 6 Digits?", "Is Last SMS OTP 4 Digits?",
            "Are Unread Notifications > 0?", "Are Unread Notifications > 5?", "Are Bubbles Enabled?",
            "Is Adaptive Notifications On?", "Is Snoozed Notification Present?", "Is Heads-Up Alert Shown?",
            "Is Ongoing Automation Notification Shown?", "Is Notification Sound Silent?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.notif_extra_$idx", label, "Notification", "Notification condition: $label"))
        }

        // 9. Phone (~26)
        listOf(
            "Is Call Idle?", "Is Phone Ringing?", "Is Call Off-Hook / Active?", "Is Call Outgoing?",
            "Is Call Incoming From Saved Contact?", "Is Call Incoming From Unknown?", "Is Dual-SIM Present?",
            "Is SIM 1 Ready?", "Is SIM 2 Ready?", "Is Mobile Signal Strong?", "Is Mobile Signal Weak?",
            "Is VoLTE Available?", "Is Wi-Fi Calling Registered?", "Is Call Forwarding Active?", "Is Conference Call Active?",
            "Is Bluetooth Handsfree Ready?", "Is Caller ID Enabled?", "Is Spam Protection Active?",
            "Is Emergency Mode Active?", "Is Hearing Aid Compatibility On?", "Is TTY Mode Off?",
            "Is Data During Call Enabled?", "Is International Roaming?", "Is Domestic Roaming?",
            "Is Call Recording Supported?", "Is Default Dialer Set?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.phone_$idx", label, "Phone", "Phone & telephony condition: $label"))
        }

        // 10. Screen (~36)
        listOf(
            "Is Screen Currently ON?", "Is Screen Currently OFF?", "Is Screen Dimmed?", "Is Auto-Brightness Enabled?",
            "Is Brightness < 25%?", "Is Brightness Between 25%-75%?", "Is Brightness > 75%?",
            "Is Touch Coordinates Inside Top Half?", "Is Touch Coordinates Inside Bottom Half?",
            "Is Touch Coordinates Inside Left Half?", "Is Touch Coordinates Inside Right Half?",
            "Was Last Gesture Tap?", "Was Last Gesture Long-Press?", "Was Last Gesture Double-Tap?",
            "Was Last Gesture Swipe?", "Was Last Gesture Multi-Point Path?", "Is Custom Touch Zone Armed?",
            "Is Screen Resolution FHD+?", "Is Screen Density > 400 dpi?", "Is Refresh Rate 120Hz?",
            "Is Night Light / Blue Filter On?", "Is Extra Dim Active?", "Is High Contrast Mode On?",
            "Is Color Inversion On?", "Is Grayscale Bedtime Mode On?", "Is Magnification Active?",
            "Is TalkBack Running?", "Is MediaProjection Frame Active?", "Is UI Tree Clickable Node Found?",
            "Is Editable Input Focused?", "Is Submit Button Visible?", "Is Continue Button Visible?",
            "Is Confirm Dialog Open?", "Is Keyguard Occluded?", "Is Lock Screen PIN Pad Visible?",
            "Is Always-On Display Active?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.screen_chk_$idx", label, "Screen", "Screen & custom touch condition: $label"))
        }

        // 11. Sensors (~36)
        listOf(
            "Is Proximity Near?", "Is Proximity Far?", "Is Device Face Up?", "Is Device Face Down?",
            "Is Device Upright?", "Is Ambient Light Dark (< 10 lux)?", "Is Ambient Light Indoor?", "Is Ambient Light Outdoor?",
            "Is Shaking Active?", "Is Free Fall Detected?", "Is Tilt Left?", "Is Tilt Right?",
            "Is Tilt Forward?", "Is Tilt Backward?", "Is Step Goal 5000 Reached?", "Is Step Goal 10000 Reached?",
            "Is Accelerometer Idle?", "Is Gyroscope Rotating?", "Is Magnetometer Calibrated?",
            "Is Heading North (315-45)?", "Is Heading East (45-135)?", "Is Heading South (135-225)?",
            "Is Heading West (225-315)?", "Is Barometer Pressure Falling?", "Is Barometer Pressure Rising?",
            "Is Ambient Temp < 15C?", "Is Ambient Temp > 30C?", "Is Humidity > 70%?",
            "Is Foldable Flat (180 deg)?", "Is Foldable Flex Mode (90 deg)?", "Is Foldable Closed?",
            "Is Linear Acceleration Calm?", "Is Significant Motion Idle?", "Is Pocket Sensor Blocked?",
            "Is Biometric Hardware Present?", "Is Fingerprint Enrolled?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.sensor_chk_$idx", label, "Sensors", "Hardware sensor condition: $label"))
        }

        // 12. Volume (~24)
        for (stream in listOf("Media", "Ring", "Notification", "Alarm", "Call", "System")) {
            addUnique(conditionSpec("cond.vol_${stream.lowercase()}_muted", "Is $stream Volume Muted (0%)?", "Volume", "True when $stream audio stream is muted."))
            addUnique(conditionSpec("cond.vol_${stream.lowercase()}_gt50", "Is $stream Volume > 50%?", "Volume", "True when $stream audio stream exceeds 50%."))
            addUnique(conditionSpec("cond.vol_${stream.lowercase()}_max", "Is $stream Volume Max (100%)?", "Volume", "True when $stream audio stream is at maximum."))
        }
        listOf(
            "Is Ringer Normal?", "Is Ringer Vibrate?", "Is Ringer Silent?", "Is Fixed Volume Device?",
            "Is Spatial Audio Enabled?", "Is Safe Media Volume Active?"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.vol_extra_$idx", label, "Volume", "Audio stream condition: $label"))
        }

        // 13. Logic Comparisons (~24)
        listOf(
            "Text Equals (Case Insensitive)", "Text Starts With", "Text Ends With", "Text Contains Substring",
            "Text Matches Regex", "Number Between Min & Max", "Number Is Even", "Number Is Odd",
            "List Length > 0", "List Contains Value", "Map Has Key", "Timestamp Age < 1 Minute",
            "Timestamp Age > 1 Hour", "AND (Both Inputs True)", "OR (Either Input True)", "XOR (Exclusive Or)",
            "NOT (Invert Condition)", "All Constraints Passed", "Any Condition Passed", "PIN Digits Valid (4-8)"
        ).forEachIndexed { idx, label ->
            addUnique(conditionSpec("cond.logic_$idx", "Logic: $label", "Logic", "Logical comparison condition: $label"))
        }

        var extra = 1
        while (list.size < neededCount) {
            addUnique(
                conditionSpec(
                    "cond.custom_rule_$extra",
                    "Custom Condition Rule #$extra",
                    if (extra % 2 == 0) "Device State" else "Studio Specific",
                    "Configurable boolean condition check #$extra."
                )
            )
            extra++
        }

        return list.take(neededCount)
    }
}
