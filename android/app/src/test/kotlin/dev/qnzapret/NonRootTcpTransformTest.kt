package dev.qnzapret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NonRootTcpTransformTest {
    @Test fun telegramWebTlsPolicyOnlyMatchesItsExactDomainSuffix() {
        org.junit.Assert.assertTrue(NonRootTcpTransform.isTelegramWebTls("kws2.web.telegram.org"))
        org.junit.Assert.assertTrue(NonRootTcpTransform.isTelegramWebTls("VENUS.WEB.TELEGRAM.ORG."))
        assertFalse(NonRootTcpTransform.isTelegramWebTls("kws2.web.telegram.org.example.com"))
        assertFalse(NonRootTcpTransform.isTelegramWebTls("telegram.org"))
        assertFalse(NonRootTcpTransform.isTelegramWebTls(null))
    }
    private val fake = ResolvedStrategyAction(StrategyActionKind.FAKE, null, 3, "tls_google", byteArrayOf(9, 9, 9))
    private val split = ResolvedStrategyAction(StrategyActionKind.SPLIT, 1, 1, null, null)

    @Test fun fakeCannotContaminateHttpByteStream() {
        val original = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val writes = NonRootTcpTransform.apply(original, StrategyProtocol.HTTP, listOf(fake, split))
        assertArrayEquals(original, writes.fold(byteArrayOf()) { all, part -> all + part })
    }

    @Test fun fakeOnlyPreservesOriginalStream() {
        val original = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(original, NonRootTcpTransform.apply(original, null, listOf(fake)).single())
    }

    @Test fun tlsSplitPreservesHandshakeAndFollowingRecordsWithoutFake() {
        val original = byteArrayOf(0x16, 3, 3, 0, 4, 1, 2, 3, 4, 0x17, 3, 3, 0, 1, 0x55)
        val chunks = NonRootTcpTransform.apply(original, StrategyProtocol.TLS, listOf(fake, split))
        assertEquals(2, chunks.size)
        assertEquals(6, chunks[0].size)
        val transformed = chunks.fold(byteArrayOf()) { all, chunk -> all + chunk }
        assertArrayEquals(byteArrayOf(0x16, 3, 3, 0, 1, 1, 0x16, 3, 3, 0, 3, 2, 3, 4,
            0x17, 3, 3, 0, 1, 0x55), transformed)
    }

    @Test fun shortClientHelloKeepsSniAcrossWritesAndPreservesHandshake() {
        val host = "www.youtube.com".toByteArray()
        val names = byteArrayOf(0, (host.size + 3).toByte(), 0, 0, host.size.toByte()) + host
        val extension = byteArrayOf(0, 0, 0, names.size.toByte()) + names
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0, 0, 2, 0x13, 1, 1, 0,
            0, extension.size.toByte()) + extension
        val handshake = byteArrayOf(1, 0, 0, body.size.toByte()) + body
        val original = byteArrayOf(0x16, 3, 1, 0, handshake.size.toByte()) + handshake
        val chunks = NonRootTcpTransform.apply(original, StrategyProtocol.TLS, listOf(fake, split))
        assertEquals(3, chunks.size)
        assertFalse(chunks.any { String(it, Charsets.US_ASCII).contains("www.youtube.com") })
        val wire = chunks.fold(byteArrayOf()) { all, chunk -> all + chunk }
        assertArrayEquals(handshake, wire.copyOfRange(5, 6) + wire.copyOfRange(11, wire.size))
    }
}
