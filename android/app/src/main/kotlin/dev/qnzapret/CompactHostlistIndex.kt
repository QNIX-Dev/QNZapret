package dev.qnzapret

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Точное сравнение UTF-8, без probabilistic hash/Bloom false positives. */
internal class CompactHostlistIndex private constructor(private val data: ByteBuffer) {
    val size: Int
    private val payloadOffset: Int

    init {
        require(data.capacity() >= 16) { "Incomplete hostlist index" }
        val magic = byteArrayOf(81, 78, 90, 72, 76, 49, 0, 0)
        require(magic.indices.all { data.get(it) == magic[it] }) { "Invalid hostlist index" }
        data.order(ByteOrder.LITTLE_ENDIAN)
        size = data.getInt(8)
        require(size in 0..2_000_000) { "Invalid hostlist entry count" }
        payloadOffset = 12 + (size + 1) * 4
        require(payloadOffset <= data.capacity()) { "Incomplete hostlist offsets" }
        require(data.getInt(12) == 0 &&
            data.getInt(12 + size * 4) == data.capacity() - payloadOffset) { "Invalid hostlist bounds" }
    }

    fun matches(host: String): Boolean {
        var candidate = host
        while (true) {
            if (containsExact(candidate.toByteArray(Charsets.UTF_8))) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun containsExact(target: ByteArray): Boolean {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val start = payloadOffset + data.getInt(12 + middle * 4)
            val end = payloadOffset + data.getInt(12 + (middle + 1) * 4)
            require(start in payloadOffset..data.capacity() && end in start..data.capacity())
            var comparison = 0
            var offset = 0
            while (offset < end - start && offset < target.size) {
                comparison = (data.get(start + offset).toInt() and 255) - (target[offset].toInt() and 255)
                if (comparison != 0) break
                offset++
            }
            if (comparison == 0) comparison = (end - start) - target.size
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        fun open(file: File): CompactHostlistIndex = FileInputStream(file).channel.use { channel ->
            require(channel.size() in 16..128L * 1024 * 1024)
            CompactHostlistIndex(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
        }

        internal fun fromBytes(bytes: ByteArray): CompactHostlistIndex = CompactHostlistIndex(ByteBuffer.wrap(bytes))
    }
}
