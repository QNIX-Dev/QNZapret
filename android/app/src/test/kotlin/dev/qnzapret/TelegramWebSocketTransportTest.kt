package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class TelegramWebSocketTransportTest {
    @Before
    fun setUp() {
        TelegramWebSocketTransport.clearRouteStateForTest()
    }

    @After
    fun tearDown() {
        TelegramWebSocketTransport.clearRouteStateForTest()
    }

    @Test
    fun cloudflareMediaRoutesPreferPrimaryHostsBeforeAltHosts() {
        val hosts = TelegramWebSocketTransport.routeHostsForTest(
            dcId = 2,
            mediaDc = true,
            cfDomains = listOf("slow.example", "active.example"),
            activeDomain = "active.example",
        )

        assertEquals("kws2.active.example", hosts[0])
        assertEquals("kws2.slow.example", hosts[1])
        assertEquals("kws2-1.active.example", hosts[2])
    }

    @Test
    fun failingActiveRouteDropsBehindHealthierPeer() {
        TelegramWebSocketTransport.recordRouteFailureForTest(
            host = "kws2.active.example",
            mediaDc = true,
            errorCode = "low_upload_ack",
        )

        val hosts = TelegramWebSocketTransport.routeHostsForTest(
            dcId = 2,
            mediaDc = true,
            cfDomains = listOf("slow.example", "active.example"),
            activeDomain = "active.example",
        )

        assertEquals("kws2.slow.example", hosts[0])
        assertEquals("kws2.active.example", hosts[1])
    }

    @Test
    fun publicMediaRoutesTryDirectBeforeCloudflareHosts() {
        val hosts = TelegramWebSocketTransport.routeHostsForTest(
            dcId = 2,
            mediaDc = true,
            cfDomains = listOf("a.example", "b.example", "c.example"),
            localDomainCount = 0,
        )

        assertEquals("kws2-1.web.telegram.org", hosts[0])
        assertEquals("venus.web.telegram.org", hosts[1])
        assertEquals("kws2.web.telegram.org", hosts[2])
        assertEquals("kws2.a.example", hosts[3])
    }

    @Test
    fun localMediaRoutesKeepLocalCloudflareBeforeDirect() {
        val hosts = TelegramWebSocketTransport.routeHostsForTest(
            dcId = 2,
            mediaDc = true,
            cfDomains = listOf("local.example", "public.example"),
            localDomainCount = 1,
        )

        assertEquals("kws2.local.example", hosts[0])
        assertEquals("kws2-1.local.example", hosts[1])
        assertEquals("kws2-1.web.telegram.org", hosts[2])
        assertEquals("venus.web.telegram.org", hosts[3])
        assertEquals("kws2.web.telegram.org", hosts[4])
        assertEquals("kws2.public.example", hosts[5])
    }

    @Test
    fun mediaRoutesSkipDirectFallbackWhileDirectIsCoolingDown() {
        TelegramWebSocketTransport.setDirectRouteCooldownForTest(
            dcId = 2,
            mediaDc = true,
            untilMs = 60_000L,
        )

        val hosts = TelegramWebSocketTransport.routeHostsForTest(
            dcId = 2,
            mediaDc = true,
            cfDomains = listOf("public.example"),
            localDomainCount = 0,
        )

        assertEquals("kws2.public.example", hosts[0])
        assertEquals("kws2-1.public.example", hosts[1])
    }

    @Test
    fun lowThroughputMediaSessionIsScoredAsFailure() {
        val outcome = TelegramWebSocketTransport.classifySessionResultForTest(
            mediaDc = true,
            bytesUp = 4_096,
            bytesDown = 4_096,
            durationMs = 25_000,
            success = true,
            errorCode = "client_closed",
        )

        assertEquals(false, outcome.success)
        assertEquals("low_media_throughput", outcome.errorCode)
        assertEquals(true, outcome.lowThroughput)
    }

    @Test
    fun explicitLowThroughputMediaSessionIsScoredAsFailure() {
        val outcome = TelegramWebSocketTransport.classifySessionResultForTest(
            mediaDc = true,
            bytesUp = 1_024,
            bytesDown = 0,
            durationMs = 10_000,
            success = false,
            errorCode = "low_media_throughput",
        )

        assertEquals(false, outcome.success)
        assertEquals("low_media_throughput", outcome.errorCode)
        assertEquals(true, outcome.lowThroughput)
    }

    @Test
    fun textSessionKeepsClientClosedSuccess() {
        val outcome = TelegramWebSocketTransport.classifySessionResultForTest(
            mediaDc = false,
            bytesUp = 4_096,
            bytesDown = 4_096,
            durationMs = 25_000,
            success = true,
            errorCode = "client_closed",
        )

        assertEquals(true, outcome.success)
        assertEquals("client_closed", outcome.errorCode)
        assertEquals(false, outcome.lowThroughput)
    }

    @Test
    fun fastMediaSessionKeepsSuccess() {
        val outcome = TelegramWebSocketTransport.classifySessionResultForTest(
            mediaDc = true,
            bytesUp = 16_384,
            bytesDown = 1_000_000,
            durationMs = 10_000,
            success = true,
            errorCode = "client_closed",
        )

        assertEquals(true, outcome.success)
        assertEquals("client_closed", outcome.errorCode)
        assertEquals(false, outcome.lowThroughput)
    }

    @Test
    fun uploadHeavyMediaSessionKeepsSuccess() {
        val outcome = TelegramWebSocketTransport.classifySessionResultForTest(
            mediaDc = true,
            bytesUp = 1_000_000,
            bytesDown = 4_096,
            durationMs = 25_000,
            success = true,
            errorCode = "client_closed",
        )

        assertEquals(true, outcome.success)
        assertEquals("client_closed", outcome.errorCode)
        assertEquals(false, outcome.lowThroughput)
    }
}
