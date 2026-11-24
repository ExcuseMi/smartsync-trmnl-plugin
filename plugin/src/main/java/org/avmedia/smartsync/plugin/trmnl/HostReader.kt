package org.avmedia.smartsync.plugin.trmnl

import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.WatchId
import java.time.LocalDateTime

/** Reads one watch's values and today's history from the host's stores (never the watch itself). */
object HostReader {
    suspend fun read(host: PluginHost, watch: WatchId, now: LocalDateTime): WatchInput {
        val paired = host.watches().firstOrNull { it.id == watch }
        val today = now.toLocalDate()
        val range = HistoryRange(today.atStartOfDay(), maxOf(now, today.atStartOfDay()))
        val day = HistoryRange.days(today, today)
        return WatchInput(
            id = watch,
            name = paired?.name ?: host.watchName(watch),
            connected = paired?.connected,
            values = host.lastValues(watch),
            today = today,
            now = now,
            hours = safe { host.history(watch, DataKind.Hours, range) },
            heartRate = safe { host.history(watch, DataKind.HeartRate, range) },
            day = safe { host.history(watch, DataKind.Days, day) }.lastOrNull(),
        )
    }

    /** A history read that fails leaves its part out of the payload; the rest still goes. */
    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
}
