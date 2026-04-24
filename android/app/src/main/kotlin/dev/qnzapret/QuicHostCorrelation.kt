package dev.qnzapret

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal data class DnsAddressAnswer(
    val host: String,
    val address: InetAddress,
    val ttlSeconds: Long,
)

internal object DnsMessageParser {
    fun parseAddressAnswers(payload: ByteArray): List<DnsAddressAnswer> {
        if (payload.size < DNS_HEADER_LENGTH) {
            return emptyList()
        }

        val flags = payload.u16(DNS_FLAGS_OFFSET)
        if (flags and DNS_RESPONSE_FLAG == 0) {
            return emptyList()
        }

        val questionCount = payload.u16(DNS_QUESTION_COUNT_OFFSET)
        val answerCount = payload.u16(DNS_ANSWER_COUNT_OFFSET)
        var cursor = DNS_HEADER_LENGTH
        val questions = mutableListOf<String>()

        repeat(questionCount) {
            val questionName = readName(payload, cursor) ?: return emptyList()
            questions += questionName.name
            cursor = questionName.nextOffset
            if (cursor + DNS_QUESTION_TRAILER_LENGTH > payload.size) {
                return emptyList()
            }
            cursor += DNS_QUESTION_TRAILER_LENGTH
        }

        val addressRecords = mutableListOf<AddressRecord>()
        val cnameRecords = mutableMapOf<String, String>()

        repeat(answerCount) {
            val answerName = readName(payload, cursor) ?: return@repeat
            cursor = answerName.nextOffset
            if (cursor + DNS_RESOURCE_RECORD_HEADER_LENGTH > payload.size) {
                return@repeat
            }

            val type = payload.u16(cursor)
            val ttlSeconds = payload.u32(cursor + DNS_TTL_OFFSET_IN_RECORD)
            val dataLength = payload.u16(cursor + DNS_RDATA_LENGTH_OFFSET_IN_RECORD)
            val dataOffset = cursor + DNS_RESOURCE_RECORD_HEADER_LENGTH
            val nextRecord = dataOffset + dataLength
            if (nextRecord > payload.size) {
                return@repeat
            }

            when (type) {
                DNS_TYPE_A -> if (dataLength == IPV4_ADDRESS_BYTES) {
                    addressRecords += AddressRecord(
                        host = answerName.name,
                        address = InetAddress.getByAddress(payload.copyOfRange(dataOffset, nextRecord)),
                        ttlSeconds = ttlSeconds,
                    )
                }
                DNS_TYPE_AAAA -> if (dataLength == IPV6_ADDRESS_BYTES) {
                    addressRecords += AddressRecord(
                        host = answerName.name,
                        address = InetAddress.getByAddress(payload.copyOfRange(dataOffset, nextRecord)),
                        ttlSeconds = ttlSeconds,
                    )
                }
                DNS_TYPE_CNAME -> {
                    val canonicalName = readName(payload, dataOffset)?.name
                    if (canonicalName != null) {
                        cnameRecords[answerName.name] = canonicalName
                    }
                }
            }

            cursor = nextRecord
        }

        return addressRecords.mapNotNull { record ->
            val preferredHost = preferredHostForRecord(record.host, questions, cnameRecords)
            val normalizedHost = HostNameNormalizer.normalize(preferredHost) ?: return@mapNotNull null
            DnsAddressAnswer(
                host = normalizedHost,
                address = record.address,
                ttlSeconds = record.ttlSeconds,
            )
        }
    }

    private fun preferredHostForRecord(
        recordHost: String,
        questions: List<String>,
        cnameRecords: Map<String, String>,
    ): String {
        questions.forEach { question ->
            var current = question
            repeat(MAX_CNAME_CHAIN_LENGTH) {
                if (current == recordHost) {
                    return question
                }
                current = cnameRecords[current] ?: return@forEach
            }
        }
        return recordHost
    }

    private fun readName(payload: ByteArray, offset: Int): DnsName? {
        var cursor = offset
        var nextOffset = -1
        var jumped = false
        var jumpCount = 0
        val visitedOffsets = mutableSetOf<Int>()
        val labels = mutableListOf<String>()

        while (cursor < payload.size) {
            if (!visitedOffsets.add(cursor)) {
                return null
            }

            val length = payload.u8(cursor)
            when {
                length == 0 -> {
                    cursor += 1
                    if (!jumped) {
                        nextOffset = cursor
                    }
                    return DnsName(
                        name = labels.joinToString("."),
                        nextOffset = nextOffset,
                    )
                }
                length and DNS_POINTER_MASK == DNS_POINTER_MASK -> {
                    if (cursor + 1 >= payload.size || jumpCount >= MAX_DNS_NAME_JUMPS) {
                        return null
                    }
                    val pointerOffset = ((length and DNS_POINTER_VALUE_MASK) shl 8) or payload.u8(cursor + 1)
                    if (pointerOffset >= payload.size) {
                        return null
                    }
                    if (!jumped) {
                        nextOffset = cursor + DNS_POINTER_LENGTH
                    }
                    cursor = pointerOffset
                    jumped = true
                    jumpCount += 1
                }
                length and DNS_POINTER_MASK != 0 -> return null
                else -> {
                    cursor += 1
                    if (cursor + length > payload.size) {
                        return null
                    }
                    labels += String(payload, cursor, length, StandardCharsets.US_ASCII)
                    cursor += length
                }
            }
        }

        return null
    }

    private data class DnsName(
        val name: String,
        val nextOffset: Int,
    )

    private data class AddressRecord(
        val host: String,
        val address: InetAddress,
        val ttlSeconds: Long,
    )

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u32(index: Int): Long {
        return ((u8(index).toLong() shl 24) or
            (u8(index + 1).toLong() shl 16) or
            (u8(index + 2).toLong() shl 8) or
            u8(index + 3).toLong()) and 0xffff_ffffL
    }

    private const val DNS_HEADER_LENGTH = 12
    private const val DNS_FLAGS_OFFSET = 2
    private const val DNS_QUESTION_COUNT_OFFSET = 4
    private const val DNS_ANSWER_COUNT_OFFSET = 6
    private const val DNS_RESPONSE_FLAG = 0x8000
    private const val DNS_QUESTION_TRAILER_LENGTH = 4
    private const val DNS_RESOURCE_RECORD_HEADER_LENGTH = 10
    private const val DNS_TTL_OFFSET_IN_RECORD = 4
    private const val DNS_RDATA_LENGTH_OFFSET_IN_RECORD = 8
    private const val DNS_TYPE_A = 1
    private const val DNS_TYPE_CNAME = 5
    private const val DNS_TYPE_AAAA = 28
    private const val IPV4_ADDRESS_BYTES = 4
    private const val IPV6_ADDRESS_BYTES = 16
    private const val DNS_POINTER_MASK = 0xc0
    private const val DNS_POINTER_VALUE_MASK = 0x3f
    private const val DNS_POINTER_LENGTH = 2
    private const val MAX_DNS_NAME_JUMPS = 16
    private const val MAX_CNAME_CHAIN_LENGTH = 8
}

internal class QuicHostCorrelation {
    private val hostsByAddress = ConcurrentHashMap<InetAddress, HostEntry>()

    fun rememberHost(
        address: InetAddress,
        host: String?,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_CORRELATION_TTL_MS,
    ) {
        val normalizedHost = HostNameNormalizer.normalize(host) ?: return
        if (ttlMs <= 0) {
            return
        }

        hostsByAddress[address] = HostEntry(
            host = normalizedHost,
            expiresAt = now + ttlMs,
        )
    }

    fun observeDnsResponse(payload: ByteArray, now: Long = System.currentTimeMillis()) {
        DnsMessageParser.parseAddressAnswers(payload).forEach { answer ->
            rememberHost(
                address = answer.address,
                host = answer.host,
                now = now,
                ttlMs = dnsTtlMs(answer.ttlSeconds),
            )
        }
    }

    fun lookupHost(address: InetAddress, now: Long = System.currentTimeMillis()): String? {
        val entry = hostsByAddress[address] ?: return null
        if (entry.expiresAt <= now) {
            hostsByAddress.remove(address, entry)
            return null
        }

        return entry.host
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        hostsByAddress.entries.removeIf { (_, entry) -> entry.expiresAt <= now }
    }

    private fun dnsTtlMs(ttlSeconds: Long): Long {
        if (ttlSeconds <= 0) {
            return 0
        }

        return ttlSeconds
            .coerceAtMost(MAX_DNS_TTL_SECONDS)
            .times(1_000L)
    }

    private data class HostEntry(
        val host: String,
        val expiresAt: Long,
    )

    private companion object {
        private const val DEFAULT_CORRELATION_TTL_MS = 300_000L
        private const val MAX_DNS_TTL_SECONDS = 1_800L
    }
}
