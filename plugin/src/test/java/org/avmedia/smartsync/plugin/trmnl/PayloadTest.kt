package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.WatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PayloadTest {
    private val day = LocalDate.of(2026, 11, 22)
    private val zone = ZoneOffset.ofHours(1)
    private val late = day.atTime(23, 59)

    private suspend fun built(host: FakeHost, w: WatchId, budget: Int): WatchPayload =
        Payload.build(HostReader.read(host, w, late), late.toEpochSecond(zone), budget, zone)

    @Test fun aFullDayFromHostHistoryFitsTrmnlsLimitWithTheWholeCurve() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.fullDay(w, day)
        val p = built(host, w, Limits.REQUEST_BUDGET_BYTES - Sender.ENVELOPE_BYTES)
        val body = Sender.request(listOf(p))
        val bytes = body.toByteArray().size
        println("full day request: $bytes bytes: $body")
        assertTrue("request $bytes bytes over the budget", bytes <= Limits.REQUEST_BUDGET_BYTES)
        assertTrue(bytes < Limits.TRMNL_PAYLOAD_BYTES)
        assertEquals(15, p.curveMinutes)
        assertEquals(96, (p.body["hc"] as List<*>).size)
        assertEquals(24, (p.body["sh"] as List<*>).size)
        assertEquals("ddeeff", p.key)
        assertEquals(123_456, p.body["st"])
        assertEquals(48, p.body["hl"])
        assertEquals(187, p.body["hx"])
    }

    @Test fun fourWatchesOnOneUrlStillFitByShrinkingTheCurve() = runTest {
        val host = FakeHost()
        val ws = (1..4).map { host.addWatch("gshock-00000000000$it") }
        ws.forEach { host.fullDay(it, day) }
        val budget = (Limits.REQUEST_BUDGET_BYTES - Sender.ENVELOPE_BYTES) / ws.size
        val ps = ws.map { built(host, it, budget) }
        val bytes = Sender.request(ps).toByteArray().size
        assertTrue("request $bytes bytes", bytes <= Limits.REQUEST_BUDGET_BYTES)
        assertTrue(ps.all { it.curveMinutes in setOf(30, 60, 0) })
        assertTrue(ps.all { it.body["st"] == 123_456 })
    }

    @Test fun oversizedTextsAndTideAreCutOrDroppedNeverOverTheBudget() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff", name = "X".repeat(500))
        host.fullDay(w, day)
        host.values[w]!!["tide.levels"] = List(400) { it * 0.01 }
        host.values[w]!!["tide.next_high"] = "05:12"
        val p = built(host, w, 1_000)
        assertTrue("${p.bytes} bytes", p.bytes <= 1_000)
        assertEquals(Payload.NAME_MAX, (p.body["n"] as String).length)
    }

    @Test fun smallTideValuesPassThrough() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.values[w]!!["tide.next_high"] = "05:12"
        host.values[w]!!["tide.height_m"] = 1.4
        val td = built(host, w, 3_000).body["td"] as Map<*, *>
        assertEquals("05:12", td["next_high"])
        assertEquals(1.4, td["height_m"])
    }

    @Test fun whatTheWatchDidNotGiveIsLeftOutNotSentAsZero() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.values[w]!!["watch.battery"] = 50
        val p = built(host, w, 3_000)
        assertEquals(50, p.body["b"])
        for (k in listOf("st", "sh", "hc", "hn", "hl", "hx", "kc", "tc", "td")) assertFalse(k, p.body.containsKey(k))
    }

    @Test fun hourlyStepsKeepGapsAndFallBackToTheValues() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.values[w]!!["health.steps_by_hour"] = listOf(0, 10, null, 30)
        val sh = built(host, w, 3_000).body["sh"] as List<*>
        assertEquals(24, sh.size)
        assertEquals(listOf(0, 10, null, 30, null), sh.take(5))
    }

    @Test fun theCurveIsTheMeanPerStep() {
        val s = listOf(0 to 60, 2 to 70, 14 to 80, 15 to 100).map {
            org.avmedia.smartsync.plugin.api.HeartRateSample(day.atStartOfDay().plusMinutes(it.first.toLong()), it.second)
        }
        assertEquals(listOf(70, 100), Payload.curve(s, 15))
    }

    @Test fun theFingerprintIgnoresTheSendTime() = runTest {
        val host = FakeHost()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.fullDay(w, day)
        val a = Payload.build(HostReader.read(host, w, late), 1, 3_000, zone)
        val b = Payload.build(HostReader.read(host, w, late), 2, 3_000, zone)
        assertEquals(a.fingerprint, b.fingerprint)
        host.values[w]!!["watch.battery"] = 66
        val c = Payload.build(HostReader.read(host, w, late), 2, 3_000, zone)
        assertFalse(a.fingerprint == c.fingerprint)
    }

    @Test fun lastSyncBecomesEpochSeconds() {
        assertEquals(1_795_372_560L, Payload.epochSec("2026-11-22T19:36:00+01:00", zone))
        assertEquals(1_795_372_560L, Payload.epochSec("2026-11-22T19:36:00", zone))
        assertNull(Payload.epochSec("soon", zone))
        assertNotNull(Payload.epochSec(5L, zone))
    }
}
