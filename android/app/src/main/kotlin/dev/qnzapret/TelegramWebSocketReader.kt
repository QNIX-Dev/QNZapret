package dev.qnzapret

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Downstream MTProto is a byte stream: forward progress before a whole WS frame arrives. */
internal class TelegramWebSocketReader(
    private val input: InputStream,
    private val pong: (ByteArray) -> Unit,
) {
    private var remaining = 0L
    private var mask: ByteArray? = null
    private var maskOffset = 0

    fun readBinaryChunk(): ByteArray? {
        while (true) {
            if (remaining > 0) {
                val chunk = ByteArray(minOf(remaining, CHUNK_BYTES.toLong()).toInt())
                val count = input.read(chunk)
                if (count < 0) throw EOFException("EOF within Telegram WebSocket payload")
                if (count == 0) continue
                mask?.let { key ->
                    for (i in 0 until count) chunk[i] = (chunk[i].toInt() xor key[(maskOffset + i) % 4].toInt()).toByte()
                    maskOffset = (maskOffset + count) % 4
                }
                remaining -= count
                return if (count == chunk.size) chunk else chunk.copyOf(count)
            }
            val first = input.read()
            if (first < 0) return null
            val second = byte()
            val opcode = first and 15
            val masked = second and 128 != 0
            var length = (second and 127).toLong()
            if (length == 126L) length = (byte().toLong() shl 8) or byte().toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or byte().toLong() }
            }
            if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("Invalid Telegram WebSocket frame length")
            mask = if (masked) ByteArray(4) { byte().toByte() } else null
            maskOffset = 0
            when (opcode) {
                0, 2 -> remaining = length
                8, 9, 10 -> {
                    if (first and 128 == 0 || length > 125) throw IOException("Invalid WebSocket control frame")
                    val payload = ByteArray(length.toInt()) { byte().toByte() }
                    mask?.let { key -> for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte() }
                    if (opcode == 8) return null
                    if (opcode == 9) pong(payload)
                }
                else -> throw IOException("Unsupported Telegram WebSocket opcode $opcode")
            }
        }
    }

    private fun byte(): Int = input.read().also { if (it < 0) throw EOFException("EOF within Telegram WebSocket header") }

    companion object {
        const val CHUNK_BYTES = 32 * 1024
        private const val MAX_FRAME_BYTES = 16L * 1024 * 1024
    }
}
