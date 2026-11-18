package com.example.nodes

import com.example.lang.StudioCapability

/**
 * Generates 200 distinct Constraint NodeTypeSpecs (macro execution guards) across all
 * MacroDroid constraint categories: Battery/Power, Connectivity, Date/Time, Device State
 * (incl. PIN Lock/Unlock, Root/Shizuku, Accessibility), Location, Studio Specific, Media,
 * Notification, Phone, Screen, Sensors, Volume.
 */
object AutomationCatalogConstraints {

    private fun constraintSpec(
        id: String,
        title: String,
        subCategory: String,
        summary: String,
        expected: String = "\"true\"",
        caps: Set<StudioCapability> = setOf(StudioCapability.SYSTEM_INFO),
        syntax: String = ""
    ) = NodeTypeSpec(
        id = id,
        title = title,
        category = NodeCategory.CONSTRAINTS,
        subCategory = subCategory,
        summary = summary,
        inputs = listOf(NodePortSpec("flow_in", "Guard", PortDirection.IN, PortType.FLOW)),
        outputs = listOf(
            NodePortSpec("flow_out", "Pass", PortDirection.OUT, PortType.FLOW),
            NodePortSpec("allowed", "Allowed", PortDirection.OUT, PortType.BOOL)
        ),
        params = listOf(NodeParamSpec("expected", "Expected", PortType.TEXT, expected)),
        requiredCapabilities = caps,
        docSyntax = syntax.ifBlank { "only if catalog.check_constraint(\"$id\", \"guard\", $expected)" },
        docExample = "macro Guarded:\n    on manual()\n    only if ${syntax.ifBlank { "catalog.check_constraint(\"$id\", \"guard\", $expected)" }}\n    do:\n        log.info(\"Constraint passed\")"
    )

    fun build(neededCount: Int): List<NodeTypeSpec> {
        val list = ArrayList<NodeTypeSpec>(neededCount + 32)
        val seenIds = HashSet<String>()
        fun addUnique(spec: NodeTypeSpec) {
            if (seenIds.add(spec.id)) list.add(spec)
        }

        // Priority Device State & PIN Unlock & SMS & Touch Constraints
        val priorityConstraints = listOf(
            Triple("constraint.pin_unlocked_only", "Only When PIN Unlocked", "only if not pin.is_locked()"),
            Triple("constraint.device_locked_only", "Only When Device Locked", "only if pin.is_locked()"),
            Triple("constraint.pin_secure_enabled", "Only If Secure PIN Lock Enabled", "only if pin.is_secure()"),
            Triple("constraint.pin_no_failed_attempts", "Only If 0 Failed PIN Attempts", "only if pin.failed_attempts() == 0"),
            Triple("constraint.sms_otp_present", "Only When SMS OTP Available", "only if sms.extract_otp() != \"\""),
            Triple("constraint.sms_access_granted", "Only If Notification SMS Access Granted", "only if sms.has_notification_access()"),
            Triple("constraint.accessibility_ready", "Only If Accessibility Service Enabled", "only if perms.is_accessibility_enabled()"),
            Triple("constraint.shizuku_ready", "Only If Shizuku Available", "only if shizuku.is_available()"),
            Triple("constraint.root_ready", "Only If Root Available", "only if root.is_available()"),
            Triple("constraint.screen_on_only", "Only While Screen Is On", "only if catalog.check_constraint(\"constraint.screen_on\", \"screen\", \"true\")"),
            Triple("constraint.screen_off_only", "Only While Screen Is Off", "only if catalog.check_constraint(\"constraint.screen_off\", \"screen\", \"true\")")
        )
        priorityConstraints.forEach { (id, title, syn) ->
            addUnique(constraintSpec(id, title, "Device State", "Execution guard: $title.", syntax = syn))
        }

        // Battery/Power Constraints (22)
        for (pct in listOf(10, 15, 20, 25, 30, 40, 50, 60, 75, 80, 90)) {
            addUnique(constraintSpec("constraint.battery_above_$pct", "Battery >= $pct%", "Battery/Power", "Allows macro only when battery >= $pct%.", "\"$pct\"", syntax = "only if device.battery_level() >= $pct"))
            addUnique(constraintSpec("constraint.battery_below_$pct", "Battery <= $pct%", "Battery/Power", "Allows macro only when battery <= $pct%.", "\"$pct\"", syntax = "only if device.battery_level() <= $pct"))
        }
        listOf(
            "Charger Connected", "Charger Disconnected", "Charging via AC Fast", "Charging via USB",
            "Charging via Wireless", "Battery Saver Off", "Battery Saver On", "Battery Temp Normal (< 40C)"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.power_$idx", "Power: $label", "Battery/Power", "Requires power state '$label'."))
        }

        // Connectivity Constraints (24)
        listOf(
            "Online (Internet Connected)", "Wi-Fi Connected", "Wi-Fi Disconnected", "Wi-Fi Enabled", "Wi-Fi Disabled",
            "Connected to Home SSID", "Connected to Specific SSID", "Mobile Data Active (No Wi-Fi)",
            "5G Network Only", "Not Roaming", "Data Saver Off", "Bluetooth Enabled", "Bluetooth Disabled",
            "Bluetooth Audio Connected", "Bluetooth Device Connected", "Hotspot Active", "Hotspot Off",
            "Airplane Mode Off", "Airplane Mode On", "VPN Connected", "VPN Disconnected",
            "NFC Enabled", "Unmetered Network Only", "Dual-Stack IPv4/IPv6 Available"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.conn_$idx", label, "Connectivity", "Network constraint: $label.", caps = setOf(StudioCapability.NETWORK_ACCESS)))
        }

        // Date/Time Constraints (22)
        listOf(
            "Daytime (06:00 - 18:00)", "Nighttime (22:00 - 06:00)", "Work Hours (09:00 - 18:00)",
            "Morning Window (06:00 - 11:00)", "Evening Window (18:00 - 22:00)", "Weekday (Mon - Fri)",
            "Weekend (Sat - Sun)", "Monday Only", "Tuesday Only", "Wednesday Only", "Thursday Only",
            "Friday Only", "Saturday Only", "Sunday Only", "After Sunrise", "After Sunset",
            "Calendar Busy Event Active", "No Active Calendar Meeting", "Stopwatch Running", "Stopwatch Stopped",
            "Month Days 1-15", "Last Week of Month"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.time_$idx", "Time: $label", "Date/Time", "Schedule constraint: $label."))
        }

        // Device State Constraints (25)
        listOf(
            "Dark Theme Active", "Light Theme Active", "Portrait Orientation", "Landscape Orientation",
            "Auto-Rotate Enabled", "Auto-Rotate Locked", "Power Interactive Awake", "Not in Doze / Idle",
            "Keyboard Currently Open", "Keyboard Closed", "Foreground App Is Target", "Foreground App Not Game",
            "Overlay Permission Granted", "Write Settings Granted", "Battery Optimization Ignored",
            "Free Storage > 2GB", "RAM Available > 512MB", "VPN Not Blocking", "USB Debugging Off",
            "USB Connected to PC", "Airplane Mode Disabled", "Work Profile Inactive",
            "Pin Verified Within Last 5m", "Zero Touch Errors", "Device Uptime > 60s"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.devstate_$idx", label, "Device State", "Device state guard: $label."))
        }

        // Location Constraints (12)
        listOf(
            "Inside Geofence Zone", "Outside Geofence Zone", "GPS Provider Enabled", "GPS Accuracy < 25m",
            "Speed == 0 (Stationary)", "Speed < 10 km/h (Walking)", "Speed > 30 km/h (Transit)",
            "At Home Zone", "At Work Zone", "At Saved Favorite Place", "Elevation < 2000m", "Not Driving"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.loc_$idx", label, "Location", "Location guard: $label."))
        }

        // Studio Specific Constraints (16)
        listOf(
            "Macro Not Already Running", "Last Run Succeeded", "Cooldown 30s Elapsed", "Cooldown 5m Elapsed",
            "StudioVar Equals Target", "StudioVar Number > 0", "Workspace File Exists", "Credential Key Stored",
            "Only One Instance (Mutex)", "Debug Mode Disabled", "AI Key Configured", "Voice Speaking Mode Idle",
            "Overlay Service Running", "Self-Host Artifact Ready", "IPv4/IPv6 Receiver Running", "Test Mode Off"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.studio_$idx", label, "Studio Specific", "Automation Studio constraint: $label."))
        }

        // Media Constraints (12)
        listOf(
            "Music Playing", "Music Stopped / Idle", "Headphones Connected", "Wired Headset Connected",
            "Bluetooth A2DP Connected", "Microphone Not In Use", "Speakerphone Inactive", "Podcast Playing",
            "Video Playback Active", "Media Session Present", "Audio Focus Free", "Voice Assistant Idle"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.media_$idx", label, "Media", "Media & audio constraint: $label."))
        }

        // Notification Constraints (14)
        listOf(
            "Notification Present From App", "No Active Notifications From App", "SMS Notification Unread",
            "Unread OTP Present", "DND Is OFF", "DND Priority Mode Active", "Notification Channel Unmuted",
            "Notification Listener Connected", "Notification Count < 10", "Ongoing Call Notification Absent",
            "No Low Battery Notification", "Specific Title Keyword Present", "No Urgent Notification Pending",
            "Lockscreen Notifications Hidden"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.notif_$idx", label, "Notification", "Notification constraint: $label."))
        }

        // Phone Constraints (12)
        listOf(
            "In Active Call", "Not In Call (Idle)", "Ringing Incoming Call", "Call On Hold",
            "Roaming Disabled", "SIM 1 Active", "SIM 2 Active", "Mobile Signal Strength >= 2 Bars",
            "Airplane Radio Active", "VoLTE Registered", "Speakerphone On", "Emergency Call Inactive"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.phone_$idx", label, "Phone", "Phone & call state constraint: $label."))
        }

        // Screen Constraints (12)
        listOf(
            "Screen Brightness < 40%", "Screen Brightness > 70%", "Screen Text Contains Target",
            "Not In Fullscreen Video", "Split Screen Inactive", "Picture-in-Picture Inactive",
            "Touch Not Pressed", "Recent Touch < 5s Ago", "Home Launcher Visible",
            "Settings Screen Visible", "Keyguard Dismissed", "Ambient Display Off"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.screen_$idx", label, "Screen", "Screen & UI constraint: $label."))
        }

        // Sensors Constraints (12)
        listOf(
            "Proximity Sensor Far (Not In Pocket)", "Proximity Sensor Near (In Pocket)", "Device Lying Flat Face Up",
            "Device Lying Face Down", "Ambient Light Dark (< 20 lux)", "Ambient Light Bright (> 500 lux)",
            "Device Stationary (No Shake)", "Device Upright In Hand", "Tilt Angle < 30 deg",
            "Foldable Unfolded", "Compass North Sector", "Battery & CPU Cool"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.sensor_$idx", label, "Sensors", "Sensor constraint: $label."))
        }

        // Volume Constraints (10)
        listOf(
            "Media Volume > 0%", "Media Volume == 0% (Muted)", "Ring Volume Normal", "Ringer Silent",
            "Ringer Vibrate", "Alarm Volume > 50%", "Call Volume < 80%", "Notification Volume > 0%",
            "System Sounds Unmuted", "Bluetooth Volume < 75%"
        ).forEachIndexed { idx, label ->
            addUnique(constraintSpec("constraint.vol_$idx", label, "Volume", "Audio volume constraint: $label."))
        }

        var extra = 1
        while (list.size < neededCount) {
            addUnique(
                constraintSpec(
                    "constraint.custom_guard_$extra",
                    "Custom Execution Guard #$extra",
                    "Device State",
                    "User-configurable execution constraint #$extra."
                )
            )
            extra++
        }

        return list.take(neededCount)
    }
}
