package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TelegramGatewayProbeTest {
    private fun reply(nonce: ByteArray, keys: List<Long>): ByteArray {
        val body = ByteBuffer.allocate(48 + keys.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x05162463).put(nonce).put(ByteArray(16)).put(byteArrayOf(1, 15, 0, 0))
            .putInt(0x1cb5c415).putInt(keys.size)
        keys.forEach { body.putLong(it) }
        return ByteBuffer.allocate(24 + body.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(20 + body.capacity()).putLong(0).putLong(0).putInt(body.capacity()).put(body.array()).array()
    }

    @Test fun readsNonceMatchedPublicKeys() {
        val nonce = ByteArray(16) { it.toByte() }
        assertEquals(setOf(11L, 22L), TelegramGatewayProbe.parsePublicKeys(reply(nonce, listOf(11, 22)), nonce))
    }
    @Test(expected = IOException::class) fun rejectsWrongNonce() {
        TelegramGatewayProbe.parsePublicKeys(reply(ByteArray(16), listOf(11)), ByteArray(16) { 1 })
    }
    @Test(expected = IOException::class) fun rejectsTruncatedVector() {
        TelegramGatewayProbe.parsePublicKeys(reply(ByteArray(16), listOf(11)).dropLast(3).toByteArray(), ByteArray(16))
    }
    @Test(expected = TelegramGatewayIdentityException::class) fun mainDcReplyDoesNotValidateCdn() {
        TelegramGatewayProbe.requireCdnKeys(setOf(11, 22), setOf(22, 33))
    }
    @Test fun separateCdnKeySetPassesRouteConsistencyCheck() {
        TelegramGatewayProbe.requireCdnKeys(setOf(11), setOf(22, 33))
    }
}
