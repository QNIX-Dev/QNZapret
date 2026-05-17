package dev.qnzapret

import android.net.Network
import android.net.VpnService
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
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
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class TelegramResolvedAddress(
    val address: InetAddress,
    val source: String,
)

internal class TelegramCloudflareResolver(private val service: VpnService) {
    fun resolve(
        host: String,
        network: Network?,
        preferIpv4Only: Boolean,
        timeoutMs: Int = DNS_TIMEOUT_MS,
    ): List<TelegramResolvedAddress> {
        val normalizedHost = host.trim().trim('.')
        val cacheKey = "$normalizedHost|ipv4Only=$preferIpv4Only"
        val now = SystemClock.elapsedRealtime()
        cache[cacheKey]?.takeIf { entry -> entry.expiresAtMs > now }?.let { entry ->
            entry.addresses.forEach { resolved ->
                Log.d(
                    LOG_TAG,
                    "telegram cf dns ok host=$normalizedHost ip=${resolved.address.hostAddress} " +
                        "source=${resolved.source}_cache",
                )
            }
            return entry.addresses
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        Log.d(
            LOG_TAG,
            "telegram cf dns start host=$normalizedHost network=${network ?: "-"} " +
                "preferIpv4Only=$preferIpv4Only timeoutMs=$timeoutMs",
        )

        val errors = mutableListOf<String>()
        val system = resolveSystem(normalizedHost, network, preferIpv4Only)
        if (system.isNotEmpty()) {
            return cacheAndLog(cacheKey, normalizedHost, "system", system, startedAtMs)
        }
        errors += "system_empty"

        val doh = resolveDoh(normalizedHost, network, preferIpv4Only, timeoutMs, errors)
        if (doh.isNotEmpty()) {
            return cacheAndLog(cacheKey, normalizedHost, "doh", doh, startedAtMs)
        }

        val udp = resolveUdp(normalizedHost, network, preferIpv4Only, timeoutMs, errors)
        if (udp.isNotEmpty()) {
            return cacheAndLog(cacheKey, normalizedHost, "udp", udp, startedAtMs)
        }

        Log.d(
            LOG_TAG,
            "telegram cf dns failed host=$normalizedHost elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "errors=${errors.joinToString(separator = "|").ifBlank { "-" }}",
        )
        return emptyList()
    }

    private fun resolveSystem(
        host: String,
        network: Network?,
        preferIpv4Only: Boolean,
    ): List<InetAddress> {
        return try {
            val addresses = if (network != null) {
                network.getAllByName(host).toList()
            } else {
                InetAddress.getAllByName(host).toList()
            }
            orderForNetwork(addresses, preferIpv4Only)
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram cf dns system failed host=$host network=${network ?: "-"} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            emptyList()
        }
    }

    private fun resolveDoh(
        host: String,
        network: Network?,
        preferIpv4Only: Boolean,
        timeoutMs: Int,
        errors: MutableList<String>,
    ): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val queryTypes = queryTypes(preferIpv4Only)
        for (endpoint in DOH_ENDPOINTS) {
            for (queryType in queryTypes) {
                val query = DnsQuery.create(host, queryType)
                val encoded = Base64.encodeToString(
                    query.message,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                for (ip in endpoint.ips) {
                    try {
                        val body = executeDohRequest(endpoint.host, ip, encoded, network, timeoutMs)
                        result += parseDnsResponse(body, query.id)
                    } catch (error: Exception) {
                        errors += "doh_${endpoint.host}_${queryType.name}_${error.javaClass.simpleName}"
                    }
                    if (result.isNotEmpty()) {
                        return orderForNetwork(result, preferIpv4Only)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun executeDohRequest(
        host: String,
        ip: String,
        encodedQuery: String,
        network: Network?,
        timeoutMs: Int,
    ): ByteArray {
        val rawSocket = Socket()
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = timeoutMs
            if (!service.protect(rawSocket)) {
                throw IOException("VpnService.protect returned false")
            }
            network?.bindSocketQuietly(rawSocket, "doh", host)
            rawSocket.connect(InetSocketAddress(InetAddress.getByName(ip), HTTPS_PORT), timeoutMs)
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, host, HTTPS_PORT, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sslSocket.session)) {
                throw IOException("DoH TLS hostname verification failed for $host")
            }
            val request = buildString {
                append("GET /dns-query?dns=$encodedQuery HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Accept: application/dns-message\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            sslSocket.getOutputStream().write(request)
            sslSocket.getOutputStream().flush()
            val response = readHttpResponse(sslSocket.getInputStream())
            if (response.statusCode != 200) {
                throw IOException("DoH HTTP ${response.statusCode}")
            }
            return response.body
        } finally {
            runCatching { rawSocket.close() }
        }
    }

    private fun resolveUdp(
        host: String,
        network: Network?,
        preferIpv4Only: Boolean,
        timeoutMs: Int,
        errors: MutableList<String>,
    ): List<InetAddress> {
        val dnsServers = buildList {
            if (network != null) {
                addAll(UnderlyingNetworkSelector.resolveDnsServers(service, network))
            }
            addAll(PUBLIC_DNS.map { ip -> InetAddress.getByName(ip) })
        }.distinctBy { address -> address.hostAddress }
        val result = mutableListOf<InetAddress>()
        for (server in dnsServers) {
            for (queryType in queryTypes(preferIpv4Only)) {
                val query = DnsQuery.create(host, queryType)
                try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = timeoutMs.coerceAtMost(UDP_DNS_TIMEOUT_MS)
                        if (!service.protect(socket)) {
                            throw IOException("VpnService.protect returned false")
                        }
                        network?.bindSocketQuietly(socket, "udp_dns", server.hostAddress ?: "-")
                        socket.connect(InetSocketAddress(server, DNS_PORT))
                        socket.send(DatagramPacket(query.message, query.message.size))
                        val buffer = ByteArray(DNS_RESPONSE_MAX_SIZE)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        result += parseDnsResponse(packet.data.copyOf(packet.length), query.id)
                    }
                } catch (error: Exception) {
                    errors += "udp_${server.hostAddress}_${queryType.name}_${error.javaClass.simpleName}"
                }
                if (result.isNotEmpty()) {
                    return orderForNetwork(result, preferIpv4Only)
                }
            }
        }
        return emptyList()
    }

    private fun cacheAndLog(
        cacheKey: String,
        host: String,
        source: String,
        addresses: List<InetAddress>,
        startedAtMs: Long,
    ): List<TelegramResolvedAddress> {
        val resolved = addresses
            .distinctBy { address -> address.hostAddress }
            .map { address -> TelegramResolvedAddress(address = address, source = source) }
        cache[cacheKey] = CacheEntry(
            addresses = resolved,
            expiresAtMs = SystemClock.elapsedRealtime() + CACHE_TTL_MS,
        )
        resolved.forEach { address ->
            Log.d(
                LOG_TAG,
                "telegram cf dns ok host=$host ip=${address.address.hostAddress} source=$source " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
        }
        return resolved
    }

    private fun queryTypes(preferIpv4Only: Boolean): List<DnsQueryType> {
        return if (preferIpv4Only) {
            listOf(DnsQueryType.A)
        } else {
            listOf(DnsQueryType.A, DnsQueryType.AAAA)
        }
    }

    private fun orderForNetwork(addresses: List<InetAddress>, preferIpv4Only: Boolean): List<InetAddress> {
        val distinct = addresses.distinctBy { address -> address.hostAddress }
        if (preferIpv4Only) {
            val ipv4 = distinct.filterIsInstance<Inet4Address>()
            if (ipv4.isNotEmpty()) {
                return ipv4
            }
        }
        return distinct.sortedBy { address -> if (address is Inet6Address) 1 else 0 }
    }

    private fun Network.bindSocketQuietly(socket: Socket, label: String, target: String) {
        try {
            bindSocket(socket)
        } catch (error: IOException) {
            Log.d(
                LOG_TAG,
                "telegram cf network bind fallback type=$label target=$target network=$this " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        }
    }

    private fun Network.bindSocketQuietly(socket: DatagramSocket, label: String, target: String) {
        try {
            bindSocket(socket)
        } catch (error: IOException) {
            Log.d(
                LOG_TAG,
                "telegram cf network bind fallback type=$label target=$target network=$this " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        }
    }

    private companion object {
        private const val LOG_TAG = "QNZapretTgCompat"
        private const val HTTPS_PORT = 443
        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 1_500
        private const val UDP_DNS_TIMEOUT_MS = 1_250
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val DNS_RESPONSE_MAX_SIZE = 4096
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"

        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val PUBLIC_DNS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4")
        private val DOH_ENDPOINTS = listOf(
            DnsOverHttpsEndpoint("cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
            DnsOverHttpsEndpoint("dns.google", listOf("8.8.8.8", "8.8.4.4")),
        )
    }
}

private data class CacheEntry(
    val addresses: List<TelegramResolvedAddress>,
    val expiresAtMs: Long,
)

private data class DnsOverHttpsEndpoint(
    val host: String,
    val ips: List<String>,
)

private data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

private enum class DnsQueryType(val code: Int) {
    A(1),
    AAAA(28),
}

private data class DnsQuery(
    val id: Int,
    val message: ByteArray,
) {
    companion object {
        private val secureRandom = SecureRandom()

        fun create(host: String, type: DnsQueryType): DnsQuery {
            val id = secureRandom.nextInt(0xffff)
            val output = ByteArrayOutputStream()
            output.write((id ushr 8) and 0xff)
            output.write(id and 0xff)
            output.write(0x01)
            output.write(0x00)
            output.write(0x00)
            output.write(0x01)
            output.write(0x00)
            output.write(0x00)
            output.write(0x00)
            output.write(0x00)
            output.write(0x00)
            output.write(0x00)
            host.trim('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63) { "Invalid DNS label length for $host" }
                output.write(bytes.size)
                output.write(bytes)
            }
            output.write(0x00)
            output.write((type.code ushr 8) and 0xff)
            output.write(type.code and 0xff)
            output.write(0x00)
            output.write(0x01)
            return DnsQuery(id = id, message = output.toByteArray())
        }
    }
}

private fun parseDnsResponse(message: ByteArray, expectedId: Int): List<InetAddress> {
    if (message.size < 12) {
        throw IOException("DNS response too short")
    }
    val id = message.readUInt16(0)
    if (id != expectedId) {
        throw IOException("DNS response id mismatch")
    }
    val flags = message.readUInt16(2)
    val rcode = flags and 0x0f
    if (rcode != 0) {
        throw IOException("DNS response rcode=$rcode")
    }
    val questionCount = message.readUInt16(4)
    val answerCount = message.readUInt16(6)
    var offset = 12
    repeat(questionCount) {
        offset = message.skipDnsName(offset)
        offset += 4
        if (offset > message.size) {
            throw IOException("DNS question overflow")
        }
    }
    val result = mutableListOf<InetAddress>()
    repeat(answerCount) {
        offset = message.skipDnsName(offset)
        if (offset + 10 > message.size) {
            throw IOException("DNS answer overflow")
        }
        val type = message.readUInt16(offset)
        val dnsClass = message.readUInt16(offset + 2)
        val length = message.readUInt16(offset + 8)
        offset += 10
        if (offset + length > message.size) {
            throw IOException("DNS rdata overflow")
        }
        if (dnsClass == 1 && type == DnsQueryType.A.code && length == 4) {
            result += InetAddress.getByAddress(message.copyOfRange(offset, offset + length))
        } else if (dnsClass == 1 && type == DnsQueryType.AAAA.code && length == 16) {
            result += InetAddress.getByAddress(message.copyOfRange(offset, offset + length))
        }
        offset += length
    }
    return result
}

private fun ByteArray.readUInt16(offset: Int): Int {
    if (offset + 1 >= size) {
        throw IOException("DNS uint16 overflow")
    }
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.skipDnsName(startOffset: Int): Int {
    var offset = startOffset
    var jumps = 0
    while (offset < size) {
        val length = this[offset].toInt() and 0xff
        when {
            length == 0 -> return offset + 1
            length and 0xc0 == 0xc0 -> {
                if (offset + 1 >= size) {
                    throw IOException("DNS compression pointer overflow")
                }
                jumps += 1
                if (jumps > 8) {
                    throw IOException("DNS compression pointer loop")
                }
                return offset + 2
            }
            length and 0xc0 != 0 -> throw IOException("Unsupported DNS label encoding")
            else -> offset += 1 + length
        }
    }
    throw IOException("DNS name overflow")
}

private fun readHttpResponse(input: InputStream): HttpResponse {
    val headerBytes = ByteArrayOutputStream()
    var previous3 = -1
    var previous2 = -1
    var previous1 = -1
    while (headerBytes.size() < MAX_HTTP_HEADER_SIZE) {
        val next = input.read()
        if (next < 0) {
            throw EOFException("Unexpected EOF in HTTP headers")
        }
        headerBytes.write(next)
        if (previous3 == '\r'.code &&
            previous2 == '\n'.code &&
            previous1 == '\r'.code &&
            next == '\n'.code
        ) {
            val headers = headerBytes.toString(Charsets.US_ASCII.name())
                .trimEnd()
                .split("\r\n")
            val statusCode = headers.firstOrNull()
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: throw IOException("HTTP status missing")
            val contentLength = headers.firstOrNull {
                it.startsWith("Content-Length:", ignoreCase = true)
            }?.substringAfter(':')?.trim()?.toIntOrNull()
            val chunked = headers.any {
                it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    it.contains("chunked", ignoreCase = true)
            }
            val body = when {
                chunked -> readChunkedBody(input)
                contentLength != null -> input.readBytesExact(contentLength)
                else -> input.readToEndLimited(MAX_DNS_BODY_SIZE)
            }
            return HttpResponse(statusCode = statusCode, body = body)
        }
        previous3 = previous2
        previous2 = previous1
        previous1 = next
    }
    throw IOException("HTTP headers too large")
}

private fun readChunkedBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (output.size() < MAX_DNS_BODY_SIZE) {
        val sizeLine = input.readAsciiLine()
        val chunkSize = sizeLine.substringBefore(';').trim().toInt(16)
        if (chunkSize == 0) {
            input.readAsciiLine()
            return output.toByteArray()
        }
        output.write(input.readBytesExact(chunkSize))
        val cr = input.read()
        val lf = input.read()
        if (cr != '\r'.code || lf != '\n'.code) {
            throw IOException("Invalid chunk delimiter")
        }
    }
    throw IOException("HTTP chunked body too large")
}

private fun InputStream.readAsciiLine(): String {
    val output = ByteArrayOutputStream()
    while (output.size() < MAX_HTTP_LINE_SIZE) {
        val next = read()
        if (next < 0) {
            throw EOFException("Unexpected EOF in HTTP line")
        }
        if (next == '\n'.code) {
            val bytes = output.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, length, Charsets.US_ASCII)
        }
        output.write(next)
    }
    throw IOException("HTTP line too large")
}

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

private fun InputStream.readToEndLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (output.size() < maxBytes) {
        val read = read(buffer)
        if (read < 0) {
            return output.toByteArray()
        }
        output.write(buffer, 0, read)
    }
    throw IOException("HTTP body too large")
}

private const val MAX_HTTP_HEADER_SIZE = 16 * 1024
private const val MAX_HTTP_LINE_SIZE = 4096
private const val MAX_DNS_BODY_SIZE = 64 * 1024
