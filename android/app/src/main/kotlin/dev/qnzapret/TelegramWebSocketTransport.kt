package dev.qnzapret

import android.content.Context
import android.net.Network
import android.net.VpnService
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class TelegramWebSocketConnection(
    val host: String,
    val routeKind: String,
    val connectedAtMs: Long,
    val stream: TelegramWebSocketStream,
    val dcId: Int = 0,
    val mediaDc: Boolean = false,
    val cfDomain: String? = null,
    val dnsMs: Long = -1L,
    val tcpConnectMs: Long = -1L,
    val tlsMs: Long = -1L,
    val wsHandshakeMs: Long = -1L,
    val totalMs: Long = -1L,
    val pooled: Boolean = false,
)

internal data class TelegramWebSocketRouteConfig(
    val cfDomains: List<String> = emptyList(),
    val cfPriority: Boolean = true,
    val tlsVerify: Boolean = true,
    val localDomainCount: Int = 0,
)

internal data class TelegramRouteProbeResult(
    val domain: String,
    val host: String,
    val dcId: Int,
    val routeKind: String,
    val success: Boolean,
    val errorCode: String? = null,
)

internal object TelegramWebSocketTransport {
    fun connect(
        service: VpnService,
        dcId: Int,
        mediaDc: Boolean = false,
        routeConfig: TelegramWebSocketRouteConfig = TelegramWebSocketRouteConfig(),
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        usePool: Boolean = true,
        strategyProxyEndpoint: LocalStrategyProxyEndpoint? = null,
    ): TelegramWebSocketConnection {
        val prefs = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val candidates = routeCandidates(
            dcId = dcId,
            mediaDc = mediaDc,
            routeConfig = routeConfig,
            activeDomain = readActiveDomain(prefs, routeConfig),
            nowMs = SystemClock.elapsedRealtime(),
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        Log.d(
            LOG_TAG,
            "telegram cf route start dc=$dcId mediaDc=$mediaDc " +
                "candidates=${candidates.joinToString(separator = ",") { it.host }} " +
                "cfDomains=${routeConfig.cfDomains.size} cfPriority=${routeConfig.cfPriority} " +
                "tlsVerify=${routeConfig.tlsVerify}",
        )
        if (!routeConfig.tlsVerify) {
            Log.d(LOG_TAG, "telegram cf route securityWarning=tls_verify_disabled")
        }

        if (usePool) {
            acquirePooledConnection(candidates, dcId, mediaDc)?.let { pooled ->
                val candidate = candidates.firstOrNull { it.host == pooled.host && it.kind == pooled.routeKind }
                Log.d(
                    LOG_TAG,
                    "telegram ws pool hit key=${poolKey(dcId, mediaDc, pooled.host)} " +
                        "host=${pooled.host} route=${pooled.routeKind} idleMs=${SystemClock.elapsedRealtime() - pooled.connectedAtMs}",
                )
                if (candidate != null) {
                    schedulePoolRefill(service, candidate, dcId, mediaDc, routeConfig, timeoutMs, strategyProxyEndpoint)
                }
                return pooled.copy(
                    connectedAtMs = SystemClock.elapsedRealtime(),
                    pooled = true,
                )
            }
            Log.d(
                LOG_TAG,
                "telegram ws pool miss dc=$dcId mediaDc=$mediaDc " +
                    "firstKey=${candidates.firstOrNull()?.let { poolKey(dcId, mediaDc, it.host) } ?: "-"}",
            )
        }

        return connectFresh(
            service = service,
            dcId = dcId,
            mediaDc = mediaDc,
            candidates = candidates,
            routeConfig = routeConfig,
            timeoutMs = timeoutMs,
            startedAtMs = startedAtMs,
            refillAfterSuccess = usePool,
            strategyProxyEndpoint = strategyProxyEndpoint,
        )
    }

    fun recordFirstWsPayload(connection: TelegramWebSocketConnection, firstWsPayloadMs: Long) {
        if (connection.host.isBlank()) {
            return
        }
        scoreFor(connection.host, connection.mediaDc).recordFirstPayload(firstWsPayloadMs)
    }

    fun recordSessionResult(
        connection: TelegramWebSocketConnection,
        bytesUp: Long,
        bytesDown: Long,
        durationMs: Long,
        success: Boolean,
        errorCode: String,
    ) {
        if (connection.host.isBlank() || durationMs <= 0L) {
            return
        }
        val outcome = classifySessionResult(
            mediaDc = connection.mediaDc,
            bytesUp = bytesUp,
            bytesDown = bytesDown,
            durationMs = durationMs,
            success = success,
            errorCode = errorCode,
        )
        if (outcome.lowThroughput) {
            connection.cfDomain?.let { domain ->
                mediaCfCooldownUntilMs[domain] = SystemClock.elapsedRealtime() + MEDIA_LOW_THROUGHPUT_COOLDOWN_MS
                Log.d(
                    LOG_TAG,
                    "telegram cf media cooldown domain=$domain reason=low_throughput " +
                        "durationMs=$durationMs bytesUp=$bytesUp bytesDown=$bytesDown",
                )
            }
        }
        if (errorCode == "low_upload_ack") {
            cfHostCooldownUntilMs[connection.host] = SystemClock.elapsedRealtime() + UPLOAD_ACK_COOLDOWN_MS
            Log.d(
                LOG_TAG,
                "telegram cf upload cooldown host=${connection.host} " +
                    "domain=${connection.cfDomain ?: "-"} reason=low_upload_ack " +
                    "durationMs=$durationMs bytesUp=$bytesUp bytesDown=$bytesDown " +
                    "cooldownMs=$UPLOAD_ACK_COOLDOWN_MS",
            )
        }
        val throughputBps = ((bytesUp + bytesDown) * 1000.0) / durationMs.toDouble()
        scoreFor(connection.host, connection.mediaDc)
            .recordSession(
                throughputBps = throughputBps,
                success = outcome.success,
                errorCode = outcome.errorCode,
            )
        Log.d(
            LOG_TAG,
            "telegram route score update host=${connection.host} mediaDc=${connection.mediaDc} " +
                "success=${outcome.success} errorCode=${outcome.errorCode} " +
                "lowThroughput=${outcome.lowThroughput} durationMs=$durationMs " +
                "bytesUp=$bytesUp bytesDown=$bytesDown throughputBps=${throughputBps.toLong()}",
        )
    }

    internal fun routeHostsForTest(
        dcId: Int,
        mediaDc: Boolean,
        cfDomains: List<String>,
        activeDomain: String? = null,
        localDomainCount: Int = cfDomains.size,
    ): List<String> {
        return routeCandidates(
            dcId = dcId,
            mediaDc = mediaDc,
            routeConfig = TelegramWebSocketRouteConfig(
                cfDomains = cfDomains,
                localDomainCount = localDomainCount,
            ),
            activeDomain = activeDomain,
            nowMs = 0L,
        ).map { candidate -> candidate.host }
    }

    internal fun setDirectRouteCooldownForTest(dcId: Int, mediaDc: Boolean, untilMs: Long) {
        directRouteCooldownUntilMs[directCooldownKey(dcId, mediaDc)] = untilMs
    }

    internal fun clearRouteStateForTest() {
        cfCooldownUntilMs.clear()
        mediaCfCooldownUntilMs.clear()
        directRouteCooldownUntilMs.clear()
        cfHostCooldownUntilMs.clear()
        routeScores.clear()
        closePool()
    }

    internal fun setCfHostCooldownForTest(host: String, untilMs: Long) {
        cfHostCooldownUntilMs[host] = untilMs
    }

    internal fun recordRouteFailureForTest(host: String, mediaDc: Boolean, errorCode: String) {
        scoreFor(host, mediaDc).recordFailure(errorCode)
    }

    internal fun classifySessionResultForTest(
        mediaDc: Boolean,
        bytesUp: Long,
        bytesDown: Long,
        durationMs: Long,
        success: Boolean,
        errorCode: String,
    ): TelegramRouteSessionOutcome {
        return classifySessionResult(
            mediaDc = mediaDc,
            bytesUp = bytesUp,
            bytesDown = bytesDown,
            durationMs = durationMs,
            success = success,
            errorCode = errorCode,
        )
    }

    fun closePool() {
        poolEpoch.incrementAndGet()
        val entries = synchronized(poolLock) {
            val all = wsPool.values.flatMap { queue -> queue.toList() }
            wsPool.clear()
            all
        }
        entries.forEach { entry -> runCatching { entry.connection.stream.close() } }
        if (entries.isNotEmpty()) {
            Log.d(LOG_TAG, "telegram ws pool closed count=${entries.size}")
        }
    }

    private fun connectFresh(
        service: VpnService,
        dcId: Int,
        mediaDc: Boolean,
        candidates: List<TelegramWebSocketRouteCandidate>,
        routeConfig: TelegramWebSocketRouteConfig,
        timeoutMs: Int,
        startedAtMs: Long,
        refillAfterSuccess: Boolean,
        strategyProxyEndpoint: LocalStrategyProxyEndpoint?,
    ): TelegramWebSocketConnection {
        val resolver = TelegramCloudflareResolver(service)
        var lastError: IOException? = null
        for ((index, candidate) in candidates.withIndex()) {
            val attemptStartedAtMs = SystemClock.elapsedRealtime()
            if (candidate.isDirectRoute && directRouteCooldownRemainingMs(dcId, mediaDc, attemptStartedAtMs) > 0L) {
                Log.d(
                    LOG_TAG,
                    "telegram direct route cooldown dc=$dcId mediaDc=$mediaDc " +
                        "host=${candidate.host} remainingMs=${directRouteCooldownRemainingMs(dcId, mediaDc, attemptStartedAtMs)}",
                )
                continue
            }
            val network = UnderlyingNetworkSelector.select(service)
            val preferIpv4Only = network != null && !UnderlyingNetworkSelector.supportsIpv6(service, network)
            Log.d(
                LOG_TAG,
                "telegram cf route attempt dc=$dcId host=${candidate.host} route=${candidate.kind} " +
                    "attempt=${index + 1}/${candidates.size} network=${network ?: "-"} " +
                    "preferIpv4Only=$preferIpv4Only timeoutMs=$timeoutMs",
            )

            try {
                val dnsStartedAtMs = SystemClock.elapsedRealtime()
                val resolved = resolver.resolve(
                    host = candidate.host,
                    network = network,
                    preferIpv4Only = preferIpv4Only,
                    timeoutMs = DNS_TIMEOUT_MS,
                )
                val dnsMs = SystemClock.elapsedRealtime() - dnsStartedAtMs
                if (resolved.isEmpty()) {
                    throw UnknownHostException(candidate.host)
                }
                val connection = connectCandidate(
                    service = service,
                    candidate = candidate,
                    resolved = resolved,
                    network = network,
                    tlsVerify = routeConfig.tlsVerify,
                    timeoutMs = timeoutMs,
                    dcId = dcId,
                    mediaDc = mediaDc,
                    startedAtMs = startedAtMs,
                    dnsMs = dnsMs,
                    strategyProxyEndpoint = strategyProxyEndpoint,
                )
                candidate.cfDomain?.let { domain ->
                    saveActiveDomain(service, domain)
                }
                if (refillAfterSuccess) {
                    schedulePoolRefill(service, candidate, dcId, mediaDc, routeConfig, timeoutMs, strategyProxyEndpoint)
                }
                return connection
            } catch (error: IOException) {
                lastError = error
                val errorCode = error.routeErrorCode()
                scoreFor(candidate.host, mediaDc).recordFailure(errorCode)
                if (errorCode == "http_429") {
                    candidate.cfDomain?.let { domain ->
                        cfCooldownUntilMs[domain] = SystemClock.elapsedRealtime() + CF_COOLDOWN_MS
                    }
                }
                if (mediaDc && candidate.isDirectRoute && errorCode in DIRECT_ROUTE_COOLDOWN_ERRORS) {
                    val cooldownUntilMs = SystemClock.elapsedRealtime() + DIRECT_ROUTE_COOLDOWN_MS
                    directRouteCooldownUntilMs[directCooldownKey(dcId, mediaDc)] = cooldownUntilMs
                    Log.d(
                        LOG_TAG,
                        "telegram direct route cooldown set dc=$dcId mediaDc=$mediaDc " +
                            "host=${candidate.host} reason=$errorCode cooldownMs=$DIRECT_ROUTE_COOLDOWN_MS",
                    )
                }
                Log.d(
                    LOG_TAG,
                    "telegram cf route failed dc=$dcId host=${candidate.host} route=${candidate.kind} " +
                        "attempt=${index + 1}/${candidates.size} " +
                        "errorCode=$errorCode httpStatus=${(error as? TelegramWebSocketHttpException)?.statusCode ?: "-"} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
        }
        throw lastError ?: IOException("No Telegram WebSocket route candidates for dc=$dcId")
    }

    fun probeCloudflareDomain(
        service: VpnService,
        domain: String,
        dcId: Int,
        mediaDc: Boolean,
        tlsVerify: Boolean,
        timeoutMs: Int,
    ): TelegramRouteProbeResult {
        val normalizedDomain = domain.trim().trim('.').lowercase()
        val candidates = cloudflareCandidates(dcId, normalizedDomain)
        val startedAtMs = SystemClock.elapsedRealtime()
        val resolver = TelegramCloudflareResolver(service)
        var lastError: IOException? = null
        Log.d(
            LOG_TAG,
            "telegram cf probe start domain=$normalizedDomain dc=$dcId " +
                "candidates=${candidates.joinToString(separator = ",") { it.host }} timeoutMs=$timeoutMs",
        )
        for (candidate in candidates) {
            val network = UnderlyingNetworkSelector.select(service)
            val preferIpv4Only = network != null && !UnderlyingNetworkSelector.supportsIpv6(service, network)
            try {
                val dnsStartedAtMs = SystemClock.elapsedRealtime()
                val resolved = resolver.resolve(
                    host = candidate.host,
                    network = network,
                    preferIpv4Only = preferIpv4Only,
                    timeoutMs = DNS_TIMEOUT_MS,
                )
                val dnsMs = SystemClock.elapsedRealtime() - dnsStartedAtMs
                if (resolved.isEmpty()) {
                    throw UnknownHostException(candidate.host)
                }
                val connection = connectCandidate(
                    service = service,
                    candidate = candidate,
                    resolved = resolved,
                    network = network,
                    tlsVerify = tlsVerify,
                    timeoutMs = timeoutMs,
                    dcId = dcId,
                    mediaDc = mediaDc,
                    startedAtMs = startedAtMs,
                    dnsMs = dnsMs,
                    strategyProxyEndpoint = null,
                )
                connection.stream.close()
                saveActiveDomain(service, normalizedDomain)
                Log.d(
                    LOG_TAG,
                    "telegram cf probe ok domain=$normalizedDomain host=${candidate.host} " +
                        "dc=$dcId httpStatus=101 elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                )
                return TelegramRouteProbeResult(
                    domain = normalizedDomain,
                    host = candidate.host,
                    dcId = dcId,
                    routeKind = candidate.kind,
                    success = true,
                )
            } catch (error: IOException) {
                lastError = error
                val errorCode = error.routeErrorCode()
                if (errorCode == "http_429") {
                    cfCooldownUntilMs[normalizedDomain] = SystemClock.elapsedRealtime() + CF_COOLDOWN_MS
                }
                Log.d(
                    LOG_TAG,
                    "telegram cf probe failed domain=$normalizedDomain host=${candidate.host} " +
                        "dc=$dcId errorCode=$errorCode " +
                        "httpStatus=${(error as? TelegramWebSocketHttpException)?.statusCode ?: "-"} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                )
            }
        }
        return TelegramRouteProbeResult(
            domain = normalizedDomain,
            host = candidates.firstOrNull()?.host ?: normalizedDomain,
            dcId = dcId,
            routeKind = candidates.firstOrNull()?.kind ?: "cloudflare",
            success = false,
            errorCode = lastError?.routeErrorCode() ?: "ws_failed",
        )
    }

    private fun connectCandidate(
        service: VpnService,
        candidate: TelegramWebSocketRouteCandidate,
        resolved: List<TelegramResolvedAddress>,
        network: Network?,
        tlsVerify: Boolean,
        timeoutMs: Int,
        dcId: Int,
        mediaDc: Boolean,
        startedAtMs: Long,
        dnsMs: Long,
        strategyProxyEndpoint: LocalStrategyProxyEndpoint?,
    ): TelegramWebSocketConnection {
        var lastError: IOException? = null
        for ((ipIndex, address) in resolved.take(MAX_IPS_PER_HOST).withIndex()) {
            val rawSocket = Socket()
            val tcpStartedAtMs = SystemClock.elapsedRealtime()
            try {
                rawSocket.tcpNoDelay = true
                rawSocket.soTimeout = timeoutMs
                val directStrategyProxyEndpoint = strategyProxyEndpoint?.takeIf { candidate.isDirectRoute }
                val viaStrategyProxy = directStrategyProxyEndpoint != null
                if (directStrategyProxyEndpoint != null) {
                    connectViaStrategyProxy(
                        service = service,
                        socket = rawSocket,
                        proxyEndpoint = directStrategyProxyEndpoint,
                        targetHost = candidate.host,
                        targetAddress = address,
                        timeoutMs = timeoutMs,
                    )
                } else {
                    if (!service.protect(rawSocket)) {
                        throw IOException("VpnService.protect returned false")
                    }
                    network?.let { selectedNetwork ->
                        try {
                            selectedNetwork.bindSocket(rawSocket)
                        } catch (error: IOException) {
                            Log.d(
                                LOG_TAG,
                                "telegram cf network bind fallback host=${candidate.host} network=$selectedNetwork " +
                                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                            )
                        }
                    }
                    val endpoint = InetSocketAddress(address.address, HTTPS_PORT)
                    rawSocket.connect(endpoint, timeoutMs)
                }
                val tcpConnectedAtMs = SystemClock.elapsedRealtime()

                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawSocket, candidate.host, HTTPS_PORT, true) as SSLSocket
                sslSocket.tcpNoDelay = true
                sslSocket.soTimeout = timeoutMs
                sslSocket.startHandshake()
                val tlsHandshakeAtMs = SystemClock.elapsedRealtime()
                if (tlsVerify &&
                    !HttpsURLConnection.getDefaultHostnameVerifier().verify(candidate.host, sslSocket.session)
                ) {
                    throw SSLException("TLS hostname verification failed for ${candidate.host}")
                }

                val openResult = TelegramWebSocketStream.open(sslSocket, candidate.host)
                sslSocket.soTimeout = STREAM_READ_TIMEOUT_MS
                val connectedAtMs = SystemClock.elapsedRealtime()
                val tcpConnectMs = tcpConnectedAtMs - tcpStartedAtMs
                val tlsMs = tlsHandshakeAtMs - tcpConnectedAtMs
                val wsHandshakeMs = connectedAtMs - tlsHandshakeAtMs
                scoreFor(candidate.host, mediaDc).recordSuccess(
                    dnsMs = dnsMs,
                    tcpConnectMs = tcpConnectMs,
                    tlsMs = tlsMs,
                    wsHandshakeMs = wsHandshakeMs,
                )
                Log.d(
                    LOG_TAG,
                    "telegram cf route ok dc=$dcId mediaDc=$mediaDc host=${candidate.host} route=${candidate.kind} " +
                        "ip=${address.address.hostAddress} ipSource=${address.source} ipAttempt=${ipIndex + 1} " +
                        "via=${if (viaStrategyProxy) "strategy_socks" else "protected_socket"} " +
                        "dnsMs=$dnsMs tcpConnectMs=$tcpConnectMs tlsMs=$tlsMs wsHandshakeMs=$wsHandshakeMs " +
                        "httpStatus=${openResult.statusCode} " +
                        "totalMs=${connectedAtMs - startedAtMs}",
                )
                return TelegramWebSocketConnection(
                    host = candidate.host,
                    routeKind = candidate.kind,
                    connectedAtMs = connectedAtMs,
                    stream = openResult.stream,
                    dcId = dcId,
                    mediaDc = mediaDc,
                    cfDomain = candidate.cfDomain,
                    dnsMs = dnsMs,
                    tcpConnectMs = tcpConnectMs,
                    tlsMs = tlsMs,
                    wsHandshakeMs = wsHandshakeMs,
                    totalMs = connectedAtMs - startedAtMs,
                )
            } catch (error: IOException) {
                lastError = error
                runCatching { rawSocket.close() }
                Log.d(
                    LOG_TAG,
                    "telegram cf route ip failed dc=$dcId mediaDc=$mediaDc host=${candidate.host} route=${candidate.kind} " +
                        "ip=${address.address.hostAddress} ipSource=${address.source} ipAttempt=${ipIndex + 1} " +
                        "errorCode=${error.routeErrorCode()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - tcpStartedAtMs} " +
                        "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
        }
        throw lastError ?: UnknownHostException(candidate.host)
    }

    private fun connectViaStrategyProxy(
        service: VpnService,
        socket: Socket,
        proxyEndpoint: LocalStrategyProxyEndpoint,
        targetHost: String,
        targetAddress: TelegramResolvedAddress,
        timeoutMs: Int,
    ) {
        if (!service.protect(socket)) {
            throw IOException("VpnService.protect returned false")
        }
        socket.connect(InetSocketAddress(proxyEndpoint.host, proxyEndpoint.port), timeoutMs)
        Socks5RelayClient.connect(
            socket = socket,
            target = Socks5RelayTarget(
                host = targetAddress.address.hostAddress ?: targetHost,
                port = HTTPS_PORT,
                inetAddress = targetAddress.address,
            ),
            auth = null,
            timeoutMs = timeoutMs,
        )
        socket.soTimeout = timeoutMs
        Log.d(
            LOG_TAG,
            "telegram direct route via strategy proxy target=$targetHost " +
                "ip=${targetAddress.address.hostAddress} proxy=${proxyEndpoint.host}:${proxyEndpoint.port}",
        )
    }

    private fun routeCandidates(
        dcId: Int,
        mediaDc: Boolean,
        routeConfig: TelegramWebSocketRouteConfig,
        activeDomain: String?,
        nowMs: Long,
    ): List<TelegramWebSocketRouteCandidate> {
        if (dcId !in 1..5) {
            throw IOException("Unsupported Telegram DC id $dcId")
        }
        val directPrimary = TelegramWebSocketRouteCandidate("kws$dcId.web.telegram.org", "direct_kws", sourceTier = 2, variantRank = 0)
        val directAlt = TelegramWebSocketRouteCandidate("kws$dcId-1.web.telegram.org", "direct_kws_alt", sourceTier = 2, variantRank = 0)
        val directCandidates = if (mediaDc) {
            listOf(
                directAlt,
                TelegramWebSocketRouteCandidate("${dcId.dcLegacyName}.web.telegram.org", "direct_legacy", sourceTier = 2, variantRank = 1),
                directPrimary,
            )
        } else {
            listOf(
                directPrimary,
                TelegramWebSocketRouteCandidate("${dcId.dcLegacyName}.web.telegram.org", "direct_legacy", sourceTier = 2, variantRank = 1),
                directAlt.copy(variantRank = 2),
            )
        }
        val direct = if (directRouteCooldownRemainingMs(dcId, mediaDc, nowMs) > 0L) {
            emptyList()
        } else {
            directCandidates
        }
        val normalizedDomains = routeConfig.cfDomains
            .map { domain -> domain.trim().trim('.').lowercase() }
            .filter { domain -> domain.isNotEmpty() }
            .distinct()
        val localLimit = routeConfig.localDomainCount.coerceIn(0, normalizedDomains.size)
        val localDomains = normalizedDomains.take(localLimit).orderDomains(activeDomain)
        val publicDomains = normalizedDomains.drop(localLimit).orderDomains(activeDomain)
        val cfLocal = localDomains.filterAvailableDomains(mediaDc, nowMs).flatMap { domain ->
            cloudflareCandidates(
                dcId = dcId,
                domain = domain,
                sourceTier = 0,
                active = domain == activeDomain,
            )
        }.filterAvailableHosts(nowMs).sortByRouteScore(mediaDc)
        val cfPublic = publicDomains.filterAvailableDomains(mediaDc, nowMs).flatMap { domain ->
            cloudflareCandidates(
                dcId = dcId,
                domain = domain,
                sourceTier = 1,
                active = domain == activeDomain,
            )
        }.filterAvailableHosts(nowMs).sortByRouteScore(mediaDc)
        val cf = if (mediaDc) cfLocal + direct + cfPublic else cfLocal + cfPublic
        return if (mediaDc) {
            if (routeConfig.cfPriority) cf else direct + cfLocal + cfPublic
        } else {
            if (routeConfig.cfPriority) cf + direct else direct + cf
        }
    }

    private fun cloudflareCandidates(
        dcId: Int,
        domain: String,
        sourceTier: Int = 0,
        active: Boolean = false,
    ): List<TelegramWebSocketRouteCandidate> {
        val primary = TelegramWebSocketRouteCandidate("kws$dcId.$domain", "cloudflare", domain, sourceTier, active, variantRank = 0)
        val alt = TelegramWebSocketRouteCandidate("kws$dcId-1.$domain", "cloudflare_alt", domain, sourceTier, active, variantRank = 1)
        return listOf(primary, alt)
    }

    private fun List<TelegramWebSocketRouteCandidate>.sortByRouteScore(
        mediaDc: Boolean,
    ): List<TelegramWebSocketRouteCandidate> {
        return mapIndexed { index, candidate -> IndexedRouteCandidate(index, candidate) }
            .sortedWith(
                compareBy<IndexedRouteCandidate> { item -> item.candidate.sourceTier }
                    .thenBy { item -> item.candidate.variantRank }
                    .thenBy { item -> scoreFor(item.candidate.host, mediaDc).sortScore() }
                    .thenBy { item -> if (item.candidate.active) 0 else 1 }
                    .thenBy { item -> item.index },
            )
            .map { item -> item.candidate }
    }

    private fun List<String>.orderDomains(activeDomain: String?): List<String> {
        val active = activeDomain?.takeIf { domain -> domain in this }?.let(::listOf).orEmpty()
        val inactive = filterNot { domain -> domain == activeDomain }
        return active + inactive
    }

    private fun List<String>.filterAvailableDomains(mediaDc: Boolean, nowMs: Long): List<String> {
        return filter { domain ->
            val cooldownUntil = cfCooldownUntilMs[domain] ?: 0L
            val mediaCooldownUntil = if (mediaDc) mediaCfCooldownUntilMs[domain] ?: 0L else 0L
            val available = cooldownUntil <= nowMs && mediaCooldownUntil <= nowMs
            if (!available) {
                val remainingMs = maxOf(cooldownUntil - nowMs, mediaCooldownUntil - nowMs)
                Log.d(
                    LOG_TAG,
                    "telegram cf route cooldown domain=$domain mediaDc=$mediaDc remainingMs=$remainingMs",
                )
            }
            available
        }
    }

    private fun List<TelegramWebSocketRouteCandidate>.filterAvailableHosts(
        nowMs: Long,
    ): List<TelegramWebSocketRouteCandidate> {
        return filter { candidate ->
            val cooldownUntil = cfHostCooldownUntilMs[candidate.host] ?: 0L
            val available = cooldownUntil <= nowMs
            if (!available) {
                runCatching {
                    Log.d(
                        LOG_TAG,
                        "telegram cf route cooldown host=${candidate.host} " +
                            "domain=${candidate.cfDomain ?: "-"} remainingMs=${cooldownUntil - nowMs}",
                    )
                }
            }
            available
        }
    }

    private fun directRouteCooldownRemainingMs(dcId: Int, mediaDc: Boolean, nowMs: Long): Long {
        return (directRouteCooldownUntilMs[directCooldownKey(dcId, mediaDc)] ?: 0L) - nowMs
    }

    private fun directCooldownKey(dcId: Int, mediaDc: Boolean): String {
        return "$dcId|$mediaDc"
    }

    private fun readActiveDomain(
        prefs: android.content.SharedPreferences,
        routeConfig: TelegramWebSocketRouteConfig,
    ): String? {
        val normalizedDomains = routeConfig.cfDomains
            .map { domain -> domain.trim().trim('.').lowercase() }
            .filter { domain -> domain.isNotEmpty() }
            .distinct()
        return prefs.getString(KEY_ACTIVE_CF_DOMAIN, null)
            ?.trim()
            ?.trim('.')
            ?.lowercase()
            ?.takeIf { domain -> domain in normalizedDomains }
    }

    private fun classifySessionResult(
        mediaDc: Boolean,
        bytesUp: Long,
        bytesDown: Long,
        durationMs: Long,
        success: Boolean,
        errorCode: String,
    ): TelegramRouteSessionOutcome {
        val progressBytes = maxOf(bytesUp, bytesDown)
        val lowThroughput = mediaDc && (
            errorCode == "low_media_throughput" ||
                (
                    success &&
                        durationMs >= MEDIA_LOW_THROUGHPUT_MIN_DURATION_MS &&
                        progressBytes < MEDIA_LOW_THROUGHPUT_MIN_PROGRESS_BYTES &&
                        progressBps(progressBytes, durationMs) < MEDIA_LOW_THROUGHPUT_PROGRESS_BPS
                    )
            )
        return if (lowThroughput) {
            TelegramRouteSessionOutcome(
                success = false,
                errorCode = "low_media_throughput",
                lowThroughput = true,
            )
        } else {
            TelegramRouteSessionOutcome(
                success = success,
                errorCode = errorCode,
                lowThroughput = false,
            )
        }
    }

    private fun progressBps(progressBytes: Long, durationMs: Long): Long {
        return (progressBytes * 1000L) / durationMs.coerceAtLeast(1L)
    }

    private fun IOException.routeErrorCode(): String {
        return when (this) {
            is SocketTimeoutException -> "timeout"
            is TelegramWebSocketHttpException -> when (statusCode) {
                429 -> "http_429"
                403 -> "http_403"
                in 300..399 -> "http_redirect"
                else -> "ws_failed"
            }
            is SSLException -> "tls_failed"
            is UnknownHostException -> "dns_failed"
            else -> "ws_failed"
        }
    }

    private val Int.dcLegacyName: String
        get() = when (this) {
            1 -> "pluto"
            2 -> "venus"
            3 -> "aurora"
            4 -> "vesta"
            5 -> "flora"
            else -> throw IOException("Unsupported Telegram DC id $this")
        }

    private fun saveActiveDomain(service: VpnService, domain: String) {
        service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CF_DOMAIN, domain)
            .apply()
        Log.d(LOG_TAG, "telegram cf active domain saved domain=$domain")
    }

    private fun acquirePooledConnection(
        candidates: List<TelegramWebSocketRouteCandidate>,
        dcId: Int,
        mediaDc: Boolean,
    ): TelegramWebSocketConnection? {
        val now = SystemClock.elapsedRealtime()
        val stale = mutableListOf<TelegramWebSocketConnection>()
        synchronized(poolLock) {
            for (candidate in candidates) {
                val key = TelegramWebSocketPoolKey(dcId, mediaDc, candidate.host)
                val queue = wsPool[key] ?: continue
                while (queue.isNotEmpty()) {
                    val entry = queue.removeFirst()
                    if (now - entry.createdAtMs <= WS_POOL_MAX_IDLE_MS) {
                        return entry.connection
                    }
                    stale += entry.connection
                    Log.d(
                        LOG_TAG,
                        "telegram ws pool stale closed key=${poolKey(dcId, mediaDc, candidate.host)} " +
                            "idleMs=${now - entry.createdAtMs}",
                    )
                }
            }
        }
        stale.forEach { connection -> runCatching { connection.stream.close() } }
        return null
    }

    private fun schedulePoolRefill(
        service: VpnService,
        candidate: TelegramWebSocketRouteCandidate,
        dcId: Int,
        mediaDc: Boolean,
        routeConfig: TelegramWebSocketRouteConfig,
        timeoutMs: Int,
        strategyProxyEndpoint: LocalStrategyProxyEndpoint?,
    ) {
        val key = TelegramWebSocketPoolKey(dcId, mediaDc, candidate.host)
        val epoch = poolEpoch.get()
        synchronized(poolLock) {
            if (poolEntryCountLocked(key) >= WS_POOL_SIZE_PER_KEY || totalPoolEntryCountLocked() >= WS_POOL_MAX_TOTAL) {
                return
            }
        }
        poolExecutor.execute {
            Log.d(
                LOG_TAG,
                "telegram ws pool warm start key=${poolKey(dcId, mediaDc, candidate.host)} " +
                    "route=${candidate.kind} tlsVerify=${routeConfig.tlsVerify}",
            )
            try {
                val startedAtMs = SystemClock.elapsedRealtime()
                val connection = connectFresh(
                    service = service,
                    dcId = dcId,
                    mediaDc = mediaDc,
                    candidates = listOf(candidate),
                    routeConfig = routeConfig,
                    timeoutMs = timeoutMs,
                    startedAtMs = startedAtMs,
                    refillAfterSuccess = false,
                    strategyProxyEndpoint = strategyProxyEndpoint,
                )
                val added = synchronized(poolLock) {
                    if (poolEpoch.get() != epoch ||
                        poolEntryCountLocked(key) >= WS_POOL_SIZE_PER_KEY ||
                        totalPoolEntryCountLocked() >= WS_POOL_MAX_TOTAL
                    ) {
                        false
                    } else {
                        wsPool.getOrPut(key) { ArrayDeque() }
                            .addLast(TelegramWebSocketPoolEntry(connection, SystemClock.elapsedRealtime()))
                        true
                    }
                }
                if (added) {
                    Log.d(
                        LOG_TAG,
                        "telegram ws pool refill ok key=${poolKey(dcId, mediaDc, candidate.host)} " +
                            "host=${connection.host} route=${connection.routeKind} " +
                            "readyMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                    )
                } else {
                    connection.stream.close()
                }
            } catch (error: IOException) {
                Log.d(
                    LOG_TAG,
                    "telegram ws pool refill failed key=${poolKey(dcId, mediaDc, candidate.host)} " +
                        "errorCode=${error.routeErrorCode()} error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                )
            }
        }
    }

    private fun poolEntryCountLocked(key: TelegramWebSocketPoolKey): Int {
        return wsPool[key]?.size ?: 0
    }

    private fun totalPoolEntryCountLocked(): Int {
        return wsPool.values.sumOf { queue -> queue.size }
    }

    private fun scoreFor(host: String, mediaDc: Boolean): TelegramRouteScore {
        return routeScores.getOrPut("$host|$mediaDc") { TelegramRouteScore() }
    }

    private fun poolKey(dcId: Int, mediaDc: Boolean, host: String): String {
        return "dc=$dcId/${if (mediaDc) "media" else "text"}/$host"
    }

    private const val LOG_TAG = "QNZapretTgCompat"
    private const val HTTPS_PORT = 443
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val STREAM_READ_TIMEOUT_MS = 0
    private const val DNS_TIMEOUT_MS = 1_500
    private const val MAX_IPS_PER_HOST = 2
    private const val CF_COOLDOWN_MS = 45_000L
    private const val UPLOAD_ACK_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MEDIA_LOW_THROUGHPUT_COOLDOWN_MS = 60_000L
    private const val MEDIA_LOW_THROUGHPUT_MIN_DURATION_MS = 10_000L
    private const val MEDIA_LOW_THROUGHPUT_MIN_PROGRESS_BYTES = 128 * 1024L
    private const val MEDIA_LOW_THROUGHPUT_PROGRESS_BPS = 8 * 1024L
    private const val DIRECT_ROUTE_COOLDOWN_MS = 60_000L
    private const val WS_POOL_SIZE_PER_KEY = 2
    private const val WS_POOL_MAX_TOTAL = 4
    private const val WS_POOL_MAX_IDLE_MS = 60_000L
    private const val PREFS_NAME = "telegram_compatibility_proxy"
    private const val KEY_ACTIVE_CF_DOMAIN = "active_cf_domain"
    private val DIRECT_ROUTE_COOLDOWN_ERRORS = setOf("timeout", "dns_failed", "tls_failed", "ws_failed")

    private val cfCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val mediaCfCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val directRouteCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val cfHostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val routeScores = ConcurrentHashMap<String, TelegramRouteScore>()
    private val poolLock = Any()
    private val wsPool = LinkedHashMap<TelegramWebSocketPoolKey, ArrayDeque<TelegramWebSocketPoolEntry>>()
    private val poolEpoch = AtomicLong(0L)
    private val poolExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QNZapretTgWsPool").apply { isDaemon = true }
    }
}

private data class TelegramWebSocketRouteCandidate(
    val host: String,
    val kind: String,
    val cfDomain: String? = null,
    val sourceTier: Int = 0,
    val active: Boolean = false,
    val variantRank: Int = 0,
) {
    val isDirectRoute: Boolean
        get() = kind.startsWith("direct_")
}

private data class IndexedRouteCandidate(
    val index: Int,
    val candidate: TelegramWebSocketRouteCandidate,
)

private data class TelegramWebSocketPoolKey(
    val dcId: Int,
    val mediaDc: Boolean,
    val host: String,
)

private data class TelegramWebSocketPoolEntry(
    val connection: TelegramWebSocketConnection,
    val createdAtMs: Long,
)

internal data class TelegramRouteSessionOutcome(
    val success: Boolean,
    val errorCode: String,
    val lowThroughput: Boolean,
)

private class TelegramRouteScore {
    private var dnsEwma: Double? = null
    private var tcpEwma: Double? = null
    private var tlsEwma: Double? = null
    private var wsEwma: Double? = null
    private var firstPayloadEwma: Double? = null
    private var throughputEwma: Double? = null
    private var failures: Int = 0

    @Synchronized
    fun recordSuccess(dnsMs: Long, tcpConnectMs: Long, tlsMs: Long, wsHandshakeMs: Long) {
        dnsEwma = ewma(dnsEwma, dnsMs.toDouble())
        tcpEwma = ewma(tcpEwma, tcpConnectMs.toDouble())
        tlsEwma = ewma(tlsEwma, tlsMs.toDouble())
        wsEwma = ewma(wsEwma, wsHandshakeMs.toDouble())
        failures = (failures - 1).coerceAtLeast(0)
    }

    @Synchronized
    fun recordFirstPayload(firstWsPayloadMs: Long) {
        firstPayloadEwma = ewma(firstPayloadEwma, firstWsPayloadMs.toDouble())
    }

    @Synchronized
    fun recordSession(throughputBps: Double, success: Boolean, errorCode: String) {
        if (throughputBps > 0.0) {
            throughputEwma = ewma(throughputEwma, throughputBps)
        }
        if (success) {
            failures = (failures - 1).coerceAtLeast(0)
        } else if (errorCode != "client_closed") {
            failures = (failures + 1).coerceAtMost(20)
        }
    }

    @Synchronized
    fun recordFailure(errorCode: String) {
        failures += if (errorCode == "http_429") 4 else 1
        failures = failures.coerceAtMost(20)
    }

    @Synchronized
    fun sortScore(): Double {
        val base = (dnsEwma ?: 250.0) +
            (tcpEwma ?: 750.0) +
            (tlsEwma ?: 750.0) +
            (wsEwma ?: 750.0) +
            ((firstPayloadEwma ?: 500.0) * 0.5)
        val throughputBonus = ((throughputEwma ?: 0.0) / 64_000.0).coerceAtMost(5.0) * 100.0
        return base + failures * 2_000.0 - throughputBonus
    }

    private fun ewma(previous: Double?, next: Double): Double {
        return previous?.let { value -> value * 0.7 + next * 0.3 } ?: next
    }
}

internal class TelegramWebSocketStream private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {
    @Synchronized
    fun writeBinary(payload: ByteArray) {
        writeFrame(opcode = OPCODE_BINARY, payload = payload)
    }

    @Synchronized
    fun writePong(payload: ByteArray) {
        writeFrame(opcode = OPCODE_PONG, payload = payload)
    }

    fun readBinary(): ByteArray? {
        while (true) {
            val first = input.read()
            if (first < 0) {
                return null
            }
            val second = input.read()
            if (second < 0) {
                throw EOFException("Unexpected EOF in WebSocket header")
            }
            val opcode = first and 0x0f
            val masked = (second and 0x80) != 0
            var length = (second and 0x7f).toLong()
            if (length == 126L) {
                length = readUnsignedShort()
            } else if (length == 127L) {
                length = readLongLength()
            }
            if (length > MAX_FRAME_SIZE) {
                throw IOException("Telegram WebSocket frame too large: $length")
            }
            val mask = if (masked) input.readBytesExact(4) else null
            val payload = input.readBytesExact(length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                OPCODE_CONTINUATION, OPCODE_BINARY -> return payload
                OPCODE_CLOSE -> return null
                OPCODE_PING -> {
                    writePong(payload)
                    continue
                }
                OPCODE_PONG -> continue
                else -> throw IOException("Unsupported Telegram WebSocket opcode $opcode")
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4)
        secureRandom.nextBytes(mask)
        val header = ByteArray(WEBSOCKET_HEADER_MAX_SIZE)
        var cursor = 0
        header[cursor++] = (0x80 or opcode).toByte()
        when {
            payload.size < 126 -> header[cursor++] = (0x80 or payload.size).toByte()
            payload.size <= 0xffff -> {
                header[cursor++] = (0x80 or 126).toByte()
                header[cursor++] = ((payload.size ushr 8) and 0xff).toByte()
                header[cursor++] = (payload.size and 0xff).toByte()
            }
            else -> {
                header[cursor++] = (0x80 or 127).toByte()
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header[cursor++] = ((size ushr shift) and 0xff).toByte()
                }
            }
        }
        mask.copyInto(header, destinationOffset = cursor)
        cursor += mask.size
        val maskedPayload = ByteArray(payload.size)
        for (index in payload.indices) {
            maskedPayload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        output.write(header, 0, cursor)
        output.write(maskedPayload)
        output.flush()
    }

    private fun readUnsignedShort(): Long {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0) {
            throw EOFException("Unexpected EOF in WebSocket length")
        }
        return ((first shl 8) or second).toLong()
    }

    private fun readLongLength(): Long {
        var result = 0L
        repeat(8) {
            val next = input.read()
            if (next < 0) {
                throw EOFException("Unexpected EOF in WebSocket length")
            }
            result = (result shl 8) or next.toLong()
        }
        return result
    }

    companion object {
        fun open(socket: SSLSocket, host: String): TelegramWebSocketOpenResult {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val keyBytes = ByteArray(16)
            secureRandom.nextBytes(keyBytes)
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = buildString {
                append("GET /apiws HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)

            output.write(request)
            output.flush()

            val headers = readHttpHeaders(input)
            val statusLine = headers.firstOrNull() ?: "-"
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            if (statusCode != 101) {
                throw TelegramWebSocketHttpException(
                    statusCode = statusCode,
                    statusLine = statusLine,
                )
            }
            val accept = headers.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            val expectedAccept = websocketAccept(key)
            if (accept != null && accept != expectedAccept) {
                throw IOException("Telegram WebSocket accept mismatch")
            }
            return TelegramWebSocketOpenResult(
                stream = TelegramWebSocketStream(socket, input, output),
                statusCode = statusCode,
                statusLine = statusLine,
            )
        }

        private fun readHttpHeaders(input: InputStream): List<String> {
            val buffer = ByteArrayOutputStream()
            var previous3 = -1
            var previous2 = -1
            var previous1 = -1
            while (buffer.size() < MAX_HEADER_SIZE) {
                val next = input.read()
                if (next < 0) {
                    throw EOFException("Unexpected EOF in WebSocket handshake")
                }
                buffer.write(next)
                if (previous3 == '\r'.code &&
                    previous2 == '\n'.code &&
                    previous1 == '\r'.code &&
                    next == '\n'.code
                ) {
                    return buffer.toString(Charsets.US_ASCII.name())
                        .trimEnd()
                        .split("\r\n")
                }
                previous3 = previous2
                previous2 = previous1
                previous1 = next
            }
            throw IOException("Telegram WebSocket handshake headers are too large")
        }

        private fun websocketAccept(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADER_SIZE = 16 * 1024
        private const val MAX_FRAME_SIZE = 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xa
        private const val OPCODE_BINARY = 0x2
        private const val WEBSOCKET_HEADER_MAX_SIZE = 14
        private val secureRandom = SecureRandom()
    }
}

internal data class TelegramWebSocketOpenResult(
    val stream: TelegramWebSocketStream,
    val statusCode: Int,
    val statusLine: String,
)

private class TelegramWebSocketHttpException(
    val statusCode: Int,
    val statusLine: String,
) : IOException("Telegram WebSocket handshake failed: $statusLine")

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
