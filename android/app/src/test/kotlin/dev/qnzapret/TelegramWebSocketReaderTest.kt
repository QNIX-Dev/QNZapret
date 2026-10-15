package dev.qnzapret

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class TelegramWebSocketReaderTest {
    private fun frame(payload: ByteArray, opcode: Int = 2, masked: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(128 or opcode)
        val maskBit = if (masked) 128 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size <= 65535 -> { out.write(maskBit or 126); out.write(payload.size shr 8); out.write(payload.size) }
            else -> { out.write(maskBit or 127); for (shift in 56 downTo 0 step 8) out.write((payload.size.toLong() shr shift).toInt()) }
        }
        val key = byteArrayOf(1, 2, 3, 4)
        if (masked) out.write(key)
        out.write(if (masked) ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() } else payload)
        return out.toByteArray()
    }

    @Test fun deliversPrefixWithoutWaitingForTheRestOfLargeFrame() {
        val whole = frame(ByteArray(1024 * 1024 + 64))
        // The rest of the payload has not arrived; old readBytesExact blocked/failed here.
        val reader = TelegramWebSocketReader(ByteArrayInputStream(whole.copyOf(10 + 23))) {}
        assertEquals(23, reader.readBinaryChunk()!!.size)
    }

    @Test fun largeMediaFrameIsStreamedInBoundedChunks() {
        val payload = ByteArray(1024 * 1024 + 128) { (it % 251).toByte() }
        val reader = TelegramWebSocketReader(ByteArrayInputStream(frame(payload))) {}
        val output = ByteArrayOutputStream()
        while (true) {
            val part = reader.readBinaryChunk() ?: break
            assertTrue(part.size <= TelegramWebSocketReader.CHUNK_BYTES)
            output.write(part)
        }
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test fun maskPositionSurvivesPartialReads() {
        val payload = ByteArray(1024) { it.toByte() }
        val input = object : ByteArrayInputStream(frame(payload, masked = true)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
        }
        val reader = TelegramWebSocketReader(input) {}
        val output = ByteArrayOutputStream()
        while (true) output.write(reader.readBinaryChunk() ?: break)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test fun pingBetweenBinaryFramesDoesNotCorruptStream() {
        val pongs = mutableListOf<ByteArray>()
        val input = frame(byteArrayOf(11)) + frame(byteArrayOf(42), 9) + frame(byteArrayOf(22), 0) + frame(byteArrayOf(), 8)
        val reader = TelegramWebSocketReader(ByteArrayInputStream(input)) { pongs.add(it) }
        assertArrayEquals(byteArrayOf(11), reader.readBinaryChunk())
        assertArrayEquals(byteArrayOf(22), reader.readBinaryChunk())
        assertArrayEquals(byteArrayOf(42), pongs.single())
        assertNull(reader.readBinaryChunk())
    }

    @Test(expected = IOException::class) fun rejectsOversizedHeaderBeforePayloadAllocation() {
        val header = byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 2, 0, 0, 0)
        TelegramWebSocketReader(ByteArrayInputStream(header)) {}.readBinaryChunk()
    }
}
