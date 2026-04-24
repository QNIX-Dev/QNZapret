package dev.qnzapret

import java.nio.charset.StandardCharsets

internal object HttpHostDetector {
    fun detectHost(payload: ByteArray): String? {
        val limit = minOf(payload.size, MAX_HEADER_BYTES)
        if (limit == 0) {
            return null
        }

        val text = String(payload, 0, limit, StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n").let { if (it >= 0) it else text.length }
        val headerBlock = text.substring(0, headerEnd)
        val firstLine = headerBlock.lineSequence().firstOrNull() ?: return null

        if (!HTTP_METHODS.any { firstLine.startsWith("$it ") }) {
            return null
        }

        return headerBlock
            .lineSequence()
            .firstOrNull { line -> line.regionMatches(0, "Host:", 0, 5, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(':')
    }

    private const val MAX_HEADER_BYTES = 8192
    private val HTTP_METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH")
}

internal object TlsClientHelloDetector {
    fun detectSni(payload: ByteArray): String? {
        if (payload.size < TLS_RECORD_HEADER_LENGTH + HANDSHAKE_HEADER_LENGTH) {
            return null
        }

        if (payload.u8(0) != TLS_HANDSHAKE_RECORD || payload.u8(5) != CLIENT_HELLO) {
            return null
        }

        val recordLength = payload.u16(3)
        val recordEnd = minOf(payload.size, TLS_RECORD_HEADER_LENGTH + recordLength)
        var cursor = TLS_RECORD_HEADER_LENGTH + HANDSHAKE_HEADER_LENGTH

        cursor += 2 // legacy_version
        cursor += 32 // random
        if (cursor >= recordEnd) return null

        val sessionIdLength = payload.u8(cursor)
        cursor += 1 + sessionIdLength
        if (cursor + 2 > recordEnd) return null

        val cipherSuitesLength = payload.u16(cursor)
        cursor += 2 + cipherSuitesLength
        if (cursor >= recordEnd) return null

        val compressionMethodsLength = payload.u8(cursor)
        cursor += 1 + compressionMethodsLength
        if (cursor + 2 > recordEnd) return null

        val extensionsLength = payload.u16(cursor)
        cursor += 2
        val extensionsEnd = minOf(recordEnd, cursor + extensionsLength)

        while (cursor + 4 <= extensionsEnd) {
            val extensionType = payload.u16(cursor)
            val extensionLength = payload.u16(cursor + 2)
            cursor += 4
            val extensionEnd = cursor + extensionLength
            if (extensionEnd > extensionsEnd) {
                return null
            }

            if (extensionType == SERVER_NAME_EXTENSION) {
                return parseServerName(payload, cursor, extensionEnd)
            }

            cursor = extensionEnd
        }

        return null
    }

    private fun parseServerName(payload: ByteArray, start: Int, end: Int): String? {
        if (start + 2 > end) {
            return null
        }

        var cursor = start + 2
        while (cursor + 3 <= end) {
            val nameType = payload.u8(cursor)
            val nameLength = payload.u16(cursor + 1)
            cursor += 3

            if (cursor + nameLength > end) {
                return null
            }

            if (nameType == HOST_NAME_TYPE) {
                return String(payload, cursor, nameLength, StandardCharsets.US_ASCII)
            }

            cursor += nameLength
        }

        return null
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private const val TLS_RECORD_HEADER_LENGTH = 5
    private const val HANDSHAKE_HEADER_LENGTH = 4
    private const val TLS_HANDSHAKE_RECORD = 0x16
    private const val CLIENT_HELLO = 0x01
    private const val SERVER_NAME_EXTENSION = 0x0000
    private const val HOST_NAME_TYPE = 0
}

internal object QuicInitialDetector {
    fun isLikelyInitial(payload: ByteArray): Boolean {
        if (payload.size < MIN_QUIC_INITIAL_LENGTH) {
            return false
        }

        val firstByte = payload[0].toInt() and 0xff
        val hasLongHeader = firstByte and 0x80 != 0
        val fixedBit = firstByte and 0x40 != 0
        val packetType = firstByte and 0x30
        return hasLongHeader && fixedBit && packetType == 0x00
    }

    private const val MIN_QUIC_INITIAL_LENGTH = 7
}
