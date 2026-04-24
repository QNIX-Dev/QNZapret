package dev.qnzapret

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpRelayStateTest {
    @Test
    fun acceptsInOrderPayloadAndFin() {
        val state = TcpRelayState(initialClientSequenceNumber = 1000)

        val payloadResult = state.processClientSegment(
            segment(sequenceNumber = 1001, payload = byteArrayOf(1, 2, 3)),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), payloadResult.payload)
        assertTrue(payloadResult.shouldAck)
        assertFalse(payloadResult.acceptedFin)
        assertEquals(1004, state.clientNextSequence)

        val finResult = state.processClientSegment(
            segment(sequenceNumber = 1004, flags = TcpFlags.ACK or TcpFlags.FIN),
        )
        assertNull(finResult.payload)
        assertTrue(finResult.shouldAck)
        assertTrue(finResult.acceptedFin)
        assertEquals(1005, state.clientNextSequence)
    }

    @Test
    fun ignoresFullDuplicatePayload() {
        val state = TcpRelayState(initialClientSequenceNumber = 42)

        state.processClientSegment(
            segment(sequenceNumber = 43, payload = byteArrayOf(10, 11, 12)),
        )
        val duplicate = state.processClientSegment(
            segment(sequenceNumber = 43, payload = byteArrayOf(10, 11, 12)),
        )

        assertNull(duplicate.payload)
        assertTrue(duplicate.duplicate)
        assertTrue(duplicate.shouldAck)
        assertEquals(46, state.clientNextSequence)
    }

    @Test
    fun acceptsOnlyNewBytesFromOverlappingRetransmission() {
        val state = TcpRelayState(initialClientSequenceNumber = 100)

        state.processClientSegment(
            segment(sequenceNumber = 101, payload = byteArrayOf(1, 2, 3)),
        )
        val overlap = state.processClientSegment(
            segment(sequenceNumber = 102, payload = byteArrayOf(2, 3, 4)),
        )

        assertArrayEquals(byteArrayOf(4), overlap.payload)
        assertFalse(overlap.duplicate)
        assertFalse(overlap.outOfOrder)
        assertEquals(105, state.clientNextSequence)
    }

    @Test
    fun acksOutOfOrderPayloadWithoutAdvancingWindow() {
        val state = TcpRelayState(initialClientSequenceNumber = 10)

        val result = state.processClientSegment(
            segment(sequenceNumber = 15, payload = byteArrayOf(99)),
        )

        assertNull(result.payload)
        assertTrue(result.outOfOrder)
        assertTrue(result.shouldAck)
        assertEquals(11, state.clientNextSequence)
    }

    @Test
    fun advancesSequenceAcrossUnsignedWrap() {
        val state = TcpRelayState(initialClientSequenceNumber = -2)

        val result = state.processClientSegment(
            segment(sequenceNumber = -1, payload = byteArrayOf(1, 2)),
        )

        assertArrayEquals(byteArrayOf(1, 2), result.payload)
        assertEquals(1, state.clientNextSequence)
    }

    @Test
    fun ignoresDuplicatePayloadAcrossUnsignedWrap() {
        val state = TcpRelayState(initialClientSequenceNumber = -3)

        state.processClientSegment(
            segment(sequenceNumber = -2, payload = byteArrayOf(1, 2, 3, 4)),
        )
        val duplicate = state.processClientSegment(
            segment(sequenceNumber = -2, payload = byteArrayOf(1, 2, 3, 4)),
        )

        assertNull(duplicate.payload)
        assertTrue(duplicate.duplicate)
        assertFalse(duplicate.outOfOrder)
        assertEquals(2, state.clientNextSequence)
    }

    private fun segment(
        sequenceNumber: Int,
        flags: Int = TcpFlags.ACK,
        payload: ByteArray = ByteArray(0),
    ): TcpSegment {
        return TcpSegment(
            ipVersion = IpVersion.IPV4,
            sourceAddress = InetAddress.getByName("10.24.0.2"),
            destinationAddress = InetAddress.getByName("93.184.216.34"),
            sourcePort = 42000,
            destinationPort = 443,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = 0,
            flags = flags,
            windowSize = 65_535,
            payload = payload,
        )
    }
}
