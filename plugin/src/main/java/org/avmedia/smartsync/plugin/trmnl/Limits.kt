package org.avmedia.smartsync.plugin.trmnl

/**
 * TRMNL's webhook limits for private plugins (docs.trmnl.com, "Webhooks", read 2026-11-24) and the margins this plugin
 * keeps under them. A request over the rate limit gets HTTP 429, a payload over the size limit HTTP 422.
 */
object Limits {
    /** Requests per hour per plugin, standard account (TRMNL+ allows 30). */
    const val TRMNL_REQUESTS_PER_HOUR = 12

    /** Payload size, standard account (TRMNL+ allows 10 KB), measured on the stored JSON. Read as 5000 bytes. */
    const val TRMNL_PAYLOAD_BYTES = 5_000

    /** The whole request body stays under this: 60 % of [TRMNL_PAYLOAD_BYTES]. */
    const val REQUEST_BUDGET_BYTES = 3_000

    /** Shortest gap between two requests to one URL: 10 per hour at most, under [TRMNL_REQUESTS_PER_HOUR]. */
    const val MIN_INTERVAL_MINUTES = 6

    const val DEFAULT_INTERVAL_MINUTES = 10
    const val MAX_INTERVAL_MINUTES = 240

    /** Changes that come close together go out in one request: wait this long after the first. */
    const val COALESCE_MILLIS = 60_000L

    fun clampInterval(minutes: Int): Int = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
}
