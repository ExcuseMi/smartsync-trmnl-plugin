package org.avmedia.smartsync.plugin.trmnl

/** How long to wait after a failed request, by kind, doubling with each failure in a row up to a cap. */
enum class FailureKind(val baseMillis: Long, val capMillis: Long, val words: String) {
    /** HTTP 429: over TRMNL's requests per hour. */
    RATE_LIMITED(15 * MIN, 2 * HOUR, "rate limited"),

    /** Another 4xx: a wrong or deleted URL (404), no Developer edition (403), payload refused (422). */
    REFUSED(1 * HOUR, 24 * HOUR, "refused"),

    /** 5xx or no answer. */
    UNAVAILABLE(2 * MIN, 1 * HOUR, "not reachable");

    fun delayMillis(failsInARow: Int, retryAfterSec: Long?): Long {
        val n = (failsInARow - 1).coerceIn(0, 20)
        val d = minOf(capMillis, baseMillis shl n)
        val server = retryAfterSec?.takeIf { it > 0 }?.let { minOf(it, 24 * 3600L) * 1000 } ?: 0L
        return maxOf(d, server)
    }

    companion object {
        fun of(r: HttpResult): FailureKind? = when {
            r.ok -> null
            r.code == 429 -> RATE_LIMITED
            r.code in 400..499 -> REFUSED
            else -> UNAVAILABLE
        }
    }
}

private const val MIN = 60_000L
private const val HOUR = 3_600_000L
