package org.avmedia.smartsync.plugin.trmnl

import android.content.Context
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.CommandResult
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.DayRecord
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.HistoryRecord
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginStorage
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingsSnapshot
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchNotification
import org.avmedia.smartsync.plugin.api.WatchValues
import java.time.LocalDate
import java.time.LocalDateTime

class MemStorage : PluginStorage {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A host with stored values and history, as the app gives them; no watch, no Android. */
class FakeHost : PluginHost {
    override val context: Context get() = error("no Android context in JVM tests")
    override val appVersion = "test"
    override val apiVersion = "0.1.0"

    val paired = mutableListOf<PairedWatch>()
    val values = HashMap<WatchId, MutableMap<String, Any?>>()
    val hours = HashMap<WatchId, List<HourRecord>>()
    val heartRate = HashMap<WatchId, List<HeartRateSample>>()
    val days = HashMap<WatchId, List<DayRecord>>()
    val logs = mutableListOf<String>()
    val plugin = MemStorage()
    private val perWatch = HashMap<WatchId, MemStorage>()
    var needs: PluginNeeds? = null

    fun addWatch(key: String, name: String = "CASIO GBX-H5600", connected: Boolean = true): WatchId {
        val id = WatchId(key)
        paired += PairedWatch(id, name, setOf(Capability.STEPS, Capability.HEART_RATE, Capability.HEALTH_LOG), connected)
        values[id] = LinkedHashMap()
        return id
    }

    override fun watches(): List<PairedWatch> = paired.toList()
    override fun connected(): WatchId? = paired.firstOrNull { it.connected }?.id
    override fun current(): WatchId? = connected() ?: paired.firstOrNull()?.id
    override fun watchName(watch: WatchId): String? = paired.firstOrNull { it.id == watch }?.name
    override suspend fun notify(watch: WatchId, notification: WatchNotification, delivery: Delivery) = CommandResult.NotSupported("test")
    override suspend fun command(watch: WatchId, command: WatchCommand, delivery: Delivery) = CommandResult.NotSupported("test")
    override fun lastValues(watch: WatchId) = WatchValues(watch, values[watch].orEmpty().toMap())
    override fun settings(watch: WatchId) = SettingsSnapshot(watch, emptyMap(), null)
    override fun settingCatalog(watch: WatchId): List<SettingInfo> = emptyList()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : HistoryRecord> history(watch: WatchId, kind: DataKind<T>, range: HistoryRange): List<T> {
        val all: List<HistoryRecord> = when (kind) {
            DataKind.Hours -> hours[watch].orEmpty().filter { range.contains(it.start) }
            DataKind.HeartRate -> heartRate[watch].orEmpty().filter { range.contains(it.time) }
            DataKind.Days -> days[watch].orEmpty().filter { range.contains(it.date.atTime(12, 0)) }
            else -> emptyList()
        }
        return all as List<T>
    }

    override fun storage(): PluginStorage = plugin
    override fun storage(watch: WatchId): PluginStorage = perWatch.getOrPut(watch) { MemStorage() }
    override fun moveWatchData(from: WatchId, to: WatchId) {}
    override fun declareNeeds(needs: PluginNeeds) { this.needs = needs }
    override fun log(line: String) { logs += line }
    override fun failure(line: String, error: Throwable) { logs += "$line: ${error.javaClass.simpleName}" }

    /** A full day up to [end]: every hour's steps, a heart rate sample every 2 minutes, every value the plugin reads. */
    fun fullDay(watch: WatchId, day: LocalDate, end: LocalDateTime = day.atTime(23, 59)) {
        hours[watch] = (0..end.hour).map { HourRecord(day.atTime(it, 0), steps = 10_000 + it * 137, heartRate = 70 + it) }
        val samples = ArrayList<HeartRateSample>()
        var t = day.atStartOfDay()
        var i = 0
        while (!t.isAfter(end)) { samples += HeartRateSample(t, 48 + (i * 37) % 140); t = t.plusMinutes(2); i++ }
        heartRate[watch] = samples
        days[watch] = listOf(DayRecord(day, steps = 123_456, kcal = 2_345, activeMin = 123, walkSec = 12_345, runSec = 3_456, stillSec = 45_678, hrRest = 55, hrMin = 48, hrMax = 187))
        values[watch]!!.putAll(
            mapOf(
                "watch.name" to "CASIO GBX-H5600", "watch.connected" to true, "watch.battery" to 67, "watch.battery_level" to 4,
                "watch.temperature" to 21, "watch.face" to "Activity", "app.last_sync" to "2026-11-22T23:58:00+01:00",
                "health.steps_today" to 123_456, "health.step_goal" to 10_000, "health.kcal_today" to 2_345,
                "health.active_minutes" to 123, "health.walk_seconds" to 12_345, "health.run_seconds" to 3_456,
                "health.still_seconds" to 45_678, "health.hr_rest" to 55, "health.recorded_at" to "2026-11-22T23:58:00",
            ),
        )
    }
}

/** Records posts and answers with the next queued result (200 when none). */
class FakeTransport : WebhookTransport {
    val posts = mutableListOf<Pair<String, String>>()
    val answers = ArrayDeque<HttpResult>()
    override fun post(url: String, body: String): HttpResult {
        posts += url to body
        return answers.removeFirstOrNull() ?: HttpResult(200)
    }
}

class FakeClock(var now: Long = 1_795_400_000_000L) {
    fun advanceMin(m: Long) { now += m * 60_000 }
}
