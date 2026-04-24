package dev.qnzapret

import java.net.InetAddress

internal object IpProtocolNumber {
    const val TCP = 6
    const val UDP = 17
}

internal data class Ipv4Packet(
    val sourceAddress: Int,
    val destinationAddress: Int,
    val protocol: Int,
    val headerLength: Int,
    val totalLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

internal data class UdpDatagram(
    val sourceAddress: Int,
    val destinationAddress: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

internal object IpPacketCodec {
    fun parseIpv4Packet(buffer: ByteArray, length: Int): Ipv4Packet? {
        if (length < IPV4_MIN_HEADER_LENGTH) {
            return null
        }

        val version = buffer.u8(0) ushr 4
        val headerLength = (buffer.u8(0) and 0x0f) * 4
        if (version != IPV4_VERSION || headerLength < IPV4_MIN_HEADER_LENGTH || length < headerLength) {
            return null
        }

        val totalLength = buffer.u16(2).coerceAtMost(length)
        if (totalLength < headerLength) {
            return null
        }

        return Ipv4Packet(
            sourceAddress = buffer.i32(12),
            destinationAddress = buffer.i32(16),
            protocol = buffer.u8(9),
            headerLength = headerLength,
            totalLength = totalLength,
            payloadOffset = headerLength,
            payloadLength = totalLength - headerLength,
        )
    }

    fun parseUdpDatagram(buffer: ByteArray, packet: Ipv4Packet): UdpDatagram? {
        if (packet.protocol != IpProtocolNumber.UDP || packet.payloadLength < UDP_HEADER_LENGTH) {
            return null
        }

        val udpOffset = packet.payloadOffset
        val udpLength = buffer.u16(udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpLength > packet.payloadLength) {
            return null
        }

        val payloadOffset = udpOffset + UDP_HEADER_LENGTH
        val payloadLength = udpLength - UDP_HEADER_LENGTH
        return UdpDatagram(
            sourceAddress = packet.sourceAddress,
            destinationAddress = packet.destinationAddress,
            sourcePort = buffer.u16(udpOffset),
            destinationPort = buffer.u16(udpOffset + 2),
            payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength),
        )
    }

    fun buildUdpIpv4Packet(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= MAX_UDP_PAYLOAD_SIZE) {
            "UDP payload is too large for a single IPv4 packet."
        }

        val udpLength = UDP_HEADER_LENGTH + payload.size
        val totalLength = IPV4_MIN_HEADER_LENGTH + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        packet.writeU16(2, totalLength)
        packet.writeU16(4, nextIdentification())
        packet.writeU16(6, 0)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = IpProtocolNumber.UDP.toByte()
        packet.writeI32(12, sourceAddress)
        packet.writeI32(16, destinationAddress)
        packet.writeU16(10, checksum(packet, 0, IPV4_MIN_HEADER_LENGTH))

        val udpOffset = IPV4_MIN_HEADER_LENGTH
        packet.writeU16(udpOffset, sourcePort)
        packet.writeU16(udpOffset + 2, destinationPort)
        packet.writeU16(udpOffset + 4, udpLength)
        packet.writeU16(udpOffset + 6, 0)
        payload.copyInto(packet, udpOffset + UDP_HEADER_LENGTH)

        val udpChecksum = udpChecksum(
            packet = packet,
            udpOffset = udpOffset,
            udpLength = udpLength,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
        )
        packet.writeU16(udpOffset + 6, if (udpChecksum == 0) 0xffff else udpChecksum)

        return packet
    }

    fun inetAddressFromIpv4(value: Int): InetAddress {
        return InetAddress.getByAddress(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    private fun udpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        sourceAddress: Int,
        destinationAddress: Int,
    ): Int {
        var sum = 0L
        sum += (sourceAddress ushr 16) and 0xffff
        sum += sourceAddress and 0xffff
        sum += (destinationAddress ushr 16) and 0xffff
        sum += destinationAddress and 0xffff
        sum += IpProtocolNumber.UDP
        sum += udpLength
        return checksum(packet, udpOffset, udpLength, sum)
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int, initialSum: Long = 0L): Int {
        var sum = initialSum
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            sum += buffer.u16(index).toLong()
            index += 2
        }

        if (index < end) {
            sum += (buffer.u8(index) shl 8).toLong()
        }

        while (sum ushr 16 != 0L) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }

        return sum.inv().toInt() and 0xffff
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.u16(index: Int): Int {
        return (u8(index) shl 8) or u8(index + 1)
    }

    private fun ByteArray.i32(index: Int): Int {
        return (u8(index) shl 24) or
            (u8(index + 1) shl 16) or
            (u8(index + 2) shl 8) or
            u8(index + 3)
    }

    private fun ByteArray.writeU16(index: Int, value: Int) {
        this[index] = (value ushr 8).toByte()
        this[index + 1] = value.toByte()
    }

    private fun ByteArray.writeI32(index: Int, value: Int) {
        this[index] = (value ushr 24).toByte()
        this[index + 1] = (value ushr 16).toByte()
        this[index + 2] = (value ushr 8).toByte()
        this[index + 3] = value.toByte()
    }

    @Synchronized
    private fun nextIdentification(): Int {
        identification = (identification + 1) and 0xffff
        return identification
    }

    private var identification = 0

    private const val IPV4_VERSION = 4
    private const val IPV4_MIN_HEADER_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val DEFAULT_TTL = 64
    private const val MAX_UDP_PAYLOAD_SIZE = 65_507
}
