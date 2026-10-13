package dev.qnzapret

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

internal class TelegramGatewayIdentityException : IOException("CDN gateway returned ordinary DC keys")

/** Route consistency only. Telegram itself still authenticates the CDN's official RSA keys. */
internal object TelegramGatewayProbe {
    fun publicKeys(stream: TelegramWebSocketStream, dc: Int): Set<Long> {
        val crypto = TelegramMtProxyCrypto.createUpstream(TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE, dc, false)
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val millis = System.currentTimeMillis()
        val messageId = (((millis / 1000) shl 32) or ((millis % 1000) * 4_294_967_296L / 1000)).and(-4L)
        val payload = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(messageId).putInt(20).putInt(0xbe7e8ef1L.toInt()).put(nonce)
            .put(ByteArray(8).also { SecureRandom().nextBytes(it) }).array()
        val packet = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).put(payload).array()
        stream.setReadTimeout(3000)
        stream.writeBinary(crypto.initPayload)
        stream.writeBinary(crypto.proxyToUpstreamCipher.transform(packet))
        val response = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (response.size() < 4096) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) throw SocketTimeoutException("Gateway probe deadline")
            stream.setReadTimeout(remainingMs.coerceAtMost(3000).toInt().coerceAtLeast(1))
            val chunk = stream.readBinary() ?: throw IOException("Gateway closed before ResPQ")
            val plain = crypto.upstreamToProxyCipher.transform(chunk)
            if (response.size() + plain.size > 4096) throw IOException("Oversized ResPQ")
            response.write(plain)
            val bytes = response.toByteArray()
            if (bytes.size >= 4) {
                val size = intAt(bytes, 0)
                if (size !in 1..4092) throw IOException("Invalid ResPQ framing")
                if (bytes.size >= 4 + size) return parsePublicKeys(bytes, nonce)
            }
        }
        throw IOException("No ResPQ")
    }

    fun parsePublicKeys(packet: ByteArray, nonce: ByteArray): Set<Long> {
        if (packet.size < 72 || intAt(packet, 24) != 0x05162463 || !packet.copyOfRange(28, 44).contentEquals(nonce)) {
            throw IOException("Invalid ResPQ nonce or constructor")
        }
        val length = intAt(packet, 20)
        if (length < 0 || length > 4072 || 24 + length > packet.size) throw IOException("Invalid ResPQ length")
        val end = 24 + length
        var position = 60
        var size = packet[position++].toInt() and 255
        if (size == 254) {
            if (position + 3 > end) throw IOException("Invalid ResPQ string")
            size = (packet[position].toInt() and 255) or ((packet[position+1].toInt() and 255) shl 8) or
                ((packet[position+2].toInt() and 255) shl 16)
            position += 3
        }
        position = (position + size + 3) / 4 * 4
        if (position + 8 > end || intAt(packet, position) != 0x1cb5c415) throw IOException("Invalid ResPQ key vector")
        val count = intAt(packet, position + 4)
        if (count !in 1..32 || position + 8 + count * 8 > end) throw IOException("Invalid ResPQ keys")
        return (0 until count).map { i ->
            ByteBuffer.wrap(packet, position + 8 + i * 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
        }.toSet()
    }

    fun requireCdnKeys(cdn: Set<Long>, main: Set<Long>) {
        if (cdn.isEmpty() || main.isEmpty() || cdn.any { it in main }) throw TelegramGatewayIdentityException()
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) throw IOException("Truncated ResPQ")
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
}
