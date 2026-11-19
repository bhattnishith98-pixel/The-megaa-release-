package com.example.runtime

import android.os.Build
import com.example.vm.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/**
 * Python Builtins & Standard Library dispatcher for StudioLang (`print`, `range`, `len`,
 * `re.*`, `random.*`, `collections.*`, `hashlib.*`, `base64.*`, `math.*`, `os.*`, `sys.*`).
 */
object PythonStdLibBridge {

    fun dispatchOrNull(
        qualifiedName: String,
        args: List<StudioValue>,
        line: Int,
        workspaceRoot: File,
        logSink: (level: String, message: String) -> Unit
    ): StudioValue? = when (qualifiedName) {
        // ---- PYTHON BUILTINS ----
        "print" -> {
            val out = args.joinToString(" ") { it.asDisplayString() }
            logSink("INFO", out)
            NoneVal
        }
        "len" -> {
            val v = args.getOrNull(0)
            val n = when (v) {
                is ListVal -> v.items.size
                is TupleVal -> v.items.size
                is SetVal -> v.items.size
                is MapVal -> v.entries.size
                is TextVal -> v.value.length
                else -> throw StudioVmException("PY001", "object of type '${v?.type?.label ?: "none"}' has no len()", line)
            }
            NumVal(n.toDouble())
        }
        "range" -> {
            val start: Int
            val stop: Int
            val step: Int
            when (args.size) {
                1 -> { start = 0; stop = args[0].asDouble().toInt(); step = 1 }
                2 -> { start = args[0].asDouble().toInt(); stop = args[1].asDouble().toInt(); step = 1 }
                else -> {
                    start = args.getOrNull(0)?.asDouble()?.toInt() ?: 0
                    stop = args.getOrNull(1)?.asDouble()?.toInt() ?: 0
                    step = (args.getOrNull(2)?.asDouble()?.toInt() ?: 1).also {
                        if (it == 0) throw StudioVmException("PY002", "range() arg 3 must not be zero", line)
                    }
                }
            }
            val items = mutableListOf<StudioValue>()
            var cur = start
            var guard = 0
            if (step > 0) {
                while (cur < stop && guard++ < 50_000) {
                    items += NumVal(cur.toDouble())
                    cur += step
                }
            } else {
                while (cur > stop && guard++ < 50_000) {
                    items += NumVal(cur.toDouble())
                    cur += step
                }
            }
            ListVal(items)
        }
        "enumerate" -> {
            val list = toList(args.getOrNull(0))
            val startIdx = args.getOrNull(1)?.asDouble()?.toInt() ?: 0
            ListVal(list.mapIndexedTo(mutableListOf()) { idx, v -> TupleVal(listOf(NumVal((startIdx + idx).toDouble()), v)) })
        }
        "zip" -> {
            if (args.isEmpty()) ListVal()
            else {
                val lists = args.map { toList(it) }
                val minLen = lists.minOf { it.size }
                val zipped = mutableListOf<StudioValue>()
                for (i in 0 until minLen) {
                    zipped += TupleVal(lists.map { it[i] })
                }
                ListVal(zipped)
            }
        }
        "any" -> BoolVal(toList(args.getOrNull(0)).any { it.asBool() })
        "all" -> BoolVal(toList(args.getOrNull(0)).all { it.asBool() })
        "sum" -> {
            var acc = args.getOrNull(1)?.asDouble() ?: 0.0
            for (item in toList(args.getOrNull(0))) acc += item.asDouble()
            NumVal(acc)
        }
        "min" -> {
            val seq = if (args.size == 1) toList(args[0]) else args
            if (seq.isEmpty()) throw StudioVmException("PY003", "min() arg is an empty sequence", line)
            seq.minWith { a, b -> compareDynamic(a, b) }
        }
        "max" -> {
            val seq = if (args.size == 1) toList(args[0]) else args
            if (seq.isEmpty()) throw StudioVmException("PY003", "max() arg is an empty sequence", line)
            seq.maxWith { a, b -> compareDynamic(a, b) }
        }
        "reversed" -> ListVal(toList(args.getOrNull(0)).reversed().toMutableList())
        "abs" -> NumVal(abs(args.getOrNull(0)?.asDouble() ?: 0.0))
        "round" -> {
            val v = args.getOrNull(0)?.asDouble() ?: 0.0
            val digits = args.getOrNull(1)?.asDouble()?.toInt() ?: 0
            if (digits <= 0) NumVal(round(v))
            else {
                val factor = 10.0.pow(digits)
                NumVal(round(v * factor) / factor)
            }
        }
        "pow" -> {
            val base = args.getOrNull(0)?.asDouble() ?: 0.0
            val exp = args.getOrNull(1)?.asDouble() ?: 0.0
            val mod = args.getOrNull(2)?.asDouble()
            if (mod != null && mod != 0.0) NumVal(base.pow(exp) % mod) else NumVal(base.pow(exp))
        }
        "divmod" -> {
            val a = args.getOrNull(0)?.asDouble() ?: 0.0
            val b = args.getOrNull(1)?.asDouble() ?: 1.0
            if (b == 0.0) throw StudioVmException("PY004", "division or modulo by zero", line)
            TupleVal(listOf(NumVal(floor(a / b)), NumVal(a % b)))
        }
        "int" -> {
            val v = args.getOrNull(0)
            val base = args.getOrNull(1)?.asDouble()?.toInt() ?: 10
            val n = when (v) {
                is TextVal -> v.value.trim().toLongOrNull(base)?.toDouble()
                    ?: v.value.trim().toDoubleOrNull()?.toLong()?.toDouble()
                    ?: throw StudioVmException("PY005", "invalid literal for int(): '${v.value}'", line)
                is NumVal -> v.value.toLong().toDouble()
                is BoolVal -> if (v.value) 1.0 else 0.0
                else -> 0.0
            }
            NumVal(n)
        }
        "float" -> NumVal(args.getOrNull(0)?.asDouble() ?: 0.0)
        "str" -> TextVal(args.getOrNull(0)?.asDisplayString() ?: "")
        "bool" -> BoolVal(args.getOrNull(0)?.asBool() ?: false)
        "list" -> ListVal(toList(args.getOrNull(0)).toMutableList())
        "tuple" -> TupleVal(toList(args.getOrNull(0)))
        "set" -> SetVal(LinkedHashSet(toList(args.getOrNull(0))))
        "dict" -> {
            val arg = args.getOrNull(0)
            val m = linkedMapOf<String, StudioValue>()
            when (arg) {
                is MapVal -> m.putAll(arg.entries)
                is ListVal -> arg.items.forEach { entry ->
                    val pair = toList(entry)
                    if (pair.size >= 2) m[pair[0].asDisplayString()] = pair[1]
                }
                else -> Unit
            }
            MapVal(m)
        }
        "isinstance" -> {
            val obj = args.getOrNull(0)
            val target = args.getOrNull(1)
            val typeStr = when (target) {
                is CallableVal -> target.name.lowercase()
                is TextVal -> target.value.lowercase()
                else -> target?.asDisplayString()?.lowercase().orEmpty()
            }
            val match = when (typeStr) {
                "int", "float", "number" -> obj is NumVal
                "str", "text" -> obj is TextVal
                "bool" -> obj is BoolVal
                "list" -> obj is ListVal
                "tuple" -> obj is TupleVal
                "set" -> obj is SetVal
                "dict", "map" -> obj is MapVal
                else -> (obj as? ObjectVal)?.className?.equals(typeStr, ignoreCase = true) == true
            }
            BoolVal(match)
        }
        "type" -> {
            val v = args.getOrNull(0)
            val tName = when (v) {
                is ObjectVal -> v.className
                is CallableVal -> if (v.isClass) "type" else "function"
                null -> "NoneType"
                else -> v.type.label
            }
            TextVal("<class '$tName'>")
        }
        "chr" -> {
            val code = args.getOrNull(0)?.asDouble()?.toInt() ?: 0
            TextVal(Char(code.coerceIn(0, 0xFFFF)).toString())
        }
        "ord" -> {
            val s = argStr(args, 0)
            if (s.isEmpty()) throw StudioVmException("PY006", "ord() expected a character", line)
            NumVal(s[0].code.toDouble())
        }
        "hex" -> TextVal("0x" + (args.getOrNull(0)?.asDouble()?.toLong() ?: 0L).toString(16))
        "bin" -> TextVal("0b" + (args.getOrNull(0)?.asDouble()?.toLong() ?: 0L).toString(2))
        "input" -> TextVal(args.getOrNull(0)?.asDisplayString().orEmpty())
        "repr", "format" -> TextVal(args.getOrNull(0)?.asDisplayString() ?: "")
        "hasattr" -> {
            val obj = args.getOrNull(0)
            val attr = argStr(args, 1)
            BoolVal(
                when (obj) {
                    is ObjectVal -> attr in obj.fields || attr in obj.methods
                    is MapVal -> attr in obj.entries
                    else -> false
                }
            )
        }
        "getattr" -> {
            val obj = args.getOrNull(0)
            val attr = argStr(args, 1)
            val fallback = args.getOrNull(2) ?: NoneVal
            when (obj) {
                is ObjectVal -> obj.fields[attr] ?: obj.methods[attr]?.let { CallableVal(it, "${obj.className}.$attr", boundSelf = obj) } ?: fallback
                is MapVal -> obj.entries[attr] ?: fallback
                else -> fallback
            }
        }
        "setattr" -> {
            val obj = args.getOrNull(0)
            val attr = argStr(args, 1)
            val valArg = args.getOrNull(2) ?: NoneVal
            when (obj) {
                is ObjectVal -> obj.fields[attr] = valArg
                is MapVal -> obj.entries[attr] = valArg
                else -> Unit
            }
            NoneVal
        }
        "py.format_val" -> {
            val v = args.getOrNull(0) ?: NoneVal
            val spec = argStr(args, 1)
            TextVal(formatSpec(v, spec))
        }
        "text.format" -> {
            var tpl = argStr(args, 0)
            for (i in 1 until args.size) {
                val rep = args[i].asDisplayString()
                tpl = if (tpl.contains("{}")) tpl.replaceFirst("{}", rep) else tpl.replace("{${i - 1}}", rep)
            }
            TextVal(tpl)
        }
        "list.len" -> {
            val n = when (val v = args.getOrNull(0)) {
                is ListVal -> v.items.size
                is TupleVal -> v.items.size
                is SetVal -> v.items.size
                is MapVal -> v.entries.size
                is TextVal -> v.value.length
                else -> 0
            }
            NumVal(n.toDouble())
        }
        "list.push" -> {
            val l = args.getOrNull(0) as? ListVal ?: throw StudioVmException("STD001", "Expected list.", line)
            l.items.add(args.getOrNull(1) ?: NoneVal)
            l
        }
        "list.pop" -> {
            val l = args.getOrNull(0) as? ListVal ?: throw StudioVmException("STD001", "Expected list.", line)
            if (l.items.isEmpty()) NoneVal else l.items.removeAt(l.items.lastIndex)
        }
        "list.contains" -> {
            val l = args.getOrNull(0) as? ListVal ?: return BoolVal(false)
            BoolVal(args.getOrNull(1) in l.items)
        }
        "list.sort" -> {
            val l = args.getOrNull(0) as? ListVal ?: return ListVal()
            ListVal(l.items.sortedBy { it.asDisplayString() }.toMutableList())
        }
        "list.reverse" -> {
            val l = args.getOrNull(0) as? ListVal ?: return ListVal()
            ListVal(l.items.reversed().toMutableList())
        }
        "json.loads" -> null // Handled via json.parse alias in StudioStdLib if routed, or handle below:
        "time.time" -> NumVal(System.currentTimeMillis() / 1000.0)
        "time.perf_counter" -> NumVal(System.nanoTime() / 1_000_000_000.0)

        // ---- MATH EXTENSIONS ----
        "math.sin" -> NumVal(sin(argNum(args, 0, line)))
        "math.cos" -> NumVal(cos(argNum(args, 0, line)))
        "math.tan" -> NumVal(tan(argNum(args, 0, line)))
        "math.asin" -> NumVal(asin(argNum(args, 0, line).coerceIn(-1.0, 1.0)))
        "math.acos" -> NumVal(acos(argNum(args, 0, line).coerceIn(-1.0, 1.0)))
        "math.atan2" -> NumVal(atan2(argNum(args, 0, line), argNum(args, 1, line)))
        "math.log" -> {
            val x = argNum(args, 0, line)
            if (args.size >= 2) NumVal(log(x, argNum(args, 1, line))) else NumVal(ln(x))
        }
        "math.log10" -> NumVal(log10(argNum(args, 0, line)))
        "math.log2" -> NumVal(log2(argNum(args, 0, line)))
        "math.exp" -> NumVal(exp(argNum(args, 0, line)))
        "math.gcd" -> {
            var a = abs(argNum(args, 0, line).toLong())
            var b = abs(argNum(args, 1, line).toLong())
            while (b != 0L) {
                val t = a % b
                a = b
                b = t
            }
            NumVal(a.toDouble())
        }
        "math.factorial" -> {
            val n = argNum(args, 0, line).toInt().coerceIn(0, 20)
            var res = 1L
            for (i in 2..n) res *= i
            NumVal(res.toDouble())
        }
        "math.radians" -> NumVal(Math.toRadians(argNum(args, 0, line)))
        "math.degrees" -> NumVal(Math.toDegrees(argNum(args, 0, line)))
        "math.isnan" -> BoolVal(argNum(args, 0, line).isNaN())
        "math.isinf" -> BoolVal(argNum(args, 0, line).isInfinite())

        // ---- REGEX (re.*) ----
        "re.match" -> BoolVal(Regex(argStr(args, 0)).matchesAt(argStr(args, 1), 0))
        "re.search" -> {
            val m = Regex(argStr(args, 0)).find(argStr(args, 1))
            if (m == null) NoneVal else TextVal(m.value)
        }
        "re.findall" -> {
            val matches = Regex(argStr(args, 0)).findAll(argStr(args, 1)).mapTo(mutableListOf()) { TextVal(it.value) as StudioValue }
            ListVal(matches)
        }
        "re.sub" -> TextVal(Regex(argStr(args, 0)).replace(argStr(args, 2), argStr(args, 1)))
        "re.split" -> ListVal(argStr(args, 1).split(Regex(argStr(args, 0))).mapTo(mutableListOf()) { TextVal(it) as StudioValue })

        // ---- RANDOM (random.*) ----
        "random.random" -> NumVal(Random.nextDouble())
        "random.randint" -> {
            val lo = argNum(args, 0, line).toInt()
            val hi = argNum(args, 1, line).toInt().coerceAtLeast(lo)
            NumVal(Random.nextInt(lo, hi + 1).toDouble())
        }
        "random.uniform" -> {
            val lo = argNum(args, 0, line)
            val hi = argNum(args, 1, line).coerceAtLeast(lo)
            NumVal(lo + Random.nextDouble() * (hi - lo))
        }
        "random.choice" -> {
            val seq = toList(args.getOrNull(0))
            if (seq.isEmpty()) throw StudioVmException("PY007", "Cannot choose from an empty sequence", line)
            seq[Random.nextInt(seq.size)]
        }
        "random.shuffle" -> {
            val l = args.getOrNull(0) as? ListVal
            if (l != null) {
                l.items.shuffle()
                l
            } else ListVal(toList(args.getOrNull(0)).shuffled().toMutableList())
        }

        // ---- COLLECTIONS ----
        "collections.Counter" -> {
            val counts = linkedMapOf<String, Int>()
            for (item in toList(args.getOrNull(0))) {
                val k = item.asDisplayString()
                counts[k] = (counts[k] ?: 0) + 1
            }
            val sorted = counts.entries.sortedByDescending { it.value }
            MapVal(linkedMapOf<String, StudioValue>().apply {
                sorted.forEach { (k, v) -> put(k, NumVal(v.toDouble())) }
            })
        }

        // ---- HASHLIB & BASE64 ----
        "hashlib.sha256" -> TextVal(digestHex("SHA-256", argStr(args, 0)))
        "hashlib.sha512" -> TextVal(digestHex("SHA-512", argStr(args, 0)))
        "hashlib.md5" -> TextVal(digestHex("MD5", argStr(args, 0)))
        "base64.b64encode", "base64.encode" -> TextVal(Base64.getEncoder().encodeToString(argStr(args, 0).toByteArray(Charsets.UTF_8)))
        "base64.b64decode", "base64.decode" -> {
            val decoded = runCatching {
                String(Base64.getDecoder().decode(argStr(args, 0)), Charsets.UTF_8)
            }.getOrDefault("")
            TextVal(decoded)
        }

        // ---- SYS & OS ----
        "sys.version" -> TextVal("StudioLang 3.12-py-hybrid (SwatantraVM on Android SDK ${Build.VERSION.SDK_INT})")
        "sys.platform" -> TextVal("swatantra-android")
        "os.getcwd" -> TextVal(workspaceRoot.absolutePath)
        "os.listdir" -> {
            val sub = argStr(args, 0)
            val dir = if (sub.isBlank()) workspaceRoot else SafePathValidator.resolveInsideWorkspace(workspaceRoot, sub)
            val items = dir.listFiles()?.sortedBy { it.name }?.mapTo(mutableListOf()) { TextVal(it.name) as StudioValue }
                ?: mutableListOf()
            ListVal(items)
        }
        "os.getenv" -> TextVal(System.getenv(argStr(args, 0)) ?: argStr(args, 1))

        else -> null
    }

    private fun toList(v: StudioValue?): List<StudioValue> = when (v) {
        is ListVal -> v.items
        is TupleVal -> v.items
        is SetVal -> v.items.toList()
        is MapVal -> v.entries.keys.map { TextVal(it) }
        is TextVal -> v.value.map { TextVal(it.toString()) }
        else -> emptyList()
    }

    private fun argNum(args: List<StudioValue>, idx: Int, line: Int): Double =
        args.getOrNull(idx)?.asDouble() ?: throw StudioVmException("STD002", "Missing numeric argument #$idx.", line)

    private fun argStr(args: List<StudioValue>, idx: Int): String =
        args.getOrNull(idx)?.let { if (it is TextVal) it.value else it.asDisplayString() } ?: ""

    private fun compareDynamic(a: StudioValue, b: StudioValue): Int = when {
        a is NumVal && b is NumVal -> a.value.compareTo(b.value)
        a is TextVal && b is TextVal -> a.value.compareTo(b.value)
        else -> a.asDisplayString().compareTo(b.asDisplayString())
    }

    private fun digestHex(algo: String, input: String): String {
        val bytes = MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun formatSpec(v: StudioValue, spec: String): String {
        if (spec.endsWith("f") || spec.endsWith("F")) {
            val digits = spec.dropWhile { it != '.' }.drop(1).dropLast(1).toIntOrNull() ?: 2
            val num = runCatching { v.asDouble() }.getOrNull() ?: return v.asDisplayString()
            return "%.${digits}f".format(Locale.US, num)
        }
        if (spec == "x") {
            val n = runCatching { v.asDouble().toLong() }.getOrNull() ?: return v.asDisplayString()
            return n.toString(16)
        }
        if (spec == "X") {
            val n = runCatching { v.asDouble().toLong() }.getOrNull() ?: return v.asDisplayString()
            return n.toString(16).uppercase()
        }
        if (spec == "b") {
            val n = runCatching { v.asDouble().toLong() }.getOrNull() ?: return v.asDisplayString()
            return n.toString(2)
        }
        return v.asDisplayString()
    }
}
