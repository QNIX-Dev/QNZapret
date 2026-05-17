package dev.qnzapret

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class TelegramCompatibilityProxyState(
    val ready: Boolean,
    val setupRequired: Boolean,
    val host: String,
    val port: Int,
    val secretWithPrefix: String,
    val message: String,
    val activeSessions: Int = 0,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
) {
    val endpoint: String
        get() = "$host:$port"
}

internal class TelegramCompatibilityProxyManager(
    private val service: VpnService,
    private val strategyProxyProvider: () -> LocalStrategyProxyEndpoint? = { null },
    private val onStateChanged: (TelegramCompatibilityProxyState) -> Unit = {},
) {
    private val prefs = service.getSharedPreferences(TELEGRAM_COMPAT_PREFS_NAME, Context.MODE_PRIVATE)
    private val setupHealthStore = TelegramSetupHealthStore(prefs)
    private val routeProvider = TelegramRouteConfigProvider(service) {
        onRouteSnapshotUpdated()
    }
    private var server: TelegramKotlinMtProxyServer? = null
    private var lastState: TelegramCompatibilityProxyState = stoppedState()

    @Synchronized
    fun start(): TelegramCompatibilityProxyState {
        server?.let { activeServer ->
            if (activeServer.isRunning()) {
                lastState = runningState(activeServer)
                return lastState
            }
        }

        val secret = ensureSecret()
        val routeSnapshot = routeProvider.loadAndProbeAsync()
        val preferredPort = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val port = selectPort(preferredPort)
        if (port != preferredPort) {
            prefs.edit()
                .putInt(KEY_PORT, port)
                .apply()
            Log.d(
                LOG_TAG,
                "telegram kotlin proxy preferred port busy previous=$preferredPort fallback=$port",
            )
        }
        val secretWithPrefix = "dd$secret"
        val setupFingerprint = TelegramSetupHealthPolicy.fingerprint(
            host = DEFAULT_HOST,
            port = port,
            secretWithPrefix = secretWithPrefix,
        )
        setupHealthStore.syncFingerprint(setupFingerprint)

        val nextServer = TelegramKotlinMtProxyServer(
            service = service,
            host = DEFAULT_HOST,
            port = port,
            secretHex = secret,
            setupFingerprint = setupFingerprint,
            routeConfigProvider = routeProvider::currentConfig,
            strategyProxyProvider = strategyProxyProvider,
            onSuccessfulHandshake = ::onSuccessfulHandshake,
            onSuccessfulBridge = ::onSuccessfulBridge,
        )
        val endpoint = try {
            nextServer.start()
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram kotlin proxy start failed endpoint=$DEFAULT_HOST:$port " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            lastState = TelegramCompatibilityProxyState(
                ready = false,
                setupRequired = false,
                host = DEFAULT_HOST,
                port = port,
                secretWithPrefix = secretWithPrefix,
                message = "Telegram compatibility proxy не запустился: ${error.message ?: error.javaClass.simpleName}.",
            )
            return lastState
        }

        server = nextServer
        lastState = TelegramCompatibilityProxyState(
            ready = true,
            setupRequired = setupHealthStore.setupRequired(setupFingerprint, nextServer.startedAtWallMs),
            host = endpoint.address,
            port = endpoint.port,
            secretWithPrefix = secretWithPrefix,
            message = runningMessage(
                endpoint = endpoint,
                routeSnapshot = routeSnapshot,
                setupRequired = setupHealthStore.setupRequired(setupFingerprint, nextServer.startedAtWallMs),
            ),
        )
        Log.d(
            LOG_TAG,
            "telegram kotlin proxy listening endpoint=${lastState.endpoint} " +
                "setupRequired=${lastState.setupRequired} fingerprint=${setupFingerprint.redactedFingerprint()}",
        )
        return lastState
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        lastState = stoppedState()
    }

    @Synchronized
    fun currentState(): TelegramCompatibilityProxyState {
        val activeServer = server
        lastState = if (activeServer?.isRunning() == true) {
            runningState(activeServer)
        } else {
            stoppedState()
        }
        return lastState
    }

    fun openSetupScreen(state: TelegramCompatibilityProxyState = currentState()): Boolean {
        if (!state.ready) {
            Log.d(LOG_TAG, "telegram setup open skipped reason=proxy_not_ready endpoint=${state.endpoint}")
            return false
        }

        val fingerprint = TelegramSetupHealthPolicy.fingerprint(
            host = state.host,
            port = state.port,
            secretWithPrefix = state.secretWithPrefix,
        )
        setupHealthStore.markSetupOpened(fingerprint, System.currentTimeMillis())
        Log.d(LOG_TAG, "telegram setup open start endpoint=${state.endpoint}")
        val opened = tryOpen(
            scheme = "tg",
            intent = Intent(
                Intent.ACTION_VIEW,
                TelegramSetupActivity.telegramUri(state.host, state.port, state.secretWithPrefix),
            ),
        ) || tryOpen(
            scheme = "https",
            intent = Intent(
                Intent.ACTION_VIEW,
                TelegramSetupActivity.telegramHttpsUri(state.host, state.port, state.secretWithPrefix),
            ),
        )
        if (!opened) {
            Log.d(LOG_TAG, "telegram setup open failed endpoint=${state.endpoint}")
        }
        return opened
    }

    fun createSetupIntent(state: TelegramCompatibilityProxyState = currentState()): Intent {
        return TelegramSetupActivity.createIntent(service, state)
    }

    @Synchronized
    fun shouldAutoOpenSetup(
        state: TelegramCompatibilityProxyState = currentState(),
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val activeServer = server?.takeIf { candidate -> candidate.isRunning() } ?: return false
        if (!state.ready || !state.setupRequired) {
            return false
        }
        return setupHealthStore.canAutoOpenSetup(
            currentFingerprint = activeServer.setupFingerprint,
            serverStartedAtMs = activeServer.startedAtWallMs,
            nowMs = nowMs,
        )
    }

    @Synchronized
    private fun onRouteSnapshotUpdated() {
        val activeServer = server?.takeIf { candidate -> candidate.isRunning() } ?: return
        lastState = runningState(activeServer)
        publishState(lastState)
    }

    @Synchronized
    private fun onSuccessfulHandshake(fingerprint: String) {
        setupHealthStore.markSuccessfulHandshake(fingerprint, System.currentTimeMillis())
        Log.d(
            LOG_TAG,
            "telegram setup handshake confirmed fingerprint=${fingerprint.redactedFingerprint()}",
        )
        publishCurrentState()
    }

    @Synchronized
    private fun onSuccessfulBridge(fingerprint: String) {
        setupHealthStore.markSuccessfulBridge(fingerprint, System.currentTimeMillis())
        Log.d(
            LOG_TAG,
            "telegram setup bridge confirmed fingerprint=${fingerprint.redactedFingerprint()}",
        )
        publishCurrentState()
    }

    private fun publishCurrentState() {
        val activeServer = server?.takeIf { candidate -> candidate.isRunning() } ?: return
        lastState = runningState(activeServer)
        publishState(lastState)
    }

    private fun publishState(state: TelegramCompatibilityProxyState) {
        QnzapretVpnRuntimeStore.updateTelegramCompatibility(
            ready = state.ready,
            setupRequired = state.setupRequired,
            endpoint = state.endpoint,
            telegramMessage = state.message,
        )
        onStateChanged(state)
    }

    private fun tryOpen(scheme: String, intent: Intent): Boolean {
        return try {
            service.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            Log.d(LOG_TAG, "telegram setup open ok scheme=$scheme")
            true
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram setup open failed scheme=$scheme " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            false
        }
    }

    private fun runningState(activeServer: TelegramKotlinMtProxyServer): TelegramCompatibilityProxyState {
        val stats = activeServer.stats()
        val setupRequired = setupHealthStore.setupRequired(
            currentFingerprint = activeServer.setupFingerprint,
            serverStartedAtMs = activeServer.startedAtWallMs,
        )
        return TelegramCompatibilityProxyState(
            ready = true,
            setupRequired = setupRequired,
            host = activeServer.endpoint.address,
            port = activeServer.endpoint.port,
            secretWithPrefix = activeServer.secretWithPrefix,
            message = runningMessage(
                endpoint = activeServer.endpoint,
                routeSnapshot = routeProvider.currentSnapshot(),
                setupRequired = setupRequired,
            ),
            activeSessions = stats.activeSessions,
            bytesUp = stats.bytesUp,
            bytesDown = stats.bytesDown,
        )
    }

    private fun stoppedState(): TelegramCompatibilityProxyState {
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        return TelegramCompatibilityProxyState(
            ready = false,
            setupRequired = false,
            host = DEFAULT_HOST,
            port = port,
            secretWithPrefix = "",
            message = "Telegram compatibility proxy остановлен.",
        )
    }

    private fun ensureSecret(): String {
        val current = prefs.getString(KEY_SECRET, null)?.trim().orEmpty()
        if (isValidSecret(current)) {
            return current
        }
        val generated = TelegramMtProxyCrypto.generateSecretHex()
        prefs.edit()
            .putString(KEY_SECRET, generated)
            .putInt(KEY_PORT, DEFAULT_PORT)
            .apply()
        Log.d(LOG_TAG, "telegram kotlin proxy generated local secret")
        return generated
    }

    private fun selectPort(preferredPort: Int): Int {
        val normalized = preferredPort.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT
        if (!isPortOpen(normalized)) {
            return normalized
        }
        for (port in (DEFAULT_PORT + 1)..(DEFAULT_PORT + PORT_FALLBACK_RANGE)) {
            if (!isPortOpen(port)) {
                return port
            }
        }
        return normalized
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(DEFAULT_HOST, port), PORT_CHECK_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidSecret(value: String): Boolean {
        return value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun runningMessage(
        endpoint: TelegramKotlinMtProxyEndpoint,
        routeSnapshot: TelegramRouteConfigSnapshot,
        setupRequired: Boolean,
    ): String {
        val setupMessage = if (setupRequired) {
            "Нужно подключить Telegram."
        } else {
            "Telegram setup подтвержден живой сессией."
        }
        return "Telegram compatibility proxy слушает ${endpoint.address}:${endpoint.port}. " +
            "$setupMessage " +
            when (routeSnapshot.status) {
                TelegramRouteStatus.MISSING -> "Telegram proxy ready, route config missing."
                TelegramRouteStatus.PROBING -> "Telegram route probing."
                TelegramRouteStatus.READY -> "Telegram route ready: ${routeSnapshot.activeDomain ?: "domain selected"}."
                TelegramRouteStatus.FAILED -> "Telegram route failed: CF probe не дал HTTP 101."
            }
    }

    private companion object {
        private const val LOG_TAG = "QNZapretTgCompat"
        private const val KEY_SECRET = "secret_hex"
        private const val KEY_PORT = "port"
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 1443
        private const val PORT_FALLBACK_RANGE = 10
        private const val PORT_CHECK_TIMEOUT_MS = 250
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }
}

internal data class TelegramKotlinMtProxyEndpoint(val address: String, val port: Int)

internal data class TelegramKotlinMtProxyStats(
    val activeSessions: Int,
    val bytesUp: Long,
    val bytesDown: Long,
)

private class TelegramKotlinMtProxyServer(
    private val service: VpnService,
    private val host: String,
    private val port: Int,
    private val secretHex: String,
    val setupFingerprint: String,
    private val routeConfigProvider: () -> TelegramWebSocketRouteConfig,
    private val strategyProxyProvider: () -> LocalStrategyProxyEndpoint?,
    private val onSuccessfulHandshake: (String) -> Unit,
    private val onSuccessfulBridge: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val closeables = ConcurrentHashMap.newKeySet<Closeable>()
    private val activeSessions = AtomicLong(0L)
    private val bytesUp = AtomicLong(0L)
    private val bytesDown = AtomicLong(0L)
    private val sessionCounter = AtomicLong(0L)
    private val handshakeFailureLogLock = Any()
    private var handshakeFailureWindowStartedAtMs = 0L
    private var handshakeFailuresLoggedInWindow = 0
    private var handshakeFailuresSuppressedInWindow = 0
    private lateinit var executor: ExecutorService
    private var serverSocket: ServerSocket? = null
    var startedAtWallMs: Long = 0L
        private set

    val secretWithPrefix: String
        get() = "dd$secretHex"

    lateinit var endpoint: TelegramKotlinMtProxyEndpoint
        private set

    fun start(): TelegramKotlinMtProxyEndpoint {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("Telegram compatibility proxy is already running")
        }
        startedAtWallMs = System.currentTimeMillis()
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "QNZapretTgCompat").apply { isDaemon = true }
        }
        val nextServerSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
        serverSocket = nextServerSocket
        closeables += nextServerSocket
        endpoint = TelegramKotlinMtProxyEndpoint(host, nextServerSocket.localPort)
        executor.execute { acceptLoop(nextServerSocket) }
        return endpoint
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }
        closeables.forEach(::closeQuietly)
        closeables.clear()
        TelegramWebSocketTransport.closePool()
        serverSocket = null
        if (::executor.isInitialized) {
            executor.shutdownNow()
        }
        Log.d(LOG_TAG, "telegram kotlin proxy stopped")
    }

    fun isRunning(): Boolean = running.get()

    fun stats(): TelegramKotlinMtProxyStats {
        return TelegramKotlinMtProxyStats(
            activeSessions = activeSessions.get().toInt(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
        )
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                client.tcpNoDelay = true
                closeables += client
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                return
            } catch (error: IOException) {
                if (running.get()) {
                    Log.d(
                        LOG_TAG,
                        "telegram kotlin proxy accept failed " +
                            "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val sessionId = sessionCounter.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        var upstream: TelegramWebSocketConnection? = null
        var upstreamToClient: Future<*>? = null
        var sessionWatchdog: Future<*>? = null
        var clientInitComplete = false
        val closeReason = AtomicReference("client_closed")
        var sessionMetrics: TelegramSessionMetrics? = null
        activeSessions.incrementAndGet()
        try {
            client.soTimeout = CLIENT_INIT_TIMEOUT_MS
            val initPayload = client.getInputStream().readBytesExact(TELEGRAM_OBFUSCATION_INIT_SIZE)
            val handshake = TelegramMtProxyCrypto.acceptClient(initPayload, secretHex)
            clientInitComplete = true
            onSuccessfulHandshake(setupFingerprint)
            val dcId = handshake.dcId
            val flowKind = if (handshake.mediaDc) "media" else "text"
            sessionMetrics = TelegramSessionMetrics(
                sessionId = sessionId,
                dcId = dcId,
                rawDcId = handshake.rawDcId,
                mediaDc = handshake.mediaDc,
                startedAtMs = startedAtMs,
            )
            Log.d(
                LOG_TAG,
                "telegram compatibility start session=$sessionId flow=$flowKind " +
                    "client=${client.inetAddress.hostAddress}:${client.port} " +
                    "dc=$dcId rawDc=${handshake.rawDcId} mediaDc=${handshake.mediaDc} " +
                    "proto=${handshake.protocol.wireValue}",
            )

            var initialClientPayload: ByteArray? = null
            var firstPayloadLogged = false
            var routeMediaDc = handshake.mediaDc
            val upstreamMediaDc = if (handshake.mediaDc) {
                val fallbackRemainingMs = positiveMediaUpstreamFallbackRemainingMs(dcId, SystemClock.elapsedRealtime())
                val usePositiveUpstream = fallbackRemainingMs > 0L
                Log.d(
                    LOG_TAG,
                    "telegram compatibility media upstream mode session=$sessionId dc=$dcId " +
                        "rawDc=${handshake.rawDcId} upstreamMediaDc=${!usePositiveUpstream} " +
                    "fallbackRemainingMs=$fallbackRemainingMs",
                )
                !usePositiveUpstream
            } else {
                val fallbackRemainingMs = uploadNegativeUpstreamFallbackRemainingMs(
                    dcId = dcId,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                if (fallbackRemainingMs > 0L) {
                    val firstPayload = readFirstClientPayload(
                        client = client,
                        clientCipher = handshake.clientToProxyCipher,
                        dcId = dcId,
                        sessionMetrics = sessionMetrics,
                        startedAtMs = startedAtMs,
                    ) ?: return
                    initialClientPayload = firstPayload
                    firstPayloadLogged = true
                    val useNegativeUpstream = firstPayload.size >= UPLOAD_FALLBACK_FIRST_CHUNK_BYTES
                    routeMediaDc = useNegativeUpstream
                    Log.d(
                        LOG_TAG,
                        "telegram compatibility upload upstream mode session=$sessionId dc=$dcId " +
                            "rawDc=${handshake.rawDcId} upstreamMediaDc=$useNegativeUpstream " +
                            "routeMediaDc=$routeMediaDc fallbackRemainingMs=$fallbackRemainingMs " +
                            "firstPayloadBytes=${firstPayload.size}",
                    )
                    useNegativeUpstream
                } else {
                    false
                }
            }
            val obfuscation = TelegramMtProxyCrypto.createUpstream(
                protocol = handshake.protocol,
                dcId = dcId,
                mediaDc = upstreamMediaDc,
            )
            val packetSplitter = TelegramMtProtoPacketSplitter(handshake.protocol)
            upstream = TelegramWebSocketTransport.connect(
                service = service,
                dcId = dcId,
                mediaDc = routeMediaDc,
                routeConfig = routeConfigProvider(),
                strategyProxyEndpoint = strategyProxyProvider(),
            )
            try {
                upstream.stream.writeBinary(obfuscation.initPayload)
            } catch (error: IOException) {
                if (upstream.pooled) {
                    Log.d(
                        LOG_TAG,
                        "telegram ws pool pooled connection failed session=$sessionId " +
                            "host=${upstream.host} error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                    )
                    closeQuietly(upstream.stream)
                    upstream = TelegramWebSocketTransport.connect(
                        service = service,
                        dcId = dcId,
                        mediaDc = routeMediaDc,
                        routeConfig = routeConfigProvider(),
                        usePool = false,
                        strategyProxyEndpoint = strategyProxyProvider(),
                    )
                    upstream.stream.writeBinary(obfuscation.initPayload)
                } else {
                    throw error
                }
            }
            client.soTimeout = 0
            Log.d(
                LOG_TAG,
                "telegram compatibility bridge started session=$sessionId flow=$flowKind " +
                    "dc=$dcId upstreamMediaDc=$upstreamMediaDc " +
                    "wsHost=${upstream.host} route=${upstream.routeKind} pooled=${upstream.pooled} " +
                    "dnsMs=${upstream.dnsMs} tcpConnectMs=${upstream.tcpConnectMs} " +
                    "tlsMs=${upstream.tlsMs} wsHandshakeMs=${upstream.wsHandshakeMs} " +
                    "connectMs=${upstream.connectedAtMs - startedAtMs}",
            )
            onSuccessfulBridge(setupFingerprint)

            sessionWatchdog = executor.submit {
                watchSession(
                    client = client,
                    upstream = upstream.stream,
                    connection = upstream,
                    sessionMetrics = sessionMetrics,
                    closeReason = closeReason,
                )
            }
            upstreamToClient = executor.submit {
                try {
                    relayUpstreamToClient(
                        upstream = upstream.stream,
                        client = client,
                        upstreamCipher = obfuscation.upstreamToProxyCipher,
                        clientCipher = handshake.proxyToClientCipher,
                        dcId = dcId,
                        sessionMetrics = sessionMetrics,
                        connection = upstream,
                        startedAtMs = startedAtMs,
                    )
                } catch (error: Exception) {
                    closeReason.compareAndSet("client_closed", error.telegramCompatibilityErrorCode())
                    closeQuietly(client)
                }
            }
            relayClientToUpstream(
                client = client,
                upstream = upstream.stream,
                clientCipher = handshake.clientToProxyCipher,
                upstreamCipher = obfuscation.proxyToUpstreamCipher,
                packetSplitter = packetSplitter,
                initialDecryptedChunk = initialClientPayload,
                firstPayloadAlreadyLogged = firstPayloadLogged,
                dcId = dcId,
                sessionMetrics = sessionMetrics,
                startedAtMs = startedAtMs,
            )
        } catch (error: SocketTimeoutException) {
            val timeoutReason = if (clientInitComplete) "network_timeout" else "client_init_timeout"
            closeReason.compareAndSet("client_closed", timeoutReason)
            Log.d(
                LOG_TAG,
                "telegram compatibility failed errorCode=$timeoutReason " +
                    "session=$sessionId elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        } catch (error: Exception) {
            closeReason.compareAndSet("client_closed", error.telegramCompatibilityErrorCode())
            if (running.get()) {
                logCompatibilityFailure(
                    error = error,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    sessionId = sessionId,
                )
            }
        } finally {
            upstreamToClient?.cancel(true)
            sessionWatchdog?.cancel(true)
            closeQuietly(upstream?.stream)
            closeQuietly(client)
            closeables.remove(client)
            activeSessions.decrementAndGet()
            sessionMetrics?.let { metrics ->
                val durationMs = SystemClock.elapsedRealtime() - startedAtMs
                val finishedConnection = upstream
                if (finishedConnection != null) {
                    TelegramWebSocketTransport.recordSessionResult(
                        connection = finishedConnection,
                        bytesUp = metrics.bytesUp.get(),
                        bytesDown = metrics.bytesDown.get(),
                        durationMs = durationMs,
                        success = closeReason.get() == "client_closed",
                        errorCode = closeReason.get(),
                    )
                }
                Log.d(
                    LOG_TAG,
                        "telegram compatibility session closed session=$sessionId " +
                        "dc=${metrics.dcId} rawDc=${metrics.rawDcId} mediaDc=${metrics.mediaDc} " +
                        "reason=${closeReason.get()} durationMs=$durationMs " +
                        "bytesUp=${metrics.bytesUp.get()} bytesDown=${metrics.bytesDown.get()}",
                )
            }
        }
    }

    private fun watchSession(
        client: Socket,
        upstream: TelegramWebSocketStream,
        connection: TelegramWebSocketConnection,
        sessionMetrics: TelegramSessionMetrics?,
        closeReason: AtomicReference<String>,
    ) {
        val metrics = sessionMetrics ?: return
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                Thread.sleep(MEDIA_WATCHDOG_INTERVAL_MS)
                val nowMs = SystemClock.elapsedRealtime()
                val mediaDecision = metrics.mediaStallDecision(nowMs)
                if (mediaDecision != null) {
                    closeReason.compareAndSet("client_closed", "low_media_throughput")
                    mediaPositiveUpstreamFallbackUntilMs[metrics.dcId] =
                        SystemClock.elapsedRealtime() + MEDIA_POSITIVE_UPSTREAM_FALLBACK_MS
                    Log.d(
                        LOG_TAG,
                        "telegram compatibility media watchdog closing session=${metrics.sessionId} " +
                            "dc=${metrics.dcId} rawDc=${metrics.rawDcId} host=${connection.host} " +
                            "route=${connection.routeKind} reason=low_media_throughput " +
                            "durationMs=${mediaDecision.durationMs} progressBps=${mediaDecision.progressBps} " +
                            "bytesUp=${mediaDecision.bytesUp} bytesDown=${mediaDecision.bytesDown} " +
                            "positiveUpstreamFallbackMs=$MEDIA_POSITIVE_UPSTREAM_FALLBACK_MS",
                    )
                    closeQuietly(upstream)
                    closeQuietly(client)
                    return
                }
                val uploadDecision = metrics.uploadAckStallDecision(nowMs)
                if (uploadDecision != null) {
                    closeReason.compareAndSet("client_closed", "low_upload_ack")
                    if (!metrics.mediaDc) {
                        uploadNegativeUpstreamFallbackUntilMs[metrics.dcId] =
                            SystemClock.elapsedRealtime() + UPLOAD_NEGATIVE_UPSTREAM_FALLBACK_MS
                    }
                    Log.d(
                        LOG_TAG,
                        "telegram compatibility upload watchdog closing session=${metrics.sessionId} " +
                            "dc=${metrics.dcId} rawDc=${metrics.rawDcId} mediaDc=${metrics.mediaDc} " +
                            "host=${connection.host} route=${connection.routeKind} reason=low_upload_ack " +
                            "durationMs=${uploadDecision.durationMs} recentUp=${uploadDecision.recentUp} " +
                            "recentDown=${uploadDecision.recentDown} bytesUp=${uploadDecision.bytesUp} " +
                            "bytesDown=${uploadDecision.bytesDown} " +
                            "negativeUpstreamFallbackMs=$UPLOAD_NEGATIVE_UPSTREAM_FALLBACK_MS",
                    )
                    closeQuietly(upstream)
                    closeQuietly(client)
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun relayClientToUpstream(
        client: Socket,
        upstream: TelegramWebSocketStream,
        clientCipher: TelegramCtrCipher,
        upstreamCipher: TelegramCtrCipher,
        packetSplitter: TelegramMtProtoPacketSplitter,
        initialDecryptedChunk: ByteArray?,
        firstPayloadAlreadyLogged: Boolean,
        dcId: Int,
        sessionMetrics: TelegramSessionMetrics?,
        startedAtMs: Long,
    ) {
        val input = client.getInputStream()
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        var firstPayloadLogged = firstPayloadAlreadyLogged
        if (initialDecryptedChunk != null) {
            writeClientPlainChunkToUpstream(
                decrypted = initialDecryptedChunk,
                upstream = upstream,
                upstreamCipher = upstreamCipher,
                packetSplitter = packetSplitter,
                dcId = dcId,
                sessionMetrics = sessionMetrics,
            )
        }
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            val decrypted = clientCipher.transform(buffer, read)
            if (!firstPayloadLogged) {
                firstPayloadLogged = true
                Log.d(
                    LOG_TAG,
                    "telegram compatibility first payload direction=client_to_ws " +
                        "session=${sessionMetrics?.sessionId ?: "-"} dc=$dcId " +
                        "bytes=${decrypted.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                )
            }
            writeClientPlainChunkToUpstream(
                decrypted = decrypted,
                upstream = upstream,
                upstreamCipher = upstreamCipher,
                packetSplitter = packetSplitter,
                dcId = dcId,
                sessionMetrics = sessionMetrics,
            )
        }
        val tail = packetSplitter.flush()
        if (tail.isNotEmpty()) {
            Log.d(
                LOG_TAG,
                "telegram compatibility mtproto splitter flush session=${sessionMetrics?.sessionId ?: "-"} " +
                    "dc=$dcId bytes=${tail.size}",
            )
            upstream.writeBinary(tail)
        }
    }

    private fun readFirstClientPayload(
        client: Socket,
        clientCipher: TelegramCtrCipher,
        dcId: Int,
        sessionMetrics: TelegramSessionMetrics?,
        startedAtMs: Long,
    ): ByteArray? {
        val input = client.getInputStream()
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) {
                return null
            }
            if (read == 0) {
                continue
            }
            val decrypted = clientCipher.transform(buffer, read)
            Log.d(
                LOG_TAG,
                "telegram compatibility first payload direction=client_to_ws " +
                    "session=${sessionMetrics?.sessionId ?: "-"} dc=$dcId " +
                    "bytes=${decrypted.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
            return decrypted
        }
        return null
    }

    private fun writeClientPlainChunkToUpstream(
        decrypted: ByteArray,
        upstream: TelegramWebSocketStream,
        upstreamCipher: TelegramCtrCipher,
        packetSplitter: TelegramMtProtoPacketSplitter,
        dcId: Int,
        sessionMetrics: TelegramSessionMetrics?,
    ) {
        if (decrypted.isEmpty()) {
            return
        }
        val encrypted = upstreamCipher.transform(decrypted)
        val frames = packetSplitter.split(decrypted, encrypted)
        if (frames.size > 1 || (frames.isEmpty() && decrypted.size >= RELAY_BUFFER_SIZE)) {
            Log.d(
                LOG_TAG,
                "telegram compatibility mtproto splitter session=${sessionMetrics?.sessionId ?: "-"} " +
                    "dc=$dcId inputBytes=${decrypted.size} frames=${frames.size} " +
                    "buffered=${packetSplitter.bufferedBytes}",
            )
        }
        frames.forEach(upstream::writeBinary)
        bytesUp.addAndGet(decrypted.size.toLong())
        sessionMetrics?.addUp(decrypted.size.toLong())
    }

    private fun relayUpstreamToClient(
        upstream: TelegramWebSocketStream,
        client: Socket,
        upstreamCipher: TelegramCtrCipher,
        clientCipher: TelegramCtrCipher,
        dcId: Int,
        sessionMetrics: TelegramSessionMetrics?,
        connection: TelegramWebSocketConnection,
        startedAtMs: Long,
    ) {
        val output = client.getOutputStream()
        var firstPayloadLogged = false
        while (running.get()) {
            val payload = upstream.readBinary() ?: return
            if (payload.isEmpty()) {
                continue
            }
            val decrypted = upstreamCipher.transform(payload)
            if (!firstPayloadLogged) {
                firstPayloadLogged = true
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                TelegramWebSocketTransport.recordFirstWsPayload(connection, elapsedMs)
                Log.d(
                    LOG_TAG,
                    "telegram compatibility first payload direction=ws_to_client " +
                        "session=${sessionMetrics?.sessionId ?: "-"} dc=$dcId " +
                        "bytes=${decrypted.size} elapsedMs=$elapsedMs",
                )
            }
            val encrypted = clientCipher.transform(decrypted)
            output.write(encrypted)
            output.flush()
            bytesDown.addAndGet(payload.size.toLong())
            sessionMetrics?.addDown(payload.size.toLong())
        }
    }

    private fun Exception.telegramCompatibilityErrorCode(): String {
        return when (this) {
            is IllegalArgumentException -> "mtproxy_handshake_failed"
            is EOFException -> "client_closed"
            is IOException -> "network_failed"
            else -> "unexpected_error"
        }
    }

    private fun logCompatibilityFailure(error: Exception, elapsedMs: Long, sessionId: Long) {
        if (error !is IllegalArgumentException) {
            Log.d(
                LOG_TAG,
                "telegram compatibility failed errorCode=${error.telegramCompatibilityErrorCode()} " +
                    "session=$sessionId elapsedMs=$elapsedMs " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            return
        }

        val decision = handshakeFailureLogDecision(SystemClock.elapsedRealtime())
        if (decision.suppressedSummary > 0) {
            Log.d(
                LOG_TAG,
                "telegram compatibility handshake failures suppressed " +
                    "count=${decision.suppressedSummary} windowMs=$HANDSHAKE_FAILURE_LOG_WINDOW_MS",
            )
        }
        if (!decision.logSample) {
            return
        }

        val marker = (error as? UnsupportedTelegramMtProxyTransportException)?.markerHex ?: "-"
        Log.d(
            LOG_TAG,
            "telegram compatibility failed errorCode=mtproxy_handshake_failed " +
                "session=$sessionId elapsedMs=$elapsedMs marker=$marker " +
                "sample=${decision.sampleIndex}/$HANDSHAKE_FAILURE_LOG_LIMIT " +
                "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
        )
    }

    private fun handshakeFailureLogDecision(nowMs: Long): HandshakeFailureLogDecision {
        synchronized(handshakeFailureLogLock) {
            val previousSuppressed =
                if (handshakeFailureWindowStartedAtMs == 0L ||
                    nowMs - handshakeFailureWindowStartedAtMs > HANDSHAKE_FAILURE_LOG_WINDOW_MS
                ) {
                    val suppressed = handshakeFailuresSuppressedInWindow
                    handshakeFailureWindowStartedAtMs = nowMs
                    handshakeFailuresLoggedInWindow = 0
                    handshakeFailuresSuppressedInWindow = 0
                    suppressed
                } else {
                    0
                }

            return if (handshakeFailuresLoggedInWindow < HANDSHAKE_FAILURE_LOG_LIMIT) {
                handshakeFailuresLoggedInWindow += 1
                HandshakeFailureLogDecision(
                    logSample = true,
                    sampleIndex = handshakeFailuresLoggedInWindow,
                    suppressedSummary = previousSuppressed,
                )
            } else {
                handshakeFailuresSuppressedInWindow += 1
                HandshakeFailureLogDecision(
                    logSample = false,
                    sampleIndex = handshakeFailuresLoggedInWindow,
                    suppressedSummary = previousSuppressed,
                )
            }
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private companion object {
        private const val LOG_TAG = "QNZapretTgCompat"
        private const val CLIENT_INIT_TIMEOUT_MS = 10_000
        private const val TELEGRAM_OBFUSCATION_INIT_SIZE = 64
        private const val RELAY_BUFFER_SIZE = 32 * 1024
        private const val SESSION_WATCHDOG_INTERVAL_MS = 1_000L
        private const val MEDIA_WATCHDOG_INTERVAL_MS = SESSION_WATCHDOG_INTERVAL_MS
        private const val MEDIA_POSITIVE_UPSTREAM_FALLBACK_MS = 5 * 60 * 1000L
        private const val UPLOAD_NEGATIVE_UPSTREAM_FALLBACK_MS = 5 * 60 * 1000L
        private const val UPLOAD_FALLBACK_FIRST_CHUNK_BYTES = RELAY_BUFFER_SIZE
        private const val HANDSHAKE_FAILURE_LOG_WINDOW_MS = 10_000L
        private const val HANDSHAKE_FAILURE_LOG_LIMIT = 8
        private val mediaPositiveUpstreamFallbackUntilMs = ConcurrentHashMap<Int, Long>()
        private val uploadNegativeUpstreamFallbackUntilMs = ConcurrentHashMap<Int, Long>()
    }

    private fun positiveMediaUpstreamFallbackRemainingMs(dcId: Int, nowMs: Long): Long {
        return ((mediaPositiveUpstreamFallbackUntilMs[dcId] ?: 0L) - nowMs).coerceAtLeast(0L)
    }

    private fun uploadNegativeUpstreamFallbackRemainingMs(dcId: Int, nowMs: Long): Long {
        return ((uploadNegativeUpstreamFallbackUntilMs[dcId] ?: 0L) - nowMs).coerceAtLeast(0L)
    }
}

private data class HandshakeFailureLogDecision(
    val logSample: Boolean,
    val sampleIndex: Int,
    val suppressedSummary: Int,
)

internal class TelegramMtProtoPacketSplitter(
    private val protocol: TelegramMtProxyTransportProtocol,
) {
    var bufferedBytes: Int = 0
        private set

    private var plainBuffer = ByteArray(0)
    private var encryptedBuffer = ByteArray(0)
    private var disabled = false

    @Synchronized
    fun split(plainChunk: ByteArray, encryptedChunk: ByteArray): List<ByteArray> {
        require(plainChunk.size == encryptedChunk.size) {
            "Telegram MTProto splitter chunks must have equal sizes"
        }
        if (plainChunk.isEmpty()) {
            return emptyList()
        }
        if (disabled) {
            return listOf(encryptedChunk)
        }

        plainBuffer += plainChunk
        encryptedBuffer += encryptedChunk
        bufferedBytes = encryptedBuffer.size

        val frames = mutableListOf<ByteArray>()
        while (plainBuffer.isNotEmpty()) {
            val packetLength = nextPacketLength(plainBuffer) ?: break
            if (packetLength <= 0 || packetLength > MAX_PACKET_BYTES) {
                disabled = true
                frames += encryptedBuffer
                clearBuffers()
                break
            }
            if (encryptedBuffer.size < packetLength) {
                break
            }
            frames += encryptedBuffer.copyOfRange(0, packetLength)
            plainBuffer = plainBuffer.copyOfRange(packetLength, plainBuffer.size)
            encryptedBuffer = encryptedBuffer.copyOfRange(packetLength, encryptedBuffer.size)
            bufferedBytes = encryptedBuffer.size
        }

        return frames
    }

    @Synchronized
    fun flush(): ByteArray {
        val tail = encryptedBuffer
        clearBuffers()
        return tail
    }

    private fun clearBuffers() {
        plainBuffer = ByteArray(0)
        encryptedBuffer = ByteArray(0)
        bufferedBytes = 0
    }

    private fun nextPacketLength(buffer: ByteArray): Int? {
        return when (protocol) {
            TelegramMtProxyTransportProtocol.ABRIDGED -> abridgedPacketLength(buffer)
            TelegramMtProxyTransportProtocol.INTERMEDIATE,
            TelegramMtProxyTransportProtocol.PADDED_INTERMEDIATE,
            -> intermediatePacketLength(buffer)
        }
    }

    private fun abridgedPacketLength(buffer: ByteArray): Int? {
        val first = buffer[0].toInt() and 0x7f
        val headerLength: Int
        val payloadLength: Int
        if (first == ABRIDGED_LONG_LENGTH_MARKER) {
            if (buffer.size < 4) {
                return null
            }
            payloadLength = (
                (buffer[1].toInt() and BYTE_MASK) or
                    ((buffer[2].toInt() and BYTE_MASK) shl 8) or
                    ((buffer[3].toInt() and BYTE_MASK) shl 16)
                ) * ABRIDGED_LENGTH_GRANULARITY
            headerLength = 4
        } else {
            payloadLength = first * ABRIDGED_LENGTH_GRANULARITY
            headerLength = 1
        }
        if (payloadLength <= 0) {
            return 0
        }
        return headerLength + payloadLength
    }

    private fun intermediatePacketLength(buffer: ByteArray): Int? {
        if (buffer.size < 4) {
            return null
        }
        val payloadLength = (
            (buffer[0].toInt() and BYTE_MASK) or
                ((buffer[1].toInt() and BYTE_MASK) shl 8) or
                ((buffer[2].toInt() and BYTE_MASK) shl 16) or
                ((buffer[3].toInt() and BYTE_MASK) shl 24)
            ) and INTERMEDIATE_LENGTH_MASK
        if (payloadLength <= 0) {
            return 0
        }
        return 4 + payloadLength
    }

    private companion object {
        private const val BYTE_MASK = 0xff
        private const val ABRIDGED_LONG_LENGTH_MARKER = 0x7f
        private const val ABRIDGED_LENGTH_GRANULARITY = 4
        private const val INTERMEDIATE_LENGTH_MASK = 0x7fffffff
        private const val MAX_PACKET_BYTES = 8 * 1024 * 1024
    }
}

private class TelegramSessionMetrics(
    val sessionId: Long,
    val dcId: Int,
    val rawDcId: Int,
    val mediaDc: Boolean,
    private val startedAtMs: Long,
) {
    val bytesUp = AtomicLong(0L)
    val bytesDown = AtomicLong(0L)
    private var windowStartedAtMs = startedAtMs
    private var windowBytesUp = 0L
    private var windowBytesDown = 0L
    private var watchdogCheckedAtMs = startedAtMs
    private var watchdogBytesUp = 0L
    private var watchdogBytesDown = 0L
    private var uploadWatchdogCheckedAtMs = startedAtMs
    private var uploadWatchdogBytesUp = 0L
    private var uploadWatchdogBytesDown = 0L

    @Synchronized
    fun addUp(count: Long) {
        bytesUp.addAndGet(count)
        windowBytesUp += count
        maybeLogWindow()
    }

    @Synchronized
    fun addDown(count: Long) {
        bytesDown.addAndGet(count)
        windowBytesDown += count
        maybeLogWindow()
    }

    private fun maybeLogWindow() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - windowStartedAtMs
        val windowMs = if (mediaDc) MEDIA_THROUGHPUT_WINDOW_MS else TEXT_THROUGHPUT_WINDOW_MS
        if (elapsedMs < windowMs) {
            return
        }
        val upBps = (windowBytesUp * 1000L) / elapsedMs.coerceAtLeast(1L)
        val downBps = (windowBytesDown * 1000L) / elapsedMs.coerceAtLeast(1L)
        Log.d(
            "QNZapretTgCompat",
            "telegram compatibility throughput session=$sessionId dc=$dcId " +
                "mediaDc=$mediaDc windowMs=$elapsedMs upBps=$upBps downBps=$downBps " +
                "totalUp=${bytesUp.get()} totalDown=${bytesDown.get()}",
        )
        windowStartedAtMs = now
        windowBytesUp = 0L
        windowBytesDown = 0L
    }

    @Synchronized
    fun mediaStallDecision(nowMs: Long): TelegramMediaStallDecision? {
        if (!mediaDc) {
            return null
        }
        val durationMs = nowMs - startedAtMs
        if (durationMs < MEDIA_STALL_MIN_DURATION_MS) {
            return null
        }
        val elapsedSinceCheckMs = nowMs - watchdogCheckedAtMs
        if (elapsedSinceCheckMs < MEDIA_STALL_RECENT_WINDOW_MS) {
            return null
        }
        val up = bytesUp.get()
        val down = bytesDown.get()
        val recentProgress = maxOf(up - watchdogBytesUp, down - watchdogBytesDown)
        val progressBps = (recentProgress * 1000L) / elapsedSinceCheckMs.coerceAtLeast(1L)
        if (recentProgress >= MEDIA_STALL_MIN_PROGRESS_BYTES || progressBps >= MEDIA_STALL_MIN_PROGRESS_BPS) {
            watchdogCheckedAtMs = nowMs
            watchdogBytesUp = up
            watchdogBytesDown = down
            return null
        }
        return TelegramMediaStallDecision(
            durationMs = durationMs,
            bytesUp = up,
            bytesDown = down,
            progressBps = progressBps,
        )
    }

    @Synchronized
    fun uploadAckStallDecision(nowMs: Long): TelegramUploadAckStallDecision? {
        val durationMs = nowMs - startedAtMs
        if (durationMs < UPLOAD_ACK_STALL_MIN_DURATION_MS) {
            return null
        }
        val up = bytesUp.get()
        val down = bytesDown.get()
        if (up < UPLOAD_ACK_STALL_MIN_UP_BYTES || down >= UPLOAD_ACK_STALL_MAX_DOWN_BYTES) {
            return null
        }
        val elapsedSinceCheckMs = nowMs - uploadWatchdogCheckedAtMs
        if (elapsedSinceCheckMs < UPLOAD_ACK_STALL_RECENT_WINDOW_MS) {
            return null
        }
        val recentUp = up - uploadWatchdogBytesUp
        val recentDown = down - uploadWatchdogBytesDown
        if (recentUp >= UPLOAD_ACK_STALL_MIN_RECENT_UP_BYTES ||
            recentDown >= UPLOAD_ACK_STALL_MIN_RECENT_DOWN_BYTES
        ) {
            uploadWatchdogCheckedAtMs = nowMs
            uploadWatchdogBytesUp = up
            uploadWatchdogBytesDown = down
            return null
        }
        return TelegramUploadAckStallDecision(
            durationMs = durationMs,
            bytesUp = up,
            bytesDown = down,
            recentUp = recentUp,
            recentDown = recentDown,
        )
    }

    private companion object {
        private const val MEDIA_THROUGHPUT_WINDOW_MS = 1_000L
        private const val TEXT_THROUGHPUT_WINDOW_MS = 5_000L
        private const val MEDIA_STALL_MIN_DURATION_MS = 10_000L
        private const val MEDIA_STALL_RECENT_WINDOW_MS = 5_000L
        private const val MEDIA_STALL_MIN_PROGRESS_BYTES = 128 * 1024L
        private const val MEDIA_STALL_MIN_PROGRESS_BPS = 8 * 1024L
        private const val UPLOAD_ACK_STALL_MIN_DURATION_MS = 12_000L
        private const val UPLOAD_ACK_STALL_RECENT_WINDOW_MS = 5_000L
        private const val UPLOAD_ACK_STALL_MIN_UP_BYTES = 128 * 1024L
        private const val UPLOAD_ACK_STALL_MAX_DOWN_BYTES = 8 * 1024L
        private const val UPLOAD_ACK_STALL_MIN_RECENT_UP_BYTES = 32 * 1024L
        private const val UPLOAD_ACK_STALL_MIN_RECENT_DOWN_BYTES = 1024L
    }
}

private data class TelegramMediaStallDecision(
    val durationMs: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val progressBps: Long,
)

private data class TelegramUploadAckStallDecision(
    val durationMs: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val recentUp: Long,
    val recentDown: Long,
)

private fun InputStream.readBytesExact(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read < 0) {
            throw EOFException("Unexpected EOF")
        }
        offset += read
    }
    return buffer
}
