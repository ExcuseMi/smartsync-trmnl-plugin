package org.avmedia.smartsync.plugin.trmnl

import org.avmedia.smartsync.plugin.api.DayRecord
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** What the host knows of one watch today, gathered by [HostReader]. */
data class WatchInput(
    val id: WatchId,
    val name: String?,
    val connected: Boolean?,
    val values: WatchValues,
    val today: LocalDate,
    val now: LocalDateTime,
    val hours: List<HourRecord> = emptyList(),
    val heartRate: List<HeartRateSample> = emptyList(),
    val day: DayRecord? = null,
)

/** One watch's payload and the heart rate curve step it was built with (0 = curve left out to fit). */
data class WatchPayload(val key: String, val body: Map<String, Any?>, val curveMinutes: Int) {
    /** Bytes of `"key":{...}` inside the request. */
    val bytes: Int get() = Json.bytes(key) + 1 + Json.bytes(body)

    /** Same for the same values: the build time [Payload.SENT_AT] is left out. */
    val fingerprint: String get() = Payload.fingerprint(body)
}

/**
 * The compact per watch payload (keys in README.md, "Payload"). A value the watch did not give is left out, never sent
 * as 0. [build] shrinks it until it fits the byte budget: a coarser heart rate curve first, then no curve, then no
 * hourly steps, then no tide.
 */
object Payload {
    const val SENT_AT = "t"
    const val NAME_MAX = 40
    const val FACE_MAX = 24
    const val TIDE_MAX_BYTES = 600

    /** Heart rate curve steps tried in turn, minutes per entry (15 min = 96 entries for a full day). */
    val CURVE_STEPS = listOf(15, 30, 60)

    /** The value keys this plugin reads ([org.avmedia.smartsync.plugin.api.PluginNeeds.values]). */
    val VALUE_KEYS = setOf(
        "watch.name", "watch.connected", "watch.battery", "watch.battery_level", "watch.temperature", "watch.face",
        "watch.face_index", "watch.step_goal", "app.last_sync", "health.steps_today", "health.step_goal",
        "health.steps_by_hour", "health.kcal_today", "health.active_minutes", "health.walk_seconds",
        "health.run_seconds", "health.still_seconds", "health.hr_rest", "health.hr_min", "health.hr_max",
        "health.recorded_at",
    )

    /** Short id of a watch in the payload: the last 6 letters or digits of its key ("gshock-aabbccddeeff" gives "ddeeff"). */
    fun shortId(id: WatchId): String = id.key.filter { it.isLetterOrDigit() }.lowercase().takeLast(6).ifEmpty { "watch" }

    fun build(input: WatchInput, sentAtEpochSec: Long, budgetBytes: Int, zone: ZoneId = ZoneId.systemDefault()): WatchPayload {
        val key = shortId(input.id)
                var last: WatchPayload? = null
        for (step in CURVE_STEPS + 0) {
            val p = WatchPayload(key, body(input, sentAtEpochSec, step, zone, hours = true, tide = true), step)
            if (p.bytes <= budgetBytes) return p
            last = p
        }
        for ((hours, tide) in listOf(false to true, false to false)) {
            val p = WatchPayload(key, body(input, sentAtEpochSec, 0, zone, hours, tide), 0)
            if (p.bytes <= budgetBytes) return p
            last = p
        }
        return last!!
    }

    fun fingerprint(body: Map<String, Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Json.write(body - SENT_AT).toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun body(i: WatchInput, sentAt: Long, curveMinutes: Int, zone: ZoneId, hours: Boolean, tide: Boolean): Map<String, Any?> {
        val v = i.values
        val out = LinkedHashMap<String, Any?>()
        fun put(k: String, value: Any?) { if (value != null) out[k] = value }

        put("n", (i.name ?: v["watch.name"] as? String)?.take(NAME_MAX))
        put(SENT_AT, sentAt)
        put("c", i.connected ?: v["watch.connected"] as? Boolean)
        put("b", int(v["watch.battery"]))
        put("bl", int(v["watch.battery_level"]))
        put("tc", int(v["watch.temperature"]))
        put("ls", epochSec(v["app.last_sync"], zone))
        put("f", (v["watch.face"] as? String)?.take(FACE_MAX) ?: int(v["watch.face_index"]))

        val day = i.day?.takeIf { it.date == i.today }
        val sh = if (hours) stepsByHour(i) else null
        put("st", int(v["health.steps_today"]) ?: day?.steps ?: sh?.filterNotNull()?.takeIf { it.isNotEmpty() }?.sum())
        put("sg", int(v["health.step_goal"]) ?: int(v["watch.step_goal"]))
        put("sh", sh)
        put("kc", int(v["health.kcal_today"]) ?: day?.kcal)
        put("am", int(v["health.active_minutes"]) ?: day?.activeMin)
        put("wm", minutes(v["health.walk_seconds"]) ?: day?.walkSec?.div(60))
        put("rm", minutes(v["health.run_seconds"]) ?: day?.runSec?.div(60))
        put("sm", minutes(v["health.still_seconds"]) ?: day?.stillSec?.div(60))

        val hr = i.heartRate.filter { it.time.toLocalDate() == i.today && it.bpm > 0 }.sortedBy { it.time }
        put("hn", hr.lastOrNull()?.bpm)
        put("ht", hr.lastOrNull()?.time?.let { "%02d:%02d".format(it.hour, it.minute) })
        put("hl", hr.minOfOrNull { it.bpm } ?: int(v["health.hr_min"]) ?: day?.hrMin)
        put("hx", hr.maxOfOrNull { it.bpm } ?: int(v["health.hr_max"]) ?: day?.hrMax)
        put("hrr", int(v["health.hr_rest"]) ?: day?.hrRest)
        if (curveMinutes > 0 && hr.isNotEmpty()) {
            put("hc", curve(hr, curveMinutes))
            put("hm", curveMinutes)
        }
        put("ra", v["health.recorded_at"] as? String)
        if (tide) put("td", tide(v))
        return out
    }

    /** 24 entries, hour of the day; null where the watch gave nothing (or the hour is still to come). */
    internal fun stepsByHour(i: WatchInput): List<Int?>? {
        val today = i.hours.filter { it.start.toLocalDate() == i.today }
        if (today.isNotEmpty()) {
            val slots = arrayOfNulls<Int>(24)
            for (h in today) h.steps?.let { slots[h.start.hour] = (slots[h.start.hour] ?: 0) + it }
            return slots.toList()
        }
        val fromValues = (i.values["health.steps_by_hour"] as? List<*>)?.map { int(it) } ?: return null
        return (fromValues + List(24) { null }).take(24)
    }

    /** Mean bpm per [minutes] from 00:00, trailing empty entries cut. */
    internal fun curve(samples: List<HeartRateSample>, minutes: Int): List<Int?> {
        val n = 1440 / minutes
        val sum = IntArray(n)
        val count = IntArray(n)
        for (s in samples) {
            val idx = (s.time.hour * 60 + s.time.minute) / minutes
            if (idx in 0 until n) { sum[idx] += s.bpm; count[idx]++ }
        }
        val out = List(n) { if (count[it] == 0) null else (sum[it].toDouble() / count[it]).roundToInt() }
        return out.dropLastWhile { it == null }
    }

    /** Values under "tide." passed through (numbers, short texts, lists of numbers), when the host has them. */
    private fun tide(v: WatchValues): Map<String, Any?>? {
        val t = v.values.filterKeys { it.startsWith("tide.") }.mapKeys { it.key.removePrefix("tide.") }
            .filterValues { it is Number || it is Boolean || (it is String && it.length <= 32) || (it is List<*> && it.all { e -> e == null || e is Number }) }
        if (t.isEmpty()) return null
        return t.takeIf { Json.bytes(it) <= TIDE_MAX_BYTES }
    }

    internal fun int(x: Any?): Int? = when (x) {
        is Int -> x
        is Long -> x.toInt()
        is Number -> x.toDouble().takeIf { !it.isNaN() }?.roundToInt()
        is String -> x.trim().toDoubleOrNull()?.roundToInt()
        else -> null
    }

    private fun minutes(x: Any?): Int? = int(x)?.div(60)

    /** "2026-11-22T19:36:00+01:00" (or without offset, read in [zone]) to epoch seconds. */
    internal fun epochSec(x: Any?, zone: ZoneId): Long? {
        val s = (x as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return (x as? Number)?.toLong()
        return runCatching { OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atZone(zone).toEpochSecond() }.getOrNull()
    }
}
