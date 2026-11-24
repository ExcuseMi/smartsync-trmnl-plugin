package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginStorage
import org.avmedia.smartsync.plugin.api.WatchId
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What the settings screen shows. */
data class SendStatus(val lastSentMillis: Long? = null, val failing: String? = null, val nextTryMillis: Long? = null)

/**
 * Sends changed watch payloads to their webhook URLs. One request per URL carries every changed watch of that URL;
 * unchanged values send nothing; requests to one URL keep the minimum interval; failures back off ([FailureKind]) and
 * are logged once per kind. [flush] never waits on a gate: it returns when to try again.
 */
class Sender(
    private val host: PluginHost,
    private val transport: WebhookTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dirty = LinkedHashSet<WatchId>()

    var status = SendStatus()
        private set

    @Synchronized fun markDirty(watch: WatchId) { dirty += watch }

    @Synchronized fun markAllDirty() { dirty += host.watches().map { it.id } }

    @Synchronized fun pending(): Boolean = dirty.isNotEmpty()

    /** Sends what is due. Returns the time (epoch ms) to call again, null when nothing waits. */
    suspend fun flush(): Long? {
        val settings = Settings.load(host)
        val waiting = synchronized(this) { dirty.toSet() }
        if (!settings.enabled) {
            synchronized(this) { dirty.clear() }
            return null
        }
        val groups = LinkedHashMap<String, MutableList<WatchId>>()
        for (w in host.watches()) settings.urlFor(host, w.id)?.let { groups.getOrPut(it) { mutableListOf() } += w.id }
        val routed = groups.values.flatten().toSet()
        synchronized(this) { dirty.retainAll(routed) }

        var next: Long? = null
        for ((url, watches) in groups) {
            val due = watches.filter { it in waiting }
            if (due.isEmpty()) continue
            val at = sendOne(url, watches.size, due, settings)
            if (at != null) next = minOf(next ?: at, at)
        }
        val now = clock()
        val left = synchronized(this) { dirty.isNotEmpty() }
        if (left && next == null) next = now + Limits.COALESCE_MILLIS
        status = status.copy(nextTryMillis = next)
        return next
    }

    /** Null when done (sent or nothing to send), else when to try again. */
    private suspend fun sendOne(url: String, watchesOnUrl: Int, due: List<WatchId>, settings: Settings): Long? {
        val gate = Gate(host.storage(), urlId(url))
        val now = clock()
        val allowed = gate.nextAllowed(settings.intervalMillis)
        if (now < allowed) return allowed

        val budget = (Limits.REQUEST_BUDGET_BYTES - ENVELOPE_BYTES) / watchesOnUrl.coerceAtLeast(1)
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val built = due.map { it to Payload.build(HostReader.read(host, it, local), now / 1000, budget, zone) }
        val changed = built.filter { (w, p) -> host.storage(w).get(sentKey(url)) != p.fingerprint }
        if (changed.isEmpty()) {
            synchronized(this) { dirty.removeAll(due.toSet()) }
            return null
        }
        val body = request(changed.map { it.second })
        val result = withContext(Dispatchers.IO) { transport.post(url, body) }
        val kind = FailureKind.of(result)
        if (kind == null) {
            gate.succeeded(now)?.let { host.log("TRMNL: sending again after $it failed requests") }
            for ((w, p) in changed) host.storage(w).put(sentKey(url), p.fingerprint)
            synchronized(this) { dirty.removeAll(due.toSet()) }
            status = SendStatus(lastSentMillis = now)
            return null
        }
        gate.failed(now, kind, result.retryAfterSec, reachedServer = result.code > 0)
        val until = gate.nextAllowed(settings.intervalMillis)
        if (gate.firstOfKind) {
            val why = result.message?.let { ": $it" }.orEmpty()
            val code = if (result.code > 0) "HTTP ${result.code}" else "no answer"
            host.log("TRMNL: ${kind.words} ($code$why), next try in ${(until - now) / 60_000} min")
        }
        status = status.copy(failing = kind.words)
        return until
    }

    companion object {
        /** `{"merge_variables":{"w":{}},"merge_strategy":"deep_merge"}` plus a margin. */
        val ENVELOPE_BYTES = Json.bytes(envelope(emptyMap())) + 16

        fun request(payloads: List<WatchPayload>): String =
            Json.write(envelope(payloads.associate { it.key to it.body }))

        private fun envelope(watches: Map<String, Any?>): Map<String, Any?> =
            linkedMapOf("merge_variables" to linkedMapOf("w" to watches), "merge_strategy" to "deep_merge")

        /** A URL's id for storage keys: the URL itself is a secret. */
        fun urlId(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        fun sentKey(url: String): String = "sent.${urlId(url)}"
    }
}

/** Interval and backoff of one URL, kept in plugin storage (survives an app restart). */
internal class Gate(private val s: PluginStorage, id: String) {
    private val kLast = "u.$id.last"
    private val kUntil = "u.$id.until"
    private val kFails = "u.$id.fails"
    private val kKind = "u.$id.kind"

    /** Set by [failed]: this failure's kind differs from the one before (log it). */
    var firstOfKind = false
        private set

    fun nextAllowed(intervalMillis: Long): Long {
        val last = s.get(kLast)?.toLongOrNull() ?: 0L
        val until = s.get(kUntil)?.toLongOrNull() ?: 0L
        return maxOf(if (last > 0) last + intervalMillis else 0L, until)
    }

    /** Returns the failures before this success, null when there were none. */
    fun succeeded(now: Long): Int? {
        val fails = s.get(kFails)?.toIntOrNull() ?: 0
        s.put(kLast, now.toString())
        s.delete(kUntil); s.delete(kFails); s.delete(kKind)
        return fails.takeIf { it > 0 }
    }

    /**
     * Records a failure and when the next request may go. A request that reached the server counts against TRMNL's limit too, so
     * the interval applies after it as well; one that found no network does not.
     */
    fun failed(now: Long, kind: FailureKind, retryAfterSec: Long?, reachedServer: Boolean) {
        val fails = (s.get(kFails)?.toIntOrNull() ?: 0) + 1
        firstOfKind = s.get(kKind) != kind.name
        val until = now + kind.delayMillis(fails, retryAfterSec)
        if (reachedServer) s.put(kLast, now.toString())
        s.put(kFails, fails.toString())
        s.put(kKind, kind.name)
        s.put(kUntil, until.toString())
    }
}
