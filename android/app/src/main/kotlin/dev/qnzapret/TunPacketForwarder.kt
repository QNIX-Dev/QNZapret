package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class TunForwarderCapabilities(
    val ipv4PacketCodecReady: Boolean,
    val ipv6PacketCodecReady: Boolean,
    val ipv4UdpForwarderReady: Boolean,
    val ipv6UdpForwarderReady: Boolean,
    val tcpForwarderReady: Boolean,
) {
    val packetCodecReady: Boolean
        get() = ipv4PacketCodecReady && ipv6PacketCodecReady

    val udpForwarderReady: Boolean
        get() = ipv4UdpForwarderReady && ipv6UdpForwarderReady

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
    private val tcpSessions = ConcurrentHashMap<TcpFlowKey, TcpRelaySession>()
    private val quicHostCorrelation = QuicHostCorrelation()
    private val lastSessionCleanupAt = AtomicLong(0)
    private var readerThread: Thread? = null
    private var cleanupThread: Thread? = null
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
        cleanupThread = Thread(::cleanupLoop, "QNZapret-session-cleanup").apply {
            isDaemon = true
            start()
        }

        return TunPacketForwarderStatus(
            running = true,
            capabilities = CAPABILITIES,
            message = "TUN packet forwarder started with IPv4/IPv6 TCP and UDP support.",
        )
    }

    fun stop() {
        running.set(false)
        udpSessions.values.forEach(UdpRelaySession::close)
        udpSessions.clear()
        tcpSessions.values.forEach(TcpRelaySession::close)
        tcpSessions.clear()

        try {
            output?.close()
        } catch (_: IOException) {
        } finally {
            output = null
        }

        readerThread?.interrupt()
        readerThread = null
        cleanupThread?.interrupt()
        cleanupThread = null
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

    private fun cleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(SESSION_CLEANUP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            cleanupExpiredSessions()
        }
    }

    private fun handlePacket(buffer: ByteArray, packetLength: Int) {
        cleanupExpiredSessions()
        val packet = IpPacketCodec.parseIpPacket(buffer, packetLength) ?: return
        when (packet.protocol) {
            IpProtocolNumber.UDP -> forwardUdp(buffer, packet)
            IpProtocolNumber.TCP -> forwardTcp(buffer, packet)
        }
    }

    private fun forwardUdp(buffer: ByteArray, packet: IpPacket) {
        val datagram = IpPacketCodec.parseUdpDatagram(buffer, packet) ?: return
        val knownHost = if (datagram.destinationPort == QUIC_HTTPS_PORT) {
            quicHostCorrelation.lookupHost(datagram.destinationAddress)
        } else {
            null
        }
        val decision = localProxy.evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.UDP,
                destinationPort = datagram.destinationPort,
                payload = datagram.payload,
                knownHost = knownHost,
            ),
        )

        val session = udpSessions.computeIfAbsent(UdpFlowKey.from(datagram)) { key ->
            UdpRelaySession(
                service = service,
                key = key,
                hostCorrelation = quicHostCorrelation,
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

    private fun forwardTcp(buffer: ByteArray, packet: IpPacket) {
        val segment = IpPacketCodec.parseTcpSegment(buffer, packet) ?: return
        val key = TcpFlowKey.from(segment)

        if (segment.hasSyn && !segment.hasAck) {
            val existingSession = tcpSessions[key]
            if (existingSession != null) {
                existingSession.handleSegment(segment)
                return
            }

            val session = TcpRelaySession(
                service = service,
                localProxy = localProxy,
                hostCorrelation = quicHostCorrelation,
                key = key,
                initialSegment = segment,
                mtu = mtu,
                isRunning = { running.get() },
                packetWriter = ::writePacketToTun,
                onClosed = { closedSession -> tcpSessions.remove(key, closedSession) },
            )
            val previousSession = tcpSessions.putIfAbsent(key, session)
            if (previousSession == null) {
                try {
                    session.start()
                } catch (_: IOException) {
                    tcpSessions.remove(key, session)
                }
            } else {
                session.close()
                previousSession.handleSegment(segment)
            }
            return
        }

        val session = tcpSessions[key]
        if (session == null) {
            sendResetForUnknownTcpSession(segment)
            return
        }

        session.handleSegment(segment)
    }

    private fun sendResetForUnknownTcpSession(segment: TcpSegment) {
        if (segment.hasRst) {
            return
        }

        val segmentLength = TcpSequence.length(segment)
        val flags: Int
        val sequenceNumber: Int
        val acknowledgementNumber: Int
        if (segment.hasAck) {
            flags = TcpFlags.RST
            sequenceNumber = segment.acknowledgementNumber
            acknowledgementNumber = 0
        } else {
            flags = TcpFlags.RST or TcpFlags.ACK
            sequenceNumber = 0
            acknowledgementNumber = TcpSequence.add(segment.sequenceNumber, segmentLength)
        }

        val response = IpPacketCodec.buildTcpPacket(
            ipVersion = segment.ipVersion,
            sourceAddress = segment.destinationAddress,
            destinationAddress = segment.sourceAddress,
            sourcePort = segment.destinationPort,
            destinationPort = segment.sourcePort,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            flags = flags,
            windowSize = 0,
        )
        writePacketToTun(response)
    }

    private fun cleanupExpiredSessions(now: Long = System.currentTimeMillis()) {
        val lastCleanup = lastSessionCleanupAt.get()
        if (now - lastCleanup < SESSION_CLEANUP_INTERVAL_MS) {
            return
        }
        if (!lastSessionCleanupAt.compareAndSet(lastCleanup, now)) {
            return
        }

        udpSessions.values
            .filter { session -> session.isExpired(now) }
            .forEach(UdpRelaySession::close)
        tcpSessions.values
            .filter { session -> session.isExpired(now) }
            .forEach(TcpRelaySession::close)
        quicHostCorrelation.cleanupExpired(now)
    }

    private fun writePacketToTun(packet: ByteArray) {
        val currentOutput = output ?: return
        try {
            synchronized(outputLock) {
                currentOutput.write(packet)
                currentOutput.flush()
            }
        } catch (_: IOException) {
            if (running.get()) {
                running.set(false)
            }
        }
    }

    private data class UdpFlowKey(
        val ipVersion: IpVersion,
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destinationAddress: InetAddress,
        val destinationPort: Int,
    ) {
        companion object {
            fun from(datagram: UdpDatagram): UdpFlowKey {
                return UdpFlowKey(
                    ipVersion = datagram.ipVersion,
                    sourceAddress = datagram.sourceAddress,
                    sourcePort = datagram.sourcePort,
                    destinationAddress = datagram.destinationAddress,
                    destinationPort = datagram.destinationPort,
                )
            }
        }
    }

    private data class TcpFlowKey(
        val ipVersion: IpVersion,
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destinationAddress: InetAddress,
        val destinationPort: Int,
    ) {
        companion object {
            fun from(segment: TcpSegment): TcpFlowKey {
                return TcpFlowKey(
                    ipVersion = segment.ipVersion,
                    sourceAddress = segment.sourceAddress,
                    sourcePort = segment.sourcePort,
                    destinationAddress = segment.destinationAddress,
                    destinationPort = segment.destinationPort,
                )
            }
        }
    }

    private class TcpRelaySession(
        private val service: VpnService,
        private val localProxy: LocalStrategyProxy,
        private val hostCorrelation: QuicHostCorrelation,
        private val key: TcpFlowKey,
        private val initialSegment: TcpSegment,
        private val mtu: Int,
        private val isRunning: () -> Boolean,
        private val packetWriter: (ByteArray) -> Unit,
        private val onClosed: (TcpRelaySession) -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val stateLock = Any()
        private val socket = Socket()
        private val pendingPayloads = ArrayDeque<ByteArray>()
        private var connectorThread: Thread? = null
        private var receiverThread: Thread? = null
        private var remoteOutput: OutputStream? = null
        private var pendingPayloadBytes = 0
        private val clientState = TcpRelayState(initialSegment.sequenceNumber)
        private val serverInitialSequence = initialServerSequence(key, initialSegment)
        private var serverNextSequence = TcpSequence.add(serverInitialSequence, 1)
        private val lastActivityAt = AtomicLong(System.currentTimeMillis())
        private var strategyEvaluated = false
        private var finSentToClient = false

        fun start() {
            if (!service.protect(socket)) {
                sendResetToClient()
                close()
                throw IOException("Failed to protect TCP socket from VPN routing loop.")
            }

            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            sendSynAck()
            connectorThread = Thread(::connectLoop, "QNZapret-TCP-connect-${key.destinationPort}").apply {
                isDaemon = true
                start()
            }
        }

        fun handleSegment(segment: TcpSegment) {
            touch()
            if (closed.get()) {
                return
            }

            if (segment.hasRst) {
                close()
                return
            }

            if (segment.hasSyn && !segment.hasAck) {
                sendSynAck()
                return
            }

            val result = synchronized(stateLock) {
                clientState.processClientSegment(segment)
            }

            result.payload?.let(::forwardClientPayload)
            if (result.acceptedFin) {
                shutdownRemoteOutput()
            }
            if (result.shouldAck) {
                sendAckToClient()
            }
        }

        fun isExpired(now: Long): Boolean {
            return now - lastActivityAt.get() > TCP_SESSION_IDLE_TIMEOUT_MS
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                connectorThread?.interrupt()
                receiverThread?.interrupt()
                onClosed(this)
            }
        }

        private fun connectLoop() {
            try {
                socket.connect(
                    InetSocketAddress(key.destinationAddress, key.destinationPort),
                    TCP_CONNECT_TIMEOUT_MS,
                )
                val output = socket.getOutputStream()
                synchronized(stateLock) {
                    remoteOutput = output
                    while (pendingPayloads.isNotEmpty()) {
                        val payload = pendingPayloads.removeFirst()
                        output.write(payload)
                        pendingPayloadBytes -= payload.size
                    }
                    output.flush()
                    if (clientState.clientFinReceived) {
                        socket.shutdownOutput()
                    }
                }

                receiverThread = Thread(::receiveLoop, "QNZapret-TCP-recv-${key.destinationPort}").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: IOException) {
                sendResetToClient()
                close()
            }
        }

        private fun receiveLoop() {
            val input = try {
                socket.getInputStream()
            } catch (_: IOException) {
                sendResetToClient()
                close()
                return
            }
            val buffer = ByteArray(maxTcpPayloadSize())
            var gracefulFinSent = false

            try {
                while (isRunning() && !closed.get()) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }

                    touch()
                    sendPayloadToClient(buffer.copyOfRange(0, read))
                }
                gracefulFinSent = sendFinToClient()
            } catch (_: IOException) {
                sendResetToClient()
            } finally {
                if (gracefulFinSent) {
                    sleepBeforeClosing()
                }
                close()
            }
        }

        private fun forwardClientPayload(payload: ByteArray) {
            val chunks = transformClientPayload(payload)
            if (chunks.isEmpty()) {
                return
            }

            try {
                var pendingOverflow = false
                synchronized(stateLock) {
                    val output = remoteOutput
                    if (output == null) {
                        val chunkBytes = chunks.sumOf { it.size }
                        if (pendingPayloadBytes + chunkBytes > MAX_PENDING_TCP_PAYLOAD_BYTES) {
                            pendingOverflow = true
                        } else {
                            chunks.forEach { payload ->
                                pendingPayloads.addLast(payload)
                                pendingPayloadBytes += payload.size
                            }
                        }
                    } else {
                        chunks.forEach(output::write)
                        output.flush()
                    }
                }
                if (pendingOverflow) {
                    sendResetToClient()
                    close()
                }
            } catch (_: IOException) {
                sendResetToClient()
                close()
            }
        }

        private fun transformClientPayload(payload: ByteArray): List<ByteArray> {
            if (payload.isEmpty()) {
                return emptyList()
            }

            if (strategyEvaluated) {
                return listOf(payload)
            }
            strategyEvaluated = true

            val decision = localProxy.evaluate(
                StrategyFlowProbe(
                    transport = StrategyTransport.TCP,
                    destinationPort = key.destinationPort,
                    payload = payload,
                ),
            )
            rememberCorrelatedHost(decision)
            if (decision.kind != StrategyDecisionKind.DESYNC) {
                return listOf(payload)
            }

            var chunks = listOf(payload)
            decision.actions.forEach { action ->
                if (action.kind == StrategyActionKind.SPLIT) {
                    chunks = splitChunks(chunks, action.position ?: DEFAULT_TCP_SPLIT_POSITION)
                }
            }
            return chunks
        }

        private fun rememberCorrelatedHost(decision: StrategyDecision) {
            if (
                decision.host != null &&
                (decision.protocol == StrategyProtocol.HTTP || decision.protocol == StrategyProtocol.TLS)
            ) {
                hostCorrelation.rememberHost(key.destinationAddress, decision.host)
            }
        }

        private fun splitChunks(chunks: List<ByteArray>, position: Int): List<ByteArray> {
            if (position <= 0) {
                return chunks
            }

            return chunks.flatMap { chunk ->
                if (position >= chunk.size) {
                    listOf(chunk)
                } else {
                    listOf(
                        chunk.copyOfRange(0, position),
                        chunk.copyOfRange(position, chunk.size),
                    )
                }
            }
        }

        private fun shutdownRemoteOutput() {
            try {
                val shouldShutdown = synchronized(stateLock) {
                    remoteOutput != null
                }
                if (shouldShutdown) {
                    socket.shutdownOutput()
                }
            } catch (_: IOException) {
                sendResetToClient()
                close()
            }
        }

        private fun sendPayloadToClient(payload: ByteArray) {
            var offset = 0
            while (offset < payload.size && isRunning() && !closed.get()) {
                val chunkSize = minOf(maxTcpPayloadSize(), payload.size - offset)
                val chunk = payload.copyOfRange(offset, offset + chunkSize)
                val response = synchronized(stateLock) {
                    val sequenceNumber = serverNextSequence
                    serverNextSequence = TcpSequence.add(serverNextSequence, chunk.size)
                    buildTcpResponsePacket(
                        sequenceNumber = sequenceNumber,
                        acknowledgementNumber = clientState.clientNextSequence,
                        flags = TcpFlags.ACK or TcpFlags.PSH,
                        payload = chunk,
                    )
                }
                packetWriter(response)
                offset += chunkSize
            }
        }

        private fun sendSynAck() {
            val response = synchronized(stateLock) {
                buildTcpResponsePacket(
                    sequenceNumber = serverInitialSequence,
                    acknowledgementNumber = clientState.clientNextSequence,
                    flags = TcpFlags.SYN or TcpFlags.ACK,
                )
            }
            packetWriter(response)
        }

        private fun sendAckToClient() {
            val response = synchronized(stateLock) {
                buildTcpResponsePacket(
                    sequenceNumber = serverNextSequence,
                    acknowledgementNumber = clientState.clientNextSequence,
                    flags = TcpFlags.ACK,
                )
            }
            packetWriter(response)
        }

        private fun sendFinToClient(): Boolean {
            val response = synchronized(stateLock) {
                if (finSentToClient) {
                    null
                } else {
                    finSentToClient = true
                    val sequenceNumber = serverNextSequence
                    serverNextSequence = TcpSequence.add(serverNextSequence, 1)
                    buildTcpResponsePacket(
                        sequenceNumber = sequenceNumber,
                        acknowledgementNumber = clientState.clientNextSequence,
                        flags = TcpFlags.FIN or TcpFlags.ACK,
                    )
                }
            }
            response?.let(packetWriter)
            return response != null
        }

        private fun sendResetToClient() {
            if (closed.get()) {
                return
            }

            val response = synchronized(stateLock) {
                buildTcpResponsePacket(
                    sequenceNumber = serverNextSequence,
                    acknowledgementNumber = clientState.clientNextSequence,
                    flags = TcpFlags.RST or TcpFlags.ACK,
                )
            }
            packetWriter(response)
        }

        private fun buildTcpResponsePacket(
            sequenceNumber: Int,
            acknowledgementNumber: Int,
            flags: Int,
            payload: ByteArray = ByteArray(0),
        ): ByteArray {
            return IpPacketCodec.buildTcpPacket(
                ipVersion = key.ipVersion,
                sourceAddress = key.destinationAddress,
                destinationAddress = key.sourceAddress,
                sourcePort = key.destinationPort,
                destinationPort = key.sourcePort,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                flags = flags,
                windowSize = DEFAULT_TCP_WINDOW_SIZE,
                payload = payload,
            )
        }

        private fun sleepBeforeClosing() {
            try {
                Thread.sleep(TCP_FIN_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun touch() {
            lastActivityAt.set(System.currentTimeMillis())
        }

        private fun maxTcpPayloadSize(): Int {
            val ipHeaderLength = if (key.ipVersion == IpVersion.IPV4) IPV4_HEADER_LENGTH else IPV6_HEADER_LENGTH
            return (mtu - ipHeaderLength - TCP_HEADER_LENGTH).coerceIn(
                MIN_TCP_PAYLOAD_SIZE,
                MAX_TCP_PAYLOAD_SIZE,
            )
        }
    }

    private class UdpRelaySession(
        private val service: VpnService,
        private val key: UdpFlowKey,
        private val hostCorrelation: QuicHostCorrelation,
        private val isRunning: () -> Boolean,
        private val packetWriter: (ByteArray) -> Unit,
        private val onClosed: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val lastActivityAt = AtomicLong(System.currentTimeMillis())
        private val socket = DatagramSocket()
        private val receiverThread: Thread

        init {
            if (!service.protect(socket)) {
                socket.close()
                throw IOException("Failed to protect UDP socket from VPN routing loop.")
            }

            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(
                key.destinationAddress,
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

            touch()
            socket.send(DatagramPacket(payload, payload.size))
        }

        fun isExpired(now: Long): Boolean {
            return now - lastActivityAt.get() > UDP_SESSION_IDLE_TIMEOUT_MS
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
                    if (key.destinationPort == DNS_PORT) {
                        hostCorrelation.observeDnsResponse(payload)
                    }
                    touch()
                    val response = IpPacketCodec.buildUdpPacket(
                        ipVersion = key.ipVersion,
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

        private fun touch() {
            lastActivityAt.set(System.currentTimeMillis())
        }
    }

    companion object {
        val CAPABILITIES = TunForwarderCapabilities(
            ipv4PacketCodecReady = true,
            ipv6PacketCodecReady = true,
            ipv4UdpForwarderReady = true,
            ipv6UdpForwarderReady = true,
            tcpForwarderReady = true,
        )

        private fun initialServerSequence(key: TcpFlowKey, segment: TcpSegment): Int {
            var seed = 17
            seed = 31 * seed + key.sourceAddress.hashCode()
            seed = 31 * seed + key.sourcePort
            seed = 31 * seed + key.destinationAddress.hashCode()
            seed = 31 * seed + key.destinationPort
            seed = 31 * seed + segment.sequenceNumber
            return seed
        }

        private const val DEFAULT_PACKET_BUFFER_SIZE = 16_384
        private const val MAX_UDP_PACKET_SIZE = 65_507
        private const val DNS_PORT = 53
        private const val QUIC_HTTPS_PORT = 443
        private const val SESSION_CLEANUP_INTERVAL_MS = 30_000L
        private const val UDP_SESSION_IDLE_TIMEOUT_MS = 120_000L
        private const val TCP_SESSION_IDLE_TIMEOUT_MS = 120_000L
        private const val TCP_CONNECT_TIMEOUT_MS = 10_000
        private const val TCP_FIN_GRACE_MS = 1_000L
        private const val SOCKET_TIMEOUT_MS = 1_000
        private const val MAX_PENDING_TCP_PAYLOAD_BYTES = 256 * 1024
        private const val IPV4_HEADER_LENGTH = 20
        private const val IPV6_HEADER_LENGTH = 40
        private const val TCP_HEADER_LENGTH = 20
        private const val MIN_TCP_PAYLOAD_SIZE = 512
        private const val MAX_TCP_PAYLOAD_SIZE = 8_192
        private const val DEFAULT_TCP_WINDOW_SIZE = 65_535
        private const val DEFAULT_TCP_SPLIT_POSITION = 1
    }
}
