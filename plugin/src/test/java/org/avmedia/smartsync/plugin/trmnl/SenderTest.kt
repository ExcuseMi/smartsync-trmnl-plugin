package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.WatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SenderTest {
    private val url = "https://trmnl.com/api/custom_plugins/00000000-0000-0000-0000-000000000000"
    private val host = FakeHost()
    private val net = FakeTransport()
    private val clock = FakeClock()
    private val sender = Sender(host, net, { clock.now }, ZoneOffset.UTC)
    private val w: WatchId = host.addWatch("gshock-aabbccddeeff")

    init {
        Settings(enabled = true, sharedUrl = url, intervalMinutes = 10).save(host)
        host.values[w]!!["watch.battery"] = 80
        host.values[w]!!["health.steps_today"] = 1_000
    }

    private suspend fun change(steps: Int): Long? {
        host.values[w]!!["health.steps_today"] = steps
        sender.markDirty(w)
        return sender.flush()
    }

    @Test fun unchangedValuesSendNothing() = runTest {
        sender.markDirty(w)
        assertNull(sender.flush())
        assertEquals(1, net.posts.size)
        clock.advanceMin(60)
        repeat(5) { sender.markDirty(w); assertNull(sender.flush()) }
        assertEquals("same values, no request", 1, net.posts.size)
        assertFalse(sender.pending())
    }

    @Test fun aChangeInsideTheIntervalWaitsAndGoesOutOnceWithTheNewestValues() = runTest {
        change(1_000)
        clock.advanceMin(2)
        val next = change(1_100)
        assertEquals(clock.now + 8 * 60_000, next)
        change(1_200)
        assertEquals(1, net.posts.size)
        clock.now = next!!
        assertNull(sender.flush())
        assertEquals(2, net.posts.size)
        assertTrue(net.posts.last().second.contains("\"st\":1200"))
    }

    @Test fun theRequestIsTheDeepMergeEnvelopeWithTheWatchUnderItsShortId() = runTest {
        change(1_000)
        val body = net.posts.single().second
        assertTrue(body, body.startsWith("{\"merge_variables\":{\"w\":{\"ddeeff\":{"))
        assertTrue(body, body.endsWith(",\"merge_strategy\":\"deep_merge\"}"))
        assertEquals(url, net.posts.single().first)
    }

    @Test fun tooManyRequestsBacksOffLogsOnceAndRecovers() = runTest {
        net.answers += HttpResult(429)
        net.answers += HttpResult(429)
        val first = change(1_000)
        assertEquals(clock.now + 15 * 60_000, first)
        clock.advanceMin(5)
        assertEquals("no request while backing off", first, change(1_001))
        assertEquals(1, net.posts.size)
        clock.now = first!!
        val second = sender.flush()
        assertEquals("doubles", clock.now + 30 * 60_000, second)
        assertEquals(2, net.posts.size)
        assertEquals(1, host.logs.count { "rate limited" in it })
        assertNotNull(sender.status.failing)
        clock.now = second!!
        assertNull(sender.flush())
        assertEquals(3, net.posts.size)
        assertEquals(1, host.logs.count { "sending again" in it })
        assertNull(sender.status.failing)
    }

    @Test fun retryAfterFromTheServerWins() = runTest {
        net.answers += HttpResult(429, retryAfterSec = 3_600)
        assertEquals(clock.now + 3_600_000, change(1_000))
    }

    @Test fun noNetworkRetriesSoonServerErrorsKeepTheIntervalRefusedWaitsLonger() = runTest {
        net.answers += HttpResult(-1, message = "UnknownHostException")
        assertEquals("no network: the request never counted", clock.now + 2 * 60_000, change(1_000))
        clock.advanceMin(2)
        net.answers += HttpResult(503)
        assertEquals("reached TRMNL: the interval applies", clock.now + 10 * 60_000, sender.flush())
        clock.advanceMin(10)
        net.answers += HttpResult(404)
        assertEquals(clock.now + 60 * 60_000 * 4, sender.flush())
        assertEquals("one line per kind", 2, host.logs.size)
        assertFalse("never logs the URL", host.logs.any { "custom_plugins" in it })
    }

    @Test fun offOrWithoutUrlSendsNothing() = runTest {
        Settings(enabled = false, sharedUrl = url).save(host)
        assertNull(change(1_000))
        Settings(enabled = true, sharedUrl = "http://insecure.example/x").save(host)
        assertNull(change(1_001))
        assertTrue(net.posts.isEmpty())
        assertFalse(sender.pending())
    }

    @Test fun perWatchUrlsGoSeparatelyAndASharedUrlCarriesBothInOneRequest() = runTest {
        val w2 = host.addWatch("gshock-112233445566")
        host.values[w2]!!["watch.battery"] = 40
        sender.markDirty(w); sender.markDirty(w2)
        sender.flush()
        assertEquals(1, net.posts.size)
        assertTrue(net.posts[0].second.contains("\"ddeeff\"") && net.posts[0].second.contains("\"445566\""))

        Settings(enabled = true, mode = UrlMode.PER_WATCH, sharedUrl = url).save(host)
        Settings.saveWatchUrl(host, w, "$url-a")
        Settings.saveWatchUrl(host, w2, "$url-b")
        sender.markAllDirty()
        sender.flush()
        assertEquals(3, net.posts.size)
        assertEquals(setOf("$url-a", "$url-b"), net.posts.drop(1).map { it.first }.toSet())
    }

    /** A watch that changes every minute all day stays under TRMNL's requests per hour on every rolling hour. */
    @Test fun aDayOfConstantChangeStaysUnderTheHourlyLimit() = runTest {
        val sent = mutableListOf<Long>()
        var wake: Long? = null
        for (minute in 0 until 24 * 60) {
            host.values[w]!!["health.steps_today"] = minute
            sender.markDirty(w)
            if (wake == null || clock.now >= wake) {
                val before = net.posts.size
                wake = sender.flush()
                if (net.posts.size > before) sent += clock.now
            }
            clock.advanceMin(1)
        }
        val worst = sent.maxOf { t -> sent.count { it in t until t + 3_600_000 } }
        assertTrue("$worst requests in one hour", worst < Limits.TRMNL_REQUESTS_PER_HOUR)
        assertTrue(sent.size >= 100)
    }
}
