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

internal object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
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

internal data class TcpSegment(
    val ipVersion: IpVersion,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Int,
    val acknowledgementNumber: Int,
    val flags: Int,
    val windowSize: Int,
    val payload: ByteArray,
) {
    val hasSyn: Boolean
        get() = flags and TcpFlags.SYN != 0

    val hasAck: Boolean
        get() = flags and TcpFlags.ACK != 0

    val hasFin: Boolean
        get() = flags and TcpFlags.FIN != 0

    val hasRst: Boolean
        get() = flags and TcpFlags.RST != 0
}

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

    fun parseTcpSegment(buffer: ByteArray, packet: IpPacket): TcpSegment? {
        if (packet.protocol != IpProtocolNumber.TCP || packet.payloadLength < TCP_MIN_HEADER_LENGTH) {
            return null
        }

        val tcpOffset = packet.payloadOffset
        val tcpHeaderLength = (buffer.u8(tcpOffset + TCP_DATA_OFFSET_INDEX) ushr 4) * 4
        if (tcpHeaderLength < TCP_MIN_HEADER_LENGTH || tcpHeaderLength > packet.payloadLength) {
            return null
        }

        val payloadOffset = tcpOffset + tcpHeaderLength
        val payloadLength = packet.payloadLength - tcpHeaderLength
        return TcpSegment(
            ipVersion = packet.version,
            sourceAddress = packet.sourceAddress,
            destinationAddress = packet.destinationAddress,
            sourcePort = buffer.u16(tcpOffset),
            destinationPort = buffer.u16(tcpOffset + 2),
            sequenceNumber = buffer.i32(tcpOffset + 4),
            acknowledgementNumber = buffer.i32(tcpOffset + 8),
            flags = buffer.u8(tcpOffset + TCP_FLAGS_INDEX),
            windowSize = buffer.u16(tcpOffset + TCP_WINDOW_INDEX),
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

    fun buildTcpPacket(
        ipVersion: IpVersion,
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Int,
        acknowledgementNumber: Int,
        flags: Int,
        windowSize: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        return when (ipVersion) {
            IpVersion.IPV4 -> buildTcpIpv4Packet(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                flags = flags,
                windowSize = windowSize,
                payload = payload,
            )
            IpVersion.IPV6 -> buildTcpIpv6Packet(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                flags = flags,
                windowSize = windowSize,
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

        val udpChecksum = transportChecksum(
            packet = packet,
            transportOffset = udpOffset,
            transportLength = udpLength,
            protocol = IpProtocolNumber.UDP,
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

        val udpChecksum = transportChecksum(
            packet = packet,
            transportOffset = udpOffset,
            transportLength = udpLength,
            protocol = IpProtocolNumber.UDP,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
        )
        packet.writeU16(udpOffset + UDP_CHECKSUM_OFFSET, if (udpChecksum == 0) 0xffff else udpChecksum)

        return packet
    }

    private fun buildTcpIpv4Packet(
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Int,
        acknowledgementNumber: Int,
        flags: Int,
        windowSize: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceAddress.address.size == IPV4_ADDRESS_LENGTH)
        require(destinationAddress.address.size == IPV4_ADDRESS_LENGTH)
        require(payload.size <= MAX_TCP_PAYLOAD_SIZE) {
            "TCP payload is too large for a single IPv4 packet."
        }

        val tcpLength = TCP_MIN_HEADER_LENGTH + payload.size
        val totalLength = IPV4_MIN_HEADER_LENGTH + tcpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        packet.writeU16(2, totalLength)
        packet.writeU16(4, nextIdentification())
        packet.writeU16(6, 0)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = IpProtocolNumber.TCP.toByte()
        sourceAddress.address.copyInto(packet, 12)
        destinationAddress.address.copyInto(packet, 16)
        packet.writeU16(10, checksum(packet, 0, IPV4_MIN_HEADER_LENGTH))

        val tcpOffset = IPV4_MIN_HEADER_LENGTH
        writeTcpHeaderAndPayload(
            packet = packet,
            tcpOffset = tcpOffset,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            flags = flags,
            windowSize = windowSize,
            payload = payload,
        )

        val tcpChecksum = transportChecksum(
            packet = packet,
            transportOffset = tcpOffset,
            transportLength = tcpLength,
            protocol = IpProtocolNumber.TCP,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
        )
        packet.writeU16(tcpOffset + TCP_CHECKSUM_OFFSET, if (tcpChecksum == 0) 0xffff else tcpChecksum)

        return packet
    }

    private fun buildTcpIpv6Packet(
        sourceAddress: InetAddress,
        destinationAddress: InetAddress,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Int,
        acknowledgementNumber: Int,
        flags: Int,
        windowSize: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceAddress.address.size == IPV6_ADDRESS_LENGTH)
        require(destinationAddress.address.size == IPV6_ADDRESS_LENGTH)
        require(payload.size <= MAX_TCP_PAYLOAD_SIZE) {
            "TCP payload is too large for a single IPv6 packet."
        }

        val tcpLength = TCP_MIN_HEADER_LENGTH + payload.size
        val packet = ByteArray(IPV6_HEADER_LENGTH + tcpLength)

        packet[0] = 0x60
        packet.writeU16(4, tcpLength)
        packet[6] = IpProtocolNumber.TCP.toByte()
        packet[7] = DEFAULT_HOP_LIMIT.toByte()
        sourceAddress.address.copyInto(packet, 8)
        destinationAddress.address.copyInto(packet, 24)

        val tcpOffset = IPV6_HEADER_LENGTH
        writeTcpHeaderAndPayload(
            packet = packet,
            tcpOffset = tcpOffset,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            flags = flags,
            windowSize = windowSize,
            payload = payload,
        )

        val tcpChecksum = transportChecksum(
            packet = packet,
            transportOffset = tcpOffset,
            transportLength = tcpLength,
            protocol = IpProtocolNumber.TCP,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
        )
        packet.writeU16(tcpOffset + TCP_CHECKSUM_OFFSET, if (tcpChecksum == 0) 0xffff else tcpChecksum)

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

    private fun writeTcpHeaderAndPayload(
        packet: ByteArray,
        tcpOffset: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Int,
        acknowledgementNumber: Int,
        flags: Int,
        windowSize: Int,
        payload: ByteArray,
    ) {
        packet.writeU16(tcpOffset, sourcePort)
        packet.writeU16(tcpOffset + 2, destinationPort)
        packet.writeI32(tcpOffset + 4, sequenceNumber)
        packet.writeI32(tcpOffset + 8, acknowledgementNumber)
        packet[tcpOffset + TCP_DATA_OFFSET_INDEX] = ((TCP_MIN_HEADER_LENGTH / 4) shl 4).toByte()
        packet[tcpOffset + TCP_FLAGS_INDEX] = flags.toByte()
        packet.writeU16(tcpOffset + TCP_WINDOW_INDEX, windowSize.coerceIn(0, 0xffff))
        packet.writeU16(tcpOffset + TCP_CHECKSUM_OFFSET, 0)
        packet.writeU16(tcpOffset + 18, 0)
        payload.copyInto(packet, tcpOffset + TCP_MIN_HEADER_LENGTH)
    }

    private fun transportChecksum(
        packet: ByteArray,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): Int {
        var sum = 0L
        sourceAddress.forEachTwoBytes { word -> sum += word }
        destinationAddress.forEachTwoBytes { word -> sum += word }

        if (sourceAddress.size == IPV6_ADDRESS_LENGTH) {
            sum += (transportLength ushr 16) and 0xffff
        }
        sum += transportLength and 0xffff
        sum += protocol

        return checksum(packet, transportOffset, transportLength, sum)
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
    private const val IPV6_VERSION = 6
    private const val IPV4_ADDRESS_LENGTH = 4
    private const val IPV6_ADDRESS_LENGTH = 16
    private const val IPV4_MIN_HEADER_LENGTH = 20
    private const val IPV6_HEADER_LENGTH = 40
    private const val UDP_HEADER_LENGTH = 8
    private const val UDP_CHECKSUM_OFFSET = 6
    private const val TCP_MIN_HEADER_LENGTH = 20
    private const val TCP_DATA_OFFSET_INDEX = 12
    private const val TCP_FLAGS_INDEX = 13
    private const val TCP_WINDOW_INDEX = 14
    private const val TCP_CHECKSUM_OFFSET = 16
    private const val DEFAULT_TTL = 64
    private const val DEFAULT_HOP_LIMIT = 64
    private const val MAX_UDP_PAYLOAD_SIZE = 65_507
    private const val MAX_TCP_PAYLOAD_SIZE = 65_495
}
