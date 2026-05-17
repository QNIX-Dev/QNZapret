package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramRouteDomainCodecTest {
    @Test
    fun decodesFlowsealPublicCfProxyDomains() {
        assertEquals(
            "noskomnadzor.co.uk",
            TelegramRouteDomainCodec.decodeFlowsealDomain("zaewayzmplad.com"),
        )
        assertEquals(
            "pyatdesyatodin.co.uk",
            TelegramRouteDomainCodec.decodeFlowsealDomain("dmohrsgmohcrwb.com"),
        )
    }

    @Test
    fun leavesAlreadyDecodedDomainsUntouched() {
        assertEquals(
            "custom.example.net",
            TelegramRouteDomainCodec.decodeFlowsealDomain("custom.example.net"),
        )
    }
}
