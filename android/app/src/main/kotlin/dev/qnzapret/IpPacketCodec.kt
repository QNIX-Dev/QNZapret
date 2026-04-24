package dev.qnzapret

import java.net.InetAddress

internal enum class IpVersion {
    IPV4,
    IPV6,
}

internal object IpProtocolNumber {
    const val TCP = 6
    const val UDP = 17
}

internal data class IpPacket(
    val version: IpVersion,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val protocol: Int,
    val headerLength: Int,
    val totalLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

internal data class UdpDatagram(
    val ipVersion: IpVersion,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

internal object IpPacketCodec {
    fun parseIpPacket(buffer: ByteArray, length: Int): IpPacket? {
        if (length == 0) {
            return null
        }

        return when (buffer.u8(0) ushr 4) {
            IPV4_VERSION -> parseIpv4Packet(buffer, length)
            IPV6_VERSION -> parseIpv6Packet(buffer, length)
            else -> null
        }
    }

    fun parseUdpDatagram(buffer: ByteArray, packet: IpPacket): UdpDatagram? {
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
            ipVersion = packet.version,
            sourceAddress = packet.sourceAddress,
            destinationAddress = packet.destinationAddress,
            sourcePort = buffer.u16(udpOffset),
            destinationPort = buffer.u16(udpOffset + 2),
            payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength),
        )
    }

    fun buildUdpPacket(
        ipVersion: IpVersion,
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        return when (ipVersion) {
            IpVersion.IPV4 -> buildUdpIpv4Packet(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                payload = payload,
            )
            IpVersion.IPV6 -> buildUdpIpv6Packet(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                payload = payload,
            )
        }
    }

    private fun parseIpv4Packet(buffer: ByteArray, length: Int): IpPacket? {
        if (length < IPV4_MIN_HEADER_LENGTH) {
            return null
        }

        val headerLength = (buffer.u8(0) and 0x0f) * 4
        if (headerLength < IPV4_MIN_HEADER_LENGTH || length < headerLength) {
            return null
        }

        val totalLength = buffer.u16(2).coerceAtMost(length)
        if (totalLength < headerLength) {
            return null
        }

        return IpPacket(
            version = IpVersion.IPV4,
            sourceAddress = InetAddress.getByAddress(buffer.copyOfRange(12, 16)),
            destinationAddress = InetAddress.getByAddress(buffer.copyOfRange(16, 20)),
            protocol = buffer.u8(9),
            headerLength = headerLength,
            totalLength = totalLength,
            payloadOffset = headerLength,
            payloadLength = totalLength - headerLength,
        )
    }

    private fun parseIpv6Packet(buffer: ByteArray, length: Int): IpPacket? {
        if (length < IPV6_HEADER_LENGTH) {
            return null
        }

        val payloadLength = buffer.u16(4)
        val totalLength = (IPV6_HEADER_LENGTH + payloadLength).coerceAtMost(length)
        if (totalLength < IPV6_HEADER_LENGTH) {
            return null
        }

        return IpPacket(
            version = IpVersion.IPV6,
            sourceAddress = InetAddress.getByAddress(buffer.copyOfRange(8, 24)),
            destinationAddress = InetAddress.getByAddress(buffer.copyOfRange(24, 40)),
            protocol = buffer.u8(6),
            headerLength = IPV6_HEADER_LENGTH,
            totalLength = totalLength,
            payloadOffset = IPV6_HEADER_LENGTH,
            payloadLength = totalLength - IPV6_HEADER_LENGTH,
        )
    }

    private fun buildUdpIpv4Packet(
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceAddress.address.size == IPV4_ADDRESS_LENGTH)
        require(destinationAddress.address.size == IPV4_ADDRESS_LENGTH)
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
        sourceAddress.address.copyInto(packet, 12)
        destinationAddress.address.copyInto(packet, 16)
        packet.writeU16(10, checksum(packet, 0, IPV4_MIN_HEADER_LENGTH))

        val udpOffset = IPV4_MIN_HEADER_LENGTH
        writeUdpHeaderAndPayload(packet, udpOffset, sourcePort, destinationPort, udpLength, payload)

        val udpChecksum = udpChecksum(
            packet = packet,
            udpOffset = udpOffset,
            udpLength = udpLength,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
        )
        packet.writeU16(udpOffset + UDP_CHECKSUM_OFFSET, if (udpChecksum == 0) 0xffff else udpChecksum)

        return packet
    }

    private fun buildUdpIpv6Packet(
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceAddress.address.size == IPV6_ADDRESS_LENGTH)
        require(destinationAddress.address.size == IPV6_ADDRESS_LENGTH)
        require(payload.size <= MAX_UDP_PAYLOAD_SIZE) {
            "UDP payload is too large for a single IPv6 packet."
        }

        val udpLength = UDP_HEADER_LENGTH + payload.size
        val packet = ByteArray(IPV6_HEADER_LENGTH + udpLength)

        packet[0] = 0x60
        packet.writeU16(4, udpLength)
        packet[6] = IpProtocolNumber.UDP.toByte()
        packet[7] = DEFAULT_HOP_LIMIT.toByte()
        sourceAddress.address.copyInto(packet, 8)
        destinationAddress.address.copyInto(packet, 24)

        val udpOffset = IPV6_HEADER_LENGTH
        writeUdpHeaderAndPayload(packet, udpOffset, sourcePort, destinationPort, udpLength, payload)

        val udpChecksum = udpChecksum(
            packet = packet,
            udpOffset = udpOffset,
            udpLength = udpLength,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
        )
        packet.writeU16(udpOffset + UDP_CHECKSUM_OFFSET, if (udpChecksum == 0) 0xffff else udpChecksum)

        return packet
    }

    private fun writeUdpHeaderAndPayload(
        packet: ByteArray,
        udpOffset: Int,
        sourcePort: Int,
        destinationPort: Int,
        udpLength: Int,
        payload: ByteArray,
    ) {
        packet.writeU16(udpOffset, sourcePort)
        packet.writeU16(udpOffset + 2, destinationPort)
        packet.writeU16(udpOffset + 4, udpLength)
        packet.writeU16(udpOffset + UDP_CHECKSUM_OFFSET, 0)
        payload.copyInto(packet, udpOffset + UDP_HEADER_LENGTH)
    }

    private fun udpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): Int {
        var sum = 0L
        sourceAddress.forEachTwoBytes { word -> sum += word }
        destinationAddress.forEachTwoBytes { word -> sum += word }

        if (sourceAddress.size == IPV6_ADDRESS_LENGTH) {
            sum += (udpLength ushr 16) and 0xffff
        }
        sum += udpLength and 0xffff
        sum += IpProtocolNumber.UDP

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

    private fun ByteArray.forEachTwoBytes(block: (Int) -> Unit) {
        var index = 0
        while (index + 1 < size) {
            block(((this[index].toInt() and 0xff) shl 8) or (this[index + 1].toInt() and 0xff))
            index += 2
        }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.u16(index: Int): Int {
        return (u8(index) shl 8) or u8(index + 1)
    }

    private fun ByteArray.writeU16(index: Int, value: Int) {
        this[index] = (value ushr 8).toByte()
        this[index + 1] = value.toByte()
    }

    @Synchronized
    private fun nextIdentification(): Int {
        identification = (identification + 1) and 0xffff
        return identification
    }

    private var identification = 0

    private const val IPV4_VERSION = 4
    private const val IPV6_VERSION = 6
    private const val IPV4_ADDRESS_LENGTH = 4
    private const val IPV6_ADDRESS_LENGTH = 16
    private const val IPV4_MIN_HEADER_LENGTH = 20
    private const val IPV6_HEADER_LENGTH = 40
    private const val UDP_HEADER_LENGTH = 8
    private const val UDP_CHECKSUM_OFFSET = 6
    private const val DEFAULT_TTL = 64
    private const val DEFAULT_HOP_LIMIT = 64
    private const val MAX_UDP_PAYLOAD_SIZE = 65_507
}
