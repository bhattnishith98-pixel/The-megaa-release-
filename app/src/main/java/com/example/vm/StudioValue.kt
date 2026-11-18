package com.example.vm

import com.example.lang.StudioType

sealed interface StudioValue {
    val type: StudioType

    fun asBool(): Boolean = when (this) {
        is BoolVal -> value
        is NumVal -> value != 0.0
        is TextVal -> value.isNotEmpty()
        is ListVal -> items.isNotEmpty()
        is MapVal -> entries.isNotEmpty()
        is DurationVal -> millis != 0L
        NoneVal -> false
        is NamespaceVal, is CallableVal -> true
    }

    fun asDouble(): Double = when (this) {
        is NumVal -> value
        is DurationVal -> millis.toDouble()
        is BoolVal -> if (value) 1.0 else 0.0
        is TextVal -> value.toDoubleOrNull() ?: throw StudioVmException("VM002", "Cannot convert text '$value' to number.")
        else -> throw StudioVmException("VM002", "Expected number, got ${type.label}.")
    }

    fun asDisplayString(): String = when (this) {
        is NumVal -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e12) value.toLong().toString() else value.toString()
        is TextVal -> if (isSecret) "••••(redacted)" else value
        is BoolVal -> value.toString()
        is DurationVal -> "${millis}ms"
        NoneVal -> "none"
        is ListVal -> "[${items.joinToString(", ") { it.asDisplayString() }}]"
        is MapVal -> "{${entries.entries.joinToString(", ") { "${it.key}: ${it.value.asDisplayString()}" }}}"
        is NamespaceVal -> "<namespace ${name}>"
        is CallableVal -> "<fn ${name}>"
    }
}

data class NumVal(val value: Double) : StudioValue {
    override val type: StudioType = StudioType.NUMBER
}

data class TextVal(val value: String, val isSecret: Boolean = false) : StudioValue {
    override val type: StudioType = StudioType.TEXT
}

data class BoolVal(val value: Boolean) : StudioValue {
    override val type: StudioType = StudioType.BOOL
}

data class DurationVal(val millis: Long) : StudioValue {
    override val type: StudioType = StudioType.DURATION
}

data object NoneVal : StudioValue {
    override val type: StudioType = StudioType.NONE
}

class ListVal(val items: MutableList<StudioValue> = mutableListOf()) : StudioValue {
    override val type: StudioType = StudioType.LIST
    override fun equals(other: Any?): Boolean = other is ListVal && items == other.items
    override fun hashCode(): Int = items.hashCode()
}

class MapVal(val entries: MutableMap<String, StudioValue> = linkedMapOf()) : StudioValue {
    override val type: StudioType = StudioType.MAP
    override fun equals(other: Any?): Boolean = other is MapVal && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
}

data class NamespaceVal(val name: String) : StudioValue {
    override val type: StudioType = StudioType.ANY
}

data class CallableVal(val funcIndex: Int, val name: String) : StudioValue {
    override val type: StudioType = StudioType.ANY
}

class StudioVmException(
    val code: String,
    override val message: String,
    val line: Int = 0
) : RuntimeException("[$code] Line $line: $message")
