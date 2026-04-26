package dev.qnzapret

internal object TlsRecordSplitTransform {
    fun splitFirstHandshakeRecord(payload: ByteArray, firstFragmentLength: Int): ByteArray? {
        if (firstFragmentLength <= 0 || payload.size < TLS_RECORD_HEADER_LENGTH) {
            return null
        }
        if ((payload[0].toInt() and BYTE_MASK) != TLS_HANDSHAKE_CONTENT_TYPE) {
            return null
        }
        if ((payload[1].toInt() and BYTE_MASK) != TLS_MAJOR_VERSION) {
            return null
        }

        val recordLength = readUnsignedShort(payload, TLS_RECORD_LENGTH_OFFSET)
        val recordEnd = TLS_RECORD_HEADER_LENGTH + recordLength
        if (recordLength <= firstFragmentLength || recordEnd > payload.size) {
            return null
        }

        val firstLength = firstFragmentLength.coerceIn(1, recordLength - 1)
        val secondLength = recordLength - firstLength
        val result = ByteArray(payload.size + TLS_RECORD_HEADER_LENGTH)
        var offset = 0

        offset = writeHeader(
            source = payload,
            target = result,
            targetOffset = offset,
            length = firstLength,
        )
        payload.copyInto(
            destination = result,
            destinationOffset = offset,
            startIndex = TLS_RECORD_HEADER_LENGTH,
            endIndex = TLS_RECORD_HEADER_LENGTH + firstLength,
        )
        offset += firstLength

        offset = writeHeader(
            source = payload,
            target = result,
            targetOffset = offset,
            length = secondLength,
        )
        payload.copyInto(
            destination = result,
            destinationOffset = offset,
            startIndex = TLS_RECORD_HEADER_LENGTH + firstLength,
            endIndex = recordEnd,
        )
        offset += secondLength

        if (recordEnd < payload.size) {
            payload.copyInto(
                destination = result,
                destinationOffset = offset,
                startIndex = recordEnd,
                endIndex = payload.size,
            )
        }

        return result
    }

    private fun writeHeader(
        source: ByteArray,
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int {
        target[targetOffset] = source[0]
        target[targetOffset + 1] = source[1]
        target[targetOffset + 2] = source[2]
        target[targetOffset + 3] = ((length ushr 8) and BYTE_MASK).toByte()
        target[targetOffset + 4] = (length and BYTE_MASK).toByte()
        return targetOffset + TLS_RECORD_HEADER_LENGTH
    }

    private fun readUnsignedShort(source: ByteArray, offset: Int): Int {
        return ((source[offset].toInt() and BYTE_MASK) shl 8) or
            (source[offset + 1].toInt() and BYTE_MASK)
    }

    private const val TLS_HANDSHAKE_CONTENT_TYPE = 0x16
    private const val TLS_MAJOR_VERSION = 0x03
    private const val TLS_RECORD_HEADER_LENGTH = 5
    private const val TLS_RECORD_LENGTH_OFFSET = 3
    private const val BYTE_MASK = 0xff
}
