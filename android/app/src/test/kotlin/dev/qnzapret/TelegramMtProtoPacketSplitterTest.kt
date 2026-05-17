package dev.qnzapret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramMtProtoPacketSplitterTest {
    @Test
    fun intermediateSplitterBuffersPartialPacketUntilComplete() {
        val packet = intermediatePacket(byteArrayOf(1, 2, 3, 4, 5, 6))
        val encrypted = packet.xorMask()
        val splitter = TelegramMtProtoPacketSplitter(TelegramMtProxyTransportProtocol.INTERMEDIATE)

        assertEquals(emptyList<ByteArray>(), splitter.split(packet.copyOfRange(0, 5), encrypted.copyOfRange(0, 5)))
        assertEquals(5, splitter.bufferedBytes)

        val frames = splitter.split(
            packet.copyOfRange(5, packet.size),
            encrypted.copyOfRange(5, encrypted.size),
        )

        assertEquals(1, frames.size)
        assertArrayEquals(encrypted, frames[0])
        assertEquals(0, splitter.bufferedBytes)
    }

    @Test
    fun intermediateSplitterEmitsMultipleWholePackets() {
        val first = intermediatePacket(byteArrayOf(10, 11, 12))
        val second = intermediatePacket(byteArrayOf(20, 21, 22, 23))
        val plain = first + second
        val encrypted = plain.xorMask()
        val splitter = TelegramMtProtoPacketSplitter(TelegramMtProxyTransportProtocol.INTERMEDIATE)

        val frames = splitter.split(plain, encrypted)

        assertEquals(2, frames.size)
        assertArrayEquals(encrypted.copyOfRange(0, first.size), frames[0])
        assertArrayEquals(encrypted.copyOfRange(first.size, encrypted.size), frames[1])
        assertEquals(0, splitter.bufferedBytes)
    }

    @Test
    fun paddedIntermediateSplitterUsesSameLengthHeader() {
        val packet = intermediatePacket(byteArrayOf(30, 31, 32, 33, 34))
        val encrypted = packet.xorMask()
        val splitter = TelegramMtProtoPacketSplitter(TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE)

        val frames = splitter.split(packet, encrypted)

        assertEquals(1, frames.size)
        assertArrayEquals(encrypted, frames[0])
    }

    @Test
    fun abridgedSplitterHandlesShortLengthHeader() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val packet = byteArrayOf(2) + payload
        val encrypted = packet.xorMask()
        val splitter = TelegramMtProtoPacketSplitter(TelegramMtProxyTransportProtocol.ABRIDGED)

        val frames = splitter.split(packet, encrypted)

        assertEquals(1, frames.size)
        assertArrayEquals(encrypted, frames[0])
    }

    @Test
    fun invalidHeaderDisablesSplitterAndPassesBytesThrough() {
        val splitter = TelegramMtProtoPacketSplitter(TelegramMtProxyTransportProtocol.INTERMEDIATE)
        val invalid = byteArrayOf(0, 0, 0, 0, 9)
        val encryptedInvalid = invalid.xorMask()
        val next = intermediatePacket(byteArrayOf(1, 2, 3, 4))
        val encryptedNext = next.xorMask()

        val firstFrames = splitter.split(invalid, encryptedInvalid)
        val secondFrames = splitter.split(next, encryptedNext)

        assertEquals(1, firstFrames.size)
        assertArrayEquals(encryptedInvalid, firstFrames[0])
        assertEquals(1, secondFrames.size)
        assertArrayEquals(encryptedNext, secondFrames[0])
    }

    private fun intermediatePacket(payload: ByteArray): ByteArray {
        val length = payload.size
        return byteArrayOf(
            (length and 0xff).toByte(),
            ((length ushr 8) and 0xff).toByte(),
            ((length ushr 16) and 0xff).toByte(),
            ((length ushr 24) and 0xff).toByte(),
        ) + payload
    }

    private fun ByteArray.xorMask(): ByteArray {
        return ByteArray(size) { index -> (this[index].toInt() xor 0x5a).toByte() }
    }
}
