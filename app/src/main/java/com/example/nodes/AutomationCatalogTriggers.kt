package com.example.nodes

import com.example.lang.StudioCapability

/**
 * Generates 495+ distinct Trigger NodeTypeSpecs across all automation categories:
 * Applications, Battery/Power, Call/SMS (incl. OTP & sender filters), Connectivity,
 * Date/Time, Device Events (incl. PIN Unlock & Custom Touch), Location,
 * Studio Specific, Sensors, and User Input.
 */
object AutomationCatalogTriggers {

    private fun triggerSpec(
        id: String,
        title: String,
        subCategory: String,
        summary: String,
        paramKey: String = "match",
        paramLabel: String = "Filter / Value",
        defaultVal: String = "\"any\"",
        caps: Set<StudioCapability> = setOf(StudioCapability.SYSTEM_INFO),
        syntax: String = ""
    ) = NodeTypeSpec(
        id = id,
        title = title,
        category = NodeCategory.TRIGGERS,
        subCategory = subCategory,
        summary = summary,
        inputs = emptyList(),
        outputs = listOf(
            NodePortSpec("flow_out", "Then", PortDirection.OUT, PortType.FLOW),
            NodePortSpec("event", "Event", PortDirection.OUT, PortType.MAP)
        ),
        params = listOf(
            NodeParamSpec("macro_name", "Macro Name", PortType.TEXT, title.replace(Regex("[^a-zA-Z0-9]"), "")),
            NodeParamSpec(paramKey, paramLabel, PortType.TEXT, defaultVal)
        ),
        requiredCapabilities = caps,
        docSyntax = syntax.ifBlank { "on catalog.fire_trigger(\"$id\", \"$title\", \"$subCategory\", $defaultVal)" },
        docExample = "macro ${title.replace(Regex("[^a-zA-Z0-9]"), "").take(20)}:\n    on manual()\n    do:\n        log.info(\"Trigger fired: $title\")"
    )

    fun build(neededCount: Int): List<NodeTypeSpec> {
        val list = ArrayList<NodeTypeSpec>(neededCount + 64)
        val seenIds = HashSet<String>()
        fun addUnique(spec: NodeTypeSpec) {
            if (seenIds.add(spec.id)) list.add(spec)
        }

        // ---- PRIORITY CURATED TRIGGERS: PIN UNLOCK, SMS, CUSTOM TOUCH, MOBILE ----
        val pinTriggers = listOf(
            Triple("trigger.pin_unlock", "PIN / Device Unlocked", "Fires when the device keyguard/PIN screen is unlocked (ACTION_USER_PRESENT)."),
            Triple("trigger.pin_verified", "Studio Automation PIN Verified", "Fires when user enters the valid Automation Studio PIN."),
            Triple("trigger.pin_failed_attempt", "PIN Unlock Failed Attempt", "Fires after an incorrect PIN attempt is recorded."),
            Triple("trigger.pin_failed_3x", "PIN Failed 3 Consecutive Times", "Security trigger when 3 wrong PIN entries happen."),
            Triple("trigger.pin_lock_engaged", "PIN Lock Engaged", "Fires when screen locks and keyguard becomes active."),
            Triple("trigger.pin_biometric_unlock", "Biometric / Fingerprint Unlock", "Fires upon biometric keyguard unlock event."),
            Triple("trigger.pin_smart_unlock_trusted", "Trusted Device Unlock", "Fires when Smart Lock / trusted Bluetooth unlocks device."),
            Triple("trigger.pin_change_detected", "Lock Security Changed", "Fires when secure keyguard state transitions.")
        )
        pinTriggers.forEach { (id, title, desc) ->
            addUnique(triggerSpec(id, title, "Device Events", desc, "pin_rule", "PIN Rule", "\"verified\"", syntax = "on pin.is_locked()"))
        }

        val smsTriggers = listOf(
            Triple("trigger.sms_received", "SMS Received (Any)", "Fires when any incoming SMS notification arrives via Notification Access."),
            Triple("trigger.sms_from_contact", "SMS From Specific Contact", "Fires when incoming SMS sender matches the configured name or number."),
            Triple("trigger.sms_contains_keyword", "SMS Contains Text / Keyword", "Fires when incoming SMS message body contains your keyword."),
            Triple("trigger.sms_otp_received", "SMS OTP Code Detected", "Extracts 4-8 digit OTP from incoming SMS notification automatically."),
            Triple("trigger.sms_bank_alert", "SMS Bank / UPI Alert", "Detects banking, debit, credit, or UPI transaction SMS alerts."),
            Triple("trigger.sms_regex_match", "SMS Regex Pattern Match", "Matches incoming SMS body against a custom regular expression."),
            Triple("trigger.sms_from_unknown", "SMS From Unknown Sender", "Fires when SMS arrives from a non-saved sender ID."),
            Triple("trigger.sms_link_detected", "SMS Contains URL / Link", "Fires when incoming SMS includes an http/https link."),
            Triple("trigger.sms_emergency_keyword", "SMS Emergency Keyword", "Triggers immediate alert on SOS/Urgent/Emergency SMS keywords."),
            Triple("trigger.sms_delivery_ack", "SMS Composer Opened / Sent", "Fires when user dispatches an SMS action via Automation Studio.")
        )
        smsTriggers.forEach { (id, title, desc) ->
            addUnique(triggerSpec(id, title, "Call/SMS", desc, "sender_or_keyword", "Sender / Keyword", "\"OTP\""))
        }

        val touchTriggers = listOf(
            Triple("trigger.custom_touch_tap", "Custom Touch Tap", "Fires on configured custom screen touch tap zone."),
            Triple("trigger.custom_touch_double_tap", "Custom Touch Double-Tap", "Fires on double-tap touch gesture event."),
            Triple("trigger.custom_touch_long_press", "Custom Touch Long-Press", "Fires when custom hold duration touch gesture triggers."),
            Triple("trigger.custom_touch_swipe_up", "Custom Touch Swipe Up", "Fires on bottom-to-top swipe gesture."),
            Triple("trigger.custom_touch_swipe_down", "Custom Touch Swipe Down", "Fires on top-to-bottom swipe gesture."),
            Triple("trigger.custom_touch_swipe_left", "Custom Touch Swipe Left", "Fires on right-to-left horizontal swipe gesture."),
            Triple("trigger.custom_touch_swipe_right", "Custom Touch Swipe Right", "Fires on left-to-right horizontal swipe gesture."),
            Triple("trigger.custom_touch_corner_tl", "Top-Left Corner Touch", "Custom touch zone at top-left screen corner."),
            Triple("trigger.custom_touch_corner_tr", "Top-Right Corner Touch", "Custom touch zone at top-right screen corner."),
            Triple("trigger.custom_touch_corner_bl", "Bottom-Left Corner Touch", "Custom touch zone at bottom-left screen corner."),
            Triple("trigger.custom_touch_corner_br", "Bottom-Right Corner Touch", "Custom touch zone at bottom-right screen corner."),
            Triple("trigger.custom_touch_multi_point", "Custom Multi-Point Gesture", "Custom user-defined multi-coordinate touch path.")
        )
        touchTriggers.forEach { (id, title, desc) ->
            addUnique(triggerSpec(id, title, "Device Events", desc, "touch_zone", "Coordinates / Zone", "\"540,960\"", setOf(StudioCapability.ACCESSIBILITY_CONTROL)))
        }

        // ---- STRUCTURED DOMAIN CATALOG ACROSS ALL 10 MACRODROID TRIGGER CATEGORIES ----
        val appsList = listOf(
            "Chrome", "YouTube", "WhatsApp", "Telegram", "Instagram", "Spotify", "Maps", "Gmail",
            "Camera", "Settings", "Gallery", "Phone", "Messages", "Clock", "Files", "Play Store",
            "Termux", "Calendar", "Calculator", "Notes", "Recorder", "Dialer", "Browser", "Meet"
        )
        for (app in appsList) {
            val slug = app.lowercase().replace(" ", "_")
            addUnique(triggerSpec("trigger.app_launch_$slug", "App Launched: $app", "Applications", "Fires when $app enters the foreground window.", "package", "Package", "\"$app\"", setOf(StudioCapability.ACCESSIBILITY_CONTROL)))
            addUnique(triggerSpec("trigger.app_close_$slug", "App Closed: $app", "Applications", "Fires when $app leaves the active foreground.", "package", "Package", "\"$app\"", setOf(StudioCapability.ACCESSIBILITY_CONTROL)))
        }
        val appGeneric = listOf(
            "Any App Launched", "Any App Closed", "App Installed", "App Updated", "App Uninstalled",
            "App Crashed / ANR", "Split Screen App Entered", "Picture-in-Picture Opened",
            "App Notification Cleared", "Background App Killed"
        )
        appGeneric.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.app_event_$idx", label, "Applications", "Application lifecycle trigger: $label."))
        }

        // Battery/Power (~55)
        for (pct in listOf(5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 75, 80, 85, 90, 95, 100)) {
            addUnique(triggerSpec("trigger.batt_drop_$pct", "Battery Drops Below $pct%", "Battery/Power", "Fires when battery level drops below $pct%.", "threshold", "Percent", "$pct"))
            addUnique(triggerSpec("trigger.batt_rise_$pct", "Battery Reaches $pct%", "Battery/Power", "Fires when charging battery reaches $pct%.", "threshold", "Percent", "$pct"))
        }
        val powerEvents = listOf(
            "Power Connected (Any Charger)", "Power Connected (AC Fast)", "Power Connected (USB)", "Power Connected (Wireless)",
            "Power Disconnected", "Battery Saver Enabled", "Battery Saver Disabled", "Battery Temperature > 42C",
            "Battery Temperature > 45C", "Battery Cooled < 35C", "Thermal Throttling Warning",
            "Charge Speed Changed", "Charging Complete (Full)", "Battery Health Check Tick",
            "Power Button Short Double Press", "Power Button Long Hold", "Doze Mode Entered", "Doze Mode Exited"
        )
        powerEvents.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.power_evt_$idx", label, "Battery/Power", "Power & thermal trigger: $label."))
        }

        // Call/SMS (~50 additional -> total ~60)
        val smsKeywords = listOf("OTP", "Bank", "Delivered", "Payment", "Urgent", "Balance", "Login", "Code", "Alert", "Order", "Cab", "Parcel", "Verify", "Pin", "Bill", "Debit", "Credit", "Transfer")
        for (kw in smsKeywords) {
            addUnique(triggerSpec("trigger.sms_kw_${kw.lowercase()}", "SMS Keyword: $kw", "Call/SMS", "Fires when incoming SMS contains '$kw'.", "keyword", "Keyword", "\"$kw\""))
        }
        val callEvents = listOf(
            "Incoming Call (Any)", "Incoming Call (From Contact)", "Incoming Call (Unknown Number)", "Incoming Call (Private/Hidden)",
            "Call Answered", "Call Ended", "Call Active Timer 1m", "Call Active Timer 5m", "Call Rejected",
            "Missed Call", "Outgoing Call Started", "Outgoing Call Connected", "Speakerphone Activated",
            "Call Muted", "Voicemail Received", "SMS Sent Event", "SMS From Saved Group", "SMS With 4-Digit PIN",
            "SMS With 6-Digit OTP", "SMS Contains Phone Number", "SMS From Bank Header", "SMS Delivery Confirmed",
            "Call Forwarded", "Wi-Fi Calling Active", "Dual SIM Slot 1 Call", "Dual SIM Slot 2 Call",
            "Dual SIM Slot 1 SMS", "Dual SIM Slot 2 SMS", "Caller ID Spam Flagged", "Bluetooth Call Audio Active",
            "Emergency Number Dialed", "Auto-Reply SMS Dispatched", "MMS Attachment Detected", "RCS Chat Message Notification"
        )
        callEvents.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.callsms_evt_$idx", label, "Call/SMS", "Telephony & SMS trigger: $label."))
        }

        // Connectivity (~55)
        val connStates = listOf(
            "Wi-Fi Connected", "Wi-Fi Disconnected", "Wi-Fi Enabled", "Wi-Fi Disabled", "Wi-Fi Signal Strong (> -55 dBm)",
            "Wi-Fi Signal Weak (< -80 dBm)", "Wi-Fi 5GHz Band Connected", "Wi-Fi Roaming Switch", "Home SSID Connected",
            "Office SSID Connected", "Public Open Wi-Fi Detected", "Captive Portal Wi-Fi Detected",
            "Mobile Data Enabled", "Mobile Data Disabled", "5G Network Connected", "4G LTE Connected", "3G/2G Fallback",
            "Mobile Data Roaming On", "Mobile Data Roaming Off", "Cellular Signal Lost", "Cellular Signal Restored",
            "Bluetooth Enabled", "Bluetooth Disabled", "Bluetooth Audio Connected", "Bluetooth Car Kit Connected",
            "Bluetooth Watch Connected", "Bluetooth Device Disconnected", "Bluetooth LE Beacon In Range",
            "Hotspot Enabled", "Hotspot Disabled", "Hotspot Client Connected", "Hotspot Client Disconnected",
            "Airplane Mode Turned On", "Airplane Mode Turned Off", "VPN Connected", "VPN Disconnected",
            "USB Tethering Connected", "USB Tethering Disconnected", "Ethernet Adapter Connected", "NFC Enabled",
            "NFC Disabled", "NFC Tag Scanned", "NDEF Text Tag Read", "Private DNS Changed",
            "Proxy Configured", "Network Metered Changed", "IPv4 Address Changed", "IPv6 Address Assigned",
            "LAN Port 8765 Packet Received", "Sync Enabled", "Sync Disabled", "Data Saver Enabled",
            "Data Saver Disabled", "Nearby Share Active", "Android Auto Connected"
        )
        connStates.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.conn_$idx", label, "Connectivity", "Network & radio trigger: $label.", caps = setOf(StudioCapability.NETWORK_ACCESS)))
        }

        // Date/Time (~45)
        val intervals = listOf("15s", "30s", "1m", "2m", "5m", "10m", "15m", "30m", "45m", "1h", "2h", "4h", "6h", "12h", "24h")
        for (iv in intervals) {
            addUnique(triggerSpec("trigger.interval_$iv", "Regular Interval: Every $iv", "Date/Time", "Repeats trigger automatically every $iv.", "interval", "Interval", "\"$iv\""))
        }
        val timePresets = listOf(
            "Morning 06:00", "Morning 07:00", "Morning 08:00", "Work Start 09:00", "Noon 12:00",
            "Afternoon 15:00", "Evening 18:00", "Dinner 20:00", "Night 22:00", "Bedtime 23:00", "Midnight 00:00",
            "Sunrise", "Sunset", "Solar Noon", "Twilight", "Monday Start", "Tuesday Trigger",
            "Wednesday Trigger", "Thursday Trigger", "Friday Evening", "Weekend Saturday", "Weekend Sunday",
            "Month First Day", "Month Last Day", "Stopwatch Lap", "Stopwatch Reset", "Countdown Finished",
            "Calendar Event Started", "Calendar Event Ending Soon", "Calendar Event Ended", "Alarm Snoozed",
            "Alarm Dismissed", "Time Zone Changed"
        )
        timePresets.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.time_preset_$idx", "Schedule: $label", "Date/Time", "Date/Time trigger for $label."))
        }

        // Device Events (~60 additional -> ~80 total with PIN + Touch)
        val devEvents = listOf(
            "Screen Turned On", "Screen Turned Off", "Device Boot Completed", "Device Shutting Down",
            "Daydream / Screensaver On", "Daydream / Screensaver Off", "Dock Inserted (Car)", "Dock Inserted (Desk)",
            "Dock Removed", "Headphones Plugged (Wired)", "Headphones Unplugged", "Headset Mic Button",
            "USB Mass Storage Connected", "USB Accessory Connected", "USB Debugging Enabled", "USB Disconnected",
            "Clipboard Content Changed", "Clipboard URL Copied", "Clipboard OTP Copied",
            "Dark Theme Enabled", "Dark Theme Disabled", "Auto-Rotate Enabled", "Auto-Rotate Locked",
            "Portrait Orientation", "Landscape Orientation", "Volume Mute Toggled", "Silent Mode Enabled",
            "Vibrate Mode Enabled", "Normal Ring Mode Enabled", "Do Not Disturb On", "Do Not Disturb Off",
            "Low Storage Warning (< 1GB)", "SD Card Mounted", "SD Card Ejected", "SIM State Ready",
            "SIM Removed", "Screenshot Taken", "Screen Recording Started", "Screen Recording Stopped",
            "Input Method / Keyboard Opened", "Keyboard Closed", "Accessibility Service Started",
            "Accessibility Focus Changed", "Notification Shade Pulled", "Quick Settings Opened",
            "Assistant Invoked", "Split Window Resized", "One-Handed Mode Toggled", "Bedtime Mode Active",
            "Focus Mode Enabled", "Work Profile Turned On", "Work Profile Paused", "Guest User Switched",
            "Locale / Language Changed", "Wallpaper Changed", "Font Scale Changed", "Location Mode Changed",
            "Camera Opened By System", "Microphone Active Indicator", "Flashlight Hardware Toggled"
        )
        devEvents.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.dev_$idx", label, "Device Events", "System event trigger: $label."))
        }

        // Location (~35)
        val locZones = listOf("Home", "Office", "Gym", "School", "Mall", "Station", "Airport", "Hospital", "Library", "Market")
        for (z in locZones) {
            val s = z.lowercase()
            addUnique(triggerSpec("trigger.loc_enter_$s", "Geofence Enter: $z", "Location", "Fires when entering saved geofence zone '$z'.", "zone", "Zone", "\"$z\""))
            addUnique(triggerSpec("trigger.loc_exit_$s", "Geofence Exit: $z", "Location", "Fires when leaving saved geofence zone '$z'.", "zone", "Zone", "\"$z\""))
        }
        val locExtra = listOf(
            "Speed > 20 km/h (Cycling)", "Speed > 50 km/h (Driving)", "Speed > 100 km/h (Highway)",
            "Speed Drops to 0 (Parked)", "GPS Fix Acquired", "GPS Signal Lost", "Cell Tower Changed",
            "Altitude > 1000m", "Location Accuracy Improved", "Walking Activity Detected",
            "Running Activity Detected", "In Vehicle Activity Detected", "On Bicycle Detected",
            "Device Stationary 10m", "Geofence Dwell 5 Minutes"
        )
        locExtra.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.loc_extra_$idx", label, "Location", "Location & mobility trigger: $label."))
        }

        // Studio Specific (~40)
        val studioEvts = listOf(
            "Studio Macro Enabled", "Studio Macro Disabled", "Studio Macro Completed", "Studio Macro Failed",
            "StudioVar Changed", "StudioVar Number Threshold", "StudioVar Boolean True", "StudioVar Text Matched",
            "Studio Floating Button Tapped", "Studio Floating Button Long-Pressed", "Studio Overlay Mic Triggered",
            "Studio AI Brief Completed", "Studio AI Plan Applied", "StudioLang Compile Succeeded",
            "StudioLang Compile Error", "ELF64 Binary Emitted", "Self-Hosted Bootstrap Finished",
            "IPv4 File Share Received", "IPv6 File Share Received", "Peer Sync Connected",
            "Quick Tile 1 Pressed", "Quick Tile 2 Pressed", "Quick Tile 3 Pressed", "Quick Tile 4 Pressed",
            "Home Screen Shortcut 1", "Home Screen Shortcut 2", "Home Screen Shortcut 3",
            "Webhook HTTP Trigger", "Local Socket Trigger", "Studio Log Error Captured",
            "Watch Variable Alert", "Debug Breakpoint Hit", "Arena Memory Near Full",
            "Credential Key Stored", "Credential Key Rotated", "App Builder Zip Exported",
            "Custom Trigger Evaluated", "Action Block Returned", "Emergency Stop Triggered", "Voice Reply Finished"
        )
        studioEvts.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.studio_$idx", label, "Studio Specific", "Automation Studio internal trigger: $label."))
        }

        // Sensors (~50)
        val sensorEvts = listOf(
            "Shake Device (Light)", "Shake Device (Medium)", "Shake Device (Hard)", "Double Shake Gesture",
            "Flip Device Face Down", "Flip Device Face Up", "Flip Face Down -> Face Up",
            "Proximity Near (Hand Wave)", "Proximity Near -> Far", "Proximity Pocket Detected",
            "Light Sensor Dark (< 10 lux)", "Light Sensor Dim (< 50 lux)", "Light Sensor Indoor (300 lux)",
            "Light Sensor Bright Sunlight (> 10000 lux)", "Step Counter 1000 Steps", "Step Counter 5000 Steps",
            "Step Counter 10000 Steps", "Accelerometer Free Fall", "Accelerometer Impact Bump",
            "Accelerometer Picked Up", "Accelerometer Put Down Flat", "Gyroscope Tilt Left",
            "Gyroscope Tilt Right", "Gyroscope Tilt Forward", "Gyroscope Tilt Back",
            "Gyroscope Twist Wrist Twice", "Magnetometer Metal Near", "Magnetometer Case Lid Closed",
            "Compass Facing North", "Compass Facing East", "Compass Facing South", "Compass Facing West",
            "Barometer Pressure Drop (Storm)", "Barometer Floor Climbed", "Ambient Temp Hot",
            "Ambient Temp Cold", "Humidity High (> 75%)", "Significant Motion Trigger",
            "Stationary Detector Idle", "Tilt Detector Portrait", "Tilt Detector Landscape",
            "Linear Acceleration Spike", "Rotation Vector Changed", "Step Detector Single Step",
            "Heart Rate Zone Alert", "Off-Body Sensor Trigger", "Hinge Angle Half-Open (Foldable)",
            "Hinge Angle Fully Open (Foldable)", "Hinge Angle Closed (Foldable)", "Pocket Mode Exited"
        )
        sensorEvts.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.sensor_$idx", label, "Sensors", "Hardware sensor trigger: $label."))
        }

        // User Input (~40)
        val inputEvts = listOf(
            "Volume Up Short Press", "Volume Up Long Press", "Volume Up Double Click",
            "Volume Down Short Press", "Volume Down Long Press", "Volume Down Double Click",
            "Volume Up + Down Simultaneous", "Media Play/Pause Button", "Media Next Track Button",
            "Media Previous Track Button", "Headset Hook Single Press", "Headset Hook Double Press",
            "Camera Shutter Hardware Key", "Swipe Screen Left Edge In", "Swipe Screen Right Edge In",
            "Swipe Screen Bottom Edge Up", "Floating Handle Tap", "Floating Handle Swipe Up",
            "Floating Handle Swipe Down", "Status Bar Double Tap", "Navigation Back Long Press",
            "Recents Key Double Tap", "Widget Button 1 Clicked", "Widget Button 2 Clicked",
            "Widget Button 3 Clicked", "Notification Action Button 1", "Notification Action Button 2",
            "Notification Inline Reply Submitted", "Drawer Shortcut Tapped", "Voice Hotword Trigger",
            "Voice Speak Command Received", "USB Keyboard Hotkey Ctrl+1", "USB Keyboard Hotkey Ctrl+2",
            "Bluetooth Remote Key A", "Bluetooth Remote Key B", "Game Controller Start Button",
            "Stylus S-Pen Button Click", "Stylus Air Action Circle", "Three-Finger Screenshot Swipe",
            "Fingerprint Sensor Swipe Down"
        )
        inputEvts.forEachIndexed { idx, label ->
            addUnique(triggerSpec("trigger.input_$idx", label, "User Input", "Physical & gesture user input trigger: $label."))
        }

        // Extra touch/SMS/mobile presets if needed to guarantee exact target size
        var extra = 1
        while (list.size < neededCount) {
            addUnique(
                triggerSpec(
                    "trigger.custom_slot_$extra",
                    "Custom Automation Event #$extra",
                    if (extra % 3 == 0) "Call/SMS" else if (extra % 2 == 0) "Device Events" else "Studio Specific",
                    "Parameterized trigger event slot #$extra for custom rules & webhooks."
                )
            )
            extra++
        }

        return list.take(neededCount)
    }
}
