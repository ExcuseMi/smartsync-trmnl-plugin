package org.avmedia.smartsync.plugin.trmnl

import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.WatchId

/** One webhook URL for all watches (each watch under its own key in the payload) or one per watch. */
enum class UrlMode(val wire: String) { SHARED("shared"), PER_WATCH("per_watch") }

/** The plugin's settings, in the host's plugin storage. URLs hold the plugin's secret id: never logged. */
data class Settings(
    val enabled: Boolean = false,
    val mode: UrlMode = UrlMode.SHARED,
    val sharedUrl: String = "",
    val intervalMinutes: Int = Limits.DEFAULT_INTERVAL_MINUTES,
) {
    val intervalMillis: Long get() = Limits.clampInterval(intervalMinutes) * 60_000L

    /** The URL [watch] posts to, null for none. */
    fun urlFor(host: PluginHost, watch: WatchId): String? {
        val u = when (mode) {
            UrlMode.SHARED -> sharedUrl
            UrlMode.PER_WATCH -> host.storage(watch).get(KEY_URL).orEmpty()
        }
        return u.trim().takeIf { validUrl(it) }
    }

    fun save(host: PluginHost) {
        val s = host.storage()
        s.put(KEY_ENABLED, enabled.toString())
        s.put(KEY_MODE, mode.wire)
        s.put(KEY_URL, sharedUrl.trim())
        s.put(KEY_INTERVAL, Limits.clampInterval(intervalMinutes).toString())
    }

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "mode"
        const val KEY_URL = "url"
        const val KEY_INTERVAL = "interval_min"

        fun load(host: PluginHost): Settings {
            val s = host.storage()
            return Settings(
                enabled = s.get(KEY_ENABLED) == "true",
                mode = UrlMode.entries.firstOrNull { it.wire == s.get(KEY_MODE) } ?: UrlMode.SHARED,
                sharedUrl = s.get(KEY_URL).orEmpty(),
                intervalMinutes = Limits.clampInterval(s.get(KEY_INTERVAL)?.toIntOrNull() ?: Limits.DEFAULT_INTERVAL_MINUTES),
            )
        }

        fun watchUrl(host: PluginHost, watch: WatchId): String = host.storage(watch).get(KEY_URL).orEmpty()

        fun saveWatchUrl(host: PluginHost, watch: WatchId, url: String) {
            if (url.isBlank()) host.storage(watch).delete(KEY_URL) else host.storage(watch).put(KEY_URL, url.trim())
        }

        /** https, no spaces, a sane length (TRMNL's is https://trmnl.com/api/custom_plugins/<uuid>; BYOS servers differ). */
        fun validUrl(u: String): Boolean =
            u.startsWith("https://") && u.length in 12..300 && u.none { it.isWhitespace() } && u.indexOf('/', 8) != 8
    }
}
