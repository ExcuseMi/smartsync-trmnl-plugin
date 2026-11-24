package org.avmedia.smartsync.plugin.trmnl

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How a POST ended. [code] -1: no answer (no network, timeout, DNS). */
data class HttpResult(val code: Int, val retryAfterSec: Long? = null, val message: String? = null) {
    val ok: Boolean get() = code in 200..299
}

fun interface WebhookTransport {
    /** Posts [body] (JSON) to [url]. Blocking; called off the main thread. Never throws. */
    fun post(url: String, body: String): HttpResult
}

/** The real transport: HttpURLConnection, 15 s timeouts. */
object HttpTransport : WebhookTransport {
    override fun post(url: String, body: String): HttpResult {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "smartsync-trmnl-plugin")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val text = if (code in 200..299) null else runCatching {
                c.errorStream?.bufferedReader()?.use { it.readText().take(300) }
            }.getOrNull()
            HttpResult(code, retry, text?.let(::message))
        } catch (e: IOException) {
            HttpResult(-1, message = e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            HttpResult(-1, message = e.javaClass.simpleName)
        } finally {
            c?.disconnect()
        }
    }

    /** The "message" field of TRMNL's error JSON, else nothing (an error page is not worth a log line). */
    internal fun message(text: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"]{1,160})\"").find(text)?.groupValues?.get(1)
}
