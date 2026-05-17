package dev.qnzapret

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object AndroidNetworkSelfTest {
    fun run(service: VpnService, stage: String) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QNZapretNetTest").apply { isDaemon = true }
        }
        val future = executor.submit {
            runBlocking(service, stage)
        }
        try {
            future.get(TOTAL_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            Log.d(
                LOG_TAG,
                "self-test timeout stage=$stage timeoutMs=$TOTAL_TIMEOUT_MS",
            )
            future.cancel(true)
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "self-test failed stage=$stage error=${error.javaClass.simpleName}:${messageOf(error)}",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runBlocking(service: VpnService, stage: String) {
        val context = service.applicationContext
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val selectedNetwork = UnderlyingNetworkSelector.select(context)
        val capabilities = selectedNetwork?.let { network ->
            connectivityManager?.getNetworkCapabilities(network)
        }
        val linkProperties = selectedNetwork?.let { network ->
            connectivityManager?.getLinkProperties(network)
        }
        val dnsServers = linkProperties
            ?.dnsServers
            .orEmpty()
            .filter { address -> !address.isAnyLocalAddress && !address.isLoopbackAddress }
            .distinctBy { address -> address.hostAddress }

        Log.d(
            LOG_TAG,
            "self-test begin stage=$stage uid=${Process.myUid()} package=${context.packageName} " +
                "selectedNetwork=${formatNetwork(selectedNetwork)} " +
                "capabilities=${formatCapabilities(capabilities)} dns=${formatDns(dnsServers)} " +
                "privateDns=${formatPrivateDns(linkProperties)}",
        )

        val publicTcpEndpoint = InetSocketAddress(
            InetAddress.getByName(PUBLIC_TCP_HOST),
            PUBLIC_TCP_PORT,
        )
        val dnsEndpoint = InetSocketAddress(
            dnsServers.firstOrNull() ?: InetAddress.getByName(FALLBACK_DNS_HOST),
            DNS_PORT,
        )

        runTcpTest(
            stage = stage,
            name = "plain_socket",
            selectedNetwork = selectedNetwork,
            endpoint = publicTcpEndpoint,
        ) {
            Socket().use { socket ->
                configureTcpSocket(socket)
                socket.connect(publicTcpEndpoint, CONNECT_TIMEOUT_MS)
            }
        }

        runTcpTest(
            stage = stage,
            name = "protected_socket",
            selectedNetwork = selectedNetwork,
            endpoint = publicTcpEndpoint,
        ) {
            Socket().use { socket ->
                configureTcpSocket(socket)
                protectTcpSocket(service, socket)
                socket.connect(publicTcpEndpoint, CONNECT_TIMEOUT_MS)
            }
        }

        if (selectedNetwork == null) {
            logSkipped(stage, "network_socket_factory_protect", publicTcpEndpoint, "no_selected_network")
            logSkipped(stage, "protected_bind_selected_network", publicTcpEndpoint, "no_selected_network")
        } else {
            runTcpTest(
                stage = stage,
                name = "network_socket_factory_protect",
                selectedNetwork = selectedNetwork,
                endpoint = publicTcpEndpoint,
            ) {
                selectedNetwork.socketFactory.createSocket().use { socket ->
                    configureTcpSocket(socket)
                    protectTcpSocket(service, socket)
                    socket.connect(publicTcpEndpoint, CONNECT_TIMEOUT_MS)
                }
            }

            runTcpTest(
                stage = stage,
                name = "protected_bind_selected_network",
                selectedNetwork = selectedNetwork,
                endpoint = publicTcpEndpoint,
            ) {
                Socket().use { socket ->
                    configureTcpSocket(socket)
                    protectTcpSocket(service, socket)
                    selectedNetwork.bindSocket(socket)
                    socket.connect(publicTcpEndpoint, CONNECT_TIMEOUT_MS)
                }
            }
        }

        runUdpDnsTest(
            stage = stage,
            selectedNetwork = selectedNetwork,
            endpoint = dnsEndpoint,
        ) {
            DatagramSocket(null).use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                protectDatagramSocket(service, socket)
                selectedNetwork?.bindSocket(socket)
                socket.bind(InetSocketAddress(0))
                socket.connect(dnsEndpoint)
                val query = buildDnsQuery()
                socket.send(DatagramPacket(query, query.size))
                val response = DatagramPacket(ByteArray(DNS_RESPONSE_BUFFER_SIZE), DNS_RESPONSE_BUFFER_SIZE)
                socket.receive(response)
                if (response.length <= DNS_HEADER_SIZE) {
                    throw IOException("DNS response is too short: ${response.length}")
                }
            }
        }

        Log.d(LOG_TAG, "self-test end stage=$stage")
    }

    private inline fun runTcpTest(
        stage: String,
        name: String,
        selectedNetwork: Network?,
        endpoint: InetSocketAddress,
        block: () -> Unit,
    ) {
        runMeasured(stage, name, selectedNetwork, endpoint, block)
    }

    private inline fun runUdpDnsTest(
        stage: String,
        selectedNetwork: Network?,
        endpoint: InetSocketAddress,
        block: () -> Unit,
    ) {
        runMeasured(stage, "protected_udp_dns_bind", selectedNetwork, endpoint, block)
    }

    private inline fun runMeasured(
        stage: String,
        name: String,
        selectedNetwork: Network?,
        endpoint: InetSocketAddress,
        block: () -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            block()
            Log.d(
                LOG_TAG,
                "self-test result stage=$stage test=$name endpoint=${formatEndpoint(endpoint)} " +
                    "selectedNetwork=${formatNetwork(selectedNetwork)} success=true " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "self-test result stage=$stage test=$name endpoint=${formatEndpoint(endpoint)} " +
                    "selectedNetwork=${formatNetwork(selectedNetwork)} success=false " +
                    "exception=${error.javaClass.simpleName} message=${messageOf(error)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    private fun logSkipped(
        stage: String,
        name: String,
        endpoint: InetSocketAddress,
        reason: String,
    ) {
        Log.d(
            LOG_TAG,
            "self-test result stage=$stage test=$name endpoint=${formatEndpoint(endpoint)} " +
                "selectedNetwork=- success=false skipped=true reason=$reason elapsedMs=0",
        )
    }

    private fun configureTcpSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
    }

    private fun protectTcpSocket(service: VpnService, socket: Socket) {
        if (!service.protect(socket)) {
            throw IOException("VpnService.protect returned false for TCP")
        }
    }

    private fun protectDatagramSocket(service: VpnService, socket: DatagramSocket) {
        if (!service.protect(socket)) {
            throw IOException("VpnService.protect returned false for UDP")
        }
    }

    private fun buildDnsQuery(): ByteArray {
        val hostnameParts = DNS_PROBE_HOST.split('.')
        val result = ByteArrayOutputStream(DNS_HEADER_SIZE + DNS_PROBE_HOST.length + 6)
        result.write((DNS_QUERY_ID ushr 8) and BYTE_MASK)
        result.write(DNS_QUERY_ID and BYTE_MASK)
        result.write(0x01)
        result.write(0x00)
        result.write(0x00)
        result.write(0x01)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        hostnameParts.forEach { part ->
            result.write(part.length and BYTE_MASK)
            part.forEach { char -> result.write(char.code and BYTE_MASK) }
        }
        result.write(0x00)
        result.write(0x00)
        result.write(0x01)
        result.write(0x00)
        result.write(0x01)
        return result.toByteArray()
    }

    private fun formatCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "-"
        }
        val transports = buildList {
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_WIFI, "wifi")
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular")
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_ETHERNET, "ethernet")
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN, "vpn")
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_BLUETOOTH, "bluetooth")
        }.ifEmpty { listOf("unknown") }
        val flags = buildList {
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET, "internet")
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_VALIDATED, "validated")
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, "notRestricted")
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_METERED, "notMetered")
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_VPN, "notVpn")
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL, "captivePortal")
        }.ifEmpty { listOf("none") }
        return "transports=${transports.joinToString("+")};caps=${flags.joinToString("+")}"
    }

    private fun MutableList<String>.addTransport(
        capabilities: NetworkCapabilities,
        transport: Int,
        label: String,
    ) {
        if (capabilities.hasTransport(transport)) {
            add(label)
        }
    }

    private fun MutableList<String>.addCapability(
        capabilities: NetworkCapabilities,
        capability: Int,
        label: String,
    ) {
        if (capabilities.hasCapability(capability)) {
            add(label)
        }
    }

    private fun formatDns(dnsServers: List<InetAddress>): String {
        return dnsServers
            .mapNotNull { address -> address.hostAddress }
            .ifEmpty { listOf("-") }
            .joinToString(",")
    }

    private fun formatPrivateDns(linkProperties: LinkProperties?): String {
        if (linkProperties == null) {
            return "-"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "active=${linkProperties.isPrivateDnsActive};server=${linkProperties.privateDnsServerName ?: "-"}"
        } else {
            "unavailable_api_${Build.VERSION.SDK_INT}"
        }
    }

    private fun formatNetwork(network: Network?): String {
        return network?.toString() ?: "-"
    }

    private fun formatEndpoint(endpoint: InetSocketAddress): String {
        return "${endpoint.address.hostAddress}:${endpoint.port}"
    }

    private fun messageOf(error: Throwable): String {
        return error.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?: "-"
    }

    private const val LOG_TAG = "QNZapretNetTest"
    private const val PUBLIC_TCP_HOST = "1.1.1.1"
    private const val PUBLIC_TCP_PORT = 443
    private const val FALLBACK_DNS_HOST = "1.1.1.1"
    private const val DNS_PORT = 53
    private const val DNS_PROBE_HOST = "example.com"
    private const val DNS_QUERY_ID = 0x514e
    private const val DNS_HEADER_SIZE = 12
    private const val DNS_RESPONSE_BUFFER_SIZE = 512
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 2_500
    private const val TOTAL_TIMEOUT_MS = 18_000
    private const val BYTE_MASK = 0xff
}
