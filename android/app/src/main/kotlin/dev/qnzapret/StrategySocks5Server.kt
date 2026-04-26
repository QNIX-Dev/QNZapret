package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class StrategySocks5Server(
    private val service: VpnService,
    private val host: String,
    private val port: Int,
    private val engine: StrategyRuntimeEngine,
) {
    private val running = AtomicBoolean(false)
    private val closeables = ConcurrentHashMap.newKeySet<Closeable>()
    private val quicHostCorrelation = QuicHostCorrelation()
    private lateinit var executor: ExecutorService
    private var serverSocket: ServerSocket? = null
    private var udpRelay: UdpRelay? = null

    fun start(): LocalStrategyProxyEndpoint {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("SOCKS5 strategy proxy is already running")
        }

        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "QNZapretSocks5").apply { isDaemon = true }
        }

        val nextServerSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
        val actualEndpoint = LocalStrategyProxyEndpoint(
            host = host,
            port = nextServerSocket.localPort,
        )
        val nextUdpRelay = UdpRelay(actualEndpoint.host).also { it.start() }

        serverSocket = nextServerSocket
        udpRelay = nextUdpRelay
        closeables += nextServerSocket
        Log.d(LOG_TAG, "socks5 strategy proxy listening ${actualEndpoint.host}:${actualEndpoint.port}")

        executor.execute { acceptLoop(nextServerSocket) }
        return actualEndpoint
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }

        closeables.forEach(::closeQuietly)
        closeables.clear()
        udpRelay?.stop()
        udpRelay = null
        serverSocket = null
        if (::executor.isInitialized) {
            executor.shutdownNow()
        }
        Log.d(LOG_TAG, "socks5 strategy proxy stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                client.tcpNoDelay = true
                closeables += client
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (running.get()) {
                    Log.d(LOG_TAG, "socks5 accept socket closed")
                }
                return
            } catch (error: IOException) {
                if (running.get()) {
                    Log.d(LOG_TAG, "socks5 accept failed ${error.javaClass.simpleName}:${error.message ?: "-"}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            handleHandshake(input, output)
            val request = readRequest(input)

            when (request.command) {
                SOCKS_CMD_CONNECT -> handleConnect(client, request, output)
                SOCKS_CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, output)
                else -> {
                    writeReply(output, SOCKS_REPLY_COMMAND_NOT_SUPPORTED, null, 0)
                    closeQuietly(client)
                }
            }
        } catch (_: EOFException) {
            closeQuietly(client)
        } catch (error: IOException) {
            Log.d(LOG_TAG, "socks5 client failed ${error.javaClass.simpleName}:${error.message ?: "-"}")
            closeQuietly(client)
        } finally {
            closeables.remove(client)
        }
    }

    private fun handleHandshake(input: InputStream, output: OutputStream) {
        val version = input.readByte()
        if (version != SOCKS_VERSION) {
            throw EOFException("Unsupported SOCKS version $version")
        }
        val methodCount = input.readByte()
        input.readBytesExact(methodCount)
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), SOCKS_AUTH_NO_AUTH.toByte()))
        output.flush()
    }

    private fun readRequest(input: InputStream): SocksRequest {
        val version = input.readByte()
        if (version != SOCKS_VERSION) {
            throw EOFException("Unsupported SOCKS request version $version")
        }
        val command = input.readByte()
        input.readByte()
        val target = readTarget(input)
        return SocksRequest(command = command, target = target)
    }

    private fun readTarget(input: InputStream): SocksTarget {
        return when (val addressType = input.readByte()) {
            SOCKS_ATYP_IPV4 -> {
                val address = InetAddress.getByAddress(input.readBytesExact(IPV4_BYTES))
                SocksTarget(
                    host = address.hostAddress ?: "",
                    port = input.readPort(),
                    inetAddress = address,
                )
            }
            SOCKS_ATYP_DOMAIN -> {
                val length = input.readByte()
                val host = String(input.readBytesExact(length), Charsets.US_ASCII)
                SocksTarget(
                    host = host,
                    port = input.readPort(),
                    inetAddress = null,
                )
            }
            SOCKS_ATYP_IPV6 -> {
                val address = InetAddress.getByAddress(input.readBytesExact(IPV6_BYTES))
                SocksTarget(
                    host = address.hostAddress ?: "",
                    port = input.readPort(),
                    inetAddress = address,
                )
            }
            else -> throw EOFException("Unsupported SOCKS address type $addressType")
        }
    }

    private fun handleConnect(
        client: Socket,
        request: SocksRequest,
        clientOutput: OutputStream,
    ) {
        var upstream: Socket? = null
        try {
            upstream = openProtectedTcpSocket(request.target)
            writeReply(clientOutput, SOCKS_REPLY_SUCCEEDED, upstream.localAddress, upstream.localPort)
            relayTcp(client, upstream, request.target)
        } catch (error: IOException) {
            if (running.get()) {
                Log.d(
                    LOG_TAG,
                    "socks tcp connect failed target=${request.target.format()} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
                runCatching { writeReply(clientOutput, SOCKS_REPLY_HOST_UNREACHABLE, null, 0) }
            }
            upstream?.let(::closeQuietly)
            closeQuietly(client)
        } finally {
            upstream?.let { closeables.remove(it) }
        }
    }

    private fun openProtectedTcpSocket(target: SocksTarget): Socket {
        if (!running.get()) {
            throw SocketException("SOCKS5 strategy proxy is stopping")
        }
        val network = UnderlyingNetworkSelector.select(service)
        val socket = createTcpSocket(network)
        closeables += socket
        try {
            if (!running.get()) {
                throw SocketException("SOCKS5 strategy proxy is stopping")
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            socket.bind(null)
            if (!service.protect(socket)) {
                throw IOException("VpnService.protect returned false")
            }
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            Log.d(
                LOG_TAG,
                "socks tcp connect ok target=${target.format()} " +
                    "network=${network ?: "-"} local=${socket.localAddress.hostAddress}:${socket.localPort}",
            )
            return socket
        } catch (error: IOException) {
            closeables.remove(socket)
            closeQuietly(socket)
            throw error
        }
    }

    private fun createTcpSocket(network: android.net.Network?): Socket {
        if (network == null) {
            return Socket()
        }
        return try {
            network.socketFactory.createSocket()
        } catch (error: IOException) {
            Log.d(
                LOG_TAG,
                "socks tcp network socket fallback network=$network " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            Socket()
        }
    }

    private fun relayTcp(client: Socket, upstream: Socket, target: SocksTarget) {
        val remoteToClient = executor.submit {
            copyStream(
                input = upstream.getInputStream(),
                output = client.getOutputStream(),
                onFinished = {
                    runCatching { client.shutdownOutput() }
                    closeQuietly(client)
                    closeQuietly(upstream)
                },
            )
        }

        try {
            val input = client.getInputStream()
            val output = upstream.getOutputStream()
            val buffer = ByteArray(TCP_BUFFER_SIZE)
            var strategyEvaluated = false

            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }

                val payload = buffer.copyOf(read)
                val writes = if (strategyEvaluated) {
                    listOf(TcpWrite(payload = payload))
                } else {
                    strategyEvaluated = true
                    transformTcpPayload(payload, target)
                }
                writes.forEach { write -> writeTcpPayload(upstream, output, target, write) }
                output.flush()
            }
        } catch (_: SocketException) {
        } catch (error: IOException) {
            Log.d(
                LOG_TAG,
                "socks tcp relay failed target=${target.format()} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        } finally {
            runCatching { upstream.shutdownOutput() }
            closeQuietly(upstream)
            closeQuietly(client)
            remoteToClient.cancel(true)
        }
    }

    private fun transformTcpPayload(payload: ByteArray, target: SocksTarget): List<TcpWrite> {
        val decision = engine.evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.TCP,
                destinationPort = target.port,
                payload = payload,
            ),
        )
        logStrategyDecision(StrategyTransport.TCP, target, null, decision)
        target.inetAddress?.let { address ->
            quicHostCorrelation.rememberHost(address, decision.host)
        }
        if (decision.kind != StrategyDecisionKind.DESYNC) {
            return listOf(TcpWrite(payload = payload))
        }

        val writes = mutableListOf<TcpWrite>()
        decision.actions
            .filter { action -> action.kind == StrategyActionKind.FAKE }
            .forEach { action ->
                val fakePayload = action.blobPayload ?: return@forEach
                repeat(action.repeats.coerceAtLeast(1)) {
                    writes += TcpWrite(payload = fakePayload, fake = true)
                }
            }

        var chunks = listOf(payload)
        decision.actions.forEach { action ->
            if (action.kind == StrategyActionKind.SPLIT) {
                chunks = splitTcpChunks(
                    chunks = chunks,
                    position = action.position ?: DEFAULT_TCP_SPLIT_POSITION,
                    protocol = decision.protocol,
                )
            }
        }
        writes += chunks.map { chunk -> TcpWrite(payload = chunk) }
        return writes
    }

    private fun writeTcpPayload(
        socket: Socket,
        output: OutputStream,
        target: SocksTarget,
        write: TcpWrite,
    ) {
        if (!write.fake) {
            output.write(write.payload)
            return
        }

        if (writeFakeToRemote(socket, output, target, write.payload)) {
            return
        }
        Log.d(LOG_TAG, "socks tcp fake skipped target=${target.format()} reason=ttl_unavailable")
    }

    private fun writeFakeToRemote(
        socket: Socket,
        output: OutputStream,
        target: SocksTarget,
        payload: ByteArray,
    ): Boolean {
        val pfd = try {
            ParcelFileDescriptor.fromSocket(socket).dup()
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "socks tcp fake fd failed target=${target.format()} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            return false
        }

        try {
            val option = hopLimitSocketOption(socket.inetAddress)
            Os.setsockoptInt(pfd.fileDescriptor, option.level, option.option, DEFAULT_TCP_FAKE_HOP_LIMIT)
            output.write(payload)
            output.flush()
            Log.d(
                LOG_TAG,
                "socks tcp fake sent target=${target.format()} ttl=$DEFAULT_TCP_FAKE_HOP_LIMIT " +
                    "bytes=${payload.size}",
            )
            restoreDefaultHopLimit(pfd, option)
            return true
        } catch (error: ErrnoException) {
            Log.d(
                LOG_TAG,
                "socks tcp fake ttl failed target=${target.format()} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            return false
        } finally {
            restoreDefaultHopLimit(pfd, hopLimitSocketOption(socket.inetAddress))
            closeQuietly(pfd)
        }
    }

    private fun splitTcpChunks(
        chunks: List<ByteArray>,
        position: Int,
        protocol: StrategyProtocol?,
    ): List<ByteArray> {
        return chunks.flatMap { chunk ->
            if (protocol == StrategyProtocol.TLS) {
                val transformed = TlsRecordSplitTransform.splitFirstHandshakeRecord(chunk, position)
                if (transformed != null) {
                    Log.d(LOG_TAG, "socks tls record split bytes=${chunk.size}->${transformed.size}")
                    return@flatMap listOf(transformed)
                }
            }

            splitStreamChunk(chunk, position)
        }
    }

    private fun splitStreamChunk(chunk: ByteArray, position: Int): List<ByteArray> {
        if (position <= 0 || position >= chunk.size) {
            return listOf(chunk)
        }
        return listOf(
            chunk.copyOfRange(0, position),
            chunk.copyOfRange(position, chunk.size),
        )
    }

    private fun handleUdpAssociate(
        client: Socket,
        output: OutputStream,
    ) {
        val relay = udpRelay ?: throw IOException("UDP relay is not running")
        writeReply(output, SOCKS_REPLY_SUCCEEDED, relay.bindAddress, relay.port)
        try {
            val input = client.getInputStream()
            while (running.get() && input.read() >= 0) {
                // The TCP control channel defines UDP association lifetime.
            }
        } catch (_: SocketException) {
        } finally {
            closeQuietly(client)
        }
    }

    private fun writeReply(
        output: OutputStream,
        reply: Int,
        bindAddress: InetAddress?,
        bindPort: Int,
    ) {
        val address = bindAddress ?: InetAddress.getByName("0.0.0.0")
        output.write(SOCKS_VERSION)
        output.write(reply)
        output.write(0)
        when (address) {
            is Inet4Address -> {
                output.write(SOCKS_ATYP_IPV4)
                output.write(address.address)
            }
            is Inet6Address -> {
                output.write(SOCKS_ATYP_IPV6)
                output.write(address.address)
            }
            else -> {
                output.write(SOCKS_ATYP_IPV4)
                output.write(byteArrayOf(0, 0, 0, 0))
            }
        }
        output.write((bindPort ushr 8) and BYTE_MASK)
        output.write(bindPort and BYTE_MASK)
        output.flush()
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        onFinished: () -> Unit,
    ) {
        try {
            val buffer = ByteArray(TCP_BUFFER_SIZE)
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } finally {
            onFinished()
        }
    }

    private inner class UdpRelay(
        host: String,
    ) {
        private val clientSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), 0))
        }
        private val remoteSocket = DatagramSocket(null)
        private val clientsByRemote = ConcurrentHashMap<UdpRemoteKey, InetSocketAddress>()

        val bindAddress: InetAddress
            get() = clientSocket.localAddress

        val port: Int
            get() = clientSocket.localPort

        fun start() {
            if (!service.protect(remoteSocket)) {
                throw IOException("VpnService.protect returned false for UDP")
            }
            val network = UnderlyingNetworkSelector.select(service)
            if (network != null) {
                try {
                    network.bindSocket(remoteSocket)
                } catch (error: IOException) {
                    Log.d(
                        LOG_TAG,
                        "socks udp network bind fallback network=$network " +
                            "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                }
            }
            remoteSocket.bind(InetSocketAddress(0))
            remoteSocket.soTimeout = UDP_SOCKET_TIMEOUT_MS

            closeables += clientSocket
            closeables += remoteSocket
            executor.execute { clientToRemoteLoop() }
            executor.execute { remoteToClientLoop() }
            Log.d(LOG_TAG, "socks udp relay listening ${bindAddress.hostAddress}:$port")
        }

        fun stop() {
            closeables.remove(clientSocket)
            closeables.remove(remoteSocket)
            closeQuietly(clientSocket)
            closeQuietly(remoteSocket)
        }

        private fun clientToRemoteLoop() {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    clientSocket.receive(packet)
                    val clientAddress = packet.socketAddress as? InetSocketAddress ?: continue
                    val frame = parseUdpFrame(packet.data, packet.offset, packet.length) ?: continue
                    clientsByRemote[UdpRemoteKey(frame.target.host, frame.target.port)] = clientAddress
                    sendUdpWithStrategy(frame.target, frame.payload)
                } catch (_: SocketException) {
                    return
                } catch (error: IOException) {
                    if (running.get()) {
                        Log.d(
                            LOG_TAG,
                            "socks udp client relay failed " +
                                "${error.javaClass.simpleName}:${error.message ?: "-"}",
                        )
                    }
                }
            }
        }

        private fun remoteToClientLoop() {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    remoteSocket.receive(packet)
                    val remoteAddress = packet.socketAddress as? InetSocketAddress ?: continue
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (remoteAddress.port == DNS_PORT) {
                        quicHostCorrelation.observeDnsResponse(payload)
                    }
                    val clientAddress = clientsByRemote[UdpRemoteKey(remoteAddress.address.hostAddress ?: "", remoteAddress.port)]
                        ?: clientsByRemote[UdpRemoteKey(remoteAddress.hostString, remoteAddress.port)]
                        ?: continue
                    val response = buildUdpFrame(remoteAddress.address, remoteAddress.port, payload)
                    clientSocket.send(DatagramPacket(response, response.size, clientAddress))
                } catch (_: SocketTimeoutException) {
                } catch (_: SocketException) {
                    return
                } catch (error: IOException) {
                    if (running.get()) {
                        Log.d(
                            LOG_TAG,
                            "socks udp remote relay failed " +
                                "${error.javaClass.simpleName}:${error.message ?: "-"}",
                        )
                    }
                }
            }
        }

        private fun sendUdpWithStrategy(target: SocksTarget, payload: ByteArray) {
            val knownHost = when {
                target.port == QUIC_HTTPS_PORT && target.inetAddress != null -> {
                    quicHostCorrelation.lookupHost(target.inetAddress)
                }
                target.inetAddress == null -> target.host
                else -> null
            }
            val decision = engine.evaluate(
                StrategyFlowProbe(
                    transport = StrategyTransport.UDP,
                    destinationPort = target.port,
                    payload = payload,
                    knownHost = knownHost,
                ),
            )
            logStrategyDecision(StrategyTransport.UDP, target, knownHost, decision)

            decision.actions
                .filter { action -> action.kind == StrategyActionKind.UDP_FAKE }
                .forEach { action ->
                    val fakePayload = action.blobPayload ?: return@forEach
                    repeat(action.repeats.coerceAtLeast(1)) {
                        sendRemoteDatagram(target, fakePayload)
                    }
                }
            sendRemoteDatagram(target, payload)
        }

        private fun sendRemoteDatagram(target: SocksTarget, payload: ByteArray) {
            val address = target.inetAddress ?: InetAddress.getByName(target.host)
            val packet = DatagramPacket(payload, payload.size, address, target.port)
            remoteSocket.send(packet)
        }

        private fun parseUdpFrame(
            source: ByteArray,
            offset: Int,
            length: Int,
        ): SocksUdpFrame? {
            if (length < SOCKS_UDP_HEADER_MIN_LENGTH) {
                return null
            }
            var cursor = offset
            val end = offset + length
            if (source[cursor].toInt() != 0 || source[cursor + 1].toInt() != 0) {
                return null
            }
            cursor += 2
            val fragment = source[cursor].toInt() and BYTE_MASK
            cursor += 1
            if (fragment != 0) {
                return null
            }

            val target = when (val addressType = source[cursor].toInt() and BYTE_MASK) {
                SOCKS_ATYP_IPV4 -> {
                    cursor += 1
                    if (cursor + IPV4_BYTES + PORT_BYTES > end) return null
                    val address = InetAddress.getByAddress(source.copyOfRange(cursor, cursor + IPV4_BYTES))
                    cursor += IPV4_BYTES
                    SocksTarget(
                        host = address.hostAddress ?: "",
                        port = source.readPort(cursor),
                        inetAddress = address,
                    ).also { cursor += PORT_BYTES }
                }
                SOCKS_ATYP_DOMAIN -> {
                    cursor += 1
                    if (cursor >= end) return null
                    val hostLength = source[cursor].toInt() and BYTE_MASK
                    cursor += 1
                    if (cursor + hostLength + PORT_BYTES > end) return null
                    val host = String(source, cursor, hostLength, Charsets.US_ASCII)
                    cursor += hostLength
                    SocksTarget(
                        host = host,
                        port = source.readPort(cursor),
                        inetAddress = null,
                    ).also { cursor += PORT_BYTES }
                }
                SOCKS_ATYP_IPV6 -> {
                    cursor += 1
                    if (cursor + IPV6_BYTES + PORT_BYTES > end) return null
                    val address = InetAddress.getByAddress(source.copyOfRange(cursor, cursor + IPV6_BYTES))
                    cursor += IPV6_BYTES
                    SocksTarget(
                        host = address.hostAddress ?: "",
                        port = source.readPort(cursor),
                        inetAddress = address,
                    ).also { cursor += PORT_BYTES }
                }
                else -> {
                    Log.d(LOG_TAG, "socks udp unsupported atyp=$addressType")
                    return null
                }
            }

            return SocksUdpFrame(
                target = target,
                payload = source.copyOfRange(cursor, end),
            )
        }

        private fun buildUdpFrame(
            address: InetAddress,
            port: Int,
            payload: ByteArray,
        ): ByteArray {
            val addressBytes = address.address
            val addressType = when (address) {
                is Inet4Address -> SOCKS_ATYP_IPV4
                is Inet6Address -> SOCKS_ATYP_IPV6
                else -> SOCKS_ATYP_IPV4
            }
            val result = ByteArray(3 + 1 + addressBytes.size + PORT_BYTES + payload.size)
            var cursor = 0
            result[cursor++] = 0
            result[cursor++] = 0
            result[cursor++] = 0
            result[cursor++] = addressType.toByte()
            addressBytes.copyInto(result, cursor)
            cursor += addressBytes.size
            result[cursor++] = ((port ushr 8) and BYTE_MASK).toByte()
            result[cursor++] = (port and BYTE_MASK).toByte()
            payload.copyInto(result, cursor)
            return result
        }
    }

    private fun logStrategyDecision(
        transport: StrategyTransport,
        target: SocksTarget,
        knownHost: String?,
        decision: StrategyDecision,
    ) {
        if (decision.kind != StrategyDecisionKind.DESYNC && decision.protocol == null) {
            return
        }
        Log.d(
            LOG_TAG,
            "strategy socks transport=${transport.name.lowercase()} target=${target.format()} " +
                "knownHost=${knownHost ?: "-"} protocol=${decision.protocol?.wireValue ?: "-"} " +
                "host=${decision.host ?: "-"} decision=${decision.kind} rule=${decision.ruleId ?: "-"} " +
                "reason=${decision.reason} actions=${formatActions(decision.actions)}",
        )
    }

    private fun formatActions(actions: List<ResolvedStrategyAction>): String {
        if (actions.isEmpty()) {
            return "-"
        }
        return actions.joinToString(separator = "+") { action ->
            when (action.kind) {
                StrategyActionKind.FAKE -> "fake(blob=${action.blobKey ?: "-"},bytes=${action.blobPayload?.size ?: 0})"
                StrategyActionKind.SPLIT -> "split(position=${action.position ?: DEFAULT_TCP_SPLIT_POSITION})"
                StrategyActionKind.UDP_FAKE -> {
                    "udpFake(blob=${action.blobKey ?: "-"},bytes=${action.blobPayload?.size ?: 0})"
                }
            }
        }
    }

    private fun restoreDefaultHopLimit(pfd: ParcelFileDescriptor, option: HopLimitOption) {
        try {
            Os.setsockoptInt(pfd.fileDescriptor, option.level, option.option, DEFAULT_TCP_HOP_LIMIT)
        } catch (_: ErrnoException) {
        }
    }

    private fun hopLimitSocketOption(address: InetAddress?): HopLimitOption {
        return if (address is Inet6Address) {
            HopLimitOption(OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS)
        } else {
            HopLimitOption(OsConstants.IPPROTO_IP, OsConstants.IP_TTL)
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    private fun InputStream.readByte(): Int {
        val value = read()
        if (value < 0) {
            throw EOFException()
        }
        return value
    }

    private fun InputStream.readBytesExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            if (read < 0) {
                throw EOFException()
            }
            offset += read
        }
        return result
    }

    private fun InputStream.readPort(): Int {
        return (readByte() shl 8) or readByte()
    }

    private fun ByteArray.readPort(offset: Int): Int {
        return (((this[offset].toInt() and BYTE_MASK) shl 8) or
            (this[offset + 1].toInt() and BYTE_MASK))
    }

    private fun SocksTarget.format(): String {
        return "$host:$port"
    }

    private data class SocksRequest(
        val command: Int,
        val target: SocksTarget,
    )

    private data class SocksTarget(
        val host: String,
        val port: Int,
        val inetAddress: InetAddress?,
    )

    private data class SocksUdpFrame(
        val target: SocksTarget,
        val payload: ByteArray,
    )

    private data class UdpRemoteKey(
        val host: String,
        val port: Int,
    )

    private data class TcpWrite(
        val payload: ByteArray,
        val fake: Boolean = false,
    )

    private data class HopLimitOption(
        val level: Int,
        val option: Int,
    )

    private companion object {
        private const val LOG_TAG = "QNZapretProxy"
        private const val SOCKS_VERSION = 0x05
        private const val SOCKS_AUTH_NO_AUTH = 0x00
        private const val SOCKS_CMD_CONNECT = 0x01
        private const val SOCKS_CMD_UDP_ASSOCIATE = 0x03
        private const val SOCKS_ATYP_IPV4 = 0x01
        private const val SOCKS_ATYP_DOMAIN = 0x03
        private const val SOCKS_ATYP_IPV6 = 0x04
        private const val SOCKS_REPLY_SUCCEEDED = 0x00
        private const val SOCKS_REPLY_HOST_UNREACHABLE = 0x04
        private const val SOCKS_REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val BYTE_MASK = 0xff
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16
        private const val PORT_BYTES = 2
        private const val SOCKS_UDP_HEADER_MIN_LENGTH = 10
        private const val TCP_BUFFER_SIZE = 16 * 1024
        private const val UDP_BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val UDP_SOCKET_TIMEOUT_MS = 1_000
        private const val DEFAULT_TCP_SPLIT_POSITION = 1
        private const val DEFAULT_TCP_FAKE_HOP_LIMIT = 8
        private const val DEFAULT_TCP_HOP_LIMIT = 64
        private const val DNS_PORT = 53
        private const val QUIC_HTTPS_PORT = 443
    }
}
