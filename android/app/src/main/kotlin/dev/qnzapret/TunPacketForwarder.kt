package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class TunForwarderCapabilities(
    val packetCodecReady: Boolean,
    val udpForwarderReady: Boolean,
    val tcpForwarderReady: Boolean,
) {
    val fullyReady: Boolean
        get() = packetCodecReady && udpForwarderReady && tcpForwarderReady
}

internal data class TunPacketForwarderStatus(
    val running: Boolean,
    val capabilities: TunForwarderCapabilities,
    val message: String,
)

internal class TunPacketForwarder(
    private val service: VpnService,
    private val localProxy: LocalStrategyProxy,
    private val descriptor: ParcelFileDescriptor,
    private val mtu: Int,
) {
    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    private val udpSessions = ConcurrentHashMap<UdpFlowKey, UdpRelaySession>()
    private var readerThread: Thread? = null
    private var output: FileOutputStream? = null

    fun start(): TunPacketForwarderStatus {
        if (!running.compareAndSet(false, true)) {
            return TunPacketForwarderStatus(
                running = true,
                capabilities = CAPABILITIES,
                message = "TUN packet forwarder is already running.",
            )
        }

        output = FileOutputStream(descriptor.fileDescriptor)
        readerThread = Thread(::readLoop, "QNZapret-TUN-reader").apply {
            isDaemon = true
            start()
        }

        return TunPacketForwarderStatus(
            running = true,
            capabilities = CAPABILITIES,
            message = "TUN packet forwarder started with IPv4/UDP support.",
        )
    }

    fun stop() {
        running.set(false)
        udpSessions.values.forEach(UdpRelaySession::close)
        udpSessions.clear()

        try {
            output?.close()
        } catch (_: IOException) {
        } finally {
            output = null
        }

        readerThread?.interrupt()
        readerThread = null
    }

    private fun readLoop() {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(mtu.coerceAtLeast(DEFAULT_PACKET_BUFFER_SIZE))

        try {
            while (running.get()) {
                val packetLength = input.read(buffer)
                if (packetLength <= 0) {
                    continue
                }
                handlePacket(buffer, packetLength)
            }
        } catch (_: IOException) {
            if (running.get()) {
                running.set(false)
            }
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, packetLength: Int) {
        val packet = IpPacketCodec.parseIpv4Packet(buffer, packetLength) ?: return
        when (packet.protocol) {
            IpProtocolNumber.UDP -> forwardUdp(buffer, packet)
            IpProtocolNumber.TCP -> {
                // TCP userspace forwarding needs a stream state machine before TUN can be enabled.
            }
        }
    }

    private fun forwardUdp(buffer: ByteArray, packet: Ipv4Packet) {
        val datagram = IpPacketCodec.parseUdpDatagram(buffer, packet) ?: return
        val decision = localProxy.evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.UDP,
                destinationPort = datagram.destinationPort,
                payload = datagram.payload,
            ),
        )

        val session = udpSessions.computeIfAbsent(UdpFlowKey.from(datagram)) { key ->
            UdpRelaySession(
                service = service,
                key = key,
                isRunning = { running.get() },
                packetWriter = ::writePacketToTun,
                onClosed = { udpSessions.remove(key) },
            )
        }

        decision.actions
            .filter { action -> action.kind == StrategyActionKind.UDP_FAKE }
            .forEach { action ->
                val payload = action.blobPayload ?: return@forEach
                repeat(action.repeats.coerceAtLeast(1)) {
                    session.send(payload)
                }
            }

        session.send(datagram.payload)
    }

    private fun writePacketToTun(packet: ByteArray) {
        val currentOutput = output ?: return
        synchronized(outputLock) {
            currentOutput.write(packet)
            currentOutput.flush()
        }
    }

    private data class UdpFlowKey(
        val sourceAddress: Int,
        val sourcePort: Int,
        val destinationAddress: Int,
        val destinationPort: Int,
    ) {
        companion object {
            fun from(datagram: UdpDatagram): UdpFlowKey {
                return UdpFlowKey(
                    sourceAddress = datagram.sourceAddress,
                    sourcePort = datagram.sourcePort,
                    destinationAddress = datagram.destinationAddress,
                    destinationPort = datagram.destinationPort,
                )
            }
        }
    }

    private class UdpRelaySession(
        private val service: VpnService,
        private val key: UdpFlowKey,
        private val isRunning: () -> Boolean,
        private val packetWriter: (ByteArray) -> Unit,
        private val onClosed: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val socket = DatagramSocket()
        private val receiverThread: Thread

        init {
            if (!service.protect(socket)) {
                socket.close()
                throw IOException("Failed to protect UDP socket from VPN routing loop.")
            }

            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(
                IpPacketCodec.inetAddressFromIpv4(key.destinationAddress),
                key.destinationPort,
            )
            receiverThread = Thread(::receiveLoop, "QNZapret-UDP-${key.destinationPort}").apply {
                isDaemon = true
                start()
            }
        }

        fun send(payload: ByteArray) {
            if (closed.get()) {
                return
            }

            socket.send(DatagramPacket(payload, payload.size))
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                socket.close()
                receiverThread.interrupt()
            }
        }

        private fun receiveLoop() {
            val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
            try {
                while (isRunning() && !closed.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val payload = packet.data.copyOfRange(
                        packet.offset,
                        packet.offset + packet.length,
                    )
                    val response = IpPacketCodec.buildUdpIpv4Packet(
                        sourceAddress = key.destinationAddress,
                        destinationAddress = key.sourceAddress,
                        sourcePort = key.destinationPort,
                        destinationPort = key.sourcePort,
                        payload = payload,
                    )
                    packetWriter(response)
                }
            } catch (_: IOException) {
            } finally {
                close()
                onClosed()
            }
        }
    }

    companion object {
        val CAPABILITIES = TunForwarderCapabilities(
            packetCodecReady = true,
            udpForwarderReady = true,
            tcpForwarderReady = false,
        )

        private const val DEFAULT_PACKET_BUFFER_SIZE = 16_384
        private const val MAX_UDP_PACKET_SIZE = 65_507
        private const val SOCKET_TIMEOUT_MS = 1_000
    }
}
