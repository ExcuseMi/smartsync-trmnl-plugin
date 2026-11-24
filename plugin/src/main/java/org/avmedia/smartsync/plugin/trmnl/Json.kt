package org.avmedia.smartsync.plugin.trmnl

/**
 * A compact JSON writer for the payload (maps keep their order). Own code: Android's org.json is not in JVM tests and
 * the payload needs only maps, lists, strings, numbers, booleans and null.
 */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun bytes(value: Any?): Int = write(value).toByteArray(Charsets.UTF_8).size

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> string(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value)
            is Double -> number(sb, value)
            is Float -> number(sb, value.toDouble())
            is Number -> number(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, v)
                }
                sb.append(']')
            }
            is IntArray -> append(sb, value.asList())
            is Array<*> -> append(sb, value.asList())
            else -> string(sb, value.toString())
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
