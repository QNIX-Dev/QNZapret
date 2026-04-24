package dev.qnzapret

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IpPacketCodecTest {
    @Test
    fun roundTripsIpv4TcpPacket() {
        val source = InetAddress.getByName("93.184.216.34")
        val destination = InetAddress.getByName("10.24.0.2")
        val payload = byteArrayOf(1, 2, 3, 4)

        val packetBytes = IpPacketCodec.buildTcpPacket(
            ipVersion = IpVersion.IPV4,
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 443,
            destinationPort = 42000,
            sequenceNumber = 123,
            acknowledgementNumber = 456,
            flags = TcpFlags.ACK or TcpFlags.PSH,
            windowSize = 4096,
            payload = payload,
        )

        val packet = IpPacketCodec.parseIpPacket(packetBytes, packetBytes.size)
        assertNotNull(packet)
        val segment = IpPacketCodec.parseTcpSegment(packetBytes, packet!!)
        assertNotNull(segment)

        assertEquals(IpVersion.IPV4, segment!!.ipVersion)
        assertEquals(source, segment.sourceAddress)
        assertEquals(destination, segment.destinationAddress)
        assertEquals(443, segment.sourcePort)
        assertEquals(42000, segment.destinationPort)
        assertEquals(123, segment.sequenceNumber)
        assertEquals(456, segment.acknowledgementNumber)
        assertEquals(TcpFlags.ACK or TcpFlags.PSH, segment.flags)
        assertEquals(4096, segment.windowSize)
        assertArrayEquals(payload, segment.payload)
    }

    @Test
    fun roundTripsIpv6TcpPacket() {
        val source = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
        val destination = InetAddress.getByName("fd00:24::2")
        val payload = byteArrayOf(9, 8, 7)

        val packetBytes = IpPacketCodec.buildTcpPacket(
            ipVersion = IpVersion.IPV6,
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = 443,
            destinationPort = 42000,
            sequenceNumber = -10,
            acknowledgementNumber = 12,
            flags = TcpFlags.ACK,
            windowSize = 8192,
            payload = payload,
        )

        val packet = IpPacketCodec.parseIpPacket(packetBytes, packetBytes.size)
        assertNotNull(packet)
        val segment = IpPacketCodec.parseTcpSegment(packetBytes, packet!!)
        assertNotNull(segment)

        assertEquals(IpVersion.IPV6, segment!!.ipVersion)
        assertEquals(source, segment.sourceAddress)
        assertEquals(destination, segment.destinationAddress)
        assertEquals(443, segment.sourcePort)
        assertEquals(42000, segment.destinationPort)
        assertEquals(-10, segment.sequenceNumber)
        assertEquals(12, segment.acknowledgementNumber)
        assertEquals(TcpFlags.ACK, segment.flags)
        assertEquals(8192, segment.windowSize)
        assertArrayEquals(payload, segment.payload)
    }
}
