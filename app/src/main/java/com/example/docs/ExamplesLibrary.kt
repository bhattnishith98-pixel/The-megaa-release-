package com.example.docs

data class StudioExample(
    val id: String,
    val title: String,
    val category: String,
    val difficulty: String,
    val code: String
)

/**
 * 52 curated, verified StudioLang programs spanning Beginner -> Advanced.
 * Every program in this library is compiled and tested in the automated test suite.
 */
object ExamplesLibrary {
    val examples: List<StudioExample> = listOf(
        // 1-10: Beginner Basics
        StudioExample("ex01", "Hello StudioLang", "Beginner", "Easy", "log.info(\"Hello from Automation Studio!\")\nreturn \"ok\""),
        StudioExample("ex02", "Variables & Math", "Beginner", "Easy", "let a = 12\nlet b = 8\nlet total = (a + b) * 2\nlog.info(\"Total: \" + total)\nreturn total"),
        StudioExample("ex03", "Immutable Constant", "Beginner", "Easy", "const VERSION = 1\nlog.info(\"v\" + VERSION)\nreturn VERSION"),
        StudioExample("ex04", "Simple If / Elif / Else", "Beginner", "Easy", "let score = 85\nif score >= 90:\n    return \"A\"\nelif score >= 80:\n    return \"B\"\nelse:\n    return \"C\""),
        StudioExample("ex05", "Repeat N Times", "Beginner", "Easy", "let sum = 0\nrepeat 5 times:\n    sum += 2\nreturn sum"),
        StudioExample("ex06", "For-Each List Sum", "Beginner", "Easy", "let nums = [10, 20, 30, 40]\nlet total = 0\nfor n in nums:\n    total += n\nreturn total"),
        StudioExample("ex07", "While Loop Countdown", "Beginner", "Easy", "let n = 4\nwhile n > 0:\n    log.info(\"Tick \" + n)\n    n -= 1\nreturn \"done\""),
        StudioExample("ex08", "Break & Continue", "Beginner", "Easy", "let total = 0\nfor x in [1, 2, 3, 4, 5, 6]:\n    if x == 2:\n        continue\n    if x == 5:\n        break\n    total += x\nreturn total"),
        StudioExample("ex09", "Function With Default Arg", "Beginner", "Easy", "def greet(name = \"Builder\") -> text:\n    return \"Hello, \" + name\nreturn greet()"),
        StudioExample("ex10", "Power & Modulo", "Beginner", "Easy", "let p = 2 ** 5\nlet m = p % 7\nreturn m"),

        // 11-20: Functions, Blocks & Error Handling
        StudioExample("ex11", "Recursive Factorial", "Functions", "Medium", "def fact(n: number) -> number:\n    if n <= 1:\n        return 1\n    return n * fact(n - 1)\nreturn fact(5)"),
        StudioExample("ex12", "Reusable Action Block", "Blocks", "Medium", "block ClampPercent(v = 120) -> number:\n    return math.clamp(v, 0, 100)\nreturn ClampPercent(145)"),
        StudioExample("ex13", "Try / On Error Recovery", "Errors", "Medium", "try:\n    raise \"Network down\"\non error e:\n    log.warn(\"Caught: \" + e.message)\n    return \"recovered\""),
        StudioExample("ex14", "Division By Zero Catch", "Errors", "Medium", "try:\n    let x = 10 / 0\non error err:\n    return err.code"),
        StudioExample("ex15", "Retry With Wait", "Errors", "Medium", "let attempts = 0\nretry 3 times, wait 5ms:\n    attempts += 1\n    if attempts < 2:\n        raise \"Not ready\"\nreturn attempts"),
        StudioExample("ex16", "Bounded Timeout", "Flow", "Medium", "timeout 2s:\n    let x = 21 * 2\n    return x"),
        StudioExample("ex17", "Compound Operators", "Data", "Easy", "let x = 10\nx += 5\nx -= 3\nx *= 2\nx /= 4\nreturn x"),
        StudioExample("ex18", "Boolean Logic", "Data", "Easy", "let a = true\nlet b = false\nreturn (a and not b) or b"),
        StudioExample("ex19", "In Membership Check", "Data", "Easy", "let tools = [\"lexer\", \"parser\", \"vm\"]\nreturn \"vm\" in tools"),
        StudioExample("ex20", "Map Property Read/Write", "Data", "Medium", "let cfg = {mode: \"dark\", fps: 60}\ncfg.fps = 120\nreturn cfg.fps"),

        // 21-30: Standard Library (Math, Text, List, JSON, Time)
        StudioExample("ex21", "Math Trigonometry & Sqrt", "StdLib", "Easy", "return math.sqrt(81) + math.abs(-6)"),
        StudioExample("ex22", "Math Min/Max/Round", "StdLib", "Easy", "return math.max(math.round(3.7), math.min(10, 5))"),
        StudioExample("ex23", "Text Case & Trim", "StdLib", "Easy", "return text.upper(text.trim(\"  studio  \"))"),
        StudioExample("ex24", "Text Split & Join", "StdLib", "Medium", "let parts = text.split(\"a,b,c\", \",\")\nreturn text.join(parts, \"-\")"),
        StudioExample("ex25", "Text Replace & Contains", "StdLib", "Easy", "let s = text.replace(\"hello world\", \"world\", \"android\")\nreturn text.contains(s, \"android\")"),
        StudioExample("ex26", "List Push Pop Reverse", "StdLib", "Medium", "let items = [1, 2]\nlist.push(items, 3)\nlet rev = list.reverse(items)\nreturn list.pop(rev)"),
        StudioExample("ex27", "List Sort", "StdLib", "Easy", "let items = [\"zeta\", \"alpha\", \"beta\"]\nreturn list.sort(items)"),
        StudioExample("ex28", "JSON Parse & Stringify", "StdLib", "Medium", "let obj = json.parse(\"{\\\"ok\\\":true,\\\"count\\\":7}\")\nreturn obj.count"),
        StudioExample("ex29", "JSON Build From Map", "StdLib", "Medium", "let m = {app: \"Studio\", ver: 1}\nreturn json.stringify(m)"),
        StudioExample("ex30", "Time & Duration", "StdLib", "Easy", "let ts = time.timestamp_ms()\nlog.info(\"Now: \" + time.now())\nreturn ts > 0"),

        // 31-42: Automation Constructs (Triggers, Conditions, Constraints, Actions, Macros)
        StudioExample("ex31", "Manual Macro", "Automation", "Easy", "macro QuickHello:\n    on manual()\n    do:\n        log.info(\"Macro executed!\")"),
        StudioExample("ex32", "Scheduled Night Mode Draft", "Automation", "Medium", "needs SYSTEM_INFO\nmacro NightMode:\n    on time.at(\"22:30\")\n    only if device.battery_level() > 10\n    do:\n        log.info(\"Night mode checks complete\")"),
        StudioExample("ex33", "Custom Trigger Definition", "Automation", "Medium", "needs SYSTEM_INFO\ntrigger FullyCharged:\n    source battery.changed\n    when event.charging and event.level == 100\n    emit {level: event.level}"),
        StudioExample("ex34", "Custom Condition & Constraint", "Automation", "Medium", "needs NETWORK_ACCESS\ncondition IsOnline() -> bool:\n    return net.is_connected()\nconstraint SafeBattery(min = 15) -> bool:\n    return true\nreturn SafeBattery()"),
        StudioExample("ex35", "Custom Action With Needs", "Automation", "Medium", "needs NOTIFICATIONS\naction SendAlert(title: text, body: text):\n    notify.show(title, body)\nlog.info(\"Action defined\")"),
        StudioExample("ex36", "Device Info Telemetry", "Android", "Easy", "needs SYSTEM_INFO\nlog.info(\"Device: \" + device.model())\nreturn device.sdk_int()"),
        StudioExample("ex37", "Battery & Charging Check", "Android", "Easy", "needs SYSTEM_INFO\nreturn {level: device.battery_level(), charging: device.is_charging()}"),
        StudioExample("ex38", "Network State Check", "Android", "Easy", "needs NETWORK_ACCESS\nreturn {online: net.is_connected(), wifi: net.is_wifi()}"),
        StudioExample("ex39", "Permission Inspection", "Android", "Medium", "let ok = perms.check(\"android.permission.INTERNET\")\nreturn ok"),
        StudioExample("ex40", "Accessibility & Capture Status", "Android", "Medium", "return {acc: perms.is_accessibility_enabled(), cap: perms.is_screen_capture_active()}"),
        StudioExample("ex41", "Notification Action", "Android", "Medium", "needs NOTIFICATIONS\nnotify.show(\"Automation Studio\", \"Hello from StudioLang\")"),
        StudioExample("ex42", "Haptic Feedback", "Android", "Easy", "needs SYSTEM_INFO\ndevice.vibrate(80)\nreturn true"),

        // 43-52: Sandboxed Files, Keystore Credentials, Safety Confirm, Apps & Benchmarks
        StudioExample("ex43", "Write & Read Workspace File", "Files", "Medium", "needs FILE_ACCESS\nfiles.write(\"demo/hello.txt\", \"StudioLang\")\nreturn files.read(\"demo/hello.txt\")"),
        StudioExample("ex44", "List Workspace Directory", "Files", "Medium", "needs FILE_ACCESS\nfiles.mkdir(\"demo\")\nreturn files.list(\"demo\")"),
        StudioExample("ex45", "Safe File Copy & Exists", "Files", "Medium", "needs FILE_ACCESS\nfiles.write(\"demo/a.txt\", \"copy_me\")\nfiles.copy(\"demo/a.txt\", \"demo/b.txt\")\nreturn files.exists(\"demo/b.txt\")"),
        StudioExample("ex46", "Confirm Before File Delete", "Security", "Medium", "needs FILE_ACCESS\nfiles.write(\"demo/tmp.txt\", \"x\")\nconfirm \"Delete demo/tmp.txt?\"\nreturn files.delete(\"demo/tmp.txt\")"),
        StudioExample("ex47", "Keystore Store & Masked Read", "Security", "Hard", "needs CREDENTIAL_MANAGEMENT\ncreds.store(\"demo_key\", \"top_secret_123\")\nlet s = creds.get(\"demo_key\")\nlog.info(\"Masked log: \" + s)\nreturn creds.list_keys()"),
        StudioExample("ex48", "Macro With Error Handler", "Automation", "Medium", "macro SafeRunner:\n    on manual()\n    do:\n        raise \"Simulated failure\"\n    on error e:\n        log.warn(\"Handled: \" + e.message)"),
        StudioExample("ex49", "Block Calling Helper Def", "Blocks", "Hard", "def square(n: number) -> number:\n    return n * n\nblock SumSquares(limit: number) -> number:\n    let acc = 0\n    let i = 1\n    while i <= limit:\n        acc += square(i)\n        i += 1\n    return acc\nreturn SumSquares(4)"),
        StudioExample("ex50", "App & Screen Declaration", "App Builder", "Medium", "app QuickNotes:\n    screen Home:\n        log.info(\"Home screen\")\nreturn \"app_ready\""),
        StudioExample("ex51", "Godot Screen Observation Script", "Agent", "Hard", "needs SCREEN_CAPTURE, ACCESSIBILITY_CONTROL\nmacro GodotTapExport:\n    on manual()\n    do:\n        let nodes = screen.find_text(\"Export\")\n        log.info(\"Found matches: \" + list.len(nodes))"),
        StudioExample("ex52", "VM Tight Loop Benchmark", "Benchmark", "Hard", "let acc = 0\nlet i = 0\nwhile i < 1000:\n    acc += i\n    i += 1\nreturn acc")
    )
}
