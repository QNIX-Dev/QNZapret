package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyRuntimeEngineTest {
    @Test
    fun quicInitialWithoutKnownHostStaysDirect() {
        val decision = engine().evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.UDP,
                destinationPort = 443,
                payload = quicInitialPayload(),
            ),
        )

        assertEquals(StrategyDecisionKind.DIRECT, decision.kind)
        assertEquals(StrategyProtocol.QUIC, decision.protocol)
        assertNull(decision.host)
        assertEquals("host_not_detected", decision.reason)
    }

    @Test
    fun quicInitialWithCorrelatedHostAppliesUdpFake() {
        val decision = engine().evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.UDP,
                destinationPort = 443,
                payload = quicInitialPayload(),
                knownHost = "www.google.com",
            ),
        )

        assertEquals(StrategyDecisionKind.DESYNC, decision.kind)
        assertEquals(StrategyProtocol.QUIC, decision.protocol)
        assertEquals("www.google.com", decision.host)
        assertEquals("quic-hostlist-fake", decision.ruleId)
        assertEquals(1, decision.actions.size)
        assertEquals(StrategyActionKind.UDP_FAKE, decision.actions.single().kind)
        assertNotNull(decision.actions.single().blobPayload)
        assertTrue(decision.actions.single().blobPayload!!.isNotEmpty())
    }

    private fun engine(): StrategyRuntimeEngine {
        return StrategyRuntimeEngine(
            profile = StrategyProfileCodec.defaultLightweight(),
            assets = StrategyAssetBundle(
                hostlists = mapOf(
                    "qnzapret/lists/list-google.txt" to SuffixHostlistMatcher("google.com"),
                    "qnzapret/lists/list-user.txt" to SuffixHostlistMatcher("user.example"),
                ),
                blobs = mapOf(
                    "tls_google" to byteArrayOf(1, 2, 3),
                    "quic_google" to byteArrayOf(9, 8, 7),
                ),
            ),
        )
    }

    private fun quicInitialPayload(): ByteArray {
        return byteArrayOf(0xc3.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00)
    }

    private class SuffixHostlistMatcher(
        private val suffix: String,
    ) : HostlistMatcher {
        override val path: String = suffix

        override val loadedEntryCount: Int = 1

        override fun matches(host: String?): Boolean {
            val normalized = HostNameNormalizer.normalize(host) ?: return false
            return normalized == suffix || normalized.endsWith(".$suffix")
        }
    }
}
