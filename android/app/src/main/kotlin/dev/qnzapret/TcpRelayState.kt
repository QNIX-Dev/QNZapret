package dev.qnzapret

internal object TcpSequence {
    fun add(sequenceNumber: Int, delta: Int): Int {
        return sequenceNumber + delta
    }

    fun length(segment: TcpSegment): Int {
        return segment.payload.size +
            (if (segment.hasSyn) 1 else 0) +
            (if (segment.hasFin) 1 else 0)
    }

    fun isBefore(left: Int, right: Int): Boolean {
        return left != right && left - right < 0
    }

    fun isAfter(left: Int, right: Int): Boolean {
        return left != right && right - left < 0
    }

    fun distance(from: Int, to: Int): Long {
        return Integer.toUnsignedLong(to - from)
    }
}

internal data class TcpClientSegmentResult(
    val payload: ByteArray?,
    val shouldAck: Boolean,
    val acceptedFin: Boolean,
    val duplicate: Boolean,
    val outOfOrder: Boolean,
)

internal class TcpRelayState(
    initialClientSequenceNumber: Int,
) {
    var clientNextSequence: Int = TcpSequence.add(initialClientSequenceNumber, 1)
        private set

    var clientFinReceived: Boolean = false
        private set

    fun processClientSegment(segment: TcpSegment): TcpClientSegmentResult {
        var acceptedPayload: ByteArray? = null
        var acceptedFin = false
        var duplicate = false
        var outOfOrder = false

        if (segment.payload.isNotEmpty()) {
            val payloadStart = segment.sequenceNumber
            val payloadEnd = TcpSequence.add(payloadStart, segment.payload.size)

            when {
                payloadStart == clientNextSequence -> {
                    acceptedPayload = segment.payload
                    clientNextSequence = payloadEnd
                }
                TcpSequence.isBefore(payloadStart, clientNextSequence) -> {
                    val alreadyAccepted = TcpSequence.distance(payloadStart, clientNextSequence)
                    if (alreadyAccepted < segment.payload.size) {
                        val offset = alreadyAccepted.toInt()
                        acceptedPayload = segment.payload.copyOfRange(offset, segment.payload.size)
                        clientNextSequence = payloadEnd
                    } else {
                        duplicate = true
                    }
                }
                else -> {
                    outOfOrder = true
                }
            }
        }

        if (segment.hasFin) {
            val finSequence = TcpSequence.add(segment.sequenceNumber, segment.payload.size)
            when {
                finSequence == clientNextSequence && !clientFinReceived -> {
                    clientNextSequence = TcpSequence.add(clientNextSequence, 1)
                    clientFinReceived = true
                    acceptedFin = true
                }
                finSequence == clientNextSequence && clientFinReceived -> {
                    duplicate = true
                }
                TcpSequence.isBefore(finSequence, clientNextSequence) -> {
                    duplicate = true
                }
                else -> {
                    outOfOrder = true
                }
            }
        }

        return TcpClientSegmentResult(
            payload = acceptedPayload,
            shouldAck = segment.payload.isNotEmpty() || segment.hasFin || duplicate || outOfOrder,
            acceptedFin = acceptedFin,
            duplicate = duplicate,
            outOfOrder = outOfOrder,
        )
    }
}
