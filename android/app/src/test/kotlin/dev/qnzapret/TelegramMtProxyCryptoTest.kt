package dev.qnzapret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class TelegramMtProxyCryptoTest {
    @Test
    fun acceptsDdSecretHandshakeAndContinuesClientCipher() {
        val secretHex = "00112233445566778899aabbccddeeff"
        val secret = TelegramMtProxyCrypto.secretHexToBytes(secretHex)
        val init = ByteArray(64) { index -> (index + 1).toByte() }
        TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE.marker.copyInto(init, destinationOffset = 56)
        init[60] = 0xfe.toByte()
        init[61] = 0xff.toByte()
        init[62] = 0x11
        init[63] = 0x22

        val clientCipher = TelegramCtrCipher.create(
            key = sha256(init.copyOfRange(8, 40) + secret),
            iv = init.copyOfRange(40, 56),
        )
        val encryptedInit = clientCipher.transform(init)
        val finalInit = init.copyOf()
        encryptedInit.copyInto(finalInit, destinationOffset = 56, startIndex = 56, endIndex = 64)

        val handshake = TelegramMtProxyCrypto.acceptClient(finalInit, secretHex)

        assertEquals(2, handshake.dcId)
        assertEquals(-2, handshake.rawDcId)
        assertEquals(true, handshake.mediaDc)
        assertEquals(TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE, handshake.protocol)

        val plainPayload = "hello".toByteArray()
        val encryptedPayload = clientCipher.transform(plainPayload)
        assertArrayEquals(plainPayload, handshake.clientToProxyCipher.transform(encryptedPayload))
    }

    @Test
    fun createsUpstreamInitForChosenProtocol() {
        val upstream = TelegramMtProxyCrypto.createUpstream(
            protocol = TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE,
            dcId = 4,
            mediaDc = true,
        )

        assertEquals(64, upstream.initPayload.size)
        val initDecryptor = TelegramCtrCipher.create(
            key = upstream.initPayload.copyOfRange(8, 40),
            iv = upstream.initPayload.copyOfRange(40, 56),
        )
        val decryptedInit = initDecryptor.transform(upstream.initPayload)
        assertArrayEquals(
            TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE.marker,
            decryptedInit.copyOfRange(56, 60),
        )
        assertEquals(0xfc.toByte(), decryptedInit[60])
        assertEquals(0xff.toByte(), decryptedInit[61])

        val encryptedPayload = upstream.proxyToUpstreamCipher.transform("ping".toByteArray())

        assertEquals(4, encryptedPayload.size)
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
}
