package dev.qnzapret

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
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
    private val endpointPolicies: List<StrategyEndpointPolicy> = emptyList(),
    private val tunnelMtu: Int = 8500,
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
        val endpointClass = request.target.endpointClass()
        if (endpointClass.isTelegramEndpointClass() && isTelegramTransparentProbeEnabled()) {
            handleTelegramTransparentProbe(
                client = client,
                target = request.target,
                clientOutput = clientOutput,
                endpointClass = endpointClass,
            )
            return
        }

        var upstream: ProtectedTcpSocket? = null
        try {
            upstream = openProtectedTcpSocket(request.target)
            writeReply(clientOutput, SOCKS_REPLY_SUCCEEDED, upstream.socket.localAddress, upstream.socket.localPort)
            relayTcp(client, upstream, request.target)
        } catch (error: IOException) {
            if (running.get()) {
                if (upstream != null) {
                    Log.d(
                        LOG_TAG,
                        "socks tcp setup failed target=${request.target.format()} " +
                            "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                }
                runCatching { writeReply(clientOutput, SOCKS_REPLY_HOST_UNREACHABLE, null, 0) }
            }
            upstream?.socket?.let(::closeQuietly)
            closeQuietly(client)
        } finally {
            upstream?.socket?.let { closeables.remove(it) }
        }
    }

    private fun isTelegramTransparentProbeEnabled(): Boolean {
        return telegramTransparentProbeFiles().any { file -> file.exists() }
    }

    private fun telegramTransparentProbeFiles(): List<File> {
        return buildList {
            service.getExternalFilesDir(null)?.let { root ->
                add(File(root, TELEGRAM_TRANSPARENT_PROBE_FLAG_PATH))
            }
            add(File(service.filesDir, TELEGRAM_TRANSPARENT_PROBE_FLAG_PATH))
            add(File(service.cacheDir, TELEGRAM_TRANSPARENT_PROBE_FLAG_PATH))
        }
    }

    private fun handleTelegramTransparentProbe(
        client: Socket,
        target: SocksTarget,
        clientOutput: OutputStream,
        endpointClass: String,
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val dcClass = target.telegramDcClass()
        Log.d(
            LOG_TAG,
            "telegram transparent probe start originalTarget=${target.format()} " +
                "dcClass=$dcClass endpointClass=$endpointClass mode=early_socks_success " +
                "timeoutMs=$TELEGRAM_TRANSPARENT_PROBE_READ_TIMEOUT_MS",
        )

        try {
            writeReply(clientOutput, SOCKS_REPLY_SUCCEEDED, null, 0)
            client.soTimeout = TELEGRAM_TRANSPARENT_PROBE_READ_TIMEOUT_MS
            val buffer = ByteArray(TELEGRAM_TRANSPARENT_PROBE_MAX_BYTES)
            val read = client.getInputStream().read(buffer)
            if (read <= 0) {
                Log.d(
                    LOG_TAG,
                    "telegram transparent probe failed originalTarget=${target.format()} " +
                        "dcClass=$dcClass endpointClass=$endpointClass errorCode=probe_no_payload " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                )
                return
            }

            val payload = buffer.copyOf(read)
            Log.d(
                LOG_TAG,
                "telegram transparent first payload originalTarget=${target.format()} " +
                    "dcClass=$dcClass endpointClass=$endpointClass bytes=$read " +
                    "protoHint=${payload.telegramProtoHint()} " +
                    "hexPreview=${payload.redactedHexPreview()} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
            Log.d(
                LOG_TAG,
                "telegram transparent probe complete originalTarget=${target.format()} " +
                    "dcClass=$dcClass endpointClass=$endpointClass " +
                    "result=payload_captured_transport_not_started",
            )
        } catch (error: SocketTimeoutException) {
            Log.d(
                LOG_TAG,
                "telegram transparent probe failed originalTarget=${target.format()} " +
                    "dcClass=$dcClass endpointClass=$endpointClass errorCode=probe_timeout " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        } catch (error: IOException) {
            if (running.get()) {
                Log.d(
                    LOG_TAG,
                    "telegram transparent probe failed originalTarget=${target.format()} " +
                        "dcClass=$dcClass endpointClass=$endpointClass errorCode=probe_io_failed " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
        } finally {
            closeQuietly(client)
        }
    }

    private fun openProtectedTcpSocket(target: SocksTarget): ProtectedTcpSocket {
        if (!running.get()) {
            throw SocketException("SOCKS5 strategy proxy is stopping")
        }
        val network = UnderlyingNetworkSelector.select(service)
        val selectedNetworkSupportsIpv6 = network
            ?.let { selectedNetwork -> UnderlyingNetworkSelector.supportsIpv6(service, selectedNetwork) }
            ?: false
        val endpointClass = target.endpointClass()
        val startedAtMs = SystemClock.elapsedRealtime()
        Log.d(
            LOG_TAG,
            "socks tcp connect start target=${target.format()} endpointClass=$endpointClass " +
                "network=${network ?: "-"} ipv6=${target.isIpv6()} " +
                "selectedIpv6=$selectedNetworkSupportsIpv6 timeoutMs=$CONNECT_TIMEOUT_MS",
        )

        if (endpointClass.isTelegramEndpointClass()) {
            val relayPolicy = selectTelegramRelayPolicy(endpointClass)
            if (relayPolicy != null) {
                return openTelegramRelayTcpSocket(
                    originalTarget = target,
                    network = network,
                    endpointClass = endpointClass,
                    startedAtMs = startedAtMs,
                    policy = relayPolicy,
                )
            }
            Log.d(
                LOG_TAG,
                "telegram relay connect failed originalTarget=${target.format()} relay=- " +
                    "errorCode=relay_unconfigured endpointClass=$endpointClass " +
                    "dcClass=${target.telegramDcClass()}",
            )
            if (target.isIpv6() && !selectedNetworkSupportsIpv6) {
                logNoIpv6Route(target, endpointClass, network, startedAtMs)
                throw NoRouteToHostException("Underlying network has no IPv6 route")
            }
            return openTelegramProtectedTcpSocket(
                originalTarget = target,
                network = network,
                selectedNetworkSupportsIpv6 = selectedNetworkSupportsIpv6,
                endpointClass = endpointClass,
                startedAtMs = startedAtMs,
            )
        }

        if (target.isIpv6() && !selectedNetworkSupportsIpv6) {
            logNoIpv6Route(target, endpointClass, network, startedAtMs)
            throw NoRouteToHostException("Underlying network has no IPv6 route")
        }

        return connectProtectedTcpSocket(
            target = target,
            network = network,
            endpointClass = endpointClass,
            timeoutMs = CONNECT_TIMEOUT_MS,
            startedAtMs = startedAtMs,
            logFailure = true,
        )
    }

    private fun openTelegramRelayTcpSocket(
        originalTarget: SocksTarget,
        network: Network?,
        endpointClass: String,
        startedAtMs: Long,
        policy: StrategyEndpointPolicy,
    ): ProtectedTcpSocket {
        val route = policy.route
        val relay = route.relayLabel()
        val dcClass = originalTarget.telegramDcClass()
        Log.d(
            LOG_TAG,
            "telegram relay connect start originalTarget=${originalTarget.format()} " +
                "relay=$relay protocol=${route.protocol.wireValue} endpointClass=$endpointClass dcClass=$dcClass",
        )

        val socket = Socket()
        closeables += socket
        val relayConnectStartedAtMs = SystemClock.elapsedRealtime()
        try {
            if (!running.get()) {
                throw SocketException("SOCKS5 strategy proxy is stopping")
            }
            if (route.kind != StrategyEndpointRouteKind.REMOTE_RELAY) {
                throw Socks5RelayException(
                    Socks5RelayClient.RELAY_PROTOCOL_ERROR,
                    "Unsupported endpoint route kind ${route.kind.wireValue}",
                )
            }
            if (route.protocol != StrategyRelayProtocol.SOCKS5) {
                throw Socks5RelayException(
                    Socks5RelayClient.RELAY_PROTOCOL_ERROR,
                    "Relay protocol ${route.protocol.wireValue} is not implemented",
                )
            }
            if (route.host.isBlank() || route.port !in 1..MAX_TCP_PORT) {
                throw Socks5RelayException(
                    Socks5RelayClient.RELAY_PROTOCOL_ERROR,
                    "Relay route is missing host or port",
                )
            }

            socket.tcpNoDelay = true
            if (!service.protect(socket)) {
                throw IOException("VpnService.protect returned false")
            }
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (error: IOException) {
                    Log.d(
                        LOG_TAG,
                        "telegram relay network bind fallback relay=$relay network=$network " +
                            "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                }
            }
            socket.connect(
                InetSocketAddress(route.host, route.port),
                route.connectTimeoutMs.coerceAtLeast(1),
            )
            val relayConnectedAtMs = SystemClock.elapsedRealtime()
            Socks5RelayClient.connect(
                socket = socket,
                target = Socks5RelayTarget(
                    host = originalTarget.host,
                    port = originalTarget.port,
                    inetAddress = originalTarget.inetAddress,
                ),
                auth = route.auth?.toSocks5RelayAuth(),
                timeoutMs = route.relayConnectTimeoutMs.coerceAtLeast(1),
            )
            val relayReadyAtMs = SystemClock.elapsedRealtime()
            socket.soTimeout = 0
            Log.d(
                LOG_TAG,
                "telegram relay connect ok originalTarget=${originalTarget.format()} relay=$relay " +
                    "connectMs=${relayConnectedAtMs - relayConnectStartedAtMs} " +
                    "relayHandshakeMs=${relayReadyAtMs - relayConnectedAtMs}",
            )
            return ProtectedTcpSocket(
                socket = socket,
                connectedAtMs = relayReadyAtMs,
                relayInfo = TelegramRelayInfo(
                    originalTarget = originalTarget.format(),
                    connectedAtMs = relayReadyAtMs,
                ),
            )
        } catch (error: Socks5RelayException) {
            logTelegramRelayFailure(
                originalTarget = originalTarget,
                relay = relay,
                endpointClass = endpointClass,
                dcClass = dcClass,
                startedAtMs = startedAtMs,
                errorCode = error.errorCode,
                error = error,
            )
            closeables.remove(socket)
            closeQuietly(socket)
            throw error
        } catch (error: IOException) {
            val relayError = Socks5RelayException(
                Socks5RelayClient.RELAY_CONNECT_FAILED,
                error.message ?: error.javaClass.simpleName,
                error,
            )
            logTelegramRelayFailure(
                originalTarget = originalTarget,
                relay = relay,
                endpointClass = endpointClass,
                dcClass = dcClass,
                startedAtMs = startedAtMs,
                errorCode = relayError.errorCode,
                error = error,
            )
            closeables.remove(socket)
            closeQuietly(socket)
            throw relayError
        }
    }

    private fun logTelegramRelayFailure(
        originalTarget: SocksTarget,
        relay: String,
        endpointClass: String,
        dcClass: String,
        startedAtMs: Long,
        errorCode: String,
        error: Exception,
    ) {
        Log.d(
            LOG_TAG,
            "telegram relay connect failed originalTarget=${originalTarget.format()} relay=$relay " +
                "errorCode=$errorCode endpointClass=$endpointClass dcClass=$dcClass " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
        )
    }

    private fun logNoIpv6Route(
        target: SocksTarget,
        endpointClass: String,
        network: Network?,
        startedAtMs: Long,
    ) {
        Log.d(
            LOG_TAG,
            "socks tcp connect failed target=${target.format()} endpointClass=$endpointClass " +
                "network=${network ?: "-"} connectMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "error=NoRouteToHostException:Underlying network has no IPv6 route",
        )
    }

    private fun openTelegramProtectedTcpSocket(
        originalTarget: SocksTarget,
        network: Network?,
        selectedNetworkSupportsIpv6: Boolean,
        endpointClass: String,
        startedAtMs: Long,
    ): ProtectedTcpSocket {
        val dcClass = originalTarget.telegramDcClass()
        val attempts = originalTarget.telegramConnectAttempts()
        Log.d(
            LOG_TAG,
            "telegram preconnect begin originalTarget=${originalTarget.format()} " +
                "targetIp=${originalTarget.targetIp()} targetPort=${originalTarget.port} " +
                "dcClass=$dcClass endpointClass=$endpointClass network=${network ?: "-"} " +
                "selectedIpv6=$selectedNetworkSupportsIpv6 attempts=${attempts.size} " +
                "timeoutMs=$TELEGRAM_CONNECT_TIMEOUT_MS",
        )

        var lastError: IOException? = null
        for ((index, attempt) in attempts.withIndex()) {
            val attemptTarget = attempt.target
            val attemptEndpointClass = attemptTarget.endpointClass().takeIf { it != "-" } ?: endpointClass
            val attemptDcClass = attemptTarget.telegramDcClass()
            val attemptStartedAtMs = SystemClock.elapsedRealtime()
            val chosenAttempt = "${index + 1}/${attempts.size}"

            Log.d(
                LOG_TAG,
                "telegram preconnect attempt originalTarget=${originalTarget.format()} " +
                    "targetIp=${attemptTarget.targetIp()} targetPort=${attemptTarget.port} " +
                    "dcClass=$attemptDcClass endpointClass=$attemptEndpointClass " +
                    "chosenAttempt=$chosenAttempt candidate=${attemptTarget.format()} " +
                    "source=${attempt.source} timeoutMs=${attempt.timeoutMs}",
            )

            if (attemptTarget.isIpv6() && !selectedNetworkSupportsIpv6) {
                val error = NoRouteToHostException("Underlying network has no IPv6 route")
                lastError = error
                Log.d(
                    LOG_TAG,
                    "telegram preconnect failed originalTarget=${originalTarget.format()} " +
                        "candidate=${attemptTarget.format()} chosenAttempt=$chosenAttempt " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
                continue
            }

            try {
                val connected = connectProtectedTcpSocket(
                    target = attemptTarget,
                    network = network,
                    endpointClass = attemptEndpointClass,
                    timeoutMs = attempt.timeoutMs,
                    startedAtMs = attemptStartedAtMs,
                    logFailure = false,
                )
                Log.d(
                    LOG_TAG,
                    "telegram preconnect ok originalTarget=${originalTarget.format()} " +
                        "candidate=${attemptTarget.format()} targetIp=${attemptTarget.targetIp()} " +
                        "targetPort=${attemptTarget.port} dcClass=$attemptDcClass " +
                        "endpointClass=$attemptEndpointClass chosenAttempt=$chosenAttempt " +
                        "connectMs=${connected.connectedAtMs - attemptStartedAtMs} " +
                        "totalMs=${connected.connectedAtMs - startedAtMs}",
                )
                return connected
            } catch (error: IOException) {
                lastError = error
                Log.d(
                    LOG_TAG,
                    "telegram preconnect failed originalTarget=${originalTarget.format()} " +
                        "candidate=${attemptTarget.format()} chosenAttempt=$chosenAttempt " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
        }

        val error = lastError ?: SocketTimeoutException("Telegram pre-connect exhausted without attempts")
        Log.d(
            LOG_TAG,
            "socks tcp connect failed target=${originalTarget.format()} endpointClass=$endpointClass " +
                "dcClass=$dcClass network=${network ?: "-"} " +
                "connectMs=${SystemClock.elapsedRealtime() - startedAtMs} attempts=${attempts.size} " +
                "directBlockedBeforePayload=true " +
                "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
        )
        throw error
    }

    private fun connectProtectedTcpSocket(
        target: SocksTarget,
        network: Network?,
        endpointClass: String,
        timeoutMs: Int,
        startedAtMs: Long,
        logFailure: Boolean,
    ): ProtectedTcpSocket {
        val socket = Socket()
        closeables += socket
        try {
            if (!running.get()) {
                throw SocketException("SOCKS5 strategy proxy is stopping")
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            if (!service.protect(socket)) {
                throw IOException("VpnService.protect returned false")
            }
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (error: IOException) {
                    Log.d(
                        LOG_TAG,
                        "socks tcp network bind fallback network=$network " +
                            "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                }
            }
            socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
            val connectedAtMs = SystemClock.elapsedRealtime()
            Log.d(
                LOG_TAG,
                "socks tcp connect ok target=${target.format()} " +
                    "endpointClass=$endpointClass network=${network ?: "-"} " +
                    "connectMs=${connectedAtMs - startedAtMs} " +
                    "local=${socket.localAddress.hostAddress}:${socket.localPort}",
            )
            return ProtectedTcpSocket(
                socket = socket,
                connectedAtMs = connectedAtMs,
            )
        } catch (error: IOException) {
            if (logFailure) {
                Log.d(
                    LOG_TAG,
                    "socks tcp connect failed target=${target.format()} endpointClass=$endpointClass " +
                        "network=${network ?: "-"} connectMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
            closeables.remove(socket)
            closeQuietly(socket)
            throw error
        }
    }

    private fun relayTcp(client: Socket, upstream: ProtectedTcpSocket, target: SocksTarget) {
        val upstreamSocket = upstream.socket
        val remoteToClient = executor.submit {
            copyStream(
                input = upstreamSocket.getInputStream(),
                output = client.getOutputStream(),
                target = target,
                sinceMs = upstream.connectedAtMs,
                relayInfo = upstream.relayInfo,
                onFinished = {
                    runCatching { client.shutdownOutput() }
                    closeQuietly(client)
                    closeQuietly(upstreamSocket)
                },
            )
        }

        try {
            val input = client.getInputStream()
            val output = upstreamSocket.getOutputStream()
            val buffer = ByteArray(TCP_BUFFER_SIZE)
            var strategyEvaluated = false
            var firstClientPayloadLogged = false

            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }

                val payload = buffer.copyOf(read)
                if (!firstClientPayloadLogged) {
                    firstClientPayloadLogged = true
                    Log.d(
                        LOG_TAG,
                        "socks tcp first payload target=${target.format()} " +
                            "endpointClass=${target.endpointClass()} bytes=$read " +
                            "sinceConnectMs=${SystemClock.elapsedRealtime() - upstream.connectedAtMs}",
                    )
                }
                val writes = if (strategyEvaluated) {
                    listOf(TcpWrite(payload = payload))
                } else {
                    strategyEvaluated = true
                    transformTcpPayload(payload, target)
                }
                writes.forEach { write -> writeTcpPayload(upstreamSocket, output, target, write) }
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
            runCatching { upstreamSocket.shutdownOutput() }
            closeQuietly(upstreamSocket)
            closeQuietly(client)
            remoteToClient.cancel(true)
        }
    }

    private fun transformTcpPayload(payload: ByteArray, target: SocksTarget): List<TcpWrite> {
        val decisionStartedAtMs = SystemClock.elapsedRealtime()
        val decision = engine.evaluate(
            StrategyFlowProbe(
                transport = StrategyTransport.TCP,
                destinationPort = target.port,
                payload = payload,
            ),
        )
        logStrategyDecision(
            transport = StrategyTransport.TCP,
            target = target,
            knownHost = null,
            decision = decision,
            payloadBytes = payload.size,
            decisionMs = SystemClock.elapsedRealtime() - decisionStartedAtMs,
        )
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
        target: SocksTarget? = null,
        sinceMs: Long? = null,
        relayInfo: TelegramRelayInfo? = null,
        onFinished: () -> Unit,
    ) {
        try {
            val buffer = ByteArray(TCP_BUFFER_SIZE)
            var firstPayloadLogged = false
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    if (!firstPayloadLogged && target != null && sinceMs != null) {
                        firstPayloadLogged = true
                        Log.d(
                            LOG_TAG,
                            "socks tcp upstream first byte target=${target.format()} " +
                                "endpointClass=${target.endpointClass()} bytes=$read " +
                                "sinceConnectMs=${SystemClock.elapsedRealtime() - sinceMs}",
                        )
                        relayInfo?.let { info ->
                            Log.d(
                                LOG_TAG,
                                "telegram relay first byte originalTarget=${info.originalTarget} " +
                                    "sinceRelayConnectMs=${SystemClock.elapsedRealtime() - info.connectedAtMs}",
                            )
                        }
                    }
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
        private val udpSentAt = ConcurrentHashMap<UdpRemoteKey, Long>()
        private val udpFirstSentAt = ConcurrentHashMap<UdpRemoteKey, Long>()
        private val udpFirstReceiveLogged = ConcurrentHashMap.newKeySet<UdpRemoteKey>()
        private val udpKnownHosts = ConcurrentHashMap<UdpRemoteKey, String>()
        private val udpThroughputWindows = ConcurrentHashMap<UdpRemoteKey, UdpThroughputWindow>()
        private var selectedNetworkSupportsIpv6 = false

        val bindAddress: InetAddress
            get() = clientSocket.localAddress

        val port: Int
            get() = clientSocket.localPort

        fun start() {
            if (!service.protect(remoteSocket)) {
                throw IOException("VpnService.protect returned false for UDP")
            }
            val network = UnderlyingNetworkSelector.select(service)
            selectedNetworkSupportsIpv6 = network
                ?.let { selectedNetwork -> UnderlyingNetworkSelector.supportsIpv6(service, selectedNetwork) }
                ?: false
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
            Log.d(
                LOG_TAG,
                "socks udp relay listening ${bindAddress.hostAddress}:$port " +
                    "network=${network ?: "-"} selectedIpv6=$selectedNetworkSupportsIpv6",
            )
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
                            .filter { answer -> answer.host.isYoutubeHost() }
                            .forEach { answer ->
                                Log.d(
                                    LOG_TAG,
                                    "socks udp dns correlation host=${answer.host} " +
                                        "address=${answer.address.hostAddress} ttlSeconds=${answer.ttlSeconds}",
                                )
                            }
                    }
                    val responseTarget = SocksTarget(
                        host = remoteAddress.address.hostAddress ?: remoteAddress.hostString,
                        port = remoteAddress.port,
                        inetAddress = remoteAddress.address,
                    )
                    val sentAtMs = udpSentAt.remove(
                        UdpRemoteKey(remoteAddress.address.hostAddress ?: "", remoteAddress.port),
                    ) ?: udpSentAt.remove(UdpRemoteKey(remoteAddress.hostString, remoteAddress.port))
                    val remoteKey = UdpRemoteKey(remoteAddress.address.hostAddress ?: remoteAddress.hostString, remoteAddress.port)
                    val knownHost = udpKnownHosts[remoteKey]
                    val nowMs = SystemClock.elapsedRealtime()
                    val firstReceiveLatencyMs = udpFirstSentAt[remoteKey]
                        ?.takeIf { udpFirstReceiveLogged.add(remoteKey) }
                        ?.let { firstSentAtMs -> nowMs - firstSentAtMs }
                    if (responseTarget.shouldLogUdpTiming()) {
                        Log.d(
                            LOG_TAG,
                            "socks udp receive target=${responseTarget.format()} " +
                                "endpointClass=${responseTarget.endpointClass()} bytes=${payload.size} " +
                                "knownHost=${knownHost ?: "-"} mtu=$tunnelMtu " +
                                "firstReceiveLatencyMs=${firstReceiveLatencyMs ?: "-"} " +
                                "rttMs=${sentAtMs?.let { SystemClock.elapsedRealtime() - it } ?: "-"}",
                        )
                    }
                    if (knownHost.isYoutubeHost()) {
                        udpThroughputWindows.getOrPut(remoteKey) {
                            UdpThroughputWindow(
                                target = responseTarget.format(),
                                knownHost = knownHost ?: "-",
                                mtu = tunnelMtu,
                            )
                        }.add(payload.size, firstReceiveLatencyMs)
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
            val decisionStartedAtMs = SystemClock.elapsedRealtime()
            val decision = engine.evaluate(
                StrategyFlowProbe(
                    transport = StrategyTransport.UDP,
                    destinationPort = target.port,
                    payload = payload,
                    knownHost = knownHost,
                ),
            )
            logStrategyDecision(
                transport = StrategyTransport.UDP,
                target = target,
                knownHost = knownHost,
                decision = decision,
                payloadBytes = payload.size,
                decisionMs = SystemClock.elapsedRealtime() - decisionStartedAtMs,
            )

            decision.actions
                .filter { action -> action.kind == StrategyActionKind.UDP_FAKE }
                .forEach { action ->
                    val fakePayload = action.blobPayload ?: return@forEach
                    repeat(action.repeats.coerceAtLeast(1)) {
                        sendRemoteDatagram(target, fakePayload, fake = true, knownHost = knownHost)
                    }
                }
            sendRemoteDatagram(target, payload, fake = false, knownHost = knownHost)
        }

        private fun sendRemoteDatagram(
            target: SocksTarget,
            payload: ByteArray,
            fake: Boolean,
            knownHost: String?,
        ) {
            val startedAtMs = SystemClock.elapsedRealtime()
            val address = target.inetAddress ?: InetAddress.getByName(target.host)
            if (address is Inet6Address && !selectedNetworkSupportsIpv6) {
                throw NoRouteToHostException("Underlying network has no IPv6 route")
            }
            val packet = DatagramPacket(payload, payload.size, address, target.port)
            remoteSocket.send(packet)
            val sentAtMs = SystemClock.elapsedRealtime()
            val remoteKey = UdpRemoteKey(address.hostAddress ?: target.host, target.port)
            if (!fake) {
                udpSentAt[remoteKey] = sentAtMs
                udpFirstSentAt.putIfAbsent(remoteKey, sentAtMs)
                if (knownHost != null) {
                    udpKnownHosts[remoteKey] = knownHost
                }
            }
            if (target.shouldLogUdpTiming()) {
                Log.d(
                    LOG_TAG,
                    "socks udp send target=${target.format()} endpointClass=${target.endpointClass()} " +
                        "bytes=${payload.size} fake=$fake knownHost=${knownHost ?: "-"} " +
                        "mtu=$tunnelMtu elapsedMs=${sentAtMs - startedAtMs}",
                )
            }
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
        payloadBytes: Int,
        decisionMs: Long,
    ) {
        Log.d(
            LOG_TAG,
            "strategy socks transport=${transport.name.lowercase()} target=${target.format()} " +
                "endpointClass=${target.endpointClass()} payloadBytes=$payloadBytes decisionMs=$decisionMs " +
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

    private fun ByteArray.telegramProtoHint(): String {
        if (isEmpty()) {
            return "empty"
        }
        if (size >= 4) {
            val head4 = String(copyOfRange(0, 4), Charsets.US_ASCII)
            if (head4 == "GET " || head4 == "POST" || head4 == "HEAD") {
                return "http_like"
            }
        }
        return when {
            (this[0].toInt() and BYTE_MASK) == 0xef -> "mtproto_abridged"
            size >= 4 &&
                (this[0].toInt() and BYTE_MASK) == 0xee &&
                (this[1].toInt() and BYTE_MASK) == 0xee &&
                (this[2].toInt() and BYTE_MASK) == 0xee &&
                (this[3].toInt() and BYTE_MASK) == 0xee -> "mtproto_intermediate"
            size >= 4 &&
                (this[0].toInt() and BYTE_MASK) == 0xdd &&
                (this[1].toInt() and BYTE_MASK) == 0xdd &&
                (this[2].toInt() and BYTE_MASK) == 0xdd &&
                (this[3].toInt() and BYTE_MASK) == 0xdd -> "mtproto_padded_intermediate"
            size >= 3 &&
                (this[0].toInt() and BYTE_MASK) == 0x16 &&
                (this[1].toInt() and BYTE_MASK) == 0x03 -> "tls_client_hello"
            size >= MTPROTO_OBFUSCATED_INIT_BYTES -> "unknown_or_obfuscated"
            else -> "unknown_short"
        }
    }

    private fun ByteArray.redactedHexPreview(): String {
        return take(TELEGRAM_TRANSPARENT_PROBE_HEX_PREVIEW_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
    }

    private fun SocksTarget.format(): String {
        return "$host:$port"
    }

    private fun SocksTarget.targetIp(): String {
        return inetAddress?.hostAddress ?: host
    }

    private fun SocksTarget.isIpv6(): Boolean {
        return inetAddress is Inet6Address || (inetAddress == null && host.contains(':'))
    }

    private fun SocksTarget.endpointClass(): String {
        val address = inetAddress
        return when {
            address?.isTelegramAddress() == true -> "telegram"
            host.contains("telegram", ignoreCase = true) -> "telegram_host"
            host.equals("t.me", ignoreCase = true) || host.endsWith(".t.me", ignoreCase = true) -> "telegram_host"
            port == TELEGRAM_MTPROTO_PORT -> "mtproto_port"
            isIpv6() -> "ipv6"
            port == QUIC_HTTPS_PORT -> "https"
            port == DNS_PORT -> "dns"
            else -> "-"
        }
    }

    private fun String.isTelegramEndpointClass(): Boolean {
        return startsWith("telegram") || this == "mtproto_port"
    }

    private fun selectTelegramRelayPolicy(endpointClass: String): StrategyEndpointPolicy? {
        return endpointPolicies
            .asSequence()
            .filter { policy ->
                policy.transport == StrategyEndpointTransport.TCP &&
                    policy.route.kind == StrategyEndpointRouteKind.REMOTE_RELAY &&
                    policy.endpointClasses.any { configuredClass -> configuredClass == endpointClass }
            }
            .sortedBy { policy ->
                if (policy.route.protocol == StrategyRelayProtocol.SOCKS5) 0 else 1
            }
            .firstOrNull()
    }

    private fun StrategyEndpointRoute.relayLabel(): String {
        return "$host:$port"
    }

    private fun StrategyRelayAuth.toSocks5RelayAuth(): Socks5RelayAuth {
        return Socks5RelayAuth(
            username = username,
            password = password,
        )
    }

    private fun SocksTarget.telegramDcClass(): String {
        val address = inetAddress
        if (address is Inet4Address) {
            val octets = address.address.map { byte -> byte.toInt() and BYTE_MASK }
            return when {
                octets[0] == 149 && octets[1] == 154 && octets[2] == 167 -> {
                    "telegram_149_154_167_24"
                }
                octets[0] == 149 && octets[1] == 154 && octets[2] in 160..175 -> {
                    "telegram_149_154_160_20"
                }
                octets[0] == 91 && octets[1] == 108 -> "telegram_91_108_0_16"
                octets[0] == 185 && octets[1] == 76 && octets[2] == 151 -> {
                    "telegram_185_76_151_24"
                }
                port.isTelegramPort() -> "mtproto_port"
                else -> "-"
            }
        }
        if (address is Inet6Address && address.isTelegramIpv6()) {
            return "telegram_2001_67c_4e8_48"
        }
        if (host.contains("telegram", ignoreCase = true) ||
            host.equals("t.me", ignoreCase = true) ||
            host.endsWith(".t.me", ignoreCase = true)
        ) {
            return "telegram_host"
        }
        return if (port.isTelegramPort()) "mtproto_port" else "-"
    }

    private fun SocksTarget.telegramConnectAttempts(): List<TelegramConnectAttempt> {
        val attempts = mutableListOf<TelegramConnectAttempt>()
        val seen = mutableSetOf<String>()

        fun addAttempt(target: SocksTarget, source: String) {
            val key = "${target.host}:${target.port}"
            if (seen.add(key)) {
                attempts += TelegramConnectAttempt(
                    target = target,
                    source = source,
                    timeoutMs = TELEGRAM_CONNECT_TIMEOUT_MS,
                )
            }
        }

        addAttempt(this, "original_endpoint")

        telegramSameDcAlternatives()
            .take(TELEGRAM_MAX_SAME_DC_ALT_HOSTS)
            .forEach { candidate ->
                addAttempt(
                    candidate.copy(port = port),
                    "same_dc_149_154_167_24",
                )
            }

        telegramCandidatePorts(port)
            .drop(1)
            .forEach { candidatePort ->
                addAttempt(
                    copy(port = candidatePort),
                    "alt_port",
                )
            }

        return attempts.take(TELEGRAM_MAX_CONNECT_ATTEMPTS)
    }

    private fun SocksTarget.telegramSameDcAlternatives(): List<SocksTarget> {
        val address = inetAddress as? Inet4Address ?: return emptyList()
        val octets = address.address.map { byte -> byte.toInt() and BYTE_MASK }
        if (octets[0] != 149 || octets[1] != 154 || octets[2] != 167) {
            return emptyList()
        }

        return TELEGRAM_149_154_167_CANDIDATES
            .asSequence()
            .filter { lastOctet -> lastOctet != octets[3] }
            .map { lastOctet -> "149.154.167.$lastOctet" }
            .map { candidateHost ->
                SocksTarget(
                    host = candidateHost,
                    port = port,
                    inetAddress = runCatching { InetAddress.getByName(candidateHost) }.getOrNull(),
                )
            }
            .filter { target -> target.inetAddress is Inet4Address }
            .toList()
    }

    private fun telegramCandidatePorts(originalPort: Int): List<Int> {
        return buildList {
            add(originalPort)
            listOf(443, 80, 5222, 5223).forEach { candidatePort ->
                if (!contains(candidatePort)) {
                    add(candidatePort)
                }
            }
        }
    }

    private fun Int.isTelegramPort(): Boolean {
        return this == 443 || this == 80 || this == 5222 || this == 5223
    }

    private fun SocksTarget.shouldLogUdpTiming(): Boolean {
        return port == QUIC_HTTPS_PORT || port == DNS_PORT || endpointClass().startsWith("telegram")
    }

    private fun String?.isYoutubeHost(): Boolean {
        val value = this?.lowercase() ?: return false
        return value.contains("youtube") ||
            value.contains("googlevideo") ||
            value.endsWith(".ytimg.com") ||
            value.endsWith(".ggpht.com")
    }

    private fun InetAddress.isTelegramAddress(): Boolean {
        return when (this) {
            is Inet4Address -> isTelegramIpv4()
            is Inet6Address -> isTelegramIpv6()
            else -> false
        }
    }

    private fun Inet4Address.isTelegramIpv4(): Boolean {
        val octets = address.map { byte -> byte.toInt() and BYTE_MASK }
        return (octets[0] == 149 && octets[1] == 154 && octets[2] in 160..175) ||
            (octets[0] == 91 && octets[1] == 108) ||
            (octets[0] == 185 && octets[1] == 76 && octets[2] == 151)
    }

    private fun Inet6Address.isTelegramIpv6(): Boolean {
        val bytes = address.map { byte -> byte.toInt() and BYTE_MASK }
        return bytes[0] == 0x20 &&
            bytes[1] == 0x01 &&
            bytes[2] == 0x06 &&
            bytes[3] == 0x7c &&
            bytes[4] == 0x04 &&
            bytes[5] == 0xe8
    }

    private data class SocksRequest(
        val command: Int,
        val target: SocksTarget,
    )

    private data class ProtectedTcpSocket(
        val socket: Socket,
        val connectedAtMs: Long,
        val relayInfo: TelegramRelayInfo? = null,
    )

    private data class SocksTarget(
        val host: String,
        val port: Int,
        val inetAddress: InetAddress?,
    )

    private data class TelegramRelayInfo(
        val originalTarget: String,
        val connectedAtMs: Long,
    )

    private data class TelegramConnectAttempt(
        val target: SocksTarget,
        val source: String,
        val timeoutMs: Int,
    )

    private data class SocksUdpFrame(
        val target: SocksTarget,
        val payload: ByteArray,
    )

    private data class UdpRemoteKey(
        val host: String,
        val port: Int,
    )

    private class UdpThroughputWindow(
        private val target: String,
        private val knownHost: String,
        private val mtu: Int,
    ) {
        private var windowStartedAtMs = SystemClock.elapsedRealtime()
        private var bytes = 0L
        private var packets = 0
        private var firstReceiveLatencyMs: Long? = null

        @Synchronized
        fun add(byteCount: Int, firstReceiveLatencyMs: Long?) {
            bytes += byteCount.toLong()
            packets += 1
            if (this.firstReceiveLatencyMs == null && firstReceiveLatencyMs != null) {
                this.firstReceiveLatencyMs = firstReceiveLatencyMs
            }
            val nowMs = SystemClock.elapsedRealtime()
            val elapsedMs = nowMs - windowStartedAtMs
            if (elapsedMs < UDP_THROUGHPUT_WINDOW_MS) {
                return
            }
            Log.d(
                LOG_TAG,
                "socks udp throughput target=$target knownHost=$knownHost mtu=$mtu " +
                    "windowMs=$elapsedMs bytesPerSec=${(bytes * 1000L) / elapsedMs.coerceAtLeast(1L)} " +
                    "packets=$packets firstReceiveLatencyMs=${this.firstReceiveLatencyMs ?: "-"}",
            )
            windowStartedAtMs = nowMs
            bytes = 0L
            packets = 0
            this.firstReceiveLatencyMs = null
        }
    }

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
        private const val TELEGRAM_CONNECT_TIMEOUT_MS = 1_500
        private const val TELEGRAM_MAX_CONNECT_ATTEMPTS = 6
        private const val TELEGRAM_MAX_SAME_DC_ALT_HOSTS = 2
        private const val TELEGRAM_TRANSPARENT_PROBE_FLAG_PATH = "qnzapret/telegram_transparent_probe"
        private const val TELEGRAM_TRANSPARENT_PROBE_READ_TIMEOUT_MS = 2_000
        private const val TELEGRAM_TRANSPARENT_PROBE_MAX_BYTES = 128
        private const val TELEGRAM_TRANSPARENT_PROBE_HEX_PREVIEW_BYTES = 16
        private const val MTPROTO_OBFUSCATED_INIT_BYTES = 64
        private const val UDP_SOCKET_TIMEOUT_MS = 1_000
        private const val UDP_THROUGHPUT_WINDOW_MS = 1_000L
        private const val DEFAULT_TCP_SPLIT_POSITION = 1
        private const val DEFAULT_TCP_FAKE_HOP_LIMIT = 8
        private const val DEFAULT_TCP_HOP_LIMIT = 64
        private const val DNS_PORT = 53
        private const val QUIC_HTTPS_PORT = 443
        private const val TELEGRAM_MTPROTO_PORT = 5222
        private const val MAX_TCP_PORT = 65_535
        private val TELEGRAM_149_154_167_CANDIDATES = listOf(41, 50, 51, 91, 92, 151)
    }
}
