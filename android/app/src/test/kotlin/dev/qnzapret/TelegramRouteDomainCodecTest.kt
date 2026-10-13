package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun publicListIgnoresCommentsDuplicatesAndUnsafeEntries() {
        assertEquals(listOf("noskomnadzor.co.uk", "custom.example.net"),
            TelegramRouteDomainCodec.decodeList("""
                # Public route seed
                zaewayzmplad.com
                custom.example.net
                CUSTOM.EXAMPLE.NET
                https://not-a-base-domain.example/path
                ../../local-file
                bad_domain.example
            """.trimIndent()))
    }

    @Test
    fun bundledRouteSeedDecodesForOfflineFirstLaunch() {
        val file = java.io.File("../../runtime/assets/qnzapret/telegram/cfproxy-domains.txt")
            .takeIf { it.exists() } ?: java.io.File("../runtime/assets/qnzapret/telegram/cfproxy-domains.txt")
        val domains = TelegramRouteDomainCodec.decodeList(file.readText())
        assertTrue("Expected at least three usable bootstrap routes", domains.size >= 3)
        assertEquals(domains.size, domains.distinct().size)
    }
}
