package com.example.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import com.example.vm.*
import java.io.File

/**
 * Host API bridge for the Custom OS Kernel (`kernel.*`, `proc.*`, `ipc.*`, `vfs.*`, `stopwatch.*`)
 * and Full Hardware Control (`hw.*`, `sensor.*`, `audio.*`, `display.*`, `camera.*`, `power.*`).
 */
object OsAndHardwareBridge {

    fun dispatchOrNull(
        context: Context,
        qualifiedName: String,
        args: List<StudioValue>,
        line: Int,
        workspaceRoot: File,
        logSink: (level: String, message: String) -> Unit
    ): StudioValue? = when (qualifiedName) {
        // ---- CUSTOM OS KERNEL ----
        "kernel.boot_info" -> anyToStudio(CustomOsKernel.uname())
        "kernel.status" -> anyToStudio(CustomOsKernel.stats())
        "kernel.benchmark_ops" -> {
            val iters = args.getOrNull(0)?.asDouble()?.toInt() ?: 50_000
            anyToStudio(CustomOsKernel.benchCpu(iters))
        }
        "kernel.syscall" -> {
            val name = argStr(args, 0).ifBlank { "sys_uname" }
            val arg = argStr(args, 1)
            anyToStudio(CustomOsKernel.syscall(name, arg))
        }
        "kernel.dmesg" -> ListVal(
            mutableListOf(
                TextVal("Swatantra-Kernel: preemptive scheduler online"),
                TextVal("OverlayVFS mounted on ${CustomOsKernel.vfsMounts().keys.joinToString(", ")}"),
                TextVal("Hardware sensors listening on Android SensorManager")
            )
        )

        // ---- PROCESS SCHEDULER ----
        "proc.spawn" -> {
            val name = argStr(args, 0).ifBlank { "worker" }
            val cmd = argStr(args, 1).ifBlank { name }
            val prio = args.getOrNull(2)?.asDouble()?.toInt() ?: 0
            val p = CustomOsKernel.spawnProcess(name, cmd, prio)
            logSink("INFO", "[proc] Spawned pid=${p.pid} '${p.name}' prio=${p.priority}")
            MapVal(linkedMapOf(
                "pid" to NumVal(p.pid.toDouble()),
                "name" to TextVal(p.name),
                "priority" to NumVal(p.priority.toDouble()),
                "state" to TextVal(p.state)
            ))
        }
        "proc.list" -> {
            val includeDead = args.getOrNull(0)?.asBool() == true
            val list = CustomOsKernel.listProcesses(includeDead).mapTo(mutableListOf<StudioValue>()) { p ->
                MapVal(linkedMapOf(
                    "pid" to NumVal(p.pid.toDouble()),
                    "name" to TextVal(p.name),
                    "command" to TextVal(p.command),
                    "priority" to NumVal(p.priority.toDouble()),
                    "state" to TextVal(p.state),
                    "cpu_ns" to NumVal(p.cpuTimeNs.toDouble())
                ))
            }
            ListVal(list)
        }
        "proc.kill" -> {
            val pid = args.getOrNull(0)?.asDouble()?.toInt() ?: -1
            val sig = argStr(args, 1).ifBlank { "SIGTERM" }
            BoolVal(CustomOsKernel.killProcess(pid, sig))
        }
        "proc.set_priority" -> {
            val pid = args.getOrNull(0)?.asDouble()?.toInt() ?: 1
            val prio = args.getOrNull(1)?.asDouble()?.toInt() ?: 0
            BoolVal(CustomOsKernel.setProcessPriority(pid, prio))
        }
        "proc.tick" -> {
            val pid = args.getOrNull(0)?.asDouble()?.toInt() ?: 1
            CustomOsKernel.yieldProcess(pid)
            anyToStudio(CustomOsKernel.stats())
        }

        // ---- IPC & MUTEXES ----
        "ipc.send" -> {
            val ch = argStr(args, 0).ifBlank { "default" }
            val msg = argStr(args, 1)
            CustomOsKernel.ipcSend(ch, msg)
            BoolVal(true)
        }
        "ipc.recv" -> {
            val ch = argStr(args, 0).ifBlank { "default" }
            TextVal(CustomOsKernel.ipcRecv(ch))
        }
        "ipc.mutex_lock" -> BoolVal(CustomOsKernel.acquireLock(argStr(args, 0).ifBlank { "global" }))
        "ipc.mutex_unlock" -> BoolVal(CustomOsKernel.releaseLock(argStr(args, 0).ifBlank { "global" }))

        // ---- OVERLAY VFS ----
        "vfs.mount" -> BoolVal(CustomOsKernel.mountVfs(argStr(args, 0).ifBlank { "/mnt/studio" }, argStr(args, 1)))
        "vfs.write" -> {
            val path = CustomOsKernel.writeVfs(argStr(args, 0), argStr(args, 1), workspaceRoot)
            BoolVal(path.isNotBlank())
        }
        "vfs.read" -> TextVal(CustomOsKernel.readVfs(argStr(args, 0), workspaceRoot))
        "vfs.ls" -> ListVal(CustomOsKernel.listVfs(argStr(args, 0).ifBlank { "/" }).mapTo(mutableListOf()) { TextVal(it) as StudioValue })
        "vfs.rm" -> BoolVal(CustomOsKernel.deleteVfs(argStr(args, 0), workspaceRoot))

        // ---- STOPWATCHES ----
        "stopwatch.start" -> {
            val name = argStr(args, 0).ifBlank { "default" }
            val ms = CustomOsKernel.startStopwatch(name)
            MapVal(linkedMapOf("name" to TextVal(name), "elapsed_ms" to NumVal(ms.toDouble())))
        }
        "stopwatch.elapsed_ms" -> NumVal(CustomOsKernel.elapsedStopwatchMs(argStr(args, 0).ifBlank { "default" }).toDouble())
        "stopwatch.stop" -> NumVal(CustomOsKernel.stopStopwatch(argStr(args, 0).ifBlank { "default" }).toDouble())
        "stopwatch.reset" -> {
            CustomOsKernel.resetStopwatch(argStr(args, 0).ifBlank { "default" })
            BoolVal(true)
        }

        // ---- FULL HARDWARE & SENSORS ----
        "hw.storage_info", "hw.capabilities" -> anyToStudio(HardwareController.hardwareSummary(context))
        "hw.network_info" -> anyToStudio(
            mapOf(
                "ipv4" to NetworkShareManager.inspectLocalIps().ipv4Addresses,
                "ipv6" to NetworkShareManager.inspectLocalIps().ipv6Addresses
            )
        )
        "sensor.list" -> anyToStudio(HardwareController.listSensors(context))
        "sensor.read" -> anyToStudio(HardwareController.readSensor(context, argStr(args, 0).ifBlank { "accel" }))
        "sensor.all" -> MapVal(linkedMapOf(
            "accel" to anyToStudio(HardwareController.readSensor(context, "accel")),
            "light_lux" to NumVal(HardwareController.lightLux(context)),
            "proximity_cm" to NumVal(HardwareController.proximityCm(context)),
            "gyro" to anyToStudio(HardwareController.readSensor(context, "gyro")),
            "magnetometer" to anyToStudio(HardwareController.readSensor(context, "mag")),
            "face_down" to BoolVal(HardwareController.isFaceDown(context)),
            "face_up" to BoolVal(HardwareController.isFaceUp(context))
        ))
        "sensor.light_lux" -> NumVal(HardwareController.lightLux(context))
        "sensor.proximity_cm" -> NumVal(HardwareController.proximityCm(context))
        "sensor.accel" -> anyToStudio(HardwareController.readSensor(context, "accel"))
        "sensor.gyro" -> anyToStudio(HardwareController.readSensor(context, "gyro"))
        "sensor.magnetometer" -> anyToStudio(HardwareController.readSensor(context, "mag"))
        "sensor.pressure_hpa" -> {
            val map = HardwareController.readSensor(context, "pressure")
            val vals = map["values"] as? List<*>
            NumVal((vals?.firstOrNull() as? Double) ?: -1.0)
        }

        // ---- AUDIO ----
        "audio.get_volume" -> NumVal(HardwareController.getVolumePercent(context, argStr(args, 0).ifBlank { "media" }).toDouble())
        "audio.set_volume" -> {
            val stream = if (args.size >= 2) argStr(args, 0) else "media"
            val pct = (if (args.size >= 2) args[1].asDouble() else args.getOrNull(0)?.asDouble() ?: 50.0).toInt()
            HardwareController.setVolumePercent(context, stream, pct)
            BoolVal(true)
        }
        "audio.get_ringer_mode" -> TextVal(HardwareController.getRingerMode(context))
        "audio.set_ringer_mode" -> BoolVal(HardwareController.setRingerMode(context, argStr(args, 0).ifBlank { "normal" }))
        "audio.set_speakerphone" -> BoolVal(HardwareController.setSpeakerphone(context, args.getOrNull(0)?.asBool() ?: true))
        "audio.is_music_active" -> BoolVal(HardwareController.isMusicActive(context))
        "audio.beep" -> {
            val dur = (args.getOrNull(0)?.asDouble()?.toInt() ?: 160).coerceIn(30, 1500)
            val ok = runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, dur)
                true
            }.getOrDefault(false)
            BoolVal(ok)
        }

        // ---- DISPLAY / CAMERA TORCH / POWER ----
        "display.info" -> anyToStudio(HardwareController.displayInfo(context))
        "display.brightness" -> NumVal(AndroidCustomizer.readDeviceTweaks(context).brightness.toDouble())
        "display.set_brightness" -> BoolVal(AndroidCustomizer.setStandardBrightness(context, args.getOrNull(0)?.asDouble()?.toInt() ?: 128))
        "camera.torch" -> BoolVal(HardwareController.setTorch(context, args.getOrNull(0)?.asBool() ?: true))
        "camera.is_torch_on" -> BoolVal(HardwareController.isTorchKnownOn())
        "power.battery_info" -> anyToStudio(HardwareController.batteryInfo(context))
        "power.temperature_c" -> NumVal((HardwareController.batteryInfo(context)["temperature_c"] as? Double) ?: 30.0)
        "power.is_power_save" -> BoolVal(HardwareController.isPowerSaveMode(context))
        "power.thermal_status" -> TextVal(HardwareController.thermalStatus(context))
        "power.vibrate_pattern" -> {
            val seq = when (val a0 = args.getOrNull(0)) {
                is ListVal -> a0.items.map { it.asDouble().toLong() }
                is TupleVal -> a0.items.map { it.asDouble().toLong() }
                else -> listOf(100L, 60L, 120L)
            }
            BoolVal(HardwareController.vibratePattern(context, seq))
        }

        else -> null
    }

    fun anyToStudio(value: Any?): StudioValue = when (value) {
        null -> NoneVal
        is StudioValue -> value
        is Boolean -> BoolVal(value)
        is Number -> NumVal(value.toDouble())
        is String -> TextVal(value)
        is Map<*, *> -> MapVal(linkedMapOf<String, StudioValue>().apply {
            value.forEach { (k, v) -> put(k?.toString().orEmpty(), anyToStudio(v)) }
        })
        is List<*> -> ListVal(value.mapTo(mutableListOf()) { anyToStudio(it) })
        else -> TextVal(value.toString())
    }

    private fun argStr(args: List<StudioValue>, idx: Int): String =
        args.getOrNull(idx)?.let { if (it is TextVal) it.value else it.asDisplayString() } ?: ""
}
