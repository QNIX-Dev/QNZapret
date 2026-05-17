package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.net.InetAddress

internal data class TunTransportState(
    val active: Boolean,
    val forwarderReady: Boolean,
    val packetCodecReady: Boolean,
    val udpForwarderReady: Boolean,
    val ipv6PacketCodecReady: Boolean,
    val ipv6UdpForwarderReady: Boolean,
    val tcpForwarderReady: Boolean,
    val message: String,
)

private data class TunForwarderCapabilities(
    val ipv4PacketCodecReady: Boolean,
    val ipv4UdpForwarderReady: Boolean,
    val ipv6PacketCodecReady: Boolean,
    val ipv6UdpForwarderReady: Boolean,
    val tcpForwarderReady: Boolean,
) {
    val packetCodecReady: Boolean
        get() = ipv4PacketCodecReady

    val udpForwarderReady: Boolean
        get() = ipv4UdpForwarderReady

    val fullyReady: Boolean
        get() = ipv4PacketCodecReady &&
            ipv4UdpForwarderReady &&
            ipv6PacketCodecReady &&
            ipv6UdpForwarderReady &&
            tcpForwarderReady
}

private data class TunEstablishResult(
    val descriptor: ParcelFileDescriptor,
    val ipv6RouteEnabled: Boolean,
)

internal class TunTransport(
    private val service: VpnService,
) {
    private var descriptor: ParcelFileDescriptor? = null
    private var configFile: File? = null

    fun start(
        config: VpnRuntimeConfig,
        proxyEndpoint: LocalStrategyProxyEndpoint,
    ): TunTransportState {
        stop()
        val capabilities = TUN2SOCKS_CAPABILITIES
        if (!config.establishTunnel) {
            return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Поднятие TUN отложено конфигурацией. IPv4/IPv6 codec, UDP relay и TCP relay готовы.",
            )
        }

        if (!capabilities.fullyReady) {
            return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Запрошен TUN для local proxy ${proxyEndpoint.host}:${proxyEndpoint.port}, " +
                    "но часть возможностей передачи пока недоступна.",
            )
        }

        val establishResult = establishTunnel(config)
            ?: return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Android не вернул TUN fd для запрошенной VPN-сессии.",
            )

        val nextDescriptor = establishResult.descriptor
        descriptor = nextDescriptor
        val nextConfigFile = writeTun2SocksConfig(config, proxyEndpoint)
        configFile = nextConfigFile
        try {
            TProxyService.TProxyStartService(nextConfigFile.absolutePath, nextDescriptor.fd)
        } catch (error: Exception) {
            descriptor = null
            configFile = null
            runCatching { nextDescriptor.close() }
            nextConfigFile.delete()
            throw error
        }
        Log.d(
            TUN_TRANSPORT_LOG_TAG,
            "tun2socks started proxy=${proxyEndpoint.host}:${proxyEndpoint.port} " +
                "config=${nextConfigFile.absolutePath}",
        )

        return TunTransportState(
            active = true,
            forwarderReady = true,
            packetCodecReady = capabilities.packetCodecReady,
            udpForwarderReady = capabilities.udpForwarderReady,
            ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
            ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
            tcpForwarderReady = capabilities.tcpForwarderReady,
            message = "TUN fd передан в hev-socks5-tunnel для local SOCKS5 proxy " +
                "${proxyEndpoint.host}:${proxyEndpoint.port}. " +
                "IPv6 route=${if (establishResult.ipv6RouteEnabled) "enabled" else "disabled"}.",
        )
    }

    fun stop() {
        runCatching { TProxyService.TProxyStopService() }
        runCatching { descriptor?.close() }
        configFile?.delete()
        descriptor = null
        configFile = null
    }

    private fun establishTunnel(config: VpnRuntimeConfig): TunEstablishResult? {
        val selectedNetwork = UnderlyingNetworkSelector.select(service)
        val ipv6RouteEnabled = selectedNetwork
            ?.let { network -> UnderlyingNetworkSelector.supportsIpv6(service, network) }
            ?: false
        val dnsServers = selectedNetwork
            ?.let { network -> UnderlyingNetworkSelector.resolveDnsServers(service, network) }
            .orEmpty()
            .ifEmpty { listOf(InetAddress.getByName(FALLBACK_DNS)) }
        val builder = service.Builder()
            .setSession("QNZapret")
            .setMtu(config.tunnelMtu)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)

        if (ipv6RouteEnabled) {
            builder
                .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX_LENGTH)
                .addRoute("::", 0)
        } else {
            Log.d(
                TUN_TRANSPORT_LOG_TAG,
                "tun ipv6 route disabled reason=no_underlying_ipv6_route " +
                    "underlying=${selectedNetwork ?: "-"}",
            )
        }

        dnsServers.forEach { dnsServer ->
            builder.addDnsServer(dnsServer.hostAddress ?: return@forEach)
        }
        selectedNetwork?.let { network ->
            builder.setUnderlyingNetworks(arrayOf(network))
        }
        try {
            builder.addDisallowedApplication(service.packageName)
        } catch (error: Exception) {
            Log.d(
                TUN_TRANSPORT_LOG_TAG,
                "failed to exclude own package from VPN " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        }

        Log.d(
            TUN_TRANSPORT_LOG_TAG,
            "tun establish dns=${dnsServers.joinToString(",") { it.hostAddress ?: "-" }} " +
                "underlying=${selectedNetwork ?: "-"} mtu=${config.tunnelMtu} " +
                "ipv6Route=$ipv6RouteEnabled " +
                selectedNetwork?.let { network -> UnderlyingNetworkSelector.describeLink(service, network) }.orEmpty(),
        )

        return builder.establish()?.let { descriptor ->
            TunEstablishResult(
                descriptor = descriptor,
                ipv6RouteEnabled = ipv6RouteEnabled,
            )
        }
    }

    private fun writeTun2SocksConfig(
        config: VpnRuntimeConfig,
        proxyEndpoint: LocalStrategyProxyEndpoint,
    ): File {
        val file = File.createTempFile("qnzapret-tun2socks-", ".yml", service.cacheDir)
        file.writeText(
            buildString {
                appendLine("tunnel:")
                appendLine("  mtu: ${config.tunnelMtu}")
                appendLine()
                appendLine("misc:")
                appendLine("  task-stack-size: 81920")
                appendLine()
                appendLine("socks5:")
                appendLine("  address: ${proxyEndpoint.host}")
                appendLine("  port: ${proxyEndpoint.port}")
                appendLine("  udp: udp")
            },
        )
        return file
    }

    private companion object {
        private const val TUN_TRANSPORT_LOG_TAG = "QNZapretTun"
        private const val TUN_IPV4_ADDRESS = "10.24.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
        private const val TUN_IPV6_ADDRESS = "fd00:24::2"
        private const val TUN_IPV6_PREFIX_LENGTH = 128
        private const val FALLBACK_DNS = "1.1.1.1"
        private val TUN2SOCKS_CAPABILITIES = TunForwarderCapabilities(
            ipv4PacketCodecReady = true,
            ipv4UdpForwarderReady = true,
            ipv6PacketCodecReady = true,
            ipv6UdpForwarderReady = true,
            tcpForwarderReady = true,
        )
    }
}
